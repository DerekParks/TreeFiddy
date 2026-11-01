package com.treefiddy.sms

import android.content.Context

/** Shared settings between the control UI (writer) and the listener (reader). */
object Settings {
    const val PREFS = "settings"
    const val FILTERING = "filtering_enabled"   // master kill switch
    const val AUTO_REPLY = "auto_reply_enabled" // whether STOP replies are sent at all
    // p(spam) at/above which the app acts (mark read; + STOP when auto-reply is on).
    // Stored key kept as "stop_threshold" for pref compatibility.
    const val ACTION_THRESHOLD = "stop_threshold"

    const val DEFAULT_FILTERING = true
    const val DEFAULT_AUTO_REPLY = false
    const val DEFAULT_ACTION = 0.9f

    // Listener liveness: stamped on every watched notification, so the UI can
    // tell a silently-dead listener from a merely quiet inbox.
    const val LAST_SEEN = "listener_last_seen_ms"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun filtering(ctx: Context) = prefs(ctx).getBoolean(FILTERING, DEFAULT_FILTERING)
    fun autoReply(ctx: Context) = prefs(ctx).getBoolean(AUTO_REPLY, DEFAULT_AUTO_REPLY)
    fun actionThreshold(ctx: Context) = prefs(ctx).getFloat(ACTION_THRESHOLD, DEFAULT_ACTION)
    fun noteSeen(ctx: Context) =
        prefs(ctx).edit().putLong(LAST_SEEN, System.currentTimeMillis()).apply()
    fun lastSeen(ctx: Context) = prefs(ctx).getLong(LAST_SEEN, 0L)
}
