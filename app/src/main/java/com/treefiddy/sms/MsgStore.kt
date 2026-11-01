package com.treefiddy.sms

import android.content.Context
import android.net.Uri
import android.provider.Telephony

/** Read-side helpers over the SMS/MMS providers (needs READ_SMS). */
object MsgStore {

    private val OPT_OUT = setOf(
        "stop", "start", "stop all", "unstop", "unsubscribe", "cancel",
        "end", "quit", "help", "yes", "no", "y", "n")

    /** True if we've sent this number a message that isn't an opt-out keyword —
     *  i.e. a two-way conversation, never spam. Shortcodes never qualify. */
    fun hasHumanReply(ctx: Context, address: String): Boolean {
        val digits = address.filter { it.isDigit() }
        if (digits.length < 7) return false
        val last10 = digits.takeLast(10)
        val candidates = linkedSetOf(address, digits, last10, "+1$last10", "1$last10").toList()
        val where = "${Telephony.Sms.ADDRESS} IN (${candidates.joinToString(",") { "?" }})"
        return try {
            ctx.contentResolver.query(
                Telephony.Sms.Sent.CONTENT_URI, arrayOf(Telephony.Sms.BODY),
                where, candidates.toTypedArray(), "${Telephony.Sms.DATE} DESC LIMIT 40"
            )?.use { c ->
                while (c.moveToNext()) {
                    val b = c.getString(0)?.trim()?.lowercase() ?: continue
                    if (b.isNotEmpty() && b !in OPT_OUT) return true
                }
                false
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** Joined text/plain parts of one MMS. */
    fun mmsText(ctx: Context, id: Long): String {
        val sb = StringBuilder()
        ctx.contentResolver.query(Uri.parse("content://mms/$id/part"),
            arrayOf("ct", "text"), null, null, null)?.use { p ->
            while (p.moveToNext()) {
                if (p.getString(0) == "text/plain") p.getString(1)?.let { sb.append(it).append(' ') }
            }
        }
        return sb.toString().trim()
    }

    /** type=137 is the MMS "from" address row. */
    fun mmsSender(ctx: Context, id: Long): String? =
        ctx.contentResolver.query(Uri.parse("content://mms/$id/addr"),
            arrayOf("address"), "type=137", null, null)?.use { a ->
            if (a.moveToFirst()) a.getString(0) else null
        }
}
