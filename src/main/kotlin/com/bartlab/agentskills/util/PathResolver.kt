package com.bartlab.agentskills.util

import java.io.File

/**
 * Utilities for working with file system paths.
 *
 * Provides cross-platform path handling with support
 * for `~` shorthand for home directory.
 */
object PathResolver {
    
    private val userHome: String by lazy { System.getProperty("user.home") }
    
    /**
     * Expands path, replacing `~` with user's home directory.
     *
     * @param path Path to expand
     * @return Absolute path
     */
    fun expandPath(path: String): String {
        val expanded = if (path.startsWith("~")) {
            userHome + path.substring(1)
        } else {
            path
        }
        return File(expanded).absolutePath
    }

    /**
     * Shortens path for UI display.
     *
     * Tries to make path relative to project base path
     * or replace home directory with `~`.
     *
     * @param path Full path
     * @param basePath Project base path (optional)
     * @return Shortened path for display
     */
    fun shortenPath(path: String, basePath: String?): String {
        val absPath = File(path).absolutePath
        
        // Try to make relative to project
        if (basePath != null) {
            val absBase = File(basePath).absolutePath
            if (absPath.startsWith(absBase)) {
                val relative = absPath.removePrefix(absBase).removePrefix(File.separator)
                return relative.ifEmpty { "." }
            }
        }
        
        // Replace home directory with ~
        if (absPath.startsWith(userHome)) {
            return "~" + absPath.substring(userHome.length)
        }
        
        return absPath
    }
}
