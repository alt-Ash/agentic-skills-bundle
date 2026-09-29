#!/usr/bin/env node
/**
 * issue-tickets — MCP server for ticket/issue management.
 *
 * Supports:
 *   - Azure DevOps:
 *       Multi-org:  AZURE_DEVOPS_ACCOUNTS = JSON {"myorg": {"url": "...", "token": "..."}, ...}
 *       Single-org: AZURE_DEVOPS_ORG_URL + AZURE_DEVOPS_TOKEN  (legacy)
 *   - GitHub:
 *       Multi-account: GITHUB_ACCOUNTS = JSON {"work": "ghp_...", "personal": "ghp_..."}
 *       Single-account: GITHUB_TOKEN  (legacy)
 *
 * Tools:
 *   pull_ticket          — fetch/list tickets from the detected or specified source
 *   create_issue         — create a new ticket/issue/work-item on the detected or specified source
 *   create_pull_request  — create a PR on the detected or specified source
 *
 * Source auto-detection order:
 *   1. .github/ directory → github
 *   2. azure-pipelines.yml or .azure/ → azure
 *   3. .git/config remote URL → azure or github
 *   4. package.json repository field → azure or github
 */

import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { detectSource, isAmbiguous } from './detect-source.js';
import { getAzureProvider, hasAzureCredentials, getAzureAccountNames } from './providers/azure.js';
import { getGithubProvider, hasGithubCredentials, getGithubAccountNames } from './providers/github.js';
import type { TicketSource, NormalizedTicket, PullTicketParams, CreatePrParams, CreateIssueParams } from './types.js';

const server = new McpServer({
  name: 'issue-tickets',
  version: '0.1.0',
});

// ─── Helper: resolve provider ─────────────────────────────────────────────────

async function resolveProvider(explicitSource?: string) {
  const source = (explicitSource as TicketSource | undefined) ?? await detectSource();
  switch (source) {
    case 'azure':  return { source, provider: getAzureProvider() };
    case 'github': return { source, provider: getGithubProvider() };
    default:
      throw new Error('Could not determine ticket source (azure or github) — pass `source` explicitly, or configure AZURE_DEVOPS_* / GITHUB_* credentials.');
  }
}

/** Run pull_ticket across all credentialed providers in parallel (ambiguous detection). */
async function pullAllProviders(params: PullTicketParams): Promise<NormalizedTicket[]> {
  const tasks: Promise<NormalizedTicket[]>[] = [];
  if (hasAzureCredentials()) tasks.push(getAzureProvider().pullTicket(params).catch(() => []));
  if (hasGithubCredentials()) tasks.push(getGithubProvider().pullTicket(params).catch(() => []));
  const results = await Promise.all(tasks);
  return results.flat();
}

// ─── Dynamic account description helpers ─────────────────────────────────────

function azureAccountDesc(): string {
  const names = getAzureAccountNames();
  if (names.length === 0) return 'Azure account name (from AZURE_DEVOPS_ACCOUNTS). Omit when only one account is configured.';
  if (names.length === 1) return `Azure account name. Currently configured: "${names[0]}". Omit to use it automatically.`;
  return `Azure account name. Configured accounts: ${names.map((n) => `"${n}"`).join(', ')}. Required when source is "azure".`;
}

function githubAccountDesc(): string {
  const names = getGithubAccountNames();
  if (names.length === 0) return 'GitHub account name (from GITHUB_ACCOUNTS). Omit when only one account is configured.';
  if (names.length === 1) return `GitHub account name. Currently configured: "${names[0]}". Omit to use it automatically.`;
  return `GitHub account name. Configured accounts: ${names.map((n) => `"${n}"`).join(', ')}. Required when source is "github".`;
}

// ── tool: pull_ticket ─────────────────────────────────────────────────────────

server.registerTool(
  'pull_ticket',
  {
    description: 'Fetch one or more tickets/issues by ID, or list tickets for a project, or list all tickets across all projects. '
    + 'Supports Azure DevOps and GitHub. '
    + 'The source is auto-detected from project config files when not specified explicitly. '
    + 'IMPORTANT: For any generic request like "get my tickets", "list my tickets", "get my last ticket", '
    + '"show open tickets", or any ticket request without a specific ID or projectId — '
    + 'always call this tool with allProjects=true. Do NOT ask the user for a projectId first. '
    + 'Supply ticketIds to get full detail, including the comment/discussion thread, '
    + 'author-flagged asides (e.g. color-styled notes), and explicit "to be elaborated" open items. '
    + 'Supply projectId to list tickets for a specific project. '
    + 'Supply allProjects=true to fetch tickets across all projects automatically.',
    inputSchema: {
      source: z.enum(['azure', 'github']).optional().describe(
        'Ticket source. If omitted, auto-detected from project config files. '
        + 'Possible values: "azure", "github".'
      ),
      account: z.string().optional().describe(
        'Account name for multi-org/multi-account setups. '
        + azureAccountDesc() + ' ' + githubAccountDesc()
      ),
      ticketIds: z.array(z.union([z.number(), z.string()])).optional().describe(
        'One or more ticket IDs to fetch in full detail (including notes/history).'
      ),
      projectId: z.union([z.number(), z.string()]).optional().describe(
        'Project ID. For GitHub: "owner/repo". For Azure: project name or ID.'
      ),
      allProjects: z.boolean().optional().describe(
        'If true, fetches tickets across ALL projects automatically. '
        + 'Results are sorted by lastUpdated descending.'
      ),
      pageSize: z.number().optional().describe('Max tickets to return (default: 25).'),
    },
  },
  async ({ source: explicitSource, account, ticketIds, projectId, allProjects, pageSize }) => {
    const params: PullTicketParams = {
      ticketIds: ticketIds as number[] | string[] | undefined,
      projectId,
      allProjects,
      pageSize,
      account,
    };

    // If no explicit source and detection is ambiguous → try all providers
    if (!explicitSource && await isAmbiguous()) {
      const tickets = await pullAllProviders(params);
      return {
        content: [{
          type: 'text',
          text: JSON.stringify({ source: 'all', total: tickets.length, tickets }, null, 2),
        }],
      };
    }

    const { source, provider } = await resolveProvider(explicitSource);
    const tickets = await provider.pullTicket(params);
    return {
      content: [{
        type: 'text',
        text: JSON.stringify({ source, total: tickets.length, tickets }, null, 2),
      }],
    };
  },
);

// ── tool: create_issue ────────────────────────────────────────────────────────

server.registerTool(
  'create_issue',
  {
    description: 'Create a new ticket, issue, or work item on Azure DevOps or GitHub. '
    + 'The source is auto-detected from project config files when not specified explicitly. '
    + 'For Azure DevOps, provide a project name or ID and optionally a work item type (default: "Task"). '
    + 'For GitHub, provide projectId in "owner/repo" format.',
    inputSchema: {
      source: z.enum(['azure', 'github']).optional().describe(
        'Target source. If omitted, auto-detected from project config files. '
        + 'Possible values: "azure", "github".'
      ),
      account: z.string().optional().describe(
        'Account name for multi-org/multi-account setups. '
        + azureAccountDesc() + ' ' + githubAccountDesc()
      ),
      title: z.string().describe('Issue/ticket title.'),
      description: z.string().optional().describe('Issue/ticket description or body (markdown supported).'),
      projectId: z.union([z.number(), z.string()]).describe(
        'Project to create the issue in. '
        + 'Azure DevOps: project name or ID. '
        + 'GitHub: "owner/repo" format.'
      ),
      type: z.string().optional().describe(
        'Issue type. '
        + 'Azure DevOps: work item type, e.g. "Bug", "User Story", "Task", "Feature" (default: "Task"). '
        + 'GitHub: ignored (GitHub issues have no type field).'
      ),
      labels: z.array(z.string()).optional().describe(
        'Labels to apply. '
        + 'GitHub: array of label names. '
        + 'Azure DevOps: used as tags.'
      ),
      assignee: z.string().optional().describe(
        'Assignee. '
        + 'GitHub: GitHub username. '
        + 'Azure DevOps: display name or email.'
      ),
    },
  },
  async ({ source: explicitSource, account, title, description, projectId, type, labels, assignee }) => {
    const { source, provider } = await resolveProvider(explicitSource);
    const params: CreateIssueParams = {
      title,
      description,
      projectId,
      type,
      labels,
      assignee,
      account,
    };
    const result = await provider.createIssue(params);
    return {
      content: [{ type: 'text', text: JSON.stringify(result, null, 2) }],
    };
  },
);

// ── tool: create_pull_request ─────────────────────────────────────────────────

server.registerTool(
  'create_pull_request',
  {
    description: 'Create a pull request on Azure DevOps or GitHub. '
    + 'The source is auto-detected from project config files when not specified explicitly. '
    + 'For Azure DevOps, supply repositoryId (repo GUID or name). '
    + 'For GitHub, supply repo in "owner/repo" format.',
    inputSchema: {
      source: z.enum(['azure', 'github']).optional().describe(
        'Target source. If omitted, auto-detected.'
      ),
      account: z.string().optional().describe(
        'Account name for multi-org/multi-account setups. '
        + azureAccountDesc() + ' ' + githubAccountDesc()
      ),
      title: z.string().describe('Pull request title.'),
      sourceBranch: z.string().describe('Source/head branch name.'),
      targetBranch: z.string().describe('Target/base branch name.'),
      repositoryId: z.string().optional().describe('Azure DevOps: repository GUID or name.'),
      repo: z.string().optional().describe('GitHub: "owner/repo" format.'),
      description: z.string().optional().describe('Pull request description/body.'),
    },
  },
  async ({ source: explicitSource, account, title, sourceBranch, targetBranch, repositoryId, repo, description }) => {
    const { source, provider } = await resolveProvider(explicitSource);

    const params: CreatePrParams = { title, sourceBranch, targetBranch, repositoryId, repo, description, account };
    const result = await provider.createPullRequest(params);
    return {
      content: [{ type: 'text', text: JSON.stringify(result, null, 2) }],
    };
  },
);

// ─── start ────────────────────────────────────────────────────────────────────

const transport = new StdioServerTransport();
await server.connect(transport);
