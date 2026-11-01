package com.treefiddy.sms

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

private val BG = 0xFF0F1115.toInt()
private val CARD = 0xFF181B22.toInt()
private val EDGE = 0xFF262B36.toInt()
private val WELL = 0xFF0C0E12.toInt()
private val INK = 0xFFE6E9EF.toInt()
private val MUTED = 0xFF8A93A6.toInt()
private val LOG_INK = 0xFFC4CCDB.toInt()
private val GREEN = 0xFF7BDC97.toInt()
private val RED = 0xFFFF8B7D.toInt()
private val AMBER = 0xFFE0A82E.toInt()
private val ACCENT = 0xFF3A86FF.toInt()

/**
 * Control panel: master on/off (kill switch), auto-reply STOP toggle, and a slider
 * for the STOP confidence threshold. Settings persist to SharedPreferences and the
 * listener reads them live (no restart needed). Plus a no-permission test box.
 */
class MainActivity : Activity() {

    private lateinit var classifier: SpamClassifier
    private lateinit var statusView: TextView
    private lateinit var lastSeenView: TextView
    private lateinit var smsAccessView: TextView
    private lateinit var smsFixBtn: Button
    private lateinit var eventsView: TextView
    private lateinit var spamCount: TextView
    private lateinit var readCount: TextView
    private lateinit var stopCount: TextView
    private val dp get() = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refreshEvents(); updateStats(); handler.postDelayed(this, 2000) }
    }

    // ---- style helpers ----

    private fun rounded(fill: Int, stroke: Int = 0, radius: Float = 14f) =
        GradientDrawable().apply {
            cornerRadius = radius * dp
            setColor(fill)
            if (stroke != 0) setStroke((1 * dp).toInt().coerceAtLeast(1), stroke)
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, EDGE)
        val p = (14 * dp).toInt()
        setPadding(p, p, p, p)
    }

    private fun pillButton(label: String, filled: Boolean = false) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        stateListAnimator = null
        background = rounded(if (filled) ACCENT else WELL, if (filled) 0 else EDGE, 10f)
        setTextColor(if (filled) Color.WHITE else INK)
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        val h = (16 * dp).toInt(); val v = (10 * dp).toInt()
        setPadding(h, v, h, v)
    }

    private fun sectionHeader(t: String) = TextView(this).apply {
        text = t.uppercase()
        textSize = 11f
        letterSpacing = 0.09f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(MUTED)
        setPadding((4 * dp).toInt(), (18 * dp).toInt(), 0, (6 * dp).toInt())
    }

    private fun styleSwitch(s: Switch) {
        s.setTextColor(INK)
        s.textSize = 16f
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        s.thumbTintList = ColorStateList(states, intArrayOf(ACCENT, MUTED))
        s.trackTintList = ColorStateList(states, intArrayOf(0x553A86FF, EDGE))
        s.setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
    }

    private fun vfill(topDp: Int = 0) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = (topDp * dp).toInt()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        classifier = SpamClassifier(this)
        val prefs = Settings.prefs(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, (32 * dp).toInt())
        }

        // ---- title ----
        root.addView(TextView(this).apply {
            text = "TreeFiddy"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
        }, vfill())
        root.addView(TextView(this).apply {
            text = "on-device political spam filter — ain't givin' 'em no tree fiddy"
            textSize = 12f
            setTextColor(MUTED)
        }, vfill(2))

        // ---- trial counters ----
        fun statCard(num: TextView, label: String, color: Int) = card().apply {
            gravity = Gravity.CENTER
            num.apply {
                textSize = 30f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(color); gravity = Gravity.CENTER
            }
            addView(num, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = 10f; letterSpacing = 0.06f
                setTextColor(MUTED); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        spamCount = TextView(this); readCount = TextView(this); stopCount = TextView(this)
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val cell = { LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * dp).toInt() } }
            addView(statCard(spamCount, "SPAM CAUGHT", AMBER), cell())
            addView(statCard(readCount, "MARKED READ", GREEN), cell())
            addView(statCard(stopCount, "STOPPED", RED),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            setOnLongClickListener {
                Stats.reset(this@MainActivity); updateStats()
                Toast.makeText(this@MainActivity, "counts reset", Toast.LENGTH_SHORT).show(); true
            }
        }
        root.addView(statsRow, vfill(16))

        // ---- notification-access status ----
        root.addView(sectionHeader("Status"))
        statusView = TextView(this).apply { textSize = 14f }
        lastSeenView = TextView(this).apply { textSize = 12f }
        smsAccessView = TextView(this).apply { textSize = 14f }
        val accessBtn = pillButton("Open notification access settings").apply {
            setOnClickListener {
                startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        smsFixBtn = pillButton("Fix SMS permission").apply {
            visibility = android.view.View.GONE
            setOnClickListener {
                startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")))
            }
        }
        root.addView(card().apply {
            addView(statusView, vfill())
            addView(lastSeenView, vfill(4))
            addView(smsAccessView, vfill(4))
            addView(accessBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = (10 * dp).toInt()
            })
            addView(smsFixBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = (8 * dp).toInt()
            })
        }, vfill())

        // ---- controls: kill switch, auto-reply, threshold ----
        root.addView(sectionHeader("Controls"))
        val master = Switch(this).apply {
            text = "Spam filtering active"
            isChecked = Settings.filtering(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(Settings.FILTERING, on).apply()
            }
        }
        val autoReply = Switch(this).apply {
            text = "Auto-reply STOP to spam"
            isChecked = Settings.autoReply(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(Settings.AUTO_REPLY, on).apply()
            }
        }
        styleSwitch(master)
        styleSwitch(autoReply)

        val thrLabel = TextView(this).apply { textSize = 13f; setTextColor(MUTED) }
        fun fmt(v: Float) = "Act on spam when confidence ≥ %.2f  (mark read; STOP if enabled)".format(v)
        val seek = SeekBar(this).apply {
            min = 50; max = 100                       // 0.50 .. 1.00
            progress = (Settings.actionThreshold(this@MainActivity) * 100).toInt()
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(EDGE)
        }
        thrLabel.text = fmt(seek.progress / 100f)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val v = p / 100f
                thrLabel.text = fmt(v)
                prefs.edit().putFloat(Settings.ACTION_THRESHOLD, v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        root.addView(card().apply {
            addView(master, vfill())
            addView(autoReply, vfill())
            addView(thrLabel, vfill(8))
            addView(seek, vfill(6))
        }, vfill())

        // ---- test box ----
        root.addView(sectionHeader("Test a message"))
        val input = EditText(this).apply {
            hint = "Paste an SMS to test…"
            setText("Chip in \$5 now to help us flip the Senate! Reply STOP to quit")
            textSize = 15f
            setTextColor(INK)
            setHintTextColor(MUTED)
            background = rounded(WELL, EDGE, 10f)
            val p = (12 * dp).toInt()
            setPadding(p, p, p, p)
        }
        val result = TextView(this).apply {
            textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (10 * dp).toInt(), 0, 0)
        }
        val classifyBtn = pillButton("Classify", filled = true).apply {
            setOnClickListener {
                val r = classifier.classify(input.text.toString())
                result.text = "%s   p(spam) = %.3f".format(if (r.isSpam) "SPAM" else "HAM", r.pSpam)
                result.setTextColor(if (r.isSpam) RED else GREEN)
            }
        }
        root.addView(card().apply {
            addView(input, vfill())
            addView(classifyBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = (10 * dp).toInt()
            })
            addView(result, vfill())
        }, vfill())

        // ---- event log ----
        root.addView(sectionHeader("Recent events (newest first)"))
        eventsView = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true)
            setTextColor(LOG_INK)
        }
        val logButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pillButton("Refresh").apply { setOnClickListener { refreshEvents() } })
            addView(pillButton("Clear").apply {
                setOnClickListener { EventLog.clear(this@MainActivity); refreshEvents() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = (8 * dp).toInt()
            })
        }
        root.addView(card().apply {
            addView(logButtons, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(eventsView, vfill(10))
        }, vfill())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(root)
        })
    }

    private fun refreshEvents() {
        val lines = EventLog.recent(this, 100).asReversed()
        eventsView.text = if (lines.isEmpty()) "(no events yet — waiting for messages)"
            else lines.joinToString("\n")
    }

    private fun updateStats() {
        spamCount.text = Stats.get(this, Stats.SPAM).toString()
        readCount.text = Stats.get(this, Stats.READ).toString()
        stopCount.text = Stats.get(this, Stats.STOPPED).toString()
        updateLastSeen()
    }

    /** Listener liveness: "access granted" only proves the permission — a dead
     *  listener looks identical to a quiet inbox without this line. */
    private fun updateLastSeen() {
        val seen = Settings.lastSeen(this)
        if (seen == 0L) {
            lastSeenView.text = "No messages seen yet"
            lastSeenView.setTextColor(MUTED)
            return
        }
        val mins = (System.currentTimeMillis() - seen) / 60_000
        val age = when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 48 * 60 -> "${mins / 60}h ago"
            else -> "${mins / (24 * 60)}d ago"
        }
        val stale = mins >= 24 * 60
        lastSeenView.text = if (stale)
            "Last message seen $age — listener may be dead; toggle notification access"
        else "Last message seen $age"
        lastSeenView.setTextColor(if (stale) AMBER else MUTED)
    }

    override fun onResume() {
        super.onResume()
        val granted = (AndroidSettings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "")
            .contains(packageName)
        statusView.text = if (granted) "● Notification access granted"
            else "● Notification access NOT granted — enable it below"
        statusView.setTextColor(if (granted) GREEN else RED)
        updateSmsAccess()
        AccessWatchdog.checkAndAlert(this, force = true)   // clears a stale alert on open
        refreshEvents()
        handler.post(ticker)          // live-refresh the log while visible
    }

    /** Restricted-permission watchdog: catches READ_SMS being revoked OR
     *  neutered-to-ignore (looks granted, reads silently fail — see SmsAccess). */
    private fun updateSmsAccess() {
        when (SmsAccess.check(this)) {
            SmsAccess.OK -> {
                smsAccessView.text = "● SMS access OK"
                smsAccessView.setTextColor(GREEN)
                smsFixBtn.visibility = android.view.View.GONE
            }
            SmsAccess.REVOKED -> {
                smsAccessView.text = "● SMS access REVOKED — full-text checks are off; re-grant below"
                smsAccessView.setTextColor(RED)
                smsFixBtn.visibility = android.view.View.VISIBLE
            }
            SmsAccess.NEUTERED -> {
                smsAccessView.text = "● SMS access blocked by the system (granted but ignored) — re-grant below"
                smsAccessView.setTextColor(RED)
                smsFixBtn.visibility = android.view.View.VISIBLE
            }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        classifier.close()
        super.onDestroy()
    }
}
