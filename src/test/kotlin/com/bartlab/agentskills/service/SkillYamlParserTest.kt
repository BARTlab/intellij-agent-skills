package com.bartlab.agentskills.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkillYamlParserTest {
    private val parser = SkillYamlParser()

    @Test
    fun `parse front matter extracts metadata and tools`() {
        val content = """
            ---
            name: sample-skill
            description: Sample description
            version: 1.2.3
            license: MIT
            compatibility: ide
            allowed-tools:
              - tool-a
              - tool-b
            metadata:
              foo: bar
            ---
            Body content here.
        """.trimIndent()

        val metadata = requireNotNull(parser.parseFrontMatter(content))

        assertEquals("sample-skill", metadata.name)
        assertEquals("Sample description", metadata.description)
        assertEquals("1.2.3", metadata.version)
        assertEquals("MIT", metadata.license)
        assertEquals("ide", metadata.compatibility)
        assertEquals(listOf("tool-a", "tool-b"), metadata.allowedTools)
        assertEquals("bar", metadata.metadata["foo"])
    }

    @Test
    fun `strip front matter returns body`() {
        val content = """
            ---
            name: sample
            ---
            # Title

            Text
        """.trimIndent()

        val body = parser.stripFrontMatter(content)

        assertTrue(body.startsWith("# Title"))
        assertTrue(body.contains("Text"))
    }
}