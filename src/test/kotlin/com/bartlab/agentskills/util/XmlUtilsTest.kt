package com.bartlab.agentskills.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class XmlUtilsTest {
    @Test
    fun `escape replaces xml entities`() {
        val raw = "5 < 6 & 7 > 3 \"quote\" 'single'"

        val escaped = XmlUtils.escape(raw)

        assertEquals("5 &lt; 6 &amp; 7 &gt; 3 &quot;quote&quot; &apos;single&apos;", escaped)
    }
}