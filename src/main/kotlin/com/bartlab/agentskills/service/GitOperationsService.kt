package com.bartlab.agentskills.service

import com.bartlab.agentskills.AgentSkillsConstants
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Service for executing Git operations.
 *
 * Provides methods for cloning repositories,
 * executing pull and other Git commands.
 */
@Service(Service.Level.PROJECT)
class GitOperationsService {
    private val log = Logger.getInstance(GitOperationsService::class.java)

    /**
     * Executes git clone with specified parameters.
     *
     * @param workingDir Directory to clone into
     * @param url Repository URL
     * @param ref Branch or tag (optional)
     * @param shallow Use shallow clone (--depth 1)
     * @return true if cloning was successful
     */
    fun clone(
        workingDir: File,
        url: String,
        ref: String? = null,
        shallow: Boolean = true
    ): Boolean {
        val args = mutableListOf("clone")
        
        if (shallow) {
            args.add("--depth")
            args.add("1")
        }
        
        if (!ref.isNullOrBlank()) {
            args.add("-b")
            args.add(ref)
        }
        
        args.add(url)
        args.add(".")
        
        return runCommand(workingDir, *args.toTypedArray())
    }

    /**
     * Executes git clone for update (without tags).
     *
     * @param workingDir Directory to clone into
     * @param url Repository URL
     * @param ref Branch or tag (optional)
     * @return true if cloning was successful
     */
    fun cloneForUpdate(
        workingDir: File,
        url: String,
        ref: String? = null
    ): Boolean {
        val args = mutableListOf("clone", "--depth", "1", "--no-tags")
        
        if (!ref.isNullOrBlank()) {
            args.add("-b")
            args.add(ref)
        }
        
        args.add(url)
        args.add(".")
        
        return runCommand(workingDir, *args.toTypedArray())
    }

    /**
     * Executes git pull in the specified directory.
     *
     * @param workingDir Directory with Git repository
     * @return true if pull was successful
     */
    fun pull(workingDir: File): Boolean = runCommand(workingDir, "pull")

    /**
     * Checks if a directory is a Git repository.
     *
     * @param dir Directory to check
     * @return true if directory contains .git
     */
    fun isGitRepository(dir: File): Boolean = File(dir, ".git").exists()

    /**
     * Executes an arbitrary Git command.
     *
     * @param workingDir Working directory
     * @param args Git command arguments
     * @return true if command executed successfully (exit code 0)
     */
    fun runCommand(workingDir: File, vararg args: String): Boolean {
        return try {
            val process = ProcessBuilder("git", *args)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exited = process.waitFor(AgentSkillsConstants.GIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)

            if (exited && process.exitValue() == 0) {
                log.info("Git command success: git ${args.joinToString(" ")}")
                log.debug("Output: $output")
                true
            } else {
                val exitInfo = if (exited) "exit code: ${process.exitValue()}" else "timeout"
                log.error("Git command failed: git ${args.joinToString(" ")} ($exitInfo)")
                log.error("Output: $output")
                false
            }
        } catch (e: Exception) {
            log.error("Error running git command: git ${args.joinToString(" ")}", e)
            false
        }
    }
}
