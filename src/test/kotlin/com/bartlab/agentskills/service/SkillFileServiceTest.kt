package com.bartlab.agentskills.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SkillFileServiceTest {
    private val service = SkillFileService()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `ensureDirectory creates missing directory`() {
        val dir = tempDir.resolve("skills").toFile()

        assertFalse(dir.exists())
        assertTrue(service.ensureDirectory(dir))
        assertTrue(dir.exists())
    }

    @Test
    fun `readText returns null for missing file`() {
        val file = tempDir.resolve("missing.txt").toFile()

        assertEquals(null, service.readText(file))
    }

    @Test
    fun `writeText writes file`() {
        val file = tempDir.resolve("skill.txt").toFile()

        assertTrue(service.writeText(file, "hello"))
        assertEquals("hello", file.readText())
    }

    @Test
    fun `findSkillFiles respects max depth`() {
        val base = tempDir.toFile()
        val shallowDir = File(base, "skill1").apply { mkdirs() }
        File(shallowDir, "SKILL.md").writeText("a")
        val deepDir = File(base, "level1/level2").apply { mkdirs() }
        File(deepDir, "SKILL.md").writeText("b")

        val files = service.findSkillFiles(base, 2).toList()

        assertEquals(1, files.size)
        assertEquals(shallowDir.absolutePath, files.first().parentFile.absolutePath)
    }
}