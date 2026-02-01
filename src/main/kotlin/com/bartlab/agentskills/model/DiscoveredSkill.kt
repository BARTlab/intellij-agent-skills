package com.bartlab.agentskills.model

import java.io.File

/**
 * Represents a skill discovered during directory scanning.
 *
 * Used when adding skills from external sources
 * (GitHub, GitLab, local directories).
 *
 * @property name Skill name from YAML frontmatter
 * @property dir Directory containing the skill
 * @property skillMd SKILL.md file
 */
data class DiscoveredSkill(
    val name: String,
    val dir: File,
    val skillMd: File
)
