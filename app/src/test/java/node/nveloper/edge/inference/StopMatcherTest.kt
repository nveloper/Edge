package node.nveloper.edge.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopMatcherTest {
    private fun feed(stops: List<String>, vararg pieces: String): Pair<String, Boolean> {
        val m = StopMatcher(stops)
        val out = StringBuilder()
        for (p in pieces) {
            out.append(m.push(p))
            if (m.stopped) break
        }
        if (!m.stopped) out.append(m.flush())
        return out.toString() to m.stopped
    }

    @Test
    fun `no stops passes everything through`() {
        assertEquals("hello world" to false, feed(emptyList(), "hello", " world"))
    }

    @Test
    fun `cuts at a stop inside one piece`() {
        assertEquals("abc" to true, feed(listOf("STOP"), "abcSTOPdef"))
    }

    @Test
    fun `cuts at a stop split across pieces`() {
        assertEquals("abc" to true, feed(listOf("STOP"), "abcST", "O", "Pdef"))
    }

    @Test
    fun `holds back a possible prefix and releases it when it is not a stop`() {
        val m = StopMatcher(listOf("</end>"))
        assertEquals("a", m.push("a</"))
        assertEquals("</x", m.push("x"))
        assertFalse(m.stopped)
    }

    @Test
    fun `flushes held back text at the end`() {
        assertEquals("tail</e" to false, feed(listOf("</end>"), "tail</e"))
    }

    @Test
    fun `earliest of several stops wins`() {
        assertEquals("x" to true, feed(listOf("BB", "A"), "xABB"))
    }

    @Test
    fun `nothing is emitted after a stop`() {
        val m = StopMatcher(listOf("\n\n"))
        assertEquals("one", m.push("one\n\ntwo"))
        assertTrue(m.stopped)
        assertEquals("", m.push("more"))
        assertEquals("", m.flush())
    }

    @Test
    fun `korean text and emoji are not split`() {
        assertEquals("안녕하세요 😀" to false, feed(listOf("끝"), "안녕", "하세요 ", "😀"))
    }
}
