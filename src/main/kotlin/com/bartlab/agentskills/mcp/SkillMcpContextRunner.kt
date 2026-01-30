package com.bartlab.agentskills.mcp

interface SkillMcpContextRunner {
    fun <T> run(block: () -> T): T
}