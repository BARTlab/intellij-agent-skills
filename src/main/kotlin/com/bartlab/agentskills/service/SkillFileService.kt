package com.bartlab.agentskills.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class SkillFileService {
    private val log = Logger.getInstance(SkillFileService::class.java)

    fun ensureDirectory(dir: File): Boolean {
        return dir.exists() || dir.mkdirs()
    }

    fun readText(file: File): String? = runCatching { file.readText() }.getOrNull()

    fun writeText(file: File, content: String): Boolean {
        return runCatching {
            file.writeText(content)
            true
        }.getOrElse {
            log.warn("Failed to write ${file.absolutePath}: ${it.message}")
            false
        }
    }

    fun createTempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    fun downloadToFile(uri: URI, target: File): Boolean {
        return runCatching {
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
    }

    fun deleteRecursively(dir: File) {
        runCatching { dir.deleteRecursively() }
            .onFailure { log.warn("Failed to delete directory recursively ${dir.absolutePath}: ${it.message}") }
    }

    fun findSkillFiles(baseDir: File, maxDepth: Int): Sequence<File> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptySequence()
        return baseDir.walkTopDown()
            .maxDepth(maxDepth)
            .filter { it.name == "SKILL.md" }
    }

    fun isSymbolicLink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    fun readSymbolicLink(file: File): Path? = runCatching { Files.readSymbolicLink(file.toPath()) }.getOrNull()

    fun deleteIfExists(file: File): Boolean {
        return runCatching { Files.deleteIfExists(file.toPath()) }
            .getOrElse {
                log.warn("Failed to delete path if exists ${file.absolutePath}: ${it.message}")
                false
            }
    }

    fun createSymbolicLink(link: File, target: File): Boolean {
        return runCatching {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        }.getOrElse {
            log.warn("Failed to create symlink ${link.absolutePath} -> ${target.absolutePath}: ${it.message}")
            false
        }
    }
}