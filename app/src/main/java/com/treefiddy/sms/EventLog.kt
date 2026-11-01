package com.treefiddy.sms

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny file-backed ring buffer of recent events, shared by the listener (writer)
 *  and the control UI (reader). Same process, so plain synchronized file I/O. */
object EventLog {
    private const val FILE = "events.log"
    private const val MAX_LINES = 2000  // ~a month of history; 200 rotated away the evidence for real-world misses
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun append(ctx: Context, line: String) {
        val f = File(ctx.filesDir, FILE)
        val lines = if (f.exists()) f.readLines().toMutableList() else mutableListOf()
        lines.add("${fmt.format(Date())}  $line")
        while (lines.size > MAX_LINES) lines.removeAt(0)
        f.writeText(lines.joinToString("\n"))
    }

    @Synchronized
    fun recent(ctx: Context, n: Int = 100): List<String> {
        val f = File(ctx.filesDir, FILE)
        return if (f.exists()) f.readLines().takeLast(n) else emptyList()
    }

    @Synchronized
    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }
}
