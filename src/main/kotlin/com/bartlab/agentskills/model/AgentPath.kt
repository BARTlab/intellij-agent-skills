package com.bartlab.agentskills.model

/**
 * Path configuration for an AI agent.
 *
 * @property name Display name of the agent
 * @property projectPath Relative path in project for storing skills
 * @property globalPath Global path (in home directory) for storing skills
 */
data class AgentPath(
    val name: String,
    val projectPath: String,
    val globalPath: String
)

/**
 * Registry of supported AI agents and their paths.
 *
 * Contains configurations for popular AI tools
 * used by developers.
 */
@Suppress("SpellCheckingInspection")
object AgentPathRegistry {
    
    /**
     * List of all supported agents with their paths.
     * Sorted alphabetically for easier lookup.
     */
    val agents: List<AgentPath> = listOf(
        AgentPath("Amp", ".agents/skills", "~/.config/agents/skills"),
        AgentPath("Antigravity", ".agent/skills", "~/.gemini/antigravity/global_skills"),
        AgentPath("Claude Code", ".claude/skills", "~/.claude/skills"),
        AgentPath("Clawdbot", "skills", "~/.clawdbot/skills"),
        AgentPath("Cline", ".cline/skills", "~/.cline/skills"),
        AgentPath("Codex", ".codex/skills", "~/.codex/skills"),
        AgentPath("Command Code", ".commandcode/skills", "~/.commandcode/skills"),
        AgentPath("Continue", ".continue/skills", "~/.continue/skills"),
        AgentPath("Crush", ".crush/skills", "~/.config/crush/skills"),
        AgentPath("Cursor", ".cursor/skills", "~/.cursor/skills"),
        AgentPath("Droid", ".factory/skills", "~/.factory/skills"),
        AgentPath("Gemini CLI", ".gemini/skills", "~/.gemini/skills"),
        AgentPath("GitHub Copilot", ".github/skills", "~/.copilot/skills"),
        AgentPath("Goose", ".goose/skills", "~/.config/goose/skills"),
        AgentPath("Kilo Code", ".kilocode/skills", "~/.kilocode/skills"),
        AgentPath("Kiro CLI", ".kiro/skills", "~/.kiro/skills"),
        AgentPath("MCPJam", ".mcpjam/skills", "~/.mcpjam/skills"),
        AgentPath("Neovate", ".neovate/skills", "~/.neovate/skills"),
        AgentPath("OpenCode", ".opencode/skills", "~/.config/opencode/skills"),
        AgentPath("OpenHands", ".openhands/skills", "~/.openhands/skills"),
        AgentPath("Pi", ".pi/skills", "~/.pi/skills"),
        AgentPath("Qoder", ".qoder/skills", "~/.qoder/skills"),
        AgentPath("Qwen Code", ".qwen/skills", "~/.qwen/skills"),
        AgentPath("Roo Code", ".roo/skills", "~/.roo/skills"),
        AgentPath("Trae", ".trae/skills", "~/.trae/skills"),
        AgentPath("Windsurf", ".windsurf/skills", "~/.codeium/windsurf/skills"),
        AgentPath("Zencoder", ".zencoder/skills", "~/.zencoder/skills")
    )
    
    /**
     * Finds an agent by name (case-insensitive).
     *
     * @param name Agent name to search for
     * @return AgentPath or null if not found
     */
    fun findByName(name: String): AgentPath? =
        agents.find { it.name.equals(name, ignoreCase = true) }
    
    /**
     * Returns all unique project paths.
     */
    val allProjectPaths: List<String>
        get() = agents.map { it.projectPath }.distinct()
    
    /**
     * Returns all unique global paths.
     */
    val allGlobalPaths: List<String>
        get() = agents.map { it.globalPath }.distinct()
}
