package com.bartlab.agentskills.service

import com.bartlab.agentskills.model.SkillSourceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SkillSourceParserTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `parse local path returns local source`() {
        val source = SkillSourceParser.parse(tempDir.toString())

        val parsed = requireNotNull(source)
        assertEquals(SkillSourceType.LOCAL, parsed.type)
        assertEquals(tempDir.toAbsolutePath().toString(), parsed.localPath)
        assertEquals(tempDir.toAbsolutePath().toString(), parsed.url)
    }

    @Test
    fun `parse direct url to skill md`() {
        val source = SkillSourceParser.parse("https://example.com/skills/skill.md")

        val parsed = requireNotNull(source)
        assertEquals(SkillSourceType.DIRECT_URL, parsed.type)
        assertEquals("https://example.com/skills/skill.md", parsed.url)
    }

    @Test
    fun `parse github tree url with subpath`() {
        val source = SkillSourceParser.parse("https://github.com/org/repo/tree/main/skills/core")

        val parsed = requireNotNull(source)
        assertEquals(SkillSourceType.GITHUB, parsed.type)
        assertEquals("https://github.com/org/repo.git", parsed.url)
        assertEquals("main", parsed.ref)
        assertEquals("skills/core", parsed.subpath)
    }

    @Test
    fun `parse gitlab tree url with subpath`() {
        val source = SkillSourceParser.parse("https://gitlab.com/org/repo/-/tree/dev/skills")

        val parsed = requireNotNull(source)
        assertEquals(SkillSourceType.GITLAB, parsed.type)
        assertEquals("https://gitlab.com/org/repo.git", parsed.url)
        assertEquals("dev", parsed.ref)
        assertEquals("skills", parsed.subpath)
    }

    @Test
    fun `parse shorthand`() {
        val source = SkillSourceParser.parse("org/repo/skills")

        val parsed = requireNotNull(source)
        assertEquals(SkillSourceType.GITHUB, parsed.type)
        assertEquals("https://github.com/org/repo.git", parsed.url)
        assertEquals("skills", parsed.subpath)
    }

    @Test
    fun `parse invalid returns null`() {
        val source = SkillSourceParser.parse("not a url")

        assertNull(source)
    }
}