package com.treefiddy.sms

import java.text.Normalizer
import java.util.Locale

/**
 * Faithful port of HuggingFace BERT WordPiece tokenization (do_lower_case=true),
 * matching google/mobilebert-uncased. Pure Kotlin/JVM — no Android imports — so
 * it can be unit-tested off-device.
 *
 * Pipeline: clean text -> add spaces around CJK -> whitespace split ->
 * lowercase + strip accents -> split on punctuation -> greedy WordPiece ->
 * [CLS] .. [SEP], pad to maxLen. Emits int64 ids + attention mask for the model.
 */
class WordpieceTokenizer(
    private val vocab: Map<String, Int>,
    private val maxLen: Int = 64,
    private val unk: String = "[UNK]",
    private val cls: String = "[CLS]",
    private val sep: String = "[SEP]",
    private val maxCharsPerWord: Int = 100,
) {
    private val unkId = vocab.getValue(unk)

    data class Encoded(val inputIds: LongArray, val attentionMask: LongArray)

    fun encode(text: String): Encoded =
        pack(wordpiece(basicTokenize(text)).let {
            if (it.size > maxLen - 2) it.subList(0, maxLen - 2) else it
        })

    /** Head + tail windows for long texts. Political spam must carry its ask
     *  and opt-out somewhere in the message; long narrative openers push them
     *  past maxLen, so score both ends (caller takes the max) instead of
     *  silently head-truncating. Short texts yield a single window. */
    fun encodeWindows(text: String): List<Encoded> {
        val tokens = wordpiece(basicTokenize(text))
        val body = maxLen - 2
        if (tokens.size <= body) return listOf(pack(tokens))
        return listOf(
            pack(tokens.subList(0, body)),
            pack(tokens.subList(tokens.size - body, tokens.size)),
        )
    }

    private fun pack(tokens: List<String>): Encoded {
        val full = ArrayList<String>(tokens.size + 2).apply { add(cls); addAll(tokens); add(sep) }
        val ids = LongArray(maxLen)          // [PAD]=0 fills the tail
        val mask = LongArray(maxLen)
        for (i in full.indices) {
            ids[i] = (vocab[full[i]] ?: unkId).toLong()
            mask[i] = 1L
        }
        return Encoded(ids, mask)
    }

    // ---- BasicTokenizer ----
    private fun basicTokenize(text: String): List<String> {
        val cleaned = tokenizeChineseChars(cleanText(text))
        val out = ArrayList<String>()
        for (tok in cleaned.split(WS_REGEX).filter { it.isNotEmpty() }) {
            val lowered = stripAccents(tok.lowercase(Locale.ROOT))
            out.addAll(splitOnPunctuation(lowered))
        }
        return out
    }

    private fun cleanText(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) continue
            if (isWhitespace(cp)) sb.append(' ') else sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun stripAccents(text: String): String {
        val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder()
        var i = 0
        while (i < nfd.length) {
            val cp = nfd.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) continue
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun splitOnPunctuation(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (isPunctuation(cp)) {
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                out.add(String(Character.toChars(cp)))
            } else {
                cur.appendCodePoint(cp)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun tokenizeChineseChars(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (isChinese(cp)) { sb.append(' '); sb.appendCodePoint(cp); sb.append(' ') }
            else sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    // ---- WordPiece ----
    private fun wordpiece(tokens: List<String>): MutableList<String> {
        val out = ArrayList<String>()
        for (token in tokens) {
            val chars = token.toCharArray()
            if (chars.size > maxCharsPerWord) { out.add(unk); continue }
            var isBad = false
            var start = 0
            val subs = ArrayList<String>()
            while (start < chars.size) {
                var end = chars.size
                var curSub: String? = null
                while (start < end) {
                    val piece = (if (start > 0) "##" else "") + String(chars, start, end - start)
                    if (vocab.containsKey(piece)) { curSub = piece; break }
                    end--
                }
                if (curSub == null) { isBad = true; break }
                subs.add(curSub); start = end
            }
            if (isBad) out.add(unk) else out.addAll(subs)
        }
        return out
    }

    // ---- BERT char predicates (match tokenization_utils) ----
    private fun isWhitespace(cp: Int): Boolean {
        if (cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return true
        return Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()
    }

    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
        val t = Character.getType(cp)
        return t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt() ||
            t == Character.LINE_SEPARATOR.toInt() || t == Character.PARAGRAPH_SEPARATOR.toInt() ||
            t == Character.PRIVATE_USE.toInt() || t == Character.SURROGATE.toInt() ||
            t == Character.UNASSIGNED.toInt()
    }

    private fun isPunctuation(cp: Int): Boolean {
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    private fun isChinese(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF ||
        cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF ||
        cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F

    companion object {
        private val WS_REGEX = Regex("\\s+")

        /** Build from vocab.txt lines (token per line; id = line index). */
        fun fromVocabLines(lines: List<String>, maxLen: Int = 64): WordpieceTokenizer {
            val map = HashMap<String, Int>(lines.size * 2)
            lines.forEachIndexed { i, line -> if (line.isNotEmpty()) map[line] = i }
            return WordpieceTokenizer(map, maxLen)
        }
    }
}
