package com.treefiddy.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Proactive READ_SMS watchdog. The status card catches a revoked/neutered
 * permission only when the user opens the app; this posts a notification the
 * moment access drops, driven off the listener's own lifecycle (no scheduler,
 * so no WorkManager/boot/wake-lock permissions). Throttled, and edge-triggered
 * so it alerts once per outage, not on every notification.
 */
object AccessWatchdog {

    private const val CHANNEL = "sms_access"
    private const val NOTIF_ID = 42
    private const val LAST_CHECK = "access_check_ms"
    private const val ALERTED = "access_alerted"
    private const val THROTTLE_MS = 15 * 60_000L

    /** Cheap to call on every notification; the real check runs at most every
     *  THROTTLE_MS. Force it (force=true) on listener connect / boot. */
    fun checkAndAlert(ctx: Context, force: Boolean = false) {
        val prefs = Settings.prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(LAST_CHECK, 0L) < THROTTLE_MS) return
        prefs.edit().putLong(LAST_CHECK, now).apply()

        val ok = SmsAccess.check(ctx) == SmsAccess.OK
        val alerted = prefs.getBoolean(ALERTED, false)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        if (ok) {
            if (alerted) {                       // recovered -> clear + re-arm
                nm.cancel(NOTIF_ID)
                prefs.edit().putBoolean(ALERTED, false).apply()
            }
            return
        }
        if (alerted) return                      // already told them; don't nag

        nm.createNotificationChannel(NotificationChannel(
            CHANNEL, "SMS access", NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIF_ID, android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("TreeFiddy can't read your texts")
            .setContentText("Android revoked SMS access — spam filtering is degraded. Tap to fix.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build())
        prefs.edit().putBoolean(ALERTED, true).apply()
    }
}
