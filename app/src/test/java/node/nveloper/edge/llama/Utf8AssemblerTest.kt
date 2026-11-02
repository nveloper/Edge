package node.nveloper.edge.llama

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8AssemblerTest {
    @Test
    fun asciiPassesThrough() {
        val a = Utf8Assembler()
        assertEquals("Hello", a.push("Hello".toByteArray()))
        assertEquals("", a.flush())
    }

    @Test
    fun hangulSplitAcrossTokensIsHeldUntilComplete() {
        val bytes = "안녕".toByteArray() // 2 chars × 3 bytes
        val a = Utf8Assembler()
        assertEquals("", a.push(bytes.copyOfRange(0, 1)))
        assertEquals("", a.push(bytes.copyOfRange(1, 2)))
        assertEquals("안", a.push(bytes.copyOfRange(2, 4)))
        assertEquals("녕", a.push(bytes.copyOfRange(4, 6)))
        assertEquals("", a.flush())
    }

    @Test
    fun emojiSplitInFourBytes() {
        val bytes = "a😀b".toByteArray()
        val a = Utf8Assembler()
        val out = StringBuilder()
        bytes.forEach { out.append(a.push(byteArrayOf(it))) }
        out.append(a.flush())
        assertEquals("a😀b", out.toString())
    }

    @Test
    fun danglingBytesAreFlushedLeniently() {
        val a = Utf8Assembler()
        assertEquals("x", a.push(byteArrayOf('x'.code.toByte(), 0xEC.toByte())))
        assertEquals("�", a.flush())
    }
}
