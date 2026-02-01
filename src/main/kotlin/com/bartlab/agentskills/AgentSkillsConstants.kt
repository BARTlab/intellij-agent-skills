package com.bartlab.agentskills

/**
 * Centralized storage for Agent Skills plugin constants.
 * All hardcoded values should be defined here for easier maintenance.
 */
object AgentSkillsConstants {
    
    /** Plugin name */
    const val PLUGIN_NAME = "Agent Skills"
    
    /** Notification group identifier */
    const val NOTIFICATION_GROUP_ID = "AgentSkills"
    
    // ============================================
    // MCP Server
    // ============================================
    
    /** MCP server version */
    const val MCP_SERVER_VERSION = "0.2.1"
    
    /** MCP server name */
    const val MCP_SERVER_NAME = "agent-skills"
    
    /** Default MCP server port */
    const val DEFAULT_MCP_PORT = 24680
    
    /** MCP server host */
    const val MCP_SERVER_HOST = "0.0.0.0"
    
    /** Graceful shutdown timeout for Ktor (ms) */
    const val KTOR_STOP_GRACE_MS = 200L
    
    /** Forced shutdown timeout for Ktor (ms) */
    const val KTOR_STOP_TIMEOUT_MS = 500L
    
    /** Delay before MCP server restart (ms) */
    const val MCP_RESTART_DELAY_MS = 300L
    
    /** Keep-alive timeout for SSE (ms) */
    const val SSE_KEEP_ALIVE_TIMEOUT_MS = 15_000L
    
    // ============================================
    // Skill Files
    // ============================================
    
    /** Skill description file name */
    const val SKILL_FILE_NAME = "SKILL.md"
    
    /** Skill source information file name */
    const val SKILL_SOURCE_FILE_NAME = "skill-source.json"
    
    /** Maximum search depth for SKILL.md files during scanning */
    const val SKILL_SCAN_MAX_DEPTH = 3
    
    /** Maximum search depth for SKILL.md files when adding */
    const val SKILL_DISCOVER_MAX_DEPTH = 5
    
    /** Maximum skill name length */
    const val SKILL_NAME_MAX_LENGTH = 64
    
    /** Minimum skill name length */
    const val SKILL_NAME_MIN_LENGTH = 1
    
    /** Regular expression for skill name validation */
    val SKILL_NAME_REGEX = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    
    // ============================================
    // Git Operations
    // ============================================
    
    /** Timeout for git operations (minutes) */
    const val GIT_TIMEOUT_MINUTES = 1L
    
    /** Prefix for temporary directories when cloning */
    const val TEMP_DIR_CLONE_PREFIX = "agent-skill-clone"
    
    /** Prefix for temporary directories when downloading directly */
    const val TEMP_DIR_DIRECT_PREFIX = "agent-skill-direct"
    
    /** Prefix for temporary directories when updating */
    const val TEMP_DIR_UPDATE_PREFIX = "agent-skill-update"
    
    // ============================================
    // UI
    // ============================================
    
    /** Table row height in settings (pixels, before scaling) */
    const val TABLE_ROW_HEIGHT = 24
    
    /** Table scroll area height (pixels, before scaling) */
    const val TABLE_VIEWPORT_HEIGHT = 200
    
    /** Checkbox column width (pixels, before scaling) */
    const val CHECKBOX_COLUMN_WIDTH = 40
    
    /** Preferred version column width (pixels, before scaling) */
    const val VERSION_COLUMN_PREFERRED_WIDTH = 80
    
    /** Maximum version column width (pixels, before scaling) */
    const val VERSION_COLUMN_MAX_WIDTH = 120
    
    /** Maximum number of characters per tooltip line */
    const val TOOLTIP_MAX_CHARS_PER_LINE = 80
    
    /** Settings panel padding */
    const val SETTINGS_PANEL_PADDING = 10
    
    // ============================================
    // Session Management
    // ============================================
    
    /** Maximum number of sessions to keep */
    const val MAX_SESSIONS_TO_KEEP = 50
    
    /** Maximum number of autocomplete results */
    const val MAX_COMPLETION_RESULTS = 100
    
    // ============================================
    // Default Paths
    // ============================================
    
    /** Default global skills path */
    const val DEFAULT_GLOBAL_SKILLS_PATH = "~/.agent-skills/skills"
    
    /** Default project local skills path */
    const val DEFAULT_PROJECT_SKILLS_PATH = ".agents/skills"
    
    /** Default custom skills path */
    const val DEFAULT_CUSTOM_PATH = "~/.agents/skills"
}
