package com.treefiddy.sms

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Companion-app path (no default-SMS-app takeover): read incoming SMS from the
 * messaging app's notification, classify, and — for confident political spam —
 * mark it read + optionally auto-reply STOP, both by firing the notification's
 * OWN actions (so we never touch the SMS provider directly).
 *
 * Setup: user grants Notification access (Settings > Notifications > Device &
 * app notifications > Special app access). Declare BIND_NOTIFICATION_LISTENER_SERVICE
 * in the manifest (see README). This is the lighter but more brittle option; the
 * robust alternative is forking an OSS SMS app and hooking the SMS_DELIVER path.
 *
 * Reliability caveats: depends on the messaging app exposing "Mark as read" /
 * inline "Reply" actions (Google Messages does), and on notifications not being
 * bundled/suppressed. Treat this as a strong starting point, not turnkey.
 */
class SpamSmsListener : NotificationListenerService() {

    private lateinit var classifier: SpamClassifier

    // Act once per (notification key, body): Google Messages posts then updates the
    // same notification, which would otherwise double-fire (two STOP replies).
    private val handled = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 500
    }

    // Per-sender STOP cooldown (sender key -> epoch-ms the cooldown expires). After we
    // reply STOP, the sender's "you've been unsubscribed" confirmation lands right after
    // and would itself score as spam; hold off on that sender until the window passes.
    private val cooldownUntil = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Long>?) = size > 500
    }

    // Per-sender conversation-history cache; the provider lookup runs once per sender.
    private val conversation = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 500
    }

    override fun onCreate() {
        super.onCreate()
        classifier = SpamClassifier(applicationContext)
    }

    override fun onDestroy() {
        if (::classifier.isInitialized) classifier.close()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        Log.i(TAG, "listener connected — watching $WATCH_PACKAGES")
        AccessWatchdog.checkAndAlert(this, force = true)   // catches post-reboot revocation
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        AccessWatchdog.checkAndAlert(this)   // throttled; runs on any notification
        if (sbn.packageName !in WATCH_PACKAGES) return
        Settings.noteSeen(this)   // liveness stamp, even when filtering is off
        if (!Settings.filtering(this)) return   // master kill switch (live from the UI)
        // skip group-summary + ongoing/foreground-service notifications (not messages)
        val flags = sbn.notification.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val notifText = messageText(sbn.notification)
        // skip blank + the redacted lockscreen "content hidden" copy
        if (notifText.isNullOrBlank() || notifText in REDACTION_SENTINELS) return

        // Notification previews truncate; upgrade to the FULL body from the SMS provider
        // (matched by prefix) so long political texts aren't judged on their opening alone.
        // Also recovers the sender address that keys the STOP cooldown below.
        val msg = fullBody(notifText)
        val body = msg.body
        val senderKey = msg.address?.takeIf { it.isNotBlank() } ?: titleKey(sbn)

        // dedup: same notification updated with the same body -> act only once
        val sig = sbn.key + " " + body
        if (handled.put(sig, true) != null) return

        // STOP cooldown: we just replied STOP to this sender, so hold off on their
        // follow-ups — above all the "you've been unsubscribed" confirmation, which
        // itself scores as spam and would otherwise get re-marked + re-STOPped.
        val until = cooldownUntil[senderKey]
        if (until != null && System.currentTimeMillis() < until) {
            // Mark the follow-up read (the unsub confirmation shouldn't sit unread),
            // but no classify and no STOP for this sender until the cooldown expires.
            val marked = fireActionByTitle(sbn, MARK_READ_TITLES)
            if (marked) Stats.bump(this, Stats.READ)
            Log.i(TAG, "  sender=%s in STOP cooldown; marked read=%s : %s"
                .format(senderKey, marked, body.take(48)))
            EventLog.append(this, "COOL [%s] : %s"
                .format(if (marked) "read" else "read-failed", body.take(40)))
            return
        }

        // Hard rail: never act on verification-code texts, whatever the model says.
        // A false positive here marks an OTP read — or STOPs a bank's shortcode,
        // blocking future codes. Banks append an SMS-Retriever app hash (e.g.
        // "rzE87/hf9zG") that scores like a spam tracking token, so this class is
        // FP-prone by construction. Link-free only: real spam always carries its
        // link, so it can't wear an OTP costume to slip past the filter.
        if (OTP_RE.containsMatchIn(body) && !URL_RE.containsMatchIn(body)) {
            Log.i(TAG, "  verification-code text; not acting : %s".format(body.take(48)))
            EventLog.append(this, "OTP  skip : %s".format(body.take(48)))
            return
        }

        // single action threshold from the UI slider — governs mark-read AND STOP,
        // so there's no dead zone below a separate hardcoded gate.
        val r = classifier.classify(body, threshold = Settings.actionThreshold(this))
        val actionTitles = sbn.notification.actions?.mapNotNull { it.title?.toString() } ?: emptyList()
        Log.i(TAG, "pkg=%s p(spam)=%.3f spam=%s actions=%s : %s"
            .format(sbn.packageName, r.pSpam, r.isSpam, actionTitles, body.take(60)))
        if (!r.isSpam) {
            EventLog.append(this, "HAM  p=%.2f : %s".format(r.pSpam, body.take(48)))
            return
        }

        // A sender you've texted back is a conversation, not spam; override the verdict.
        val addr = msg.address
        if (addr != null && conversation.getOrPut(senderKey) { MsgStore.hasHumanReply(this, addr) }) {
            Log.i(TAG, "  known conversation with %s; not acting : %s".format(senderKey, body.take(48)))
            EventLog.append(this, "CONV p=%.2f skip : %s".format(r.pSpam, body.take(40)))
            return
        }

        Stats.bump(this, Stats.SPAM)

        // 1) mark read via the notification's own action (updates the DB through the SMS app)
        val marked = fireActionByTitle(sbn, MARK_READ_TITLES)
        Log.i(TAG, "  mark-read action fired=$marked")
        var acted = if (marked) "read" else "read-failed"
        if (marked) Stats.bump(this, Stats.READ)

        // 2) auto-reply STOP — same action threshold as mark-read (already passed above),
        //    just additionally gated by the auto-reply toggle. A high-confidence *political*
        //    spam verdict is itself the compliance signal (10DLC senders must honor STOP).
        if (Settings.autoReply(this)) {
            val replied = replyViaRemoteInput(sbn, "STOP")
            Log.i(TAG, "  reply STOP fired=$replied")
            if (replied) {
                acted += "+STOP"; Stats.bump(this, Stats.STOPPED)
                cooldownUntil[senderKey] = System.currentTimeMillis() + COOLDOWN_MS
            }
        } else {
            Log.i(TAG, "  auto-reply disabled")
        }
        EventLog.append(this, "SPAM p=%.2f [%s] : %s".format(r.pSpam, acted, body.take(48)))
    }

    private fun messageText(n: Notification): String? {
        val extras = n.extras
        // Google Messages uses MessagingStyle: the body is in EXTRA_MESSAGES, not EXTRA_TEXT.
        @Suppress("DEPRECATION")
        val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!msgs.isNullOrEmpty()) {
            val texts = msgs.mapNotNull { (it as? Bundle)?.getCharSequence("text")?.toString() }
            if (texts.isNotEmpty()) return texts.joinToString(" ")   // last = newest message
        }
        return (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
    }

    /** A message resolved from the notification: its full body and (when READ_SMS is
     *  granted and the row matched) the sender address that keys the STOP cooldown. */
    private data class InboxMsg(val body: String, val address: String?)

    /** Look up the full SMS body + sender address from the provider by matching the
     *  notification preview's prefix against recent inbox messages. Falls back to the
     *  (possibly truncated) notification text and a null address if READ_SMS isn't
     *  granted or no match is found (e.g. MMS). */
    private fun fullBody(notifText: String): InboxMsg {
        // MMS notifications prefix a media placeholder ("Image Hi there...")
        // that isn't in the stored text part — match on both keys.
        val wantKeys = listOf(notifText, stripMediaPlaceholders(notifText))
            .map { keyOf(it) }.filter { it.length >= 6 }.distinct()
        if (wantKeys.isEmpty()) return InboxMsg(notifText, null)  // too short to match safely
        return try {
            smsLookup(wantKeys, notifText)
                ?: mmsLookup(wantKeys)
                ?: InboxMsg(notifText, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_SMS not granted; using truncated notification text")
            InboxMsg(notifText, null)
        } catch (e: Exception) {
            Log.w(TAG, "SMS/MMS provider read failed", e); InboxMsg(notifText, null)
        }
    }

    private fun smsLookup(wantKeys: List<String>, notifText: String): InboxMsg? {
        contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.ADDRESS),
            null, null, "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            var scanned = 0
            while (c.moveToNext() && scanned++ < 15) {
                val body = c.getString(0) ?: continue
                if (System.currentTimeMillis() - c.getLong(1) > 24 * 3_600_000L) break
                if (keyOf(body) in wantKeys) {
                    val full = if (body.length > notifText.length) body else notifText
                    return InboxMsg(full, c.getString(2))
                }
            }
        }
        return null
    }

    /** MMS bodies live in per-message text/plain parts; DATE is in SECONDS. */
    private fun mmsLookup(wantKeys: List<String>): InboxMsg? {
        contentResolver.query(
            Telephony.Mms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE),
            null, null, "${Telephony.Mms.DATE} DESC"
        )?.use { c ->
            var scanned = 0
            while (c.moveToNext() && scanned++ < 15) {
                if (System.currentTimeMillis() / 1000 - c.getLong(1) > 24 * 3600) break
                val id = c.getLong(0)
                val body = MsgStore.mmsText(this, id)
                if (body.length >= 6 && keyOf(body) in wantKeys) {
                    return InboxMsg(body, MsgStore.mmsSender(this, id))
                }
            }
        }
        return null
    }

    private fun stripMediaPlaceholders(s: String): String {
        var t = s.trim()
        while (true) {
            val m = MEDIA_PLACEHOLDERS.firstOrNull { t.startsWith("$it ") } ?: break
            t = t.removePrefix("$m ").trim()
        }
        return t
    }

    /** Sender key for the STOP cooldown when the provider address is unavailable (MMS,
     *  READ_SMS not granted): the conversation title, which stays stable per sender. */
    private fun titleKey(sbn: StatusBarNotification): String =
        (sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.trim()?.lowercase()?.ifBlank { null }) ?: ("key:" + sbn.key)

    /** Whitespace-insensitive first-20-char fingerprint for prefix matching. */
    private fun keyOf(s: String) =
        s.trim().replace(Regex("\\s+"), " ").take(20).replace(" ", "").lowercase()

    /** Fire the PendingIntent of the first action whose title matches (e.g. "Mark as read"). */
    private fun fireActionByTitle(sbn: StatusBarNotification, titles: Set<String>): Boolean {
        val action = sbn.notification.actions?.firstOrNull {
            it.title?.toString()?.lowercase()?.let { t -> titles.any { t.contains(it) } } == true
        } ?: return false
        return try {
            action.actionIntent.send(); true
        } catch (e: Exception) {
            Log.w(TAG, "mark-read action failed", e); false
        }
    }

    /** Find the inline reply action (one carrying a RemoteInput) and send [text] through it. */
    private fun replyViaRemoteInput(sbn: StatusBarNotification, text: String): Boolean {
        val action = sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return false
        val remoteInputs = action.remoteInputs ?: return false
        val intent = Intent()
        val results = Bundle()
        remoteInputs.forEach { ri -> results.putCharSequence(ri.resultKey, text) }
        RemoteInput.addResultsToIntent(remoteInputs, intent, results)
        return try {
            action.actionIntent.send(applicationContext, 0, intent); true
        } catch (e: Exception) {
            Log.w(TAG, "reply action failed", e); false
        }
    }

    companion object {
        private const val TAG = "SpamSmsListener"
        // How long to leave a sender alone after replying STOP — long enough to swallow the
        // "you've been unsubscribed" confirmation, short enough that a later fresh blast still acts.
        private const val COOLDOWN_MS = 30 * 60_000L   // 30 minutes
        // Redacted lockscreen copies of a notification (locale-dependent sentinel text).
        private val REDACTION_SENTINELS = setOf("Sensitive notification content hidden")

        private val SMS_PACKAGES = setOf(
            "com.google.android.apps.messaging",     // Google Messages
            "com.samsung.android.messaging",         // Samsung Messages
        )
        // Debug builds also accept `adb shell cmd notification post` test messages.
        private val WATCH_PACKAGES =
            if (BuildConfig.DEBUG) SMS_PACKAGES + "com.android.shell" else SMS_PACKAGES
        private val MARK_READ_TITLES = setOf("mark as read", "mark read")
        // Google Messages prepends these to MessagingStyle text for media messages.
        private val MEDIA_PLACEHOLDERS = listOf("Image", "GIF", "Video", "Photo", "Sticker", "Audio")

        // Verification-code text: an OTP keyword within reach of a 4-8 digit code,
        // in either order ("verification code is: 022114" / "G-583920 is your code").
        private const val OTP_WORDS =
            "(?:verification|security|login|auth(?:entication)?|one[- ]?time|2fa|otp|codes?)"
        // \d{4,8} excluding bare 4-digit years ("login ... in 2026" is not a code).
        private const val OTP_DIGITS = "(?!(?:19|20)\\d\\d\\b)\\d{4,8}"
        private val OTP_RE = Regex(
            "(?i)\\b$OTP_WORDS\\b.{0,40}?\\b$OTP_DIGITS\\b" +
            "|\\b$OTP_DIGITS\\b.{0,40}?\\b$OTP_WORDS\\b")
        // Domain-with-path, the invariant part of every spam link ("cbpac.org/l/x").
        private val URL_RE = Regex("(?i)\\b[a-z0-9-]+\\.[a-z]{2,}/")
    }
}
