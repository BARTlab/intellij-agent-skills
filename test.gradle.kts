import java.io.File
import org.gradle.api.tasks.testing.Test

val logConfigContent = """
    handlers=java.util.logging.ConsoleHandler
    .level=INFO
    java.util.logging.ConsoleHandler.level=INFO
""".trimIndent()

val coroutinesFileName = "coroutines-javaagent.jar"
val logConfigFileName = "test-log.properties"
val logConfigRelativePath = "src/test/resources/$logConfigFileName"

fun ensureLogConfig(file: File, content: String) {
    if (!file.exists()) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    val logConfig = file(logConfigRelativePath)
    systemProperty("java.util.logging.config.file", logConfig)
    doFirst {
        val logConfigPath = systemProperties["java.util.logging.config.file"] as? String
        if (!logConfigPath.isNullOrBlank()) {
            ensureLogConfig(file(logConfigPath), logConfigContent)
        }
        val transformsDir = File(gradle.gradleUserHomeDir, "caches/${gradle.gradleVersion}/transforms")
        if (transformsDir.exists()) {
            transformsDir.walkTopDown()
                .maxDepth(4)
                .filter { it.isDirectory && it.name.startsWith("idea-") }
                .forEach { ideaDir ->
                    ensureLogConfig(File(ideaDir, logConfigFileName), logConfigContent)
                }
        }
        jvmArgumentProviders.removeIf { provider ->
            provider.asArguments().any { arg ->
                arg.contains(coroutinesFileName) || arg.contains(logConfigFileName)
            }
        }
        val filtered = (jvmArgs ?: emptyList()).filterNot { arg ->
            arg.contains(coroutinesFileName) || arg.contains(logConfigFileName)
        }
        val opensArgs = listOf(
            "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.desktop/javax.swing=ALL-UNNAMED"
        )
        val loggingArg = "-Djava.util.logging.config.file=${logConfig.absolutePath}"
        jvmArgs = (filtered + opensArgs + loggingArg).distinct()
    }
}