package com.bartlab.agentskills.util

import java.io.File

object PathResolver {
    fun expandPath(path: String): String {
        var p = path
        if (p.startsWith("~")) {
            p = System.getProperty("user.home") + p.substring(1)
        }
        // Handle Windows paths if needed, though File handles / and \
        return File(p).absolutePath
    }

    fun shortenPath(path: String, basePath: String?): String {
        val absPath = File(path).absolutePath
        if (basePath != null) {
            val absBase = File(basePath).absolutePath
            if (absPath.startsWith(absBase)) {
                var relative = absPath.substring(absBase.length)
                if (relative.startsWith(File.separator)) {
                    relative = relative.substring(1)
                }
                return relative.ifEmpty { "." }
            }
        }
        
        val userHome = System.getProperty("user.home")
        if (absPath.startsWith(userHome)) {
            return "~" + absPath.substring(userHome.length)
        }
        
        return absPath
    }
}
