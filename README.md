# Agent Skills Manager

[![JetBrains Plugins](https://img.shields.io/jetbrains/plugin/v/24680-agent-skills-manager?style=flat-square)](https://plugins.jetbrains.com/plugin/24680-agent-skills-manager)
[![License](https://img.shields.io/badge/license-MIT-blue.svg?style=flat-square)](LICENSE)
[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20Me%20A%20Coffee-Donate-orange?style=flat-square&logo=buy-me-a-coffee)](https://www.buymeacoffee.com/bartlab)

**Agent Skills Manager** is an IntelliJ IDEA plugin that transforms your development environment into a powerful "skills" server for AI assistants. It allows you to export toolsets, instructions, and project context to external AI tools via the **Model Context Protocol (MCP)**.

---

## ✨ What does this plugin do?

The plugin automates the process of sharing project-specific knowledge with your AI assistant. It scans your project for skill definitions (`SKILL.md` files) and provides them through an MCP interface.

### Key Features:
- 🚀 **MCP Server (SSE):** Built-in server supporting connections via Server-Sent Events. No need to run additional terminal processes.
- 🔍 **Smart Scanning:** Automatically finds skills in folders like `.agents/skills`, `.claude/skills`, `.cursor/skills`, `.roo/skills`, and many others (supports 25+ popular AI agents).
- 📦 **Skill Manager:** Install ready-to-use toolsets from external repositories (e.g., GitHub) or create your own directly in the IDE.
- 🔄 **Auto-update:** Keep your skills up to date by updating them from source repositories with a single click.
- 🛡 **Visibility Control:** Choose exactly which skills are available to the assistant in the current session ("Auto" and "Selected Only" modes).

---

## 🛠 Configuration

1. **Open Settings:** `Settings → Tools → Agent Skills`.
2. **Enable Integration:** Ensure the **Enable MCP server** checkbox is active.
3. **Configure Port:** The default port is `24680`. If it's occupied, you can change it here.
4. **Custom Paths (Optional):** If you store skills in a non-standard location, enable **Skill search directory** and specify the folder path.

---

## 🔌 Connecting your Assistant

The plugin operates as an SSE server. To connect most modern clients (Claude Desktop, Cursor, Roo Code, Windsurf), use the URL: `http://127.0.0.1:24680/sse`.

### Example for Claude Desktop (`claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "agent-skills": {
      "url": "http://127.0.0.1:24680/sse"
    }
  }
}
```

For quick setup, use the **Copy SSE Config** button in the plugin settings—it copies the ready-to-use JSON block to your clipboard.

### Quick Chat Start
In the IDE menu, select **Tools → Agent Skills List**. This opens a list of available skills and allows you to copy an MCP "preset" to start working with your assistant.

---

## 📝 Managing Skills

The plugin supports several ways to manage skills:

1. **Automatic:** Create a `.agents/skills/my-skill/SKILL.md` file in your project root. The plugin will detect it instantly.
2. **Install from GitHub:** In settings, click the **+** (Add) button, select **INSTALL** mode, and provide the repository URL or shorthand (e.g., `owner/repo`).
3. **Initialize:** Select **INIT** mode in the add window to create a new skill from a template.

### 🔍 Finding Skills
You can find a directory of community-contributed skills at [**skills.sh**](https://skills.sh/). Simply copy the repository URL and use the **INSTALL** feature to add them to your project.

### SKILL.md Format
A skill is a Markdown file with a YAML frontmatter:
```markdown
---
name: my-feature-expert
description: Expert in implementing new functionality
version: 1.0.0
---
# Instructions
You are an expert in our internal framework...
Use the following rules when writing code:
...
```

---

## 💡 Useful Information

### Available MCP Tools
The assistant gains access to the following tools:
- `skillslist` — get a list of all available Agent Skills (metadata only).
- `skills` — load the full content of a specific skill.
- `skillsset` — dynamically manage the list of active skills in the current session.

### Security
You have full control over what data is shared with the assistant. Use the **Selected Only** mode in settings to restrict access to verified skills only.

---

## ☕ Support

If this plugin helps you in your work, you can support the author:

[![Buy Me A Coffee](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://www.buymeacoffee.com/bartlab)

Developed with ❤️ by the **B@RT** team.
