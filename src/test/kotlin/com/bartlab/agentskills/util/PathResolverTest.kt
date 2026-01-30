package com.bartlab.agentskills.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class PathResolverTest {
    @Test
    fun `expand path replaces tilde`() {
        val userHome = System.getProperty("user.home")
        val expanded = PathResolver.expandPath("~/skills")

        assertEquals(File(userHome, "skills").absolutePath, expanded)
    }

    @Test
    fun `shorten path relative to base`() {
        val base = File(System.getProperty("user.home"), "project").absolutePath
        val path = File(base, "skills${File.separator}alpha").absolutePath

        val shortened = PathResolver.shortenPath(path, base)

        assertEquals("skills${File.separator}alpha", shortened)
    }

    @Test
    fun `shorten path returns dot for base`() {
        val base = File(System.getProperty("user.home"), "project").absolutePath

        val shortened = PathResolver.shortenPath(base, base)

        assertEquals(".", shortened)
    }

    @Test
    fun `shorten path uses tilde for user home`() {
        val userHome = System.getProperty("user.home")
        val path = File(userHome, "skills${File.separator}alpha").absolutePath

        val shortened = PathResolver.shortenPath(path, null)

        assertEquals("~${File.separator}skills${File.separator}alpha", shortened)
    }
}