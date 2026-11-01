import com.treefiddy.sms.WordpieceTokenizer
import java.io.File

/**
 * Off-device parity check: the Kotlin WordPiece tokenizer must produce byte-identical
 * input_ids + attention_mask to HuggingFace's tokenizer.
 *
 *   java -jar toktest.jar <vocab.txt> <tok_reference.tsv>
 *
 * reference lines: text \t id0,id1,... \t mask0,mask1,...
 */
fun main(args: Array<String>) {
    val vocabPath = args.getOrElse(0) { "app/src/main/assets/vocab.txt" }
    val refPath = args.getOrElse(1) { "tokenizer_test/tok_reference.tsv" }

    val tok = WordpieceTokenizer.fromVocabLines(File(vocabPath).readLines(), maxLen = 64)

    var pass = 0
    var fail = 0
    for (line in File(refPath).readLines()) {
        if (line.isBlank()) continue
        val (text, idsStr, maskStr) = line.split('\t')
        val refIds = idsStr.split(',').map { it.trim().toLong() }
        val refMask = maskStr.split(',').map { it.trim().toLong() }

        val enc = tok.encode(text)
        val gotIds = enc.inputIds.toList()
        val gotMask = enc.attentionMask.toList()

        if (gotIds == refIds && gotMask == refMask) {
            pass++
            println("PASS  \"${text.take(48)}\"")
        } else {
            fail++
            println("FAIL  \"$text\"")
            val n = maxOf(refIds.indexOfLast { it != 0L }, gotIds.indexOfLast { it != 0L }) + 2
            println("   ref ids: ${refIds.take(n)}")
            println("   got ids: ${gotIds.take(n)}")
            if (gotMask != refMask) {
                println("   ref msk: ${refMask.take(n)}")
                println("   got msk: ${gotMask.take(n)}")
            }
        }
    }
    println("\n=== $pass passed, $fail failed ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
