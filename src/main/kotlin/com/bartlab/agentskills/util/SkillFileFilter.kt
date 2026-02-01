package com.bartlab.agentskills.util

import com.bartlab.agentskills.AgentSkillsConstants
import java.io.File

/**
 * Utility for filtering files when copying skills.
 *
 * Determines which files should be excluded when copying
 * a skill from source to target directory.
 */
object SkillFileFilter {
    
    /**
     * File names that are always excluded.
     */
    private val EXCLUDED_FILES = setOf(
        "README.md",
        "metadata.json",
        AgentSkillsConstants.SKILL_SOURCE_FILE_NAME
    )
    
    /**
     * File name patterns to exclude (lowercase).
     */
    private val EXCLUDED_PATTERNS = listOf(
        "license",
        "package.json",
        "package-lock.json",
        "yarn.lock"
    )

    /**
     * Checks if a file is "junk" and should be excluded when copying.
     *
     * Excluded:
     * - README.md, metadata.json, skill-source.json
     * - Files starting with dot (hidden)
     * - Files starting with underscore (except __NOT_A_JUNK__)
     * - License files
     * - Package manager files (package.json, yarn.lock, etc.)
     *
     * @param file File to check
     * @return true if file should be excluded
     */
    fun isJunkFile(file: File): Boolean {
        val name = file.name
        val lowerName = name.lowercase()

        // Explicit exclusions
        if (name in EXCLUDED_FILES) return true
        
        // Files starting with underscore (except special marker)
        if (name.startsWith("_") && name != "__NOT_A_JUNK__") return true

        // Hidden files (starting with dot)
        if (lowerName.startsWith(".")) return true
        
        // License files and package manager files
        return EXCLUDED_PATTERNS.any { lowerName.contains(it) }
    }

    /**
     * Recursively copies a directory, excluding "junk" files.
     *
     * @param src Source directory
     * @param targetDir Target directory
     */
    fun copyFiltered(src: File, targetDir: File) {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        
        src.listFiles()?.forEach { file ->
            if (isJunkFile(file)) return@forEach
            
            val target = File(targetDir, file.name)
            if (file.isDirectory) {
                copyFiltered(file, target)
            } else {
                file.copyTo(target, overwrite = true)
            }
        }
    }
}
