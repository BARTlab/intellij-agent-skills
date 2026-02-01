package com.bartlab.agentskills.service

import com.bartlab.agentskills.AgentSkillsConstants
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Service for file operations related to skills.
 *
 * Provides safe wrappers over standard file operations
 * with error logging.
 */
@Service(Service.Level.PROJECT)
class SkillFileService {
    private val log = Logger.getInstance(SkillFileService::class.java)

    /**
     * Creates a directory if it does not exist.
     *
     * @param dir Directory to create
     * @return true if directory exists or was created
     */
    fun ensureDirectory(dir: File): Boolean =
        dir.exists() || dir.mkdirs()

    /**
     * Reads text from a file.
     *
     * @param file File to read
     * @return File contents or null on error
     */
    fun readText(file: File): String? =
        runCatching { file.readText() }.getOrNull()

    /**
     * Writes text to a file.
     *
     * @param file File to write to
     * @param content Content to write
     * @return true if write was successful
     */
    fun writeText(file: File, content: String): Boolean =
        runCatching {
            file.writeText(content)
            true
        }.getOrElse {
            log.warn("Failed to write ${file.absolutePath}: ${it.message}")
            false
        }

    /**
     * Creates a temporary directory.
     *
     * @param prefix Directory name prefix
     * @return Created temporary directory
     */
    fun createTempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    /**
     * Downloads a file from URI.
     *
     * @param uri Source URI
     * @param target Target file
     * @return true if download was successful
     */
    fun downloadToFile(uri: URI, target: File): Boolean =
        runCatching {
            uri.toURL().openStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        }.getOrElse {
            log.warn("Failed to download $uri to ${target.absolutePath}: ${it.message}")
            false
        }

    /**
     * Recursively deletes a directory.
     *
     * @param dir Directory to delete
     */
    fun deleteRecursively(dir: File) {
        runCatching { dir.deleteRecursively() }
            .onFailure { log.warn("Failed to delete directory recursively ${dir.absolutePath}: ${it.message}") }
    }

    /**
     * Finds SKILL.md files in a directory.
     *
     * @param baseDir Base directory for search
     * @param maxDepth Maximum search depth
     * @return Sequence of found files
     */
    fun findSkillFiles(baseDir: File, maxDepth: Int): Sequence<File> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptySequence()
        return baseDir.walkTopDown()
            .maxDepth(maxDepth)
            .filter { it.name == AgentSkillsConstants.SKILL_FILE_NAME }
    }

    /**
     * Checks if a file is a symbolic link.
     */
    fun isSymbolicLink(file: File): Boolean =
        Files.isSymbolicLink(file.toPath())

    /**
     * Reads the target of a symbolic link.
     *
     * @param file Symbolic link
     * @return Path to target or null on error
     */
    fun readSymbolicLink(file: File): Path? =
        runCatching { Files.readSymbolicLink(file.toPath()) }.getOrNull()

    /**
     * Deletes a file if it exists.
     *
     * @param file File to delete
     * @return true if file was deleted or did not exist
     */
    fun deleteIfExists(file: File): Boolean =
        runCatching { Files.deleteIfExists(file.toPath()) }
            .getOrElse {
                log.warn("Failed to delete path if exists ${file.absolutePath}: ${it.message}")
                false
            }

    /**
     * Creates a symbolic link.
     *
     * @param link Path for the link to be created
     * @param target Link target
     * @return true if link was created successfully
     */
    fun createSymbolicLink(link: File, target: File): Boolean =
        runCatching {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        }.getOrElse {
            log.warn("Failed to create symlink ${link.absolutePath} -> ${target.absolutePath}: ${it.message}")
            false
        }
}