package com.bartlab.agentskills.settings

import com.bartlab.agentskills.AgentSkillsConstants
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * Persistent storage for Agent Skills plugin settings.
 *
 * Saves settings to `agent-skills.xml` file in project's `.idea` directory.
 * Use [getInstance] to get instance for a specific project.
 *
 * @see State for description of all settings
 */
@Service(Service.Level.PROJECT)
@State(name = "AgentSkillsSettings", storages = [Storage("agent-skills.xml")])
class SkillSettingsState : PersistentStateComponent<SkillSettingsState.State> {

    /**
     * Skill exposure mode determines which skills are visible to AI agents.
     */
    enum class SkillExposureMode {
        /**
         * Metadata is published for all discovered skills.
         * Full text/instructions are provided only on request via `skills` tool.
         */
        AUTO_ALL_METADATA,

        /**
         * Metadata is published only for user-selected skills.
         * Full text/instructions are provided only on request via `skills` tool.
         */
        SELECTED_ONLY_METADATA
    }

    /**
     * Plugin settings state.
     *
     * All fields have default values and can be changed by user
     * via settings UI or programmatically.
     */
    class State {
        /** Use custom path for skill search instead of standard directories */
        var useCustomPath: Boolean = false
        
        /** Custom path to directory with skills */
        var customPath: String = AgentSkillsConstants.DEFAULT_CUSTOM_PATH

        /** Skill exposure mode for MCP */
        var exposureMode: SkillExposureMode = SkillExposureMode.AUTO_ALL_METADATA
        
        /** List of selected skill names (used in SELECTED_ONLY_METADATA mode) */
        var selectedSkillNames: MutableList<String> = mutableListOf()

        /** Enable MCP server for integration with AI assistants */
        var integrateAiAssistant: Boolean = true
        
        /** Port for MCP server */
        var mcpPort: Int = AgentSkillsConstants.DEFAULT_MCP_PORT
        
        /** Prompt template for MCP */
        var mcpPromptTemplate: String = DEFAULT_MCP_PROMPT_TEMPLATE
    }

    private var myState = State()

    override fun getState(): State = myState
    
    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        /**
         * Default prompt template for MCP.
         *
         * Supports the following placeholders:
         * - `{{skills_xml}}` - list of available skills in XML format
         * - `{{session}}` - current session identifier
         */
        val DEFAULT_MCP_PROMPT_TEMPLATE: String = """
            Agent Skills (MCP) is enabled.

            Below is a list of available skills (metadata only). If any skill matches the user's request, call `skills` to load full text.

            {{skills_xml}}

            Tools:
            - `skills(name)` — load full skill text by name
            - `skillslist()` — refresh metadata list
            - `skillsset(mode, name)` — change skill visibility for this session

            Workflow:
            - Read the user's request.
            - Check the available skills list.
            - If a skill matches, call `skills`.
            - Follow the skill instructions.
            - In the final response, explicitly state that you used Agent Skills from the local MCP server.

            Session: {{session}}
        """.trimIndent()

        /**
         * Get settings instance for a project.
         *
         * @param project Project for which settings are needed
         * @return [SkillSettingsState] instance for the given project
         */
        fun getInstance(project: Project): SkillSettingsState =
            project.getService(SkillSettingsState::class.java)
    }
}