package node.nveloper.edge.llama

import java.io.ByteArrayOutputStream

/**
 * Tokens are byte sequences and may split a multi-byte UTF-8 character (common for Korean and
 * emoji). Bytes are buffered until they form complete characters.
 */
class Utf8Assembler {
    private val pending = ByteArrayOutputStream()

    /** Appends [bytes] and returns the text that is now complete (possibly empty). */
    fun push(bytes: ByteArray): String {
        pending.write(bytes)
        val all = pending.toByteArray()
        val complete = completeLength(all)
        pending.reset()
        pending.write(all, complete, all.size - complete)
        return String(all, 0, complete, Charsets.UTF_8)
    }

    /** Whatever is left at the end of generation, decoded leniently. */
    fun flush(): String {
        val rest = pending.toByteArray()
        pending.reset()
        return String(rest, Charsets.UTF_8)
    }

    companion object {
        /** Length of the longest prefix that does not end inside a multi-byte character. */
        fun completeLength(b: ByteArray): Int {
            var i = b.size - 1
            var continuation = 0
            while (i >= 0 && continuation < 3 && (b[i].toInt() and 0xC0) == 0x80) {
                i--
                continuation++
            }
            if (i < 0) return b.size
            val lead = b[i].toInt() and 0xFF
            val expected = when {
                lead < 0x80 -> 1
                lead shr 5 == 0b110 -> 2
                lead shr 4 == 0b1110 -> 3
                lead shr 3 == 0b11110 -> 4
                else -> 1 // invalid lead byte: let the decoder replace it
            }
            return if (continuation + 1 >= expected) b.size else i
        }
    }
}
