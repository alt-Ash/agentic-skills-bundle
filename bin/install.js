#!/usr/bin/env node

import inquirer from 'inquirer';
import chalk from 'chalk';
import ora from 'ora';
import fs from 'fs-extra';
import path from 'path';
import os from 'os';
import { fileURLToPath } from 'url';
import { execFile } from 'child_process';
import { promisify } from 'util';
import YAML from 'yaml';

const execFileAsync = promisify(execFile);

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SKILLS_DIR = path.join(__dirname, '..', 'skills');
const COMMANDS_DIR = path.join(__dirname, '..', '.opencode', 'commands');
const AGENTS_DIR = path.join(__dirname, '..', 'agents');
const OB_TICKETS_MCP_SRC = path.join(__dirname, '..', 'mcp', 'issue-tickets');
const OB_TICKETS_MCP_INSTALL_DIR = path.join(os.homedir(), '.config', 'opencode', 'mcp', 'issue-tickets');
const SECURITY_SCANNER_MCP_SRC = path.join(__dirname, '..', 'mcp', 'security-scanner');
const SECURITY_SCANNER_MCP_INSTALL_DIR = path.join(os.homedir(), '.config', 'opencode', 'mcp', 'security-scanner');
const TEMPLATES_DIR = path.join(__dirname, '..', 'templates');


// ─── AI tool configurations ──────────────────────────────────────────────────

const AGENTS = {
  opencode: {
    name: 'OpenCode',
    globalPath: path.join(os.homedir(), '.config', 'opencode', 'skills'),
    projectFolder: '.opencode/skills',
    commandsGlobalPath: path.join(os.homedir(), '.config', 'opencode', 'commands'),
    commandsProjectFolder: '.opencode/commands',
    agentsGlobalPath: path.join(os.homedir(), '.config', 'opencode', 'agents'),
    agentsProjectFolder: '.opencode/agents',
    agentConfigFile: path.join(os.homedir(), '.config', 'opencode', 'opencode.json'),
    agentConfigKey: 'agent',
    supportsCommands: true,
    supportsAgents: true,
    detectPath: path.join(os.homedir(), '.config', 'opencode'),
  },
  claude: {
    name: 'Claude Code',
    globalPath: path.join(os.homedir(), '.claude', 'skills'),
    projectFolder: '.claude/skills',
    commandsGlobalPath: path.join(os.homedir(), '.claude', 'commands'),
    commandsProjectFolder: '.claude/commands',
    agentsGlobalPath: path.join(os.homedir(), '.claude', 'agents'),
    agentsProjectFolder: '.claude/agents',
    // Claude Code auto-discovers agents from .claude/agents/ — no JSON registration needed
    supportsCommands: true,
    supportsAgents: true,
    detectPath: path.join(os.homedir(), '.claude'),
  },
  cursor: {
    name: 'Cursor',
    globalPath: path.join(os.homedir(), '.cursor', 'rules'),
    projectFolder: '.cursor/rules',
    supportsCommands: false,
    agentsGlobalPath: path.join(os.homedir(), '.cursor', 'agents'),
    agentsProjectFolder: '.cursor/agents',
    supportsAgents: true,
    detectPath: path.join(os.homedir(), '.cursor'),
  },
  gemini: {
    name: 'Gemini CLI',
    globalPath: path.join(os.homedir(), '.gemini', 'skills'),
    projectFolder: '.gemini/skills',
    agentsGlobalPath: path.join(os.homedir(), '.gemini', 'agents'),
    agentsProjectFolder: '.gemini/agents',
    supportsCommands: false,
    supportsAgents: true,
    detectPath: path.join(os.homedir(), '.gemini'),
  },
  codex: {
    name: 'OpenAI Codex CLI',
    globalPath: path.join(os.homedir(), '.codex', 'skills'),
    projectFolder: '.codex/skills',
    agentsGlobalPath: path.join(os.homedir(), '.codex', 'agents'),
    agentsProjectFolder: '.codex/agents',
    supportsCommands: false,
    supportsAgents: true,
    detectPath: path.join(os.homedir(), '.codex'),
  },
  vscode: {
    name: 'VS Code (GitHub Copilot)',
    globalPath: path.join(os.homedir(), '.vscode', 'skills'),
    projectFolder: '.vscode/skills',
    agentsGlobalPath: path.join(os.homedir(), '.copilot', 'agents'),
    supportsCommands: false,
    supportsAgents: true,
    detectPath: getVSCodeUserDir(),
  },
  windsurf: {
    name: 'Windsurf',
    globalPath: path.join(os.homedir(), '.codeium', 'windsurf', 'skills'),
    projectFolder: '.windsurf/rules',
    supportsCommands: false,
    supportsAgents: false,
    detectPath: path.join(os.homedir(), '.codeium', 'windsurf'),
  },
  zed: {
    name: 'Zed AI',
    globalPath: path.join(os.homedir(), '.config', 'zed', 'skills'),
    projectFolder: '.zed/skills',
    supportsCommands: false,
    supportsAgents: false,
    detectPath: path.join(os.homedir(), '.config', 'zed'),
  },
};

// MCP server definitions per agent file, keyed by AI tool.
// opencode: type "local", command as array.
// claude:   type "stdio", command string + args array (.mcp.json / settings.json format).
const AGENT_MCP_SERVERS = {
  'react-browser-debugger': {
    opencode: {
      'chrome-devtools': {
        type: 'local',
        command: ['npx', '-y', 'chrome-devtools-mcp@latest', '--no-usage-statistics'],
      },
      playwright: {
        type: 'local',
        command: ['npx', '-y', '@playwright/mcp@latest', '--browser', 'chromium', '--headless', 'false'],
      },
    },
    claude: {
      'chrome-devtools': {
        type: 'stdio',
        command: 'npx',
        args: ['-y', 'chrome-devtools-mcp@latest', '--no-usage-statistics'],
      },
      playwright: {
        type: 'stdio',
        command: 'npx',
        args: ['-y', '@playwright/mcp@latest', '--browser', 'chromium', '--headless', 'false'],
      },
    },
  },
  'figma-style-migrator': {
    opencode: {
      'figma-mcp': {
        type: 'local',
        command: ['npx', '-y', '@figma/mcp'],
        environment: { FIGMA_ACCESS_TOKEN: '{env:FIGMA_ACCESS_TOKEN}' },
      },
    },
    claude: {
      'figma-mcp': {
        type: 'stdio',
        command: 'npx',
        args: ['-y', '@figma/mcp'],
        env: { FIGMA_ACCESS_TOKEN: '{env:FIGMA_ACCESS_TOKEN}' },
      },
    },
    cursor: {
      'figma-mcp': {
        type: 'stdio',
        command: 'npx',
        args: ['-y', '@figma/mcp'],
        env: { FIGMA_ACCESS_TOKEN: '{env:FIGMA_ACCESS_TOKEN}' },
      },
    },
    gemini: {
      'figma-mcp': {
        type: 'stdio',
        command: 'npx',
        args: ['-y', '@figma/mcp'],
        env: { FIGMA_ACCESS_TOKEN: '{env:FIGMA_ACCESS_TOKEN}' },
      },
    },
    codex: {
      'figma-mcp': {
        type: 'stdio',
        command: 'npx',
        args: ['-y', '@figma/mcp'],
        env: { FIGMA_ACCESS_TOKEN: '{env:FIGMA_ACCESS_TOKEN}' },
      },
    },
  },
};

// ─── MCP helpers ────────────────────────────────────────────────────────────

/** Returns the platform-appropriate VS Code User data directory. */
function getVSCodeUserDir() {
  if (process.platform === 'darwin') {
    return path.join(os.homedir(), 'Library', 'Application Support', 'Code', 'User');
  }
  if (process.platform === 'win32') {
    return path.join(process.env.APPDATA ?? os.homedir(), 'Code', 'User');
  }
  return path.join(os.homedir(), '.config', 'Code', 'User');
}

// MCP config file info per AI tool.
const MCP_CONFIG = {
  opencode: {
    globalFile: path.join(os.homedir(), '.config', 'opencode', 'opencode.json'),
    mcpKey: 'mcp',
    serverFormat: 'opencode',
  },
  claude: {
    // Dead for MCP install purposes: Claude Code never reads MCP servers from
    // settings.json, only from `.mcp.json` or `~/.claude.json`. installMcpServers/
    // uninstallMcpServers/detectInstalledMcpServers all special-case 'claude' to
    // shell out to the `claude mcp` CLI instead of touching this file.
    globalFile: path.join(os.homedir(), '.claude', 'settings.json'),
    mcpKey: 'mcpServers',
    serverFormat: 'stdio',
  },
  cursor: {
    globalFile: path.join(os.homedir(), '.cursor', 'mcp.json'),
    mcpKey: 'mcpServers',
    serverFormat: 'stdio',
  },
  vscode: {
    globalFile: path.join(getVSCodeUserDir(), 'mcp.json'),
    mcpKey: 'servers',
    serverFormat: 'stdio',
  },
  windsurf: {
    globalFile: path.join(os.homedir(), '.codeium', 'windsurf', 'mcp_config.json'),
    mcpKey: 'mcpServers',
    serverFormat: 'stdio',
  },
  zed: {
    globalFile: path.join(os.homedir(), '.config', 'zed', 'settings.json'),
    mcpKey: 'context_servers',
    serverFormat: 'zed',
  },
};

// ─── Global tools (Engram + Context7) ───────────────────────────────────────

/**
 * Builds the Engram MCP server config for a given tool key.
 * Engram must be installed locally (`brew install gentleman-programming/tap/engram`).
 */
function engramMcpConfig(toolKey) {
  if (toolKey === 'opencode') {
    return { type: 'local', command: ['engram', 'mcp'] };
  }
  if (toolKey === 'zed') {
    return {
      source: 'custom',
      command: 'engram',
      args: ['mcp'],
    };
  }
  // claude, cursor, vscode, windsurf → stdio format
  return { type: 'stdio', command: 'engram', args: ['mcp'] };
}

/**
 * Builds the Context7 MCP server config for a given tool key.
 * apiKey may be null/undefined for the free (rate-limited) tier.
 */
function context7McpConfig(toolKey, apiKey) {
  const apiKeyArg = apiKey ? ['--api-key', apiKey] : [];
  const headers = apiKey ? { CONTEXT7_API_KEY: apiKey } : {};

  if (toolKey === 'opencode') {
    // OpenCode supports both remote and local; remote is preferred (no npx startup cost)
    const cfg = {
      type: 'remote',
      url: 'https://mcp.context7.com/mcp',
      enabled: true,
    };
    if (apiKey) cfg.headers = headers;
    return cfg;
  }
  if (toolKey === 'zed') {
    return {
      source: 'custom',
      command: 'npx',
      args: ['-y', '@upstash/context7-mcp', ...apiKeyArg],
    };
  }
  if (toolKey === 'vscode') {
    // VS Code uses `servers` key with `type: "http"` for remote
    const cfg = { type: 'http', url: 'https://mcp.context7.com/mcp' };
    if (apiKey) cfg.headers = headers;
    return cfg;
  }
  if (toolKey === 'windsurf') {
    // Windsurf uses `serverUrl` key for remote
    const cfg = { serverUrl: 'https://mcp.context7.com/mcp' };
    if (apiKey) cfg.headers = headers;
    return cfg;
  }
  // claude, cursor → stdio format with npx
  return {
    type: 'stdio',
    command: 'npx',
    args: ['-y', '@upstash/context7-mcp', ...apiKeyArg],
  };
}

/**
 * Builds the Figma MCP server config for a given tool key.
 * Requires @figma/mcp to be available via npx.
 * The access token is referenced via {env:FIGMA_ACCESS_TOKEN} substitution.
 */
function figmaMcpConfig(toolKey) {
  const env = { FIGMA_ACCESS_TOKEN: '{env:FIGMA_ACCESS_TOKEN}' };

  if (toolKey === 'opencode') {
    return { type: 'local', command: ['npx', '-y', '@figma/mcp'], environment: env };
  }
  if (toolKey === 'zed') {
    return { source: 'custom', command: 'npx', args: ['-y', '@figma/mcp'], env };
  }
  // claude, cursor, vscode, windsurf → stdio format
  return { type: 'stdio', command: 'npx', args: ['-y', '@figma/mcp'], env };
}

// Skill-linked MCP servers: skill name → agent key → server definitions.
const figmaMcpEntries = {
  opencode: {
    'figma-mcp': {
      type: 'local',
      command: ['npx', '-y', '@figma/mcp'],
      environment: { FIGMA_ACCESS_TOKEN: '{env:FIGMA_ACCESS_TOKEN}' },
    },
  },
  claude: {
    'figma-mcp': {
      type: 'stdio',
      command: 'npx',
      args: ['-y', '@figma/mcp'],
      env: { FIGMA_ACCESS_TOKEN: '{env:FIGMA_ACCESS_TOKEN}' },
    },
  },
  cursor: {
    'figma-mcp': {
      type: 'stdio',
      command: 'npx',
      args: ['-y', '@figma/mcp'],
      env: { FIGMA_ACCESS_TOKEN: '{env:FIGMA_ACCESS_TOKEN}' },
    },
  },
  gemini: {
    'figma-mcp': {
      type: 'stdio',
      command: 'npx',
      args: ['-y', '@figma/mcp'],
      env: { FIGMA_ACCESS_TOKEN: '{env:FIGMA_ACCESS_TOKEN}' },
    },
  },
  codex: {
    'figma-mcp': {
      type: 'stdio',
      command: 'npx',
      args: ['-y', '@figma/mcp'],
      env: { FIGMA_ACCESS_TOKEN: '{env:FIGMA_ACCESS_TOKEN}' },
    },
  },
  vscode: {
    'figma-mcp': {
      type: 'stdio',
      command: 'npx',
      args: ['-y', '@figma/mcp'],
      env: { FIGMA_ACCESS_TOKEN: '{env:FIGMA_ACCESS_TOKEN}' },
    },
  },
  windsurf: {
    'figma-mcp': {
      type: 'stdio',
      command: 'npx',
      args: ['-y', '@figma/mcp'],
      env: { FIGMA_ACCESS_TOKEN: '{env:FIGMA_ACCESS_TOKEN}' },
    },
  },
  zed: {
    'figma-mcp': {
      source: 'custom',
      command: 'npx',
      args: ['-y', '@figma/mcp'],
      env: { FIGMA_ACCESS_TOKEN: '{env:FIGMA_ACCESS_TOKEN}' },
    },
  },
};

const SKILL_MCPS = {
  'figma-design-to-code': figmaMcpEntries,
  'figma-generate-library': figmaMcpEntries,
  'figma-code-connect': figmaMcpEntries,
  'mui-migration': {
    opencode: {
      'mui-mcp': {
        type: 'local',
        command: ['npx', '-y', '@mui/mcp@latest'],
      },
    },
    claude: {
      'mui-mcp': {
        type: 'stdio',
        command: 'npx',
        args: ['-y', '@mui/mcp@latest'],
      },
    },
    cursor: {
      'mui-mcp': {
        type: 'stdio',
        command: 'npx',
        args: ['-y', '@mui/mcp@latest'],
      },
    },
    vscode: {
      'mui-mcp': {
        type: 'stdio',
        command: 'npx',
        args: ['-y', '@mui/mcp@latest'],
      },
    },
    windsurf: {
      'mui-mcp': {
        type: 'stdio',
        command: 'npx',
        args: ['-y', '@mui/mcp@latest'],
      },
    },
    zed: {
      'mui-mcp-server': {
        command: {
          path: 'npx',
          args: ['-y', '@mui/mcp@latest'],
          env: {},
        },
      },
    },
  },
};

// Map skill name → command file names (without .md) that companion it.
const SKILL_COMMANDS = {
  'nodejs-version-migrator': ['migrate-node'],
  'cra-to-vite': ['cra-to-vite'],
  'vite-version-migrator': ['migrate-vite'],
  'mui-migration': ['migrate-mui'],
  'react-migration': ['migrate-react'],
  'secure-feature-gate': ['security-gate'],
};

// Map agent name → command file names (without .md) that companion it.
// Used the same way as SKILL_COMMANDS but keyed on agent file basename.
const AGENT_COMMANDS = {
  'issue-architect': ['new-issue'],
  'issue-implementer': ['implement-issue'],
  'pr-reviewer': ['pr-check'],
};

// Map agent name → extra files (relative to AGENTS_DIR) to copy alongside the agent.
const AGENT_COMPANION_FILES = {
  'security-auditor': ['audit-triage.sh', 'security-scan.sh'],
};

// Template files to install alongside agents for the project-initializer agent
const TEMPLATE_FILES = ['AGENT.md', 'ARCHITECTURE.md', 'CLAUDE.md', 'DESIGN.md', 'GLOSSARY.md', 'MEMORY.md'];


/**
 * Parses YAML frontmatter from a markdown file string.
 * Uses a real YAML parser so quoted keys, deep nesting, lists, and all
 * standard YAML scalars work correctly. Returns an empty object if no
 * frontmatter is present or parsing fails.
 */
function parseFrontmatter(content) {
  const match = content.match(/^---\r?\n([\s\S]*?)\r?\n---/);
  if (!match) return {};
  try {
    const parsed = YAML.parse(match[1]);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

/**
 * Returns the correct output filename for an agent given the target tool.
 * - OpenCode / Claude: <name>.md
 * - VS Code Copilot: <name>.agent.md
 */
function agentFileName(name, toolKey) {
  if (toolKey === 'vscode') return `${name}.agent.md`;
  return `${name}.md`;
}

/**
 * Transforms an agent markdown file (written in OpenCode frontmatter format)
 * into the correct format for the target AI tool.
 *
 * OpenCode source frontmatter keys used here:
 *   mode: primary | subagent | all
 *   description, temperature, color, model, permission, hidden, steps, top_p
 *
 * Target mappings:
 *   opencode  → no change (source format)
 *   claude    → name, description, color, model passed through; permission is
 *               mapped to disallowedTools on a best-effort basis (see the
 *               claude branch below for the exact rule — Claude Code's
 *               tools/disallowedTools are coarse allow/deny-by-tool-name, so
 *               OpenCode's per-bash-command-pattern `ask` gating has no
 *               equivalent and is intentionally not simulated)
 *   vscode    → rewrite as VS Code .agent.md format:
 *               description, name, tools (derived from permission), user-invocable
 *
 * Returns the transformed file content string.
 */
function transformAgentContent(content, toolKey, agentName) {
  if (toolKey === 'opencode') return content; // source format — no change

  const fmMatch = content.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/);
  if (!fmMatch) return content; // no frontmatter — pass through unchanged

  const fm = parseFrontmatter(content);
  const body = fmMatch[2];

  if (toolKey === 'claude') {
    // Claude Code requires name + description, and also supports tools/
    // disallowedTools/model/color natively. Map permission → disallowedTools
    // on a best-effort basis: Claude Code can only allow/deny a whole tool,
    // not gate individual bash command patterns the way OpenCode's
    // permission.bash object can, so an `ask`-gated or fine-grained-mixed
    // bash policy is left usable rather than approximated as a hard deny.
    const lines = ['---'];
    if (agentName) lines.push(`name: ${agentName}`);
    if (fm.description) lines.push(`description: ${fm.description}`);

    const perm = fm.permission ?? {};
    const disallowedTools = [];
    if (perm.edit === 'deny') disallowedTools.push('Edit');
    if (perm.write === 'deny') disallowedTools.push('Write');
    if (perm.webfetch === 'deny') disallowedTools.push('WebFetch');
    if (perm.task === 'deny') disallowedTools.push('Task');
    const bashFullyDenied =
      perm.bash === 'deny' ||
      (perm.bash && typeof perm.bash === 'object' && perm.bash['*'] === 'deny');
    if (bashFullyDenied) disallowedTools.push('Bash');
    if (disallowedTools.length > 0) {
      lines.push(`disallowedTools: ${disallowedTools.join(', ')}`);
    }

    if (fm.model) lines.push(`model: ${fm.model}`);
    // Quoted: an unquoted leading `#` (hex color) is parsed as a YAML comment.
    if (fm.color) lines.push(`color: "${fm.color}"`);
    lines.push('---');
    lines.push('');
    lines.push(body.trimStart());
    return lines.join('\n');
  }

  if (toolKey === 'vscode') {
    // VS Code Copilot .agent.md format.
    // - description → description
    // - mode: primary → user-invocable: true
    // - mode: subagent → user-invocable: false, disable-model-invocation: false
    // - permission.bash/edit/write allow → tools list
    const lines = ['---'];
    if (fm.description) lines.push(`description: ${fm.description}`);

    const isPrimary = fm.mode === 'primary' || fm.mode === 'all' || !fm.mode;
    lines.push(`user-invocable: ${isPrimary}`);

    // Derive a tools list from permission settings.
    // OpenCode permission keys → VS Code tool names
    const perm = fm.permission ?? {};
    const toolMap = {
      read: 'read',
      edit: 'edit',
      bash: 'terminal',
      webfetch: 'web/fetch',
      glob: 'search/codebase',
      grep: 'search/codebase',
    };
    const allowedTools = Object.entries(perm)
      .filter(([, v]) => v === 'allow')
      .map(([k]) => toolMap[k])
      .filter(Boolean);
    const uniqueTools = [...new Set(allowedTools)];
    if (uniqueTools.length > 0) {
      lines.push(`tools: [${uniqueTools.map((t) => `'${t}'`).join(', ')}]`);
    }

    if (fm.model) lines.push(`model: ${fm.model}`);
    lines.push('---');
    lines.push('');
    lines.push(body.trimStart());
    return lines.join('\n');
  }

  // Cursor / Windsurf / Zed don't support agents — should never reach here
  // since supportsAgents is false for them, but guard just in case.
  return content;
}

/**
 * Returns true if an existing agent config entry looks malformed and should be
 * rewritten (instead of preserved). Currently detects: any permission value
 * that is the empty string, which OpenCode rejects with
 * `Expected PermissionActionConfig, got ""`.
 */
function isMalformedAgentEntry(entry) {
  if (!entry || typeof entry !== 'object') return true;
  const perm = entry.permission;
  if (perm && typeof perm === 'object') {
    for (const v of Object.values(perm)) {
      if (v === '' || v === null) return true;
      if (typeof v === 'object' && v !== null) {
        for (const inner of Object.values(v)) {
          if (inner === '' || inner === null) return true;
        }
      }
    }
  }
  return false;
}

/**
 * Registers an agent in a tool's global JSON config file.
 * For OpenCode → opencode.json under "agent" key.
 * For Claude  → .claude/settings.json under "agents" key (if supported).
 * Skips if the agent key already exists AND the entry is well-formed.
 * Overwrites if the existing entry is malformed (e.g. empty-string permissions).
 * Returns { name, success, skipped, repaired, configFile }.
 */
async function registerAgentInConfig(toolKey, agentName, frontmatter) {
  const tool = AGENTS[toolKey];
  if (!tool.agentConfigFile) return null; // tool doesn't support JSON agent registration

  const configFile = tool.agentConfigFile;
  let config = {};
  if (await fs.pathExists(configFile)) {
    try {
      config = JSON.parse(await fs.readFile(configFile, 'utf8'));
    } catch {
      // leave as empty; will create fresh
    }
  }

  const agentSection = tool.agentConfigKey ?? 'agent';
  if (!config[agentSection]) config[agentSection] = {};

  const existing = config[agentSection][agentName];
  let repaired = false;
  if (existing !== undefined) {
    if (isMalformedAgentEntry(existing)) {
      repaired = true; // fall through to overwrite
    } else {
      return { name: agentName, success: true, skipped: true, configFile };
    }
  }

  // Build entry from frontmatter — only include recognised OpenCode agent fields
  const entry = {};
  const scalar = (k) => { if (frontmatter[k] !== undefined) entry[k] = frontmatter[k]; };
  scalar('description');
  scalar('mode');
  scalar('temperature');
  scalar('top_p');
  scalar('color');
  scalar('hidden');
  scalar('model');
  scalar('steps');
  entry.permission = (frontmatter.permission && typeof frontmatter.permission === 'object')
    ? frontmatter.permission
    : { edit: 'allow', write: 'allow', bash: 'allow' };

  // Prompt references the installed file (relative to the config file directory)
  const agentsRelDir = path.relative(path.dirname(configFile), tool.agentsGlobalPath);
  entry.prompt = `{file:./${agentsRelDir.replace(/\\/g, '/')}/${agentName}.md}`;

  config[agentSection][agentName] = entry;

  await fs.ensureDir(path.dirname(configFile));
  await fs.writeFile(configFile, JSON.stringify(config, null, 2) + '\n', 'utf8');

  return { name: agentName, success: true, skipped: false, repaired, configFile };
}

// ─── MCP build helpers ────────────────────────────────────────────────────────

const PM_MARKER_FILE = '.pm-marker';

async function readNodeModulesPm(dir) {
  try {
    return (await fs.readFile(path.join(dir, 'node_modules', PM_MARKER_FILE), 'utf8')).trim();
  } catch {
    return null;
  }
}

async function writeNodeModulesPm(dir, pm) {
  await fs.writeFile(path.join(dir, 'node_modules', PM_MARKER_FILE), pm, 'utf8');
}

/**
 * Removes dir/node_modules only if it wasn't already built by the given
 * package manager — a node_modules tree built by a different pm has a shape
 * npm's arborist can't reconcile and crashes on. When the pm matches, leave
 * the tree in place so `install` runs as a fast incremental update.
 */
async function cleanNodeModulesIfPmChanged(dir, pm) {
  if (await readNodeModulesPm(dir) === pm) return;
  await fs.remove(path.join(dir, 'node_modules'));
}

/**
 * Returns an env object using {env:VAR} placeholder syntax — only for opencode,
 * which is the only host with a confirmed, documented expansion mechanism.
 */
function opencodeEnvRefs(names) {
  return Object.fromEntries(names.map((name) => [name, `{env:${name}}`]));
}

/**
 * Returns an env object containing only defined, non-empty entries.
 * Skipped credentials must not become explicit empty-string overrides,
 * which would shadow whatever the shell profile already exports.
 */
function definedEnv(pairs) {
  return Object.fromEntries(Object.entries(pairs).filter(([, v]) => v !== undefined && v !== null && v !== ''));
}

function encodeAzureAccountsB64(azureOrgs = []) {
  if (azureOrgs.length === 0) return null;
  const json = JSON.stringify(Object.fromEntries(azureOrgs.map(({ name, url, token }) => [name, { url, token }])));
  return Buffer.from(json).toString('base64');
}

function encodeGithubAccountsB64(githubAccounts = []) {
  if (githubAccounts.length === 0) return null;
  const json = JSON.stringify(Object.fromEntries(githubAccounts.map(({ name, token }) => [name, token])));
  return Buffer.from(json).toString('base64');
}

/**
 * Writes Azure DevOps and GitHub credentials to the user's shell profile.
 *
 * azureOrgs:      Array of { name, url, token } — written as AZURE_DEVOPS_ACCOUNTS JSON.
 * githubAccounts: Array of { name, token }       — written as GITHUB_ACCOUNTS JSON.
 *
 * If the env var already exists in the profile it is NOT overwritten (manual edit required).
 */
async function writeObTicketsEnvVars({ azureOrgs = [], githubAccounts = [] }) {
  const shell = process.env.SHELL ?? '';
  const profileFile = shell.includes('zsh')
    ? path.join(os.homedir(), '.zshrc')
    : path.join(os.homedir(), '.bashrc');

  const existing = (await fs.pathExists(profileFile))
    ? await fs.readFile(profileFile, 'utf8')
    : '';

  const lines = [];

  const azureB64 = encodeAzureAccountsB64(azureOrgs);
  if (azureB64 && !existing.includes('AZURE_DEVOPS_ACCOUNTS_B64')) {
    const marker = '# Azure DevOps credentials (issue-tickets MCP)';
    if (!existing.includes(marker)) lines.push('', marker);
    lines.push(`export AZURE_DEVOPS_ACCOUNTS_B64='${azureB64}'`);
  }

  const githubB64 = encodeGithubAccountsB64(githubAccounts);
  if (githubB64 && !existing.includes('GITHUB_ACCOUNTS_B64')) {
    const marker = '# GitHub credentials (issue-tickets MCP)';
    if (!existing.includes(marker)) lines.push('', marker);
    lines.push(`export GITHUB_ACCOUNTS_B64='${githubB64}'`);
  }

  if (lines.length > 0) {
    await fs.appendFile(profileFile, lines.join('\n') + '\n', 'utf8');
  }

  return { profileFile, skipped: lines.length === 0 };
}

function resolveShellProfileFile() {
  const shell = process.env.SHELL ?? '';
  return shell.includes('zsh')
    ? path.join(os.homedir(), '.zshrc')
    : path.join(os.homedir(), '.bashrc');
}

function decodeAccountsB64(b64) {
  try {
    return JSON.parse(Buffer.from(b64, 'base64').toString('utf8'));
  } catch {
    return null;
  }
}

/**
 * Reads the issue-tickets Azure DevOps / GitHub accounts currently stored in the
 * user's shell profile (used by the "update token" flow to list what's
 * already configured before prompting for a replacement).
 */
async function readObTicketsAccounts() {
  const profileFile = resolveShellProfileFile();
  const existing = (await fs.pathExists(profileFile))
    ? await fs.readFile(profileFile, 'utf8')
    : '';

  const azureMatch = existing.match(/export AZURE_DEVOPS_ACCOUNTS_B64=['"]([^'"]*)['"]/);
  const azureDecoded = azureMatch ? decodeAccountsB64(azureMatch[1]) : null;
  const azureOrgs = azureDecoded
    ? Object.entries(azureDecoded).map(([name, { url, token }]) => ({ name, url, token }))
    : [];

  const githubMatch = existing.match(/export GITHUB_ACCOUNTS_B64=['"]([^'"]*)['"]/);
  const githubDecoded = githubMatch ? decodeAccountsB64(githubMatch[1]) : null;
  const githubAccounts = githubDecoded
    ? Object.entries(githubDecoded).map(([name, token]) => ({ name, token }))
    : [];

  return { azureOrgs, githubAccounts };
}

/**
 * Replaces (rather than skips) the Azure DevOps / GitHub credential blobs in
 * the user's shell profile — used to rotate an expired PAT. Re-encodes the
 * full azureOrgs/githubAccounts arrays and substitutes the existing export
 * line in place, or appends it if it isn't there yet.
 */
async function overwriteObTicketsEnvVars({ azureOrgs = [], githubAccounts = [] }) {
  const profileFile = resolveShellProfileFile();
  let existing = (await fs.pathExists(profileFile))
    ? await fs.readFile(profileFile, 'utf8')
    : '';

  const azureB64 = encodeAzureAccountsB64(azureOrgs);
  if (azureB64) {
    const line = `export AZURE_DEVOPS_ACCOUNTS_B64='${azureB64}'`;
    if (/export AZURE_DEVOPS_ACCOUNTS_B64=['"][^'"]*['"]/.test(existing)) {
      existing = existing.replace(/export AZURE_DEVOPS_ACCOUNTS_B64=['"][^'"]*['"]/, line);
    } else {
      const marker = '# Azure DevOps credentials (issue-tickets MCP)';
      existing += (existing.includes(marker) ? '' : `\n${marker}`) + `\n${line}\n`;
    }
  }

  const githubB64 = encodeGithubAccountsB64(githubAccounts);
  if (githubB64) {
    const line = `export GITHUB_ACCOUNTS_B64='${githubB64}'`;
    if (/export GITHUB_ACCOUNTS_B64=['"][^'"]*['"]/.test(existing)) {
      existing = existing.replace(/export GITHUB_ACCOUNTS_B64=['"][^'"]*['"]/, line);
    } else {
      const marker = '# GitHub credentials (issue-tickets MCP)';
      existing += (existing.includes(marker) ? '' : `\n${marker}`) + `\n${line}\n`;
    }
  }

  await fs.ensureDir(path.dirname(profileFile));
  await fs.writeFile(profileFile, existing, 'utf8');
  return { profileFile };
}

/**
 * Appends the Figma access token export to the user's shell profile.
 * Skips if already present. Returns the profile path written to.
 */
async function writeFigmaEnvVar({ accessToken }) {
  const shell = process.env.SHELL ?? '';
  const profileFile = shell.includes('zsh')
    ? path.join(os.homedir(), '.zshrc')
    : path.join(os.homedir(), '.bashrc');

  const existing = await fs.pathExists(profileFile)
    ? await fs.readFile(profileFile, 'utf8')
    : '';

  const lines = [];
  const marker = '# Figma MCP credentials';
  if (!existing.includes(marker)) lines.push('', marker);
  if (!existing.includes('FIGMA_ACCESS_TOKEN')) {
    lines.push(`export FIGMA_ACCESS_TOKEN="${accessToken}"`);
  }

  if (lines.length > 0) {
    await fs.appendFile(profileFile, lines.join('\n') + '\n', 'utf8');
  }

  return { profileFile, skipped: lines.length === 0 };
}

// ─── issue-tickets MCP ───────────────────────────────────────────────────────────

/**
 * Throws a clear, actionable error if `mvn` isn't on PATH. Unlike npm/pnpm
 * (interchangeable JS package managers with a documented fallback), there's
 * no equivalent alternate build tool to fall back to for a Maven project, so
 * this fails hard immediately rather than surfacing an opaque ENOENT deep
 * inside `execFileAsync('mvn', ...)`.
 */
async function checkMavenAvailable() {
  try {
    await execFileAsync('mvn', ['--version']);
  } catch {
    throw new Error(
      'Maven (`mvn`) is required to build the issue-tickets/security-scanner MCP servers ' +
      '(Java/Spring, not Node) but was not found on PATH. Install Java 21+ and Maven, then retry.',
    );
  }
}

/**
 * Builds the issue-tickets MCP server (Java/Spring, Maven) from source and
 * copies the resulting self-contained fat jar to
 * ~/.config/opencode/mcp/issue-tickets/. No separate dependency-install step
 * is needed post-build — the jar is complete.
 */
async function installObTicketsMcp() {
  const dest = OB_TICKETS_MCP_INSTALL_DIR;

  await checkMavenAvailable();
  await execFileAsync('mvn', ['-q', '-DskipTests', 'package'], { cwd: OB_TICKETS_MCP_SRC });

  await fs.ensureDir(dest);
  await fs.copy(
    path.join(OB_TICKETS_MCP_SRC, 'target', 'issue-tickets.jar'),
    path.join(dest, 'issue-tickets.jar'),
    { overwrite: true },
  );

  return { success: true, installDir: dest };
}

/**
 * Builds the issue-tickets MCP server config entry for a given tool.
 * opencode gets {env:VAR} placeholders; all other hosts get resolved values.
 * The server is a self-contained Spring Boot fat jar — launched via
 * `java -jar`, no separate runtime dependency install needed post-build.
 */
function obTicketsMcpConfig(toolKey, { azureAccountsB64, githubAccountsB64 } = {}) {
  const jarPath = path.join(OB_TICKETS_MCP_INSTALL_DIR, 'issue-tickets.jar');

  if (toolKey === 'opencode') {
    const names = ['AZURE_DEVOPS_ACCOUNTS_B64', 'GITHUB_ACCOUNTS_B64'];
    return { type: 'local', command: ['java', '-jar', jarPath], environment: opencodeEnvRefs(names) };
  }
  const env = definedEnv({
    AZURE_DEVOPS_ACCOUNTS_B64: azureAccountsB64,
    GITHUB_ACCOUNTS_B64: githubAccountsB64,
  });
  if (toolKey === 'zed') {
    return { source: 'custom', command: 'java', args: ['-jar', jarPath], env };
  }
  return { type: 'stdio', command: 'java', args: ['-jar', jarPath], env };
}

/**
 * Removes the issue-tickets install directory.
 */
async function uninstallObTicketsMcp() {
  if (await fs.pathExists(OB_TICKETS_MCP_INSTALL_DIR)) {
    await fs.remove(OB_TICKETS_MCP_INSTALL_DIR);
    return { success: true, skipped: false };
  }
  return { success: true, skipped: true };
}

// ─── security-scanner MCP ─────────────────────────────────────────────────────

/**
 * Builds the security-scanner MCP server (Java/Spring, Maven) from source
 * and copies the resulting self-contained fat jar to
 * ~/.config/opencode/mcp/security-scanner/.
 *
 * Unlike issue-tickets, this MCP needs no credentials — it is gated
 * entirely by a project-local allowlist file it reads at runtime, not by
 * install-time secrets.
 */
async function installSecurityScannerMcp() {
  const dest = SECURITY_SCANNER_MCP_INSTALL_DIR;

  await checkMavenAvailable();
  await execFileAsync('mvn', ['-q', '-DskipTests', 'package'], { cwd: SECURITY_SCANNER_MCP_SRC });

  await fs.ensureDir(dest);
  await fs.copy(
    path.join(SECURITY_SCANNER_MCP_SRC, 'target', 'security-scanner.jar'),
    path.join(dest, 'security-scanner.jar'),
    { overwrite: true },
  );

  return { success: true, installDir: dest };
}

/**
 * Builds the security-scanner MCP server config entry for a given tool.
 * No env vars needed — the allowlist gate is read from a project-local file
 * at runtime (`.security-scanner/allowlist.json`, resolved relative to the
 * host's working directory), not from install-time configuration. The
 * server is a self-contained Spring Boot fat jar — launched via `java -jar`.
 */
function securityScannerMcpConfig(toolKey) {
  const jarPath = path.join(SECURITY_SCANNER_MCP_INSTALL_DIR, 'security-scanner.jar');

  if (toolKey === 'opencode') {
    return { type: 'local', command: ['java', '-jar', jarPath] };
  }
  if (toolKey === 'zed') {
    return { source: 'custom', command: 'java', args: ['-jar', jarPath] };
  }
  return { type: 'stdio', command: 'java', args: ['-jar', jarPath] };
}

/**
 * Removes the security-scanner install directory.
 */
async function uninstallSecurityScannerMcp() {
  if (await fs.pathExists(SECURITY_SCANNER_MCP_INSTALL_DIR)) {
    await fs.remove(SECURITY_SCANNER_MCP_INSTALL_DIR);
    return { success: true, skipped: false };
  }
  return { success: true, skipped: true };
}

// ─── Detection ───────────────────────────────────────────────────────────────

/** Returns a map of { toolKey: boolean } indicating which tools are detected. */
async function detectInstalledTools() {
  const results = {};
  await Promise.all(
    Object.entries(AGENTS).map(async ([key, agent]) => {
      results[key] = agent.detectPath
        ? await fs.pathExists(agent.detectPath)
        : false;
    })
  );
  return results;
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

function printBanner() {
  console.log('');
  console.log(
    chalk.bold.hex('#FF6B35')(
      '  ╔═══════════════════════════════════════╗'
    )
  );
  console.log(
    chalk.bold.hex('#FF6B35')('  ║') +
      chalk.bold.white('   Agentic  ') +
      chalk.bold.hex('#FF8C42')('Skills Bundle Installer') +
      chalk.bold.hex('#FF6B35')('   ║')
  );
  console.log(
    chalk.bold.hex('#FF6B35')(
      '  ╚═══════════════════════════════════════╝'
    )
  );
  console.log('');
  console.log(
    chalk.dim('  Install AI agent skills for your projects or global setup.')
  );
  console.log('');
}

async function discoverSkills() {
  const categories = await fs.readdir(SKILLS_DIR);
  const skills = [];

  for (const category of categories) {
    const categoryPath = path.join(SKILLS_DIR, category);
    const stat = await fs.stat(categoryPath);
    if (!stat.isDirectory()) continue;

    const entries = await fs.readdir(categoryPath);
    for (const entry of entries) {
      const entryPath = path.join(categoryPath, entry);
      const entryStat = await fs.stat(entryPath);
      if (!entryStat.isDirectory()) continue;

      skills.push({
        name: `${chalk.dim(`[${category}]`)} ${chalk.cyan(entry)}`,
        value: { category, name: entry, sourcePath: entryPath },
        short: entry,
      });
    }
  }

  return skills;
}

/** Returns command file objects for the given selected skills. */
async function resolveCommands(selectedSkills) {
  const commands = [];
  for (const skill of selectedSkills) {
    const commandNames = SKILL_COMMANDS[skill.name] ?? [];
    for (const cmdName of commandNames) {
      const srcFile = path.join(COMMANDS_DIR, `${cmdName}.md`);
      if (await fs.pathExists(srcFile)) {
        commands.push({ name: cmdName, srcFile });
      }
    }
  }
  return commands;
}

/** Returns command file objects for the given selected agents. */
async function resolveAgentCommands(selectedAgentFiles) {
  const commands = [];
  for (const agent of selectedAgentFiles) {
    const commandNames = AGENT_COMMANDS[agent.name] ?? [];
    for (const cmdName of commandNames) {
      const srcFile = path.join(COMMANDS_DIR, `${cmdName}.md`);
      if (await fs.pathExists(srcFile)) {
        commands.push({ name: cmdName, srcFile });
      }
    }
  }
  return commands;
}

async function discoverAgents() {
  if (!(await fs.pathExists(AGENTS_DIR))) return [];
  const entries = await fs.readdir(AGENTS_DIR);
  const agents = [];
  for (const entry of entries) {
    if (!entry.endsWith('.md')) continue;
    const name = entry.replace(/\.md$/, '');
    const srcFile = path.join(AGENTS_DIR, entry);
    const content = await fs.readFile(srcFile, 'utf8');
    const frontmatter = parseFrontmatter(content);
    if (!frontmatter?.description) continue; // docs files (e.g. AGENTS.md) have no description
    agents.push({
      name: chalk.cyan(name),
      value: { name, srcFile, frontmatter },
      short: name,
    });
  }
  return agents;
}

async function installAgentFiles(agents, targetPath, toolKey) {
  await fs.ensureDir(targetPath);
  const results = [];
  for (const agentDef of agents) {
    const destFile = agentFileName(agentDef.name, toolKey);
    const dest = path.join(targetPath, destFile);
    try {
      const sourceContent = await fs.readFile(agentDef.srcFile, 'utf8');
      const transformedContent = transformAgentContent(sourceContent, toolKey, agentDef.name);
      await fs.writeFile(dest, transformedContent, 'utf8');
      results.push({ name: agentDef.name, success: true });
    } catch (err) {
      results.push({ name: agentDef.name, success: false, error: err.message });
    }

    // Copy companion files (e.g. shell scripts) that the agent depends on
    const companions = AGENT_COMPANION_FILES[agentDef.name] ?? [];
    for (const filename of companions) {
      const src = path.join(AGENTS_DIR, filename);
      const dst = path.join(targetPath, filename);
      try {
        await fs.copy(src, dst, { overwrite: true });
        // Preserve executable bit
        await fs.chmod(dst, 0o755);
      } catch (err) {
        // Non-fatal: log but don't fail the agent install
        results.push({ name: `${agentDef.name}/${filename}`, success: false, error: err.message });
      }
    }
  }
  return results;
}

async function installSkills(skills, targetPath) {
  await fs.ensureDir(targetPath);

  const results = [];
  for (const skill of skills) {
    const dest = path.join(targetPath, skill.name);
    try {
      await fs.copy(skill.sourcePath, dest, { overwrite: true });
      results.push({ skill: skill.name, success: true });
    } catch (err) {
      results.push({ skill: skill.name, success: false, error: err.message });
    }
  }
  return results;
}

async function installCommands(commands, targetPath) {
  await fs.ensureDir(targetPath);

  const results = [];
  for (const cmd of commands) {
    const dest = path.join(targetPath, `${cmd.name}.md`);
    try {
      await fs.copy(cmd.srcFile, dest, { overwrite: true });
      results.push({ name: cmd.name, success: true });
    } catch (err) {
      results.push({ name: cmd.name, success: false, error: err.message });
    }
  }
  return results;
}

async function installTemplates(agentsGlobalPath) {
  const templatesDir = path.join(agentsGlobalPath, 'templates');
  await fs.ensureDir(templatesDir);

  const results = [];
  const projectTemplatesDir = path.join(TEMPLATES_DIR, 'project');

  for (const file of TEMPLATE_FILES) {
    const src = path.join(projectTemplatesDir, file);
    const dest = path.join(templatesDir, file);
    try {
      await fs.copy(src, dest, { overwrite: true });
      results.push({ name: file, success: true });
    } catch (err) {
      results.push({ name: file, success: false, error: err.message });
    }
  }
  return results;
}

/** Returns merged MCP server definitions for the given selected agents and AI tool key. */
function resolveAgentMcpServers(selectedAgentFiles, toolKey) {
  const merged = {};
  for (const agent of selectedAgentFiles) {
    const servers = AGENT_MCP_SERVERS[agent.name]?.[toolKey];
    if (servers) Object.assign(merged, servers);
  }
  return merged;
}

/** Returns merged MCP server definitions for the given selected skills and AI tool key. */
function resolveSkillMcpServers(selectedSkills, toolKey) {
  const merged = {};
  for (const skill of selectedSkills) {
    const servers = SKILL_MCPS[skill.name]?.[toolKey];
    if (servers) Object.assign(merged, servers);
  }
  return merged;
}

/**
 * Writes servers not already present into a single config file.
 * Returns the set of server names that were actually written.
 */
async function installMcpServersToFile(servers, cfg, configFile) {
  let existing = {};
  if (await fs.pathExists(configFile)) {
    try { existing = JSON.parse(await fs.readFile(configFile, 'utf8')); } catch { /* leave empty */ }
  }
  const existingServers = existing[cfg.mcpKey] ?? {};
  const toInstall = Object.fromEntries(
    Object.entries(servers).filter(([name]) => existingServers[name] === undefined),
  );
  if (Object.keys(toInstall).length > 0) {
    existing[cfg.mcpKey] = { ...existingServers, ...toInstall };
    await fs.ensureDir(path.dirname(configFile));
    await fs.writeFile(configFile, JSON.stringify(existing, null, 2) + '\n', 'utf8');
  }
  return new Set(Object.keys(toInstall));
}

const CLAUDE_MCP_CONFIG_LABEL = '~/.claude.json (user scope, via `claude mcp add`)';

/**
 * Claude Code never reads MCP servers from settings.json — only from `.mcp.json`
 * (project scope) or `~/.claude.json` (user scope), and only the CLI writes the
 * exact schema it expects. So unlike every other tool here, Claude registration
 * shells out to `claude mcp add`/`claude mcp remove` instead of merging JSON directly.
 */
async function claudeMcpExists(name) {
  try {
    await execFileAsync('claude', ['mcp', 'get', name]);
    return true;
  } catch {
    return false;
  }
}

async function installClaudeMcpServer(name, serverConfig, { force = false } = {}) {
  if (!force && await claudeMcpExists(name)) {
    return { name, success: true, skipped: true, configFile: CLAUDE_MCP_CONFIG_LABEL };
  }
  const envArgs = Object.entries(serverConfig.env ?? {}).flatMap(([key, value]) => ['-e', `${key}=${value}`]);
  const args = [
    'mcp', 'add', '--scope', 'user',
    ...envArgs,
    '--transport', 'stdio',
    name, '--', serverConfig.command, ...(serverConfig.args ?? []),
  ];
  try {
    await execFileAsync('claude', args);
    return { name, success: true, skipped: false, configFile: CLAUDE_MCP_CONFIG_LABEL };
  } catch (err) {
    return { name, success: false, skipped: false, configFile: CLAUDE_MCP_CONFIG_LABEL, error: err.message };
  }
}

async function uninstallClaudeMcpServer(name) {
  try {
    await execFileAsync('claude', ['mcp', 'remove', name, '--scope', 'user']);
    return { name, success: true, skipped: false };
  } catch {
    return { name, success: true, skipped: true };
  }
}

/** Merges MCP server entries into all global config files for the given tool.
 *  Each file is checked independently — servers already present are skipped per file.
 *  Returns an array of result objects with { name, success, skipped, configFile },
 *  where skipped/installed status is based on the first config file.
 */
async function installMcpServers(servers, toolKey) {
  if (toolKey === 'claude') {
    // Each `claude mcp add` does its own non-atomic read-modify-write of
    // ~/.claude.json — running them concurrently races and silently drops entries.
    const results = [];
    for (const [name, serverConfig] of Object.entries(servers)) {
      results.push(await installClaudeMcpServer(name, serverConfig));
    }
    return results;
  }

  const cfg = MCP_CONFIG[toolKey];
  if (!cfg) return [];

  const configFiles = cfg.globalFiles ?? [cfg.globalFile];
  const [firstFile, ...remainingFiles] = configFiles;

  const installedInFirst = await installMcpServersToFile(servers, cfg, firstFile);
  for (const configFile of remainingFiles) {
    await installMcpServersToFile(servers, cfg, configFile);
  }

  return Object.keys(servers).map((name) => ({
    name,
    success: true,
    skipped: !installedInFirst.has(name),
    configFile: firstFile,
  }));
}

/**
 * Checks whether a server name is already registered for a tool — used by the
 * "update token" flow to find which tools need their issue-tickets entry refreshed.
 */
async function isMcpServerRegistered(name, toolKey) {
  if (toolKey === 'claude') {
    return claudeMcpExists(name);
  }
  const cfg = MCP_CONFIG[toolKey];
  if (!cfg) return false;
  const configFiles = cfg.globalFiles ?? [cfg.globalFile];
  for (const configFile of configFiles) {
    if (!(await fs.pathExists(configFile))) continue;
    try {
      const existing = JSON.parse(await fs.readFile(configFile, 'utf8'));
      if (existing[cfg.mcpKey]?.[name] !== undefined) return true;
    } catch { /* treat unreadable file as not registered */ }
  }
  return false;
}

/**
 * Unconditionally replaces a single server entry for a tool — unlike
 * installMcpServers, this never skips an entry that's already present.
 * Used to push a rotated credential (e.g. a replaced PAT) into a config that
 * already has the (now stale) entry.
 */
async function overwriteMcpServerEntry(name, serverConfig, toolKey) {
  if (toolKey === 'claude') {
    await uninstallClaudeMcpServer(name);
    return installClaudeMcpServer(name, serverConfig, { force: true });
  }

  const cfg = MCP_CONFIG[toolKey];
  if (!cfg) return { name, success: false, skipped: true, configFile: null };

  const configFiles = cfg.globalFiles ?? [cfg.globalFile];
  for (const configFile of configFiles) {
    let existing = {};
    if (await fs.pathExists(configFile)) {
      try { existing = JSON.parse(await fs.readFile(configFile, 'utf8')); } catch { /* leave empty */ }
    }
    existing[cfg.mcpKey] = { ...(existing[cfg.mcpKey] ?? {}), [name]: serverConfig };
    await fs.ensureDir(path.dirname(configFile));
    await fs.writeFile(configFile, JSON.stringify(existing, null, 2) + '\n', 'utf8');
  }

  return { name, success: true, skipped: false, configFile: configFiles[0] };
}

/**
 * Targeted flow for rotating a single expired issue-tickets PAT (Azure DevOps or
 * GitHub) without rerunning the full installer. Rewrites the shell profile
 * and re-registers the issue-tickets MCP for every tool where it's already
 * configured — everything else (skills, agents, hooks) is untouched.
 */
async function runTokenUpdate() {
  console.log('');
  console.log(chalk.bold.red('  Update token'));
  console.log(chalk.dim('  Replace an expired Azure DevOps or GitHub PAT used by the issue-tickets MCP.'));
  console.log('');

  const { azureOrgs, githubAccounts } = await readObTicketsAccounts();

  if (azureOrgs.length === 0 && githubAccounts.length === 0) {
    console.log(chalk.yellow('  No issue-tickets credentials found in your shell profile.'));
    console.log(chalk.dim('  Run Install / Quick install first to configure issue-tickets.'));
    console.log('');
    return;
  }

  const items = [
    ...azureOrgs.map((org, index) => ({
      kind: 'azure',
      index,
      name: `Azure DevOps — ${org.name}`,
    })),
    ...githubAccounts.map((account, index) => ({
      kind: 'github',
      index,
      name: `GitHub — ${account.name}`,
    })),
  ];

  const { selected } = await inquirer.prompt([
    {
      type: 'list',
      name: 'selected',
      message: chalk.bold('Which token has expired?'),
      choices: items.map((item) => ({ name: item.name, value: item })),
    },
  ]);

  const { token } = await inquirer.prompt([
    {
      type: 'password',
      name: 'token',
      message: chalk.bold(
        selected.kind === 'azure' ? 'New Azure Personal Access Token:' : 'New GitHub Personal Access Token:',
      ),
      mask: '*',
      validate: (v) => v.trim().length > 0 || 'Token cannot be blank.',
    },
  ]);

  if (selected.kind === 'azure') {
    azureOrgs[selected.index].token = token.trim();
  } else {
    githubAccounts[selected.index].token = token.trim();
  }

  const { profileFile } = await overwriteObTicketsEnvVars({ azureOrgs, githubAccounts });
  console.log('');
  console.log(chalk.dim(`  Updated credentials written to ${profileFile}`));

  const obTicketsToolKeys = [...Object.keys(MCP_CONFIG).filter((k) => k !== 'claude'), 'claude'];
  const refreshedTools = [];
  for (const toolKey of obTicketsToolKeys) {
    if (!(await isMcpServerRegistered('issue-tickets', toolKey))) continue;
    const serverConfig = obTicketsMcpConfig(toolKey, {
      azureAccountsB64: encodeAzureAccountsB64(azureOrgs),
      githubAccountsB64: encodeGithubAccountsB64(githubAccounts),
    });
    await overwriteMcpServerEntry('issue-tickets', serverConfig, toolKey);
    refreshedTools.push(AGENTS[toolKey]?.name ?? toolKey);
  }

  console.log('');
  if (refreshedTools.length > 0) {
    console.log(chalk.bold.green(`  ✔ Re-registered issue-tickets for: ${refreshedTools.join(', ')}`));
  } else {
    console.log(chalk.yellow('  issue-tickets isn\'t registered with any AI tool yet — run Install to add it.'));
  }
  console.log(chalk.dim('  Open a new terminal (or restart your shell) for the updated env vars to take effect.'));
  console.log('');
}

// ─── Summary ─────────────────────────────────────────────────────────────────

/**
 * resultsByTool: { [toolKey]: { skills, commands, agents, mcps, skillsPath, commandsPath, agentsPath } }
 */
function printSummary(resultsByTool) {
  console.log('');
  console.log(chalk.bold('  Installation Summary'));
  console.log(chalk.dim('  ' + '─'.repeat(40)));

  let totalSucceeded = 0;
  let totalFailed = 0;

  for (const [toolKey, r] of Object.entries(resultsByTool)) {
    const toolName = AGENTS[toolKey].name;
    console.log('');
    console.log(`  ${chalk.bold.yellow(toolName)}`);

    // Skills
    if (r.skills.length > 0) {
      for (const s of r.skills.filter((x) => x.success)) {
        console.log(`    ${chalk.green('✔')}  ${chalk.white(s.skill)} ${chalk.dim(`→ ${r.skillsPath}`)}`);
        totalSucceeded++;
      }
      for (const s of r.skills.filter((x) => !x.success)) {
        console.log(`    ${chalk.red('✖')}  ${chalk.white(s.skill)} ${chalk.dim(`— ${s.error}`)}`);
        totalFailed++;
      }
    }

    // Commands
    if (r.commands.length > 0) {
      for (const c of r.commands.filter((x) => x.success)) {
        console.log(`    ${chalk.green('✔')}  ${chalk.white('/' + c.name)} ${chalk.dim(`→ ${r.commandsPath}`)}`);
        totalSucceeded++;
      }
      for (const c of r.commands.filter((x) => !x.success)) {
        console.log(`    ${chalk.red('✖')}  ${chalk.white('/' + c.name)} ${chalk.dim(`— ${c.error}`)}`);
        totalFailed++;
      }
    }

    // Agents
    if (r.agents.length > 0) {
      for (const a of r.agents.filter((x) => x.success)) {
        console.log(`    ${chalk.green('✔')}  ${chalk.white('@' + a.name)} ${chalk.dim(`→ ${r.agentsPath}`)}`);
        totalSucceeded++;
      }
      for (const a of r.agents.filter((x) => !x.success)) {
        console.log(`    ${chalk.red('✖')}  ${chalk.white('@' + a.name)} ${chalk.dim(`— ${a.error}`)}`);
        totalFailed++;
      }
    }

    // Config registrations
    if (r.configRegs.length > 0) {
      for (const c of r.configRegs.filter((x) => x.success && !x.skipped && !x.repaired)) {
        console.log(`    ${chalk.green('✔')}  ${chalk.white('@' + c.name)} ${chalk.dim(`registered in ${c.configFile}`)}`);
        totalSucceeded++;
      }
      for (const c of r.configRegs.filter((x) => x.success && !x.skipped && x.repaired)) {
        console.log(`    ${chalk.green('✔')}  ${chalk.white('@' + c.name)} ${chalk.dim(`repaired malformed entry in ${c.configFile}`)}`);
        totalSucceeded++;
      }
      for (const c of r.configRegs.filter((x) => x.success && x.skipped)) {
        console.log(`    ${chalk.yellow('~')}  ${chalk.white('@' + c.name)} ${chalk.dim(`already in config — skipped`)}`);
      }
      for (const c of r.configRegs.filter((x) => !x.success)) {
        console.log(`    ${chalk.red('✖')}  ${chalk.white('@' + c.name)} ${chalk.dim(`config registration failed — ${c.error}`)}`);
        totalFailed++;
      }
    }

    // Templates
    if (r.templates.length > 0) {
      for (const t of r.templates.filter((x) => x.success)) {
        console.log(`    ${chalk.green('✔')}  ${chalk.white(t.name)} ${chalk.dim('(template)')}`);
        totalSucceeded++;
      }
      for (const t of r.templates.filter((x) => !x.success)) {
        console.log(`    ${chalk.red('✖')}  ${chalk.white(t.name)} ${chalk.dim(`— ${t.error}`)}`);
        totalFailed++;
      }
    }

    // MCPs
    if (r.mcps.length > 0) {
      for (const m of r.mcps.filter((x) => x.success && !x.skipped)) {
        console.log(`    ${chalk.green('✔')}  ${chalk.white(m.name)} ${chalk.dim(`(MCP → ${m.configFile})`)}`);
        totalSucceeded++;
      }
      for (const m of r.mcps.filter((x) => x.success && x.skipped)) {
        console.log(`    ${chalk.yellow('~')}  ${chalk.white(m.name)} ${chalk.dim('(MCP — already installed, skipped)')}`);
      }
      for (const m of r.mcps.filter((x) => !x.success)) {
        console.log(`    ${chalk.red('✖')}  ${chalk.white(m.name)} ${chalk.dim(`(MCP — ${m.error})`)}`);
        totalFailed++;
      }
    }

    if (r.skills.length === 0 && r.commands.length === 0 && r.agents.length === 0 && r.templates.length === 0 && r.configRegs.length === 0 && r.mcps.length === 0) {
      console.log(`    ${chalk.dim('nothing installed')}`);
    }
  }

  console.log('');
  if (totalSucceeded > 0) {
    console.log(`  ${chalk.bold.green(`${totalSucceeded} item${totalSucceeded > 1 ? 's' : ''} installed successfully`)}`);
  }
  if (totalFailed > 0) {
    console.log(`  ${chalk.bold.red(`${totalFailed} item${totalFailed > 1 ? 's' : ''} failed`)}`);
  }
  console.log('');
}

// ─── Main flow ───────────────────────────────────────────────────────────────

// ─── Uninstall helpers ────────────────────────────────────────────────────────

/**
 * Removes server names from a single config file.
 * Returns the set of names that were actually removed.
 */
async function uninstallMcpServersFromFile(serverNames, cfg, configFile) {
  if (!(await fs.pathExists(configFile))) return new Set();
  let existing = {};
  try { existing = JSON.parse(await fs.readFile(configFile, 'utf8')); } catch { return new Set(); }

  const section = existing[cfg.mcpKey] ?? {};
  const removed = new Set();
  for (const name of serverNames) {
    if (section[name] !== undefined) {
      delete section[name];
      removed.add(name);
    }
  }
  if (removed.size > 0) {
    existing[cfg.mcpKey] = section;
    await fs.writeFile(configFile, JSON.stringify(existing, null, 2) + '\n', 'utf8');
  }
  return removed;
}

/**
 * Removes a set of MCP server entries from all global config files for a tool.
 * Returns an array of { name, success, skipped, configFile } objects,
 * where skipped/removed status is based on the first config file.
 */
async function uninstallMcpServers(serverNames, toolKey) {
  if (toolKey === 'claude') {
    // Same non-atomic ~/.claude.json write race as installMcpServers — serialize.
    const results = [];
    for (const name of serverNames) {
      results.push(await uninstallClaudeMcpServer(name));
    }
    return results;
  }

  const cfg = MCP_CONFIG[toolKey];
  if (!cfg) return [];

  const configFiles = cfg.globalFiles ?? [cfg.globalFile];
  const [firstFile, ...remainingFiles] = configFiles;

  const removedFromFirst = await uninstallMcpServersFromFile(serverNames, cfg, firstFile);
  for (const configFile of remainingFiles) {
    await uninstallMcpServersFromFile(serverNames, cfg, configFile);
  }

  return serverNames.map((name) => ({
    name,
    success: true,
    skipped: !removedFromFirst.has(name),
    configFile: firstFile,
  }));
}

/**
 * Removes an agent's entry from the tool's global JSON config file.
 * Returns { name, success, skipped, configFile } or null.
 */
async function unregisterAgentFromConfig(toolKey, agentName) {
  const tool = AGENTS[toolKey];
  if (!tool.agentConfigFile) return null;

  const configFile = tool.agentConfigFile;
  if (!(await fs.pathExists(configFile))) {
    return { name: agentName, success: true, skipped: true, configFile };
  }

  let config = {};
  try {
    config = JSON.parse(await fs.readFile(configFile, 'utf8'));
  } catch {
    return { name: agentName, success: true, skipped: true, configFile };
  }

  const agentSection = tool.agentConfigKey ?? 'agent';
  if (!config[agentSection] || config[agentSection][agentName] === undefined) {
    return { name: agentName, success: true, skipped: true, configFile };
  }

  delete config[agentSection][agentName];
  await fs.writeFile(configFile, JSON.stringify(config, null, 2) + '\n', 'utf8');
  return { name: agentName, success: true, skipped: false, configFile };
}

/**
 * Prints the uninstall summary.
 */
function printUninstallSummary(resultsByTool) {
  console.log('');
  console.log(chalk.bold('  Uninstall Summary'));
  console.log(chalk.dim('  ' + '─'.repeat(40)));

  let totalRemoved = 0;
  let totalSkipped = 0;
  let totalFailed = 0;

  for (const [toolKey, r] of Object.entries(resultsByTool)) {
    const toolName = AGENTS[toolKey].name;
    console.log('');
    console.log(`  ${chalk.bold.yellow(toolName)}`);

    const printItem = (icon, label, detail) => console.log(`    ${icon}  ${chalk.white(label)} ${chalk.dim(detail)}`);

    for (const s of r.skills ?? []) {
      if (s.success && !s.skipped) { printItem(chalk.green('✔'), s.skill, `removed from ${r.skillsPath}`); totalRemoved++; }
      else if (s.skipped) { printItem(chalk.yellow('~'), s.skill, 'not found — skipped'); totalSkipped++; }
      else { printItem(chalk.red('✖'), s.skill, s.error ?? 'error'); totalFailed++; }
    }

    for (const c of r.commands ?? []) {
      if (c.success && !c.skipped) { printItem(chalk.green('✔'), '/' + c.name, `removed from ${r.commandsPath}`); totalRemoved++; }
      else if (c.skipped) { printItem(chalk.yellow('~'), '/' + c.name, 'not found — skipped'); totalSkipped++; }
      else { printItem(chalk.red('✖'), '/' + c.name, c.error ?? 'error'); totalFailed++; }
    }

    for (const a of r.agents ?? []) {
      if (a.success && !a.skipped) { printItem(chalk.green('✔'), '@' + a.name, `removed from ${r.agentsPath}`); totalRemoved++; }
      else if (a.skipped) { printItem(chalk.yellow('~'), '@' + a.name, 'not found — skipped'); totalSkipped++; }
      else { printItem(chalk.red('✖'), '@' + a.name, a.error ?? 'error'); totalFailed++; }
    }

    for (const c of r.configRegs ?? []) {
      if (c.success && !c.skipped) { printItem(chalk.green('✔'), '@' + c.name, `unregistered from ${c.configFile}`); totalRemoved++; }
      else if (c.skipped) { printItem(chalk.yellow('~'), '@' + c.name, 'not in config — skipped'); totalSkipped++; }
      else { printItem(chalk.red('✖'), '@' + c.name, c.error ?? 'error'); totalFailed++; }
    }

    for (const m of r.mcps ?? []) {
      if (m.success && !m.skipped) { printItem(chalk.green('✔'), m.name, `(MCP removed from ${m.configFile})`); totalRemoved++; }
      else if (m.skipped) { printItem(chalk.yellow('~'), m.name, '(MCP — not found, skipped)'); totalSkipped++; }
      else { printItem(chalk.red('✖'), m.name, m.error ?? 'error'); totalFailed++; }
    }

    const total = (r.skills?.length ?? 0) + (r.commands?.length ?? 0) + (r.agents?.length ?? 0) + (r.configRegs?.length ?? 0) + (r.mcps?.length ?? 0);
    if (total === 0) console.log(`    ${chalk.dim('nothing to remove')}`);
  }

  console.log('');
  if (totalRemoved > 0) console.log(`  ${chalk.bold.green(`${totalRemoved} item${totalRemoved > 1 ? 's' : ''} removed successfully`)}`);
  if (totalSkipped > 0) console.log(`  ${chalk.bold.yellow(`${totalSkipped} item${totalSkipped > 1 ? 's' : ''} not found — skipped`)}`);
  if (totalFailed > 0) console.log(`  ${chalk.bold.red(`${totalFailed} item${totalFailed > 1 ? 's' : ''} failed`)}`);
  console.log('');
}

// ─── Uninstall detection helpers ─────────────────────────────────────────────

/**
 * For each skill, returns true if it exists in ANY of the selected tools' global paths.
 * Returns a Set of installed skill names.
 */
async function detectInstalledSkills(skills, toolKeys) {
  const installed = new Set();
  await Promise.all(
    skills.map(async (skill) => {
      for (const toolKey of toolKeys) {
        const skillsPath = AGENTS[toolKey].globalPath;
        if (skillsPath && await fs.pathExists(path.join(skillsPath, skill.name))) {
          installed.add(skill.name);
          return;
        }
      }
    })
  );
  return installed;
}

/**
 * For each agent, returns true if it exists in ANY of the selected tools' global agents paths.
 * Returns a Set of installed agent names.
 */
async function detectInstalledAgents(agents, toolKeys) {
  const installed = new Set();
  await Promise.all(
    agents.map(async (agent) => {
      for (const toolKey of toolKeys) {
        const agentsPath = AGENTS[toolKey].agentsGlobalPath;
        if (!agentsPath) continue;
        const destFile = agentFileName(agent.name, toolKey);
        if (await fs.pathExists(path.join(agentsPath, destFile))) {
          installed.add(agent.name);
          return;
        }
      }
    })
  );
  return installed;
}

/**
 * For each command name, returns true if the .md file exists in the global commands path.
 * Returns a Set of installed command names.
 */
async function detectInstalledCommands(commandNames, toolKeys) {
  const installed = new Set();
  await Promise.all(
    commandNames.map(async (name) => {
      for (const toolKey of toolKeys) {
        const commandsPath = AGENTS[toolKey].commandsGlobalPath;
        if (!commandsPath) continue;
        if (await fs.pathExists(path.join(commandsPath, `${name}.md`))) {
          installed.add(name);
          return;
        }
      }
    })
  );
  return installed;
}

/**
 * For each MCP server name, returns true if it exists in the tool's MCP config.
 * Returns a Set of installed server names.
 */
async function detectInstalledMcpServers(serverNames, toolKey) {
  const installed = new Set();

  if (toolKey === 'claude') {
    await Promise.all(serverNames.map(async (name) => {
      try {
        await execFileAsync('claude', ['mcp', 'get', name]);
        installed.add(name);
      } catch {
        /* not installed */
      }
    }));
    return installed;
  }

  const cfg = MCP_CONFIG[toolKey];
  if (!cfg) return installed;
  if (!(await fs.pathExists(cfg.globalFile))) return installed;
  let existing = {};
  try {
    existing = JSON.parse(await fs.readFile(cfg.globalFile, 'utf8'));
  } catch {
    return installed;
  }
  const section = existing[cfg.mcpKey] ?? {};
  for (const name of serverNames) {
    if (section[name] !== undefined) installed.add(name);
  }
  return installed;
}

// ─── Uninstall flow ───────────────────────────────────────────────────────────

async function runUninstall(availableSkills, availableAgentFiles, detectedTools) {
  console.log('');
  console.log(chalk.bold.red('  Uninstaller'));
  console.log(chalk.dim('  Detects what is installed and removes only what you select.'));
  console.log('');

  // 1. Select AI tools (only detected ones shown pre-checked)
  const toolChoices = Object.entries(AGENTS).map(([key, agent]) => {
    const detected = detectedTools[key];
    return {
      name: detected
        ? `${chalk.cyan(agent.name)}  ${chalk.green('✔ detected')}`
        : chalk.dim(agent.name),
      value: key,
      short: agent.name,
      checked: detected,
    };
  });

  const { selectedTools } = await inquirer.prompt([
    {
      type: 'checkbox',
      name: 'selectedTools',
      message: chalk.bold('Which AI tools do you want to uninstall from?'),
      choices: toolChoices,
      pageSize: 10,
      validate: (v) => v.length > 0 || 'Select at least one AI tool.',
    },
  ]);

  // 2. Detect what is actually installed across selected tools
  const detectionSpinner = ora({ text: chalk.dim('Detecting installed items…'), color: 'yellow' }).start();

  const allSkillDefs = availableSkills.map((s) => s.value); // { category, name, sourcePath }
  const allAgentDefs = availableAgentFiles.map((a) => a.value);

  // Collect all possible command names from all skills and agents
  const allCommandNames = [
    ...new Set([
      ...allSkillDefs.flatMap((s) => SKILL_COMMANDS[s.name] ?? []),
      ...allAgentDefs.flatMap((a) => AGENT_COMMANDS[a.name] ?? []),
    ]),
  ];

  // Collect all possible MCP server names per tool
  const allSkillMcpNames = (toolKey) => Object.keys(
    allSkillDefs.reduce((acc, s) => Object.assign(acc, SKILL_MCPS[s.name]?.[toolKey] ?? {}), {})
  );
  const allAgentMcpNames = (toolKey) => Object.keys(
    allAgentDefs.reduce((acc, a) => Object.assign(acc, AGENT_MCP_SERVERS[a.name]?.[toolKey] ?? {}), {})
  );

  const [installedSkillNames, installedAgentNames, installedCommandNames] = await Promise.all([
    detectInstalledSkills(allSkillDefs, selectedTools),
    detectInstalledAgents(allAgentDefs, selectedTools),
    detectInstalledCommands(allCommandNames, selectedTools),
  ]);

  // Detect installed MCPs per tool
  const installedMcpsByTool = {};
  await Promise.all(
    selectedTools.map(async (toolKey) => {
      const names = [...allSkillMcpNames(toolKey), ...allAgentMcpNames(toolKey), 'engram', 'context7', 'figma-mcp', 'issue-tickets'];
      installedMcpsByTool[toolKey] = await detectInstalledMcpServers(names, toolKey);
    })
  );

  detectionSpinner.stop();

  // 3. Select skills to remove — only show installed ones, pre-checked
  let selectedSkills = [];
  const installedSkillChoices = availableSkills.filter((s) => installedSkillNames.has(s.value.name));

  if (installedSkillChoices.length > 0) {
    const result = await inquirer.prompt([
      {
        type: 'checkbox',
        name: 'selectedSkills',
        message: chalk.bold('Select skills to remove:'),
        choices: installedSkillChoices.map((s) => ({ ...s, checked: true })),
        pageSize: 20,
      },
    ]);
    selectedSkills = result.selectedSkills;
  } else {
    console.log(chalk.dim('  No installed skills detected — skipping.'));
  }

  // 4. Skills install target (only asked if skills selected)
  let skillsInstallTarget = 'global';
  let projectPath = null;

  if (selectedSkills.length > 0) {
    const { target } = await inquirer.prompt([
      {
        type: 'list',
        name: 'target',
        message: chalk.bold('Remove from:'),
        choices: [
          { name: `${chalk.cyan('Global')}  ${chalk.dim('— AI agent\'s global config')}`, value: 'global' },
          { name: `${chalk.magenta('Project')} ${chalk.dim('— specific project directory')}`, value: 'project' },
        ],
      },
    ]);
    skillsInstallTarget = target;

    if (target === 'project') {
      const { inputPath } = await inquirer.prompt([
        {
          type: 'input',
          name: 'inputPath',
          message: chalk.bold('Project path:'),
          default: process.cwd(),
          validate: async (input) => {
            const resolved = path.resolve(input);
            const exists = await fs.pathExists(resolved);
            if (!exists) return chalk.red(`Path does not exist: ${resolved}`);
            const stat = await fs.stat(resolved);
            if (!stat.isDirectory()) return chalk.red('Path must be a directory.');
            return true;
          },
          filter: (input) => path.resolve(input),
        },
      ]);
      projectPath = inputPath;
    }
  }

  // 5. Select agents to remove — only show installed ones, pre-checked
  const toolsSupportingAgents = selectedTools.filter((t) => AGENTS[t].supportsAgents);
  let selectedAgentFiles = [];

  if (toolsSupportingAgents.length > 0) {
    const installedAgentChoices = availableAgentFiles.filter((a) => installedAgentNames.has(a.value.name));
    if (installedAgentChoices.length > 0) {
      const result = await inquirer.prompt([
        {
          type: 'checkbox',
          name: 'selectedAgentFiles',
          message: chalk.bold('Select agents to remove:'),
          choices: installedAgentChoices.map((a) => ({ ...a, checked: true })),
          pageSize: 20,
        },
      ]);
      selectedAgentFiles = result.selectedAgentFiles;
    } else {
      console.log(chalk.dim('  No installed agents detected — skipping.'));
    }
  }

  // 6. Slash commands — only show if installed, pre-checked
  let removeCommands = false;
  let commandsToRemove = [];

  if (selectedTools.some((t) => AGENTS[t].supportsCommands)) {
    const skillCmds = selectedSkills.length > 0 ? await resolveCommands(selectedSkills) : [];
    const agentCmds = selectedAgentFiles.length > 0 ? await resolveAgentCommands(selectedAgentFiles) : [];
    const seen = new Set();
    const candidates = [...skillCmds, ...agentCmds].filter((c) => {
      if (seen.has(c.name)) return false;
      seen.add(c.name);
      return true;
    });
    commandsToRemove = candidates.filter((c) => installedCommandNames.has(c.name));

    if (commandsToRemove.length > 0) {
      const commandNames = commandsToRemove.map((c) => chalk.cyan('/' + c.name)).join(', ');
      const { wantRemoveCommands } = await inquirer.prompt([
        {
          type: 'confirm',
          name: 'wantRemoveCommands',
          message: chalk.bold('Also remove slash commands? ') + chalk.dim(`(${commandNames})`),
          default: true,
        },
      ]);
      removeCommands = wantRemoveCommands;
    }
  }

  // 7. MCP servers — only show installed ones, pre-checked
  let removeSkillMcps = false;
  let removeAgentMcps = false;

  const installedSkillMcpPreview = selectedTools.flatMap((toolKey) => {
    const servers = resolveSkillMcpServers(selectedSkills, toolKey);
    return Object.keys(servers)
      .filter((n) => installedMcpsByTool[toolKey]?.has(n))
      .map((n) => `${chalk.cyan(n)} ${chalk.dim(`(${AGENTS[toolKey].name})`)}`);
  });
  if (installedSkillMcpPreview.length > 0) {
    const { wantRemove } = await inquirer.prompt([
      {
        type: 'confirm',
        name: 'wantRemove',
        message: chalk.bold('Remove skill-linked MCP servers? ') + chalk.dim(`(${installedSkillMcpPreview.join(', ')})`),
        default: true,
      },
    ]);
    removeSkillMcps = wantRemove;
  }

  const installedAgentMcpPreview = selectedTools.flatMap((toolKey) => {
    const servers = resolveAgentMcpServers(selectedAgentFiles, toolKey);
    return Object.keys(servers)
      .filter((n) => installedMcpsByTool[toolKey]?.has(n))
      .map((n) => `${chalk.cyan(n)} ${chalk.dim(`(${AGENTS[toolKey].name})`)}`);
  });
  if (installedAgentMcpPreview.length > 0) {
    const { wantRemove } = await inquirer.prompt([
      {
        type: 'confirm',
        name: 'wantRemove',
        message: chalk.bold('Remove agent-linked MCP servers? ') + chalk.dim(`(${installedAgentMcpPreview.join(', ')})`),
        default: true,
      },
    ]);
    removeAgentMcps = wantRemove;
  }

  // 8. Global tools (Engram + Context7 + issue-tickets) — only show if installed in at least one tool
  let globalMcpsToRemove = [];

  const obTicketsInstalled = await fs.pathExists(path.join(OB_TICKETS_MCP_INSTALL_DIR, 'issue-tickets.jar'));
  const securityScannerInstalled = await fs.pathExists(path.join(SECURITY_SCANNER_MCP_INSTALL_DIR, 'security-scanner.jar'));
  const installedGlobalChoices = ['engram', 'context7', 'figma-mcp'].filter((name) =>
    selectedTools.some((toolKey) => installedMcpsByTool[toolKey]?.has(name))
  );
  const obTicketsMcpConfigInstalled = selectedTools.some((toolKey) => installedMcpsByTool[toolKey]?.has('issue-tickets'));
  const securityScannerMcpConfigInstalled = selectedTools.some((toolKey) => installedMcpsByTool[toolKey]?.has('security-scanner'));

  if (installedGlobalChoices.length > 0) {
    const { chosenGlobal } = await inquirer.prompt([
      {
        type: 'checkbox',
        name: 'chosenGlobal',
        message: chalk.bold('Remove global MCP tools from config?'),
        choices: installedGlobalChoices.map((name) => ({
          name: chalk.cyan(name.charAt(0).toUpperCase() + name.slice(1)),
          value: name,
          short: name,
          checked: true,
        })),
        pageSize: 10,
      },
    ]);
    globalMcpsToRemove = chosenGlobal;
  }

  if (obTicketsMcpConfigInstalled || obTicketsInstalled) {
    const { wantRemoveObTickets } = await inquirer.prompt([
      {
        type: 'confirm',
        name: 'wantRemoveObTickets',
        message: chalk.bold('Remove issue-tickets from MCP config?') + chalk.dim(' (installed files kept)'),
        default: true,
      },
    ]);
    if (wantRemoveObTickets) globalMcpsToRemove.push('issue-tickets');
  }

  if (securityScannerMcpConfigInstalled || securityScannerInstalled) {
    const { wantRemoveSecurityScanner } = await inquirer.prompt([
      {
        type: 'confirm',
        name: 'wantRemoveSecurityScanner',
        message: chalk.bold('Remove security-scanner from MCP config?') + chalk.dim(' (installed files kept)'),
        default: true,
      },
    ]);
    if (wantRemoveSecurityScanner) globalMcpsToRemove.push('security-scanner');
  }

  if (
    selectedSkills.length === 0 &&
    selectedAgentFiles.length === 0 &&
    !removeCommands &&
    !removeSkillMcps &&
    !removeAgentMcps &&
    globalMcpsToRemove.length === 0
  ) {
    console.log('');
    console.log(chalk.dim('  Nothing selected. Uninstall cancelled.'));
    console.log('');
    return;
  }

  // 8. Confirm
  console.log('');
  console.log(chalk.bold('  Ready to remove'));
  console.log(chalk.dim('  ' + '─'.repeat(40)));
  console.log(`  ${chalk.dim('Tools    :')} ${chalk.yellow(selectedTools.map((t) => AGENTS[t].name).join(', '))}`);
  if (selectedSkills.length > 0) {
    const label = skillsInstallTarget === 'project' ? `project (${projectPath})` : 'global';
    console.log(`  ${chalk.dim('Skills   :')} ${chalk.cyan(selectedSkills.map((s) => s.name).join(', '))} ${chalk.dim(`[${label}]`)}`);
  }
  if (removeCommands && commandsToRemove.length > 0) {
    console.log(`  ${chalk.dim('Commands :')} ${chalk.cyan(commandsToRemove.map((c) => '/' + c.name).join(', '))} ${chalk.dim('[global]')}`);
  }
  if (selectedAgentFiles.length > 0) {
    console.log(`  ${chalk.dim('Agents   :')} ${chalk.cyan(selectedAgentFiles.map((a) => '@' + a.name).join(', '))} ${chalk.dim('[global]')}`);
  }
  if (removeSkillMcps || removeAgentMcps || globalMcpsToRemove.length > 0) {
    console.log(`  ${chalk.dim('MCPs     :')} ${chalk.dim('[global config]')}`);
  }
  console.log('');

  const { confirmed } = await inquirer.prompt([
    {
      type: 'confirm',
      name: 'confirmed',
      message: chalk.bold.red('Proceed with uninstall?'),
      default: false,
    },
  ]);

  if (!confirmed) {
    console.log('');
    console.log(chalk.dim('  Uninstall cancelled.'));
    console.log('');
    return;
  }

  // 9. Execute removal
  const resultsByTool = {};

  for (const toolKey of selectedTools) {
    const tool = AGENTS[toolKey];
    const toolResults = { skills: [], commands: [], agents: [], configRegs: [], mcps: [], skillsPath: null, commandsPath: null, agentsPath: null };

    // Skills
    if (selectedSkills.length > 0) {
      const skillsPath = skillsInstallTarget === 'global'
        ? tool.globalPath
        : path.join(projectPath, tool.projectFolder);
      toolResults.skillsPath = skillsPath;

      const spinner = ora({ text: chalk.dim(`[${tool.name}] Removing skills…`), color: 'cyan' }).start();
      for (const skill of selectedSkills) {
        const dest = path.join(skillsPath, skill.name);
        try {
          if (await fs.pathExists(dest)) {
            await fs.remove(dest);
            toolResults.skills.push({ skill: skill.name, success: true, skipped: false });
          } else {
            toolResults.skills.push({ skill: skill.name, success: true, skipped: true });
          }
        } catch (err) {
          toolResults.skills.push({ skill: skill.name, success: false, error: err.message });
        }
      }
      spinner.stop();
    }

    // Commands
    if (tool.supportsCommands && removeCommands && commandsToRemove.length > 0) {
      const hasAgentCommands = selectedAgentFiles.some((a) => (AGENT_COMMANDS[a.name] ?? []).length > 0);
      const isGlobal = hasAgentCommands || skillsInstallTarget === 'global' || selectedSkills.length === 0;
      const commandsPath = isGlobal
        ? tool.commandsGlobalPath
        : path.join(projectPath, tool.commandsProjectFolder);
      toolResults.commandsPath = commandsPath;

      const spinner = ora({ text: chalk.dim(`[${tool.name}] Removing commands…`), color: 'cyan' }).start();
      for (const cmd of commandsToRemove) {
        const dest = path.join(commandsPath, `${cmd.name}.md`);
        try {
          if (await fs.pathExists(dest)) {
            await fs.remove(dest);
            toolResults.commands.push({ name: cmd.name, success: true, skipped: false });
          } else {
            toolResults.commands.push({ name: cmd.name, success: true, skipped: true });
          }
        } catch (err) {
          toolResults.commands.push({ name: cmd.name, success: false, error: err.message });
        }
      }
      spinner.stop();
    }

    // Agents
    if (tool.supportsAgents && selectedAgentFiles.length > 0) {
      const agentsPath = tool.agentsGlobalPath;
      toolResults.agentsPath = agentsPath;

      const spinner = ora({ text: chalk.dim(`[${tool.name}] Removing agents…`), color: 'cyan' }).start();
      for (const agentDef of selectedAgentFiles) {
        const destFile = agentFileName(agentDef.name, toolKey);
        const dest = path.join(agentsPath, destFile);
        try {
          if (await fs.pathExists(dest)) {
            await fs.remove(dest);
            toolResults.agents.push({ name: agentDef.name, success: true, skipped: false });
          } else {
            toolResults.agents.push({ name: agentDef.name, success: true, skipped: true });
          }
        } catch (err) {
          toolResults.agents.push({ name: agentDef.name, success: false, error: err.message });
        }

        // Remove companion files
        const companions = AGENT_COMPANION_FILES[agentDef.name] ?? [];
        for (const filename of companions) {
          const dst = path.join(agentsPath, filename);
          try {
            if (await fs.pathExists(dst)) await fs.remove(dst);
          } catch {
            // non-fatal
          }
        }
      }
      spinner.stop();

      // Unregister from JSON config
      if (tool.agentConfigFile) {
        const regSpinner = ora({ text: chalk.dim(`[${tool.name}] Removing agent config entries…`), color: 'cyan' }).start();
        for (const agentDef of selectedAgentFiles) {
          try {
            const result = await unregisterAgentFromConfig(toolKey, agentDef.name);
            if (result) toolResults.configRegs.push(result);
          } catch (err) {
            toolResults.configRegs.push({ name: agentDef.name, success: false, error: err.message, configFile: tool.agentConfigFile });
          }
        }
        regSpinner.stop();
      }
    }

    // MCPs
    const mcpSpinner = ora({ text: chalk.dim(`[${tool.name}] Cleaning MCP config…`), color: 'cyan' });

    if (removeSkillMcps) {
      const servers = resolveSkillMcpServers(selectedSkills, toolKey);
      const names = Object.keys(servers);
      if (names.length > 0) {
        mcpSpinner.start();
        try {
          toolResults.mcps.push(...await uninstallMcpServers(names, toolKey));
        } catch (err) {
          toolResults.mcps.push(...names.map((name) => ({ name, success: false, error: err.message })));
        }
        mcpSpinner.stop();
      }
    }

    if (removeAgentMcps) {
      const servers = resolveAgentMcpServers(selectedAgentFiles, toolKey);
      const names = Object.keys(servers);
      if (names.length > 0) {
        mcpSpinner.start();
        try {
          toolResults.mcps.push(...await uninstallMcpServers(names, toolKey));
        } catch (err) {
          toolResults.mcps.push(...names.map((name) => ({ name, success: false, error: err.message })));
        }
        mcpSpinner.stop();
      }
    }

    if (globalMcpsToRemove.length > 0) {
      mcpSpinner.start();
      try {
        toolResults.mcps.push(...await uninstallMcpServers(globalMcpsToRemove, toolKey));
      } catch (err) {
        toolResults.mcps.push(...globalMcpsToRemove.map((name) => ({ name, success: false, error: err.message })));
      }
      mcpSpinner.stop();
    }

    resultsByTool[toolKey] = toolResults;
  }

  printUninstallSummary(resultsByTool);
}

// ─── Main flow ────────────────────────────────────────────────────────────────

async function main() {
  // Detect uninstall mode: --uninstall flag OR invoked as agentic-skills-uninstall
  const isUninstallBin = path.basename(process.argv[1] ?? '').replace(/\.js$/, '') === 'uninstall';
  const hasUninstallFlag = process.argv.includes('--uninstall');
  let mode = isUninstallBin || hasUninstallFlag ? 'uninstall' : null;

  if (!mode) {
    printBanner();
    const { action } = await inquirer.prompt([
      {
        type: 'list',
        name: 'action',
        message: chalk.bold('What do you want to do?'),
        choices: [
          { name: `${chalk.bold.green('Quick install')} — select tools, install everything globally (recommended)`, value: 'install-quick' },
          { name: `${chalk.green('Install')}   — add skills, agents, and commands`, value: 'install' },
          { name: `${chalk.yellow('Update token')} — replace an expired PAT for issue-tickets`, value: 'update-token' },
          { name: `${chalk.red('Uninstall')} — remove skills, agents, and commands`, value: 'uninstall' },
        ],
      },
    ]);
    mode = action;
  } else {
    printBanner();
  }

  // 1. Discover available skills and agent files
  const spinner = ora({ text: chalk.dim('Discovering skills & agents…'), color: 'yellow' }).start();
  let availableSkills;
  let availableAgentFiles;
  let detectedTools;
  try {
    [availableSkills, availableAgentFiles, detectedTools] = await Promise.all([
      discoverSkills(),
      discoverAgents(),
      detectInstalledTools(),
    ]);
    spinner.stop();
  } catch (err) {
    spinner.fail(chalk.red('Failed to read skills/agents directory: ' + err.message));
    process.exit(1);
  }

  if (availableSkills.length === 0 && availableAgentFiles.length === 0) {
    console.log(chalk.yellow('  No skills or agents found.'));
    process.exit(0);
  }

  if (mode === 'uninstall') {
    await runUninstall(availableSkills, availableAgentFiles, detectedTools);
    return;
  }

  if (mode === 'update-token') {
    await runTokenUpdate();
    return;
  }


  // ── Quick install: only ask which tools, install everything globally ─────
  if (mode === 'install-quick') {
    // discoverAgents() returns inquirer choice objects { name, value: {name,srcFile,frontmatter}, short }
    // Unwrap to plain agent defs for use in install/register functions.
    const quickAgentFiles = availableAgentFiles.map((c) => c.value ?? c);

    const toolChoicesQuick = Object.entries(AGENTS).map(([key, agent]) => {
      const detected = detectedTools[key];
      return {
        name: detected
          ? `${chalk.cyan(agent.name)}  ${chalk.green('✔ detected')}`
          : chalk.dim(agent.name),
        value: key,
        short: agent.name,
        checked: detected,
      };
    });

    const { quickTools } = await inquirer.prompt([
      {
        type: 'checkbox',
        name: 'quickTools',
        message: chalk.bold('Which AI tools are you installing for?'),
        choices: toolChoicesQuick,
        pageSize: 10,
        validate: (v) => v.length > 0 || 'Select at least one AI tool.',
      },
    ]);

    // Azure DevOps credentials — optional, supports multiple orgs
    const quickAzureOrgs = [];
    console.log('');
    console.log(chalk.bold('  Azure DevOps') + chalk.dim(' — add one or more organisations (leave org URL blank to skip)'));
    console.log(chalk.dim('  Token: https://dev.azure.com/<org> → User settings → Personal access tokens → New token'));
    console.log(chalk.dim('  Required scopes: Work Items (Read), Code (Read)'));
    console.log('');
    let addingAzureOrgs = true;
    while (addingAzureOrgs) {
      const azureOrgAnswers = await inquirer.prompt([
        {
          type: 'input',
          name: 'orgUrl',
          message: chalk.bold('Azure org URL') + chalk.dim(' e.g. https://dev.azure.com/myorg  (blank to skip):'),
          filter: (v) => v.trim(),
        },
        {
          type: 'input',
          name: 'name',
          message: chalk.bold('Short name for this org') + chalk.dim(' e.g. myorg (used to select it in the MCP):'),
          filter: (v) => v.trim(),
          when: (ans) => ans.orgUrl.length > 0,
          default: (ans) => ans.orgUrl.replace(/^https?:\/\/dev\.azure\.com\//, '').split('/')[0] || 'org',
        },
        {
          type: 'password',
          name: 'token',
          message: chalk.bold('Azure Personal Access Token:'),
          mask: '*',
          when: (ans) => ans.orgUrl.length > 0,
        },
      ]);
      if (!azureOrgAnswers.orgUrl) {
        addingAzureOrgs = false;
      } else {
        quickAzureOrgs.push({ name: azureOrgAnswers.name, url: azureOrgAnswers.orgUrl, token: azureOrgAnswers.token });
        const { addAnother } = await inquirer.prompt([
          { type: 'confirm', name: 'addAnother', message: chalk.dim('Add another Azure org?'), default: false },
        ]);
        addingAzureOrgs = addAnother;
      }
    }

    // GitHub credentials — optional, supports multiple accounts
    const quickGithubAccounts = [];
    console.log('');
    console.log(chalk.bold('  GitHub') + chalk.dim(' — add one or more accounts (leave blank to skip)'));
    console.log(chalk.dim('  Token: https://github.com/settings/tokens → Generate new token (classic)'));
    console.log(chalk.dim('  Required scopes: repo (full)'));
    console.log('');
    let addingGithubAccounts = true;
    while (addingGithubAccounts) {
      const githubAccountAnswers = await inquirer.prompt([
        {
          type: 'password',
          name: 'token',
          message: chalk.bold('GitHub Personal Access Token') + chalk.dim(' (blank to skip):'),
          mask: '*',
          filter: (v) => v.trim(),
        },
        {
          type: 'input',
          name: 'name',
          message: chalk.bold('Short name for this account') + chalk.dim(' e.g. work, personal:'),
          filter: (v) => v.trim(),
          when: (ans) => ans.token.length > 0,
          default: quickGithubAccounts.length === 0 ? 'default' : 'account' + (quickGithubAccounts.length + 1),
        },
      ]);
      if (!githubAccountAnswers.token) {
        addingGithubAccounts = false;
      } else {
        quickGithubAccounts.push({ name: githubAccountAnswers.name, token: githubAccountAnswers.token });
        const { addAnother } = await inquirer.prompt([
          { type: 'confirm', name: 'addAnother', message: chalk.dim('Add another GitHub account?'), default: false },
        ]);
        addingGithubAccounts = addAnother;
      }
    }

    // Figma access token — optional
    let quickFigmaAccessToken = null;
    console.log('');
    console.log(chalk.bold('  Figma MCP') + chalk.dim(' — leave blank to skip (set FIGMA_ACCESS_TOKEN manually)'));
    console.log(chalk.dim('  Get a token: figma.com → Settings → Security → Personal access tokens'));
    console.log('');
    const figmaQuickAnswers = await inquirer.prompt([
      {
        type: 'password',
        name: 'accessToken',
        message: chalk.bold('Figma access token') + chalk.dim(' (blank to skip):'),
        mask: '*',
        filter: (v) => v.trim(),
      },
    ]);
    quickFigmaAccessToken = figmaQuickAnswers.accessToken || null;

    // Resolve commands + MCPs for all skills + agents
    const skillCmds = await resolveCommands(availableSkills);
    const agentCmds = await resolveAgentCommands(quickAgentFiles);
    const seen = new Set();
    const quickCommands = [...skillCmds, ...agentCmds].filter((c) => {
      if (seen.has(c.name)) return false;
      seen.add(c.name);
      return true;
    });

    // Print summary
    console.log('');
    console.log(chalk.bold('  Ready to install'));
    console.log(chalk.dim('  ' + '─'.repeat(40)));
    console.log(`  ${chalk.dim('Tools    :')} ${chalk.yellow(quickTools.map((t) => AGENTS[t].name).join(', '))}`);
    console.log(`  ${chalk.dim('Skills   :')} ${chalk.cyan(availableSkills.map((s) => s.name).join(', '))} ${chalk.dim('[global]')}`);
    if (quickCommands.length > 0) {
      console.log(`  ${chalk.dim('Commands :')} ${chalk.cyan(quickCommands.map((c) => '/' + c.name).join(', '))} ${chalk.dim('[global]')}`);
    }
    console.log(`  ${chalk.dim('Agents   :')} ${chalk.cyan(quickAgentFiles.map((a) => '@' + a.name).join(', '))} ${chalk.dim('[global]')}`);
    console.log(`  ${chalk.dim('MCPs     :')} ${chalk.cyan('engram, context7, figma-mcp, issue-tickets, security-scanner')} ${chalk.dim('[global]')}`);
    if (quickAzureOrgs.length === 0) {
      console.log(`  ${chalk.dim('          ')} ${chalk.yellow('⚠ Azure credentials not provided — set AZURE_DEVOPS_ACCOUNTS in your shell profile manually.')}`);
    } else {
      console.log(`  ${chalk.dim('Azure    :')} ${chalk.cyan(quickAzureOrgs.map((o) => o.name).join(', '))} ${chalk.dim('[configured]')}`);
    }
    if (quickGithubAccounts.length === 0) {
      console.log(`  ${chalk.dim('          ')} ${chalk.yellow('⚠ GitHub token not provided — set GITHUB_ACCOUNTS in your shell profile manually.')}`);
    } else {
      console.log(`  ${chalk.dim('GitHub   :')} ${chalk.cyan(quickGithubAccounts.map((a) => a.name).join(', '))} ${chalk.dim('[configured]')}`);
    }
    if (!quickFigmaAccessToken) {
      console.log(`  ${chalk.dim('          ')} ${chalk.yellow('⚠ Figma token not provided — set FIGMA_ACCESS_TOKEN in your shell profile manually.')}`);
    } else {
      console.log(`  ${chalk.dim('Figma    :')} ${chalk.cyan('token provided')} ${chalk.dim('[configured]')}`);
    }
    console.log('');

    const { confirmedQuick } = await inquirer.prompt([
      {
        type: 'confirm',
        name: 'confirmedQuick',
        message: chalk.bold('Proceed with installation?'),
        default: true,
      },
    ]);
    if (!confirmedQuick) {
      console.log(chalk.dim('\n  Installation cancelled.\n'));
      process.exit(0);
    }

    // ── Execute quick install ───────────────────────────────────────────────
    for (const toolKey of quickTools) {
      const tool = AGENTS[toolKey];
      const skillsPath = tool.globalPath;

      const skillSpinner = ora({ text: chalk.dim(`[${tool.name}] Installing skills…`), color: 'cyan' }).start();
      await installSkills(availableSkills, skillsPath);
      skillSpinner.stop();

      if (tool.supportsCommands && quickCommands.length > 0) {
        const cmdSpinner = ora({ text: chalk.dim(`[${tool.name}] Installing commands…`), color: 'cyan' }).start();
        await installCommands(quickCommands, tool.commandsGlobalPath);
        cmdSpinner.stop();
      }

      if (tool.supportsAgents && quickAgentFiles.length > 0) {
        const agentSpinner = ora({ text: chalk.dim(`[${tool.name}] Installing agents…`), color: 'cyan' }).start();
        await installAgentFiles(quickAgentFiles, tool.agentsGlobalPath, toolKey);
        agentSpinner.stop();
        // Register agents in the tool's JSON config (e.g. opencode.json)
        for (const agentDef of quickAgentFiles) {
          await registerAgentInConfig(toolKey, agentDef.name, agentDef.frontmatter);
        }
      }

      // Engram + Context7 + Figma MCP config
      const globalMcpServers = { engram: engramMcpConfig(toolKey), context7: context7McpConfig(toolKey, null), 'figma-mcp': figmaMcpConfig(toolKey) };
      for (const [name, cfg] of Object.entries(globalMcpServers)) {
        if (!cfg) continue;
        const cfgSpinner = ora({ text: chalk.dim(`[${tool.name}] Registering ${name} MCP…`), color: 'cyan' }).start();
        await installMcpServers({ [name]: cfg }, toolKey);
        cfgSpinner.stop();
      }

      // Skill/agent linked MCPs
      const skillMcpServers = resolveSkillMcpServers(availableSkills, toolKey);
      const agentMcpServers = resolveAgentMcpServers(quickAgentFiles, toolKey);
      const linkedMcps = { ...skillMcpServers, ...agentMcpServers };
      if (Object.keys(linkedMcps).length > 0) {
        const cfgSpinner = ora({ text: chalk.dim(`[${tool.name}] Registering linked MCPs…`), color: 'cyan' }).start();
        await installMcpServers(linkedMcps, toolKey);
        cfgSpinner.stop();
      }
    }

    // Build + install issue-tickets MCP
    const obSpinner = ora({ text: chalk.dim('Building issue-tickets…'), color: 'cyan' }).start();
    try {
      await installObTicketsMcp();
      obSpinner.succeed(chalk.dim('issue-tickets built and installed.'));
      for (const toolKey of quickTools) {
        const cfg = obTicketsMcpConfig(toolKey, {
          azureAccountsB64: encodeAzureAccountsB64(quickAzureOrgs),
          githubAccountsB64: encodeGithubAccountsB64(quickGithubAccounts),
        });
        await installMcpServers({ 'issue-tickets': cfg }, toolKey);
      }
      if (quickAzureOrgs.length > 0 || quickGithubAccounts.length > 0) {
        const { profileFile } = await writeObTicketsEnvVars({
          azureOrgs: quickAzureOrgs,
          githubAccounts: quickGithubAccounts,
        });
        console.log(chalk.dim(`  Azure/GitHub credentials written to ${profileFile}`));
      }
    } catch (err) {
      obSpinner.fail(chalk.red('issue-tickets build failed: ' + err.message));
    }

    // Build + install security-scanner MCP
    const scannerSpinner = ora({ text: chalk.dim('Building security-scanner…'), color: 'cyan' }).start();
    try {
      await installSecurityScannerMcp();
      scannerSpinner.succeed(chalk.dim('security-scanner built and installed.'));
      for (const toolKey of quickTools) {
        const cfg = securityScannerMcpConfig(toolKey);
        await installMcpServers({ 'security-scanner': cfg }, toolKey);
      }
    } catch (err) {
      scannerSpinner.fail(chalk.red('security-scanner build failed: ' + err.message));
    }

    // Write Figma access token to shell profile
    if (quickFigmaAccessToken) {
      try {
        const { profileFile } = await writeFigmaEnvVar({ accessToken: quickFigmaAccessToken });
        console.log(chalk.dim(`  Figma access token written to ${profileFile}`));
      } catch (err) {
        console.log(chalk.yellow('  Figma MCP: could not write token to shell profile: ' + err.message));
      }
    }

    console.log('');
    console.log(chalk.bold.green('  ✔ Quick install complete!'));
    console.log('');
    return;
  }

  // 2. Select AI tools (multi-select, detected ones pre-checked)
  const toolChoices = Object.entries(AGENTS).map(([key, agent]) => {
    const detected = detectedTools[key];
    return {
      name: detected
        ? `${chalk.cyan(agent.name)}  ${chalk.green('✔ detected')}`
        : chalk.dim(agent.name),
      value: key,
      short: agent.name,
      checked: detected,
    };
  });

  const { selectedTools } = await inquirer.prompt([
    {
      type: 'checkbox',
      name: 'selectedTools',
      message: chalk.bold('Which AI tools are you installing for?'),
      choices: toolChoices,
      pageSize: 10,
      validate: (v) => v.length > 0 || 'Select at least one AI tool.',
    },
  ]);

  // 3. Select skills
  let selectedSkills = [];
  if (availableSkills.length > 0) {
    const result = await inquirer.prompt([
      {
        type: 'checkbox',
        name: 'selectedSkills',
        message: chalk.bold('Select the skills to install:'),
        choices: availableSkills,
        pageSize: 20,
      },
    ]);
    selectedSkills = result.selectedSkills;
  }

  // 4. Skills install target (global or project) — only asked when skills are selected
  let skillsInstallTarget = 'global';
  let projectPath = null;

  if (selectedSkills.length > 0) {
    const { target } = await inquirer.prompt([
      {
        type: 'list',
        name: 'target',
        message: chalk.bold('Install skills:'),
        choices: [
          {
            name: `${chalk.cyan('Global')}  ${chalk.dim('— AI agent\'s global config')}`,
            value: 'global',
          },
          {
            name: `${chalk.magenta('Project')} ${chalk.dim('— specific project directory')}`,
            value: 'project',
          },
        ],
      },
    ]);
    skillsInstallTarget = target;

    if (target === 'project') {
      const { inputPath } = await inquirer.prompt([
        {
          type: 'input',
          name: 'inputPath',
          message: chalk.bold('Project path:'),
          default: process.cwd(),
          validate: async (input) => {
            const resolved = path.resolve(input);
            const exists = await fs.pathExists(resolved);
            if (!exists) return chalk.red(`Path does not exist: ${resolved}`);
            const stat = await fs.stat(resolved);
            if (!stat.isDirectory()) return chalk.red('Path must be a directory.');
            return true;
          },
          filter: (input) => path.resolve(input),
        },
      ]);
      projectPath = inputPath;
    }
  }

  // 5. Select agents (only for tools that support agents)
  const toolsSupportingAgents = selectedTools.filter((t) => AGENTS[t].supportsAgents);
  let selectedAgentFiles = [];
  let agentInstallTarget = 'global';

  if (toolsSupportingAgents.length > 0 && availableAgentFiles.length > 0) {
    const result = await inquirer.prompt([
      {
        type: 'checkbox',
        name: 'selectedAgentFiles',
        message:
          chalk.bold('Select the agents to install') +
          chalk.dim(':'),
        choices: availableAgentFiles,
        pageSize: 20,
      },
    ]);
    selectedAgentFiles = result.selectedAgentFiles;

    if (selectedAgentFiles.length > 0) {
      const { target } = await inquirer.prompt([
        {
          type: 'list',
          name: 'target',
          message: chalk.bold('Install agents:'),
          choices: [
            {
              name: `${chalk.cyan('Global')}  ${chalk.dim('— AI agent\'s global config')}`,
              value: 'global',
            },
            {
              name: `${chalk.magenta('Project')} ${chalk.dim('— specific project directory')}`,
              value: 'project',
            },
          ],
          default: skillsInstallTarget === 'project' ? 'project' : 'global',
        },
      ]);
      agentInstallTarget = target;

      if (agentInstallTarget === 'project' && !projectPath) {
        const { inputPath } = await inquirer.prompt([
          {
            type: 'input',
            name: 'inputPath',
            message: chalk.bold('Project path:'),
            default: process.cwd(),
            validate: async (input) => {
              const resolved = path.resolve(input);
              const exists = await fs.pathExists(resolved);
              if (!exists) return chalk.red(`Path does not exist: ${resolved}`);
              const stat = await fs.stat(resolved);
              if (!stat.isDirectory()) return chalk.red('Path must be a directory.');
              return true;
            },
            filter: (input) => path.resolve(input),
          },
        ]);
        projectPath = inputPath;
      }
    }
  }

  // 6. Global tools (Engram + Context7) — always global, independent of skills/agents
  let selectedGlobalTools = [];
  let context7ApiKey = null;
  let figmaAccessToken = null;

  const globalToolChoices = [
    {
      name: `${chalk.cyan('Engram')}  ${chalk.dim('— persistent memory for AI agents (binary must be installed)')}`,
      value: 'engram',
      short: 'Engram',
    },
    {
      name: `${chalk.cyan('Context7')} ${chalk.dim('— up-to-date library docs via MCP')}`,
      value: 'context7',
      short: 'Context7',
    },
    {
      name: `${chalk.cyan('Figma')}    ${chalk.dim('— Figma design tool integration via MCP (requires FIGMA_ACCESS_TOKEN)')}`,
      value: 'figma',
      short: 'Figma',
    },
  ];

  const { wantGlobalTools } = await inquirer.prompt([
    {
      type: 'confirm',
      name: 'wantGlobalTools',
      message: chalk.bold('Install global MCP tools (Engram, Context7, Figma)?'),
      default: true,
    },
  ]);

  if (wantGlobalTools) {
    const { chosenGlobalTools } = await inquirer.prompt([
      {
        type: 'checkbox',
        name: 'chosenGlobalTools',
        message: chalk.bold('Select global tools to install:'),
        choices: globalToolChoices,
        pageSize: 10,
      },
    ]);
    selectedGlobalTools = chosenGlobalTools;

    if (selectedGlobalTools.includes('context7')) {
      const { apiKey } = await inquirer.prompt([
        {
          type: 'input',
          name: 'apiKey',
          message:
            chalk.bold('Context7 API key ') +
            chalk.dim('(optional — leave blank for free tier; get one at context7.com/dashboard):'),
          default: '',
        },
      ]);
      context7ApiKey = apiKey.trim() || null;
    }

    if (selectedGlobalTools.includes('figma')) {
      console.log('');
      console.log(chalk.dim('  Get a Figma token: figma.com → Settings → Security → Personal access tokens'));
      console.log('');
      const { accessToken } = await inquirer.prompt([
        {
          type: 'password',
          name: 'accessToken',
          message: chalk.bold('Figma access token') + chalk.dim(' (blank to skip — set FIGMA_ACCESS_TOKEN manually):'),
          mask: '*',
          filter: (v) => v.trim(),
        },
      ]);
      figmaAccessToken = accessToken || null;
    }
  }

  // 6b. issue-tickets MCP — optional, OpenCode only for now
  let installObTicketsMcp_ = false;

  if (selectedTools.includes('opencode')) {
    const alreadyInstalled = await fs.pathExists(path.join(OB_TICKETS_MCP_INSTALL_DIR, 'issue-tickets.jar'));
    const { wantObTickets } = await inquirer.prompt([
      {
        type: 'confirm',
        name: 'wantObTickets',
        message: chalk.bold('Install issue-tickets MCP?') + chalk.dim(
          alreadyInstalled ? ' (already installed — will rebuild)' : ' — ticket/issue management for Azure DevOps & GitHub'
        ),
        default: false,
      },
    ]);
    if (wantObTickets) {
      installObTicketsMcp_ = true;
      console.log('');
      console.log(chalk.dim('  issue-tickets reads credentials from env vars — only providers with env vars set will be active.'));
      console.log('');
    }
  }

  // 6c. issue-tickets credentials — only ask if installing issue-tickets
  let obTicketsCredentials = { azureOrgs: [], githubAccounts: [] };
  if (installObTicketsMcp_) {
    console.log(chalk.bold('  Azure DevOps') + chalk.dim(' — add one or more organisations (leave org URL blank to skip)'));
    console.log(chalk.dim('  Token: https://dev.azure.com/<org> → User settings → Personal access tokens → New token'));
    console.log(chalk.dim('  Required scopes: Work Items (Read), Code (Read)'));
    console.log('');
    let addingAzureOrgs = true;
    while (addingAzureOrgs) {
      const azureOrgAnswers = await inquirer.prompt([
        {
          type: 'input',
          name: 'orgUrl',
          message: chalk.bold('Azure org URL') + chalk.dim(' e.g. https://dev.azure.com/myorg  (blank to skip):'),
          filter: (v) => v.trim(),
        },
        {
          type: 'input',
          name: 'name',
          message: chalk.bold('Short name for this org') + chalk.dim(' e.g. myorg (used to select it in the MCP):'),
          filter: (v) => v.trim(),
          when: (ans) => ans.orgUrl.length > 0,
          default: (ans) => ans.orgUrl.replace(/^https?:\/\/dev\.azure\.com\//, '').split('/')[0] || 'org',
        },
        {
          type: 'password',
          name: 'token',
          message: chalk.bold('Azure Personal Access Token:'),
          mask: '*',
          when: (ans) => ans.orgUrl.length > 0,
        },
      ]);
      if (!azureOrgAnswers.orgUrl) {
        addingAzureOrgs = false;
      } else {
        obTicketsCredentials.azureOrgs.push({ name: azureOrgAnswers.name, url: azureOrgAnswers.orgUrl, token: azureOrgAnswers.token });
        const { addAnother } = await inquirer.prompt([
          { type: 'confirm', name: 'addAnother', message: chalk.dim('Add another Azure org?'), default: false },
        ]);
        addingAzureOrgs = addAnother;
      }
    }

    console.log('');
    console.log(chalk.bold('  GitHub') + chalk.dim(' — add one or more accounts (leave blank to skip)'));
    console.log(chalk.dim('  Token: https://github.com/settings/tokens → Generate new token (classic)'));
    console.log(chalk.dim('  Required scopes: repo (full)'));
    console.log('');
    let addingGithubAccounts = true;
    while (addingGithubAccounts) {
      const githubAccountAnswers = await inquirer.prompt([
        {
          type: 'password',
          name: 'token',
          message: chalk.bold('GitHub Personal Access Token') + chalk.dim(' (blank to skip):'),
          mask: '*',
          filter: (v) => v.trim(),
        },
        {
          type: 'input',
          name: 'name',
          message: chalk.bold('Short name for this account') + chalk.dim(' e.g. work, personal:'),
          filter: (v) => v.trim(),
          when: (ans) => ans.token.length > 0,
          default: obTicketsCredentials.githubAccounts.length === 0 ? 'default' : 'account' + (obTicketsCredentials.githubAccounts.length + 1),
        },
      ]);
      if (!githubAccountAnswers.token) {
        addingGithubAccounts = false;
      } else {
        obTicketsCredentials.githubAccounts.push({ name: githubAccountAnswers.name, token: githubAccountAnswers.token });
        const { addAnother } = await inquirer.prompt([
          { type: 'confirm', name: 'addAnother', message: chalk.dim('Add another GitHub account?'), default: false },
        ]);
        addingGithubAccounts = addAnother;
      }
    }
    console.log('');
  }

  // 6e. security-scanner MCP — optional, OpenCode only for now
  let installSecurityScannerMcp_ = false;

  if (selectedTools.includes('opencode')) {
    const alreadyInstalled = await fs.pathExists(path.join(SECURITY_SCANNER_MCP_INSTALL_DIR, 'security-scanner.jar'));
    const { wantSecurityScanner } = await inquirer.prompt([
      {
        type: 'confirm',
        name: 'wantSecurityScanner',
        message: chalk.bold('Install security-scanner MCP?') + chalk.dim(
          alreadyInstalled
            ? ' (already installed — will rebuild)'
            : ' — active URL vulnerability scanner, gated by a per-project allowlist (never targets production)'
        ),
        default: false,
      },
    ]);
    if (wantSecurityScanner) {
      installSecurityScannerMcp_ = true;
      console.log('');
      console.log(chalk.dim('  security-scanner refuses to run against any host not listed in that project\'s'));
      console.log(chalk.dim('  .security-scanner/allowlist.json — see mcp/security-scanner/README.md for the format.'));
      console.log('');
    }
  }

  if (selectedSkills.length === 0 && selectedAgentFiles.length === 0 && selectedGlobalTools.length === 0 && !installObTicketsMcp_ && !installSecurityScannerMcp_) {
    console.log('');
    console.log(chalk.dim('  Nothing selected. Installation cancelled.'));
    console.log('');
    process.exit(0);
  }

  // 7. Slash commands (any tool with supportsCommands) — pulled from skill AND agent companions.
  //    Skipped if neither selected skills nor selected agents have companions.
  let installCommands_ = false;
  let availableCommands = [];

  if (selectedTools.some((t) => AGENTS[t].supportsCommands)) {
    const skillCmds = selectedSkills.length > 0 ? await resolveCommands(selectedSkills) : [];
    const agentCmds = selectedAgentFiles.length > 0 ? await resolveAgentCommands(selectedAgentFiles) : [];
    // De-duplicate by command name (skills take priority over agents if both register the same name)
    const seen = new Set();
    availableCommands = [...skillCmds, ...agentCmds].filter((c) => {
      if (seen.has(c.name)) return false;
      seen.add(c.name);
      return true;
    });

    if (availableCommands.length > 0) {
      const commandNames = availableCommands.map((c) => chalk.cyan('/' + c.name)).join(', ');
      const { wantCommands } = await inquirer.prompt([
        {
          type: 'confirm',
          name: 'wantCommands',
          message:
            chalk.bold('Also install slash commands? ') +
            chalk.dim(`(${commandNames})`),
          default: true,
        },
      ]);
      installCommands_ = wantCommands;
    }
  }

  // 8. Skill-linked MCPs — always global, confirm once
  let installSkillMcps = false;
  const skillMcpPreview = selectedTools.flatMap((toolKey) => {
    const servers = resolveSkillMcpServers(selectedSkills, toolKey);
    return Object.keys(servers).map((n) => `${chalk.cyan(n)} ${chalk.dim(`(${AGENTS[toolKey].name})`)}`);
  });

  if (skillMcpPreview.length > 0) {
    const { wantSkillMcp } = await inquirer.prompt([
      {
        type: 'confirm',
        name: 'wantSkillMcp',
        message:
          chalk.bold('Install recommended MCP servers for selected skills? ') +
          chalk.dim(`(${skillMcpPreview.join(', ')})`),
        default: true,
      },
    ]);
    installSkillMcps = wantSkillMcp;
  }

  // 9. Agent-linked MCPs — always global, confirm once
  let installAgentMcps = false;
  const agentMcpPreview = selectedTools.flatMap((toolKey) => {
    const servers = resolveAgentMcpServers(selectedAgentFiles, toolKey);
    return Object.keys(servers).map((n) => `${chalk.cyan(n)} ${chalk.dim(`(${AGENTS[toolKey].name})`)}`);
  });

  if (agentMcpPreview.length > 0) {
    const { wantMcp } = await inquirer.prompt([
      {
        type: 'confirm',
        name: 'wantMcp',
        message:
          chalk.bold('Install required MCP servers for selected agents? ') +
          chalk.dim(`(${agentMcpPreview.join(', ')})`),
        default: true,
      },
    ]);
    installAgentMcps = wantMcp;
  }

  // 10. Confirm summary preview
  console.log('');
  console.log(chalk.bold('  Ready to install'));
  console.log(chalk.dim('  ' + '─'.repeat(40)));
  console.log(`  ${chalk.dim('Tools    :')} ${chalk.yellow(selectedTools.map((t) => AGENTS[t].name).join(', '))}`);
  if (selectedSkills.length > 0) {
    const skillsLabel = skillsInstallTarget === 'project' ? `project (${projectPath})` : 'global';
    console.log(`  ${chalk.dim('Skills   :')} ${chalk.cyan(selectedSkills.map((s) => s.name).join(', '))} ${chalk.dim(`[${skillsLabel}]`)}`);
  }
  if (installCommands_ && availableCommands.length > 0) {
    console.log(`  ${chalk.dim('Commands :')} ${chalk.cyan(availableCommands.map((c) => '/' + c.name).join(', '))} ${chalk.dim('[OpenCode global]')}`);
  }
  if (selectedAgentFiles.length > 0) {
    const agentsLabel = agentInstallTarget === 'project' ? `project (${projectPath})` : 'global';
    console.log(`  ${chalk.dim('Agents   :')} ${chalk.cyan(selectedAgentFiles.map((a) => '@' + a.name).join(', '))} ${chalk.dim(`[${agentsLabel}]`)}`);
  }
  if (installSkillMcps || installAgentMcps) {
    console.log(`  ${chalk.dim('MCPs     :')} ${chalk.dim('[global]')}`);
  }
  if (installObTicketsMcp_) {
    console.log(`  ${chalk.dim('Tickets  :')} ${chalk.cyan('issue-tickets')} ${chalk.dim('[build + install to ~/.config/opencode/mcp/]')}`);
  }
  if (installSecurityScannerMcp_) {
    console.log(`  ${chalk.dim('Scanner  :')} ${chalk.cyan('security-scanner')} ${chalk.dim('[build + install to ~/.config/opencode/mcp/]')}`);
  }
  if (selectedGlobalTools.length > 0) {
    console.log(`  ${chalk.dim('Tools    :')} ${chalk.cyan(selectedGlobalTools.join(', '))} ${chalk.dim('[global MCP]')}`);
  }
  console.log('');

  const { confirmed } = await inquirer.prompt([
    {
      type: 'confirm',
      name: 'confirmed',
      message: chalk.bold('Proceed with installation?'),
      default: true,
    },
  ]);

  if (!confirmed) {
    console.log('');
    console.log(chalk.dim('  Installation cancelled.'));
    console.log('');
    process.exit(0);
  }

  // 11. Install — loop over each selected tool
  const resultsByTool = {};

  for (const toolKey of selectedTools) {
    const tool = AGENTS[toolKey];
    const toolResults = {
      skills: [],
      commands: [],
      agents: [],
      templates: [],
      configRegs: [],
      mcps: [],
      skillsPath: null,
      commandsPath: null,
      agentsPath: null,
    };

    // Skills
    if (selectedSkills.length > 0) {
      const skillsPath =
        skillsInstallTarget === 'global'
          ? tool.globalPath
          : path.join(projectPath, tool.projectFolder);
      toolResults.skillsPath = skillsPath;

      const skillSpinner = ora({
        text: chalk.dim(`[${tool.name}] Installing skills…`),
        color: 'cyan',
      }).start();
      toolResults.skills = await installSkills(selectedSkills, skillsPath);
      skillSpinner.stop();
    }

    // Commands (any tool with supportsCommands). Install globally if any selected agent
    // contributes a command, or if skills are global; otherwise install alongside the project skills.
    if (tool.supportsCommands && installCommands_ && availableCommands.length > 0) {
      const hasAgentCommands = selectedAgentFiles.some(
        (a) => (AGENT_COMMANDS[a.name] ?? []).length > 0
      );
      const installGlobal = hasAgentCommands || skillsInstallTarget === 'global' || selectedSkills.length === 0;
      const commandsPath = installGlobal
        ? tool.commandsGlobalPath
        : path.join(projectPath, tool.commandsProjectFolder);
      toolResults.commandsPath = commandsPath;

      const cmdSpinner = ora({
        text: chalk.dim(`[${tool.name}] Installing commands…`),
        color: 'cyan',
      }).start();
      toolResults.commands = await installCommands(availableCommands, commandsPath);
      cmdSpinner.stop();
    }

    // Agents
    if (tool.supportsAgents && selectedAgentFiles.length > 0) {
      const agentsPath =
        agentInstallTarget === 'project'
          ? path.join(projectPath, tool.agentsProjectFolder ?? 'agents')
          : tool.agentsGlobalPath;
      toolResults.agentsPath = agentsPath;

      const agentSpinner = ora({
        text: chalk.dim(`[${tool.name}] Installing agents…`),
        color: 'cyan',
      }).start();
      toolResults.agents = await installAgentFiles(selectedAgentFiles, agentsPath, toolKey);
      agentSpinner.stop();

      // Register each agent in the tool's global JSON config (e.g. opencode.json)
      if (tool.agentConfigFile) {
        const regSpinner = ora({
          text: chalk.dim(`[${tool.name}] Registering agents in config…`),
          color: 'cyan',
        }).start();
        for (const agentDef of selectedAgentFiles) {
          try {
            const result = await registerAgentInConfig(toolKey, agentDef.name, agentDef.frontmatter);
            if (result) toolResults.configRegs.push(result);
          } catch (err) {
            toolResults.configRegs.push({ name: agentDef.name, success: false, error: err.message, configFile: tool.agentConfigFile });
          }
        }
        regSpinner.stop();
      }

      // Install templates to global agents folder (for project-initializer to use)
      if (tool.agentsGlobalPath) {
        const templatesSpinner = ora({
          text: chalk.dim(`[${tool.name}] Installing templates…`),
          color: 'cyan',
        }).start();
        const templateResults = await installTemplates(tool.agentsGlobalPath);
        toolResults.templates = templateResults;
        templatesSpinner.stop();
      }
    }

    // MCPs — always global
    const mcpSpinner = ora({
      text: chalk.dim(`[${tool.name}] Writing MCP config…`),
      color: 'cyan',
    });

    if (installSkillMcps) {
      const skillMcpServers = resolveSkillMcpServers(selectedSkills, toolKey);
      if (Object.keys(skillMcpServers).length > 0) {
        mcpSpinner.start();
        try {
          const results = await installMcpServers(skillMcpServers, toolKey);
          toolResults.mcps.push(...results);
        } catch (err) {
          toolResults.mcps.push(
            ...Object.keys(skillMcpServers).map((name) => ({ name, success: false, error: err.message }))
          );
        }
        mcpSpinner.stop();
      }
    }

    if (installAgentMcps) {
      const agentMcpServers = resolveAgentMcpServers(selectedAgentFiles, toolKey);
      if (Object.keys(agentMcpServers).length > 0) {
        mcpSpinner.start();
        try {
          const results = await installMcpServers(agentMcpServers, toolKey);
          toolResults.mcps.push(...results);
        } catch (err) {
          toolResults.mcps.push(
            ...Object.keys(agentMcpServers).map((name) => ({ name, success: false, error: err.message }))
          );
        }
        mcpSpinner.stop();
      }
    }

    // Global tools (Engram + Context7) — always global
    if (selectedGlobalTools.length > 0) {
      const globalServers = {};
      if (selectedGlobalTools.includes('engram')) {
        globalServers['engram'] = engramMcpConfig(toolKey);
      }
      if (selectedGlobalTools.includes('context7')) {
        globalServers['context7'] = context7McpConfig(toolKey, context7ApiKey);
      }
      if (selectedGlobalTools.includes('figma')) {
        globalServers['figma-mcp'] = figmaMcpConfig(toolKey);
      }
      if (Object.keys(globalServers).length > 0) {
        mcpSpinner.start();
        try {
          const results = await installMcpServers(globalServers, toolKey);
          toolResults.mcps.push(...results);
        } catch (err) {
          toolResults.mcps.push(
            ...Object.keys(globalServers).map((name) => ({ name, success: false, error: err.message }))
          );
        }
        mcpSpinner.stop();
      }
    }

    // issue-tickets MCP — build and install, then write config entry (OpenCode only)
    if (toolKey === 'opencode' && installObTicketsMcp_) {
      const obTicketsSpinner = ora({
        text: chalk.dim('[issue-tickets MCP] Building…'),
        color: 'cyan',
      }).start();
      try {
        obTicketsSpinner.text = chalk.dim('[issue-tickets MCP] Building with Maven…');
        await installObTicketsMcp();
        obTicketsSpinner.text = chalk.dim('[issue-tickets MCP] Writing MCP config entry…');
        const serverConfig = obTicketsMcpConfig(toolKey, {
          azureAccountsB64: encodeAzureAccountsB64(obTicketsCredentials.azureOrgs),
          githubAccountsB64: encodeGithubAccountsB64(obTicketsCredentials.githubAccounts),
        });
        const mcpResults = await installMcpServers({ 'issue-tickets': serverConfig }, toolKey);
        toolResults.mcps.push(...mcpResults);
        obTicketsSpinner.stop();
        // Write Azure/GitHub credentials to shell profile if provided
        if (obTicketsCredentials.azureOrgs.length > 0 || obTicketsCredentials.githubAccounts.length > 0) {
          const { profileFile } = await writeObTicketsEnvVars(obTicketsCredentials);
          console.log(chalk.dim(`  Azure/GitHub credentials written to ${profileFile}`));
        }
      } catch (err) {
        obTicketsSpinner.stop();
        toolResults.mcps.push({ name: 'issue-tickets', success: false, error: err.message });
      }
    }

    // security-scanner MCP — build and install, then write config entry (OpenCode only)
    if (toolKey === 'opencode' && installSecurityScannerMcp_) {
      const scannerSpinner = ora({
        text: chalk.dim('[security-scanner] Building…'),
        color: 'cyan',
      }).start();
      try {
        scannerSpinner.text = chalk.dim('[security-scanner] Building with Maven…');
        await installSecurityScannerMcp();
        scannerSpinner.text = chalk.dim('[security-scanner] Writing MCP config entry…');
        const serverConfig = securityScannerMcpConfig(toolKey);
        const mcpResults = await installMcpServers({ 'security-scanner': serverConfig }, toolKey);
        toolResults.mcps.push(...mcpResults);
        scannerSpinner.stop();
      } catch (err) {
        scannerSpinner.stop();
        toolResults.mcps.push({ name: 'security-scanner', success: false, error: err.message });
      }
    }

    resultsByTool[toolKey] = toolResults;
  }

  // 12. Summary
  printSummary(resultsByTool);

  // Post-install: write Figma access token to shell profile
  if (selectedGlobalTools.includes('figma') && figmaAccessToken) {
    try {
      const { profileFile, skipped } = await writeFigmaEnvVar({ accessToken: figmaAccessToken });
      if (skipped) {
        console.log(chalk.dim('  Figma MCP: FIGMA_ACCESS_TOKEN already present in shell profile — skipped.'));
      } else {
        console.log(chalk.green(`  Figma MCP: access token written to ${profileFile}`));
        console.log(chalk.dim('  Run `source ' + profileFile + '` or open a new terminal for the var to take effect.'));
      }
    } catch (err) {
      console.log(chalk.yellow('  Figma MCP: could not write to shell profile: ' + err.message));
      console.log(chalk.dim('  Add this line manually:'));
      console.log(`    export FIGMA_ACCESS_TOKEN="<your-token>"`);
    }
    console.log('');
  }
}

main().catch((err) => {
  if (err.name === 'ExitPromptError') {
    console.log('');
    console.log(chalk.dim('  Cancelled.'));
    console.log('');
    process.exit(0);
  }
  console.error(chalk.red('\n  Unexpected error: ' + err.message));
  process.exit(1);
});
