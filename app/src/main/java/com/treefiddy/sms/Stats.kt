package com.treefiddy.sms

import android.content.Context

/** Persistent trial tallies (survive the event-log ring buffer). Shared prefs file. */
object Stats {
    const val SPAM = "cnt_spam"
    const val READ = "cnt_read"
    const val STOPPED = "cnt_stopped"

    fun bump(ctx: Context, key: String) {
        val p = Settings.prefs(ctx)
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
    }

    fun get(ctx: Context, key: String) = Settings.prefs(ctx).getInt(key, 0)

    fun reset(ctx: Context) {
        Settings.prefs(ctx).edit().remove(SPAM).remove(READ).remove(STOPPED).apply()
    }
}
