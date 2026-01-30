plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"

    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "com.bartlab.agentskills"

val buildNumberFile = file("build.number")
if (!buildNumberFile.exists()) {
    buildNumberFile.writeText("1")
}
val buildNumber = buildNumberFile.readText().trim()
version = "1.0.$buildNumber"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.3")
    }

    // MCP HTTP server inside IDE (Ktor)
    implementation("io.ktor:ktor-server-core-jvm:2.3.12")
    implementation("io.ktor:ktor-server-cio-jvm:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // MCP SDK
    implementation("io.modelcontextprotocol.sdk:mcp:0.17.2")
    implementation("io.modelcontextprotocol.sdk:mcp-core:0.17.2")
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson2:0.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.2")
    implementation("io.projectreactor:reactor-core:3.6.11")
    implementation("org.yaml:snakeyaml:2.2")

    compileOnly("org.junit.jupiter:junit-jupiter-api:5.10.2")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("253")
            untilBuild.set("253.*")
        }
    }

    signing {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishing {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}

apply(from = "test.gradle.kts")

tasks {
    runIde {
        jvmArgs("-Dide.browser.jcef.sandbox.enable=false")
    }

    val incrementBuildNumber by registering {
        doLast {
            val nextNumber = (buildNumber.toIntOrNull() ?: 0) + 1
            buildNumberFile.writeText(nextNumber.toString())
            println("Build number incremented to $nextNumber")
        }
    }

    buildPlugin {
        finalizedBy(incrementBuildNumber)
    }
}