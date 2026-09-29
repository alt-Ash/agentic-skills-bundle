/**
 * azure.ts — Azure DevOps ticket/PR provider for issue-tickets.
 *
 * Credentials — two modes (multi-org takes precedence):
 *
 *   Multi-org (recommended):
 *     AZURE_DEVOPS_ACCOUNTS  JSON: {"myorg": {"url": "https://dev.azure.com/myorg", "token": "PAT"}, ...}
 *
 *   Single-org (legacy / backwards compat):
 *     AZURE_DEVOPS_ORG_URL  e.g. https://dev.azure.com/myorg
 *     AZURE_DEVOPS_TOKEN    Personal Access Token
 */

import * as azdev from 'azure-devops-node-api';
import type { TicketProvider, NormalizedTicket, TicketComment, PullRequestResult, PullTicketParams, CreatePrParams, CreateIssueParams, CreateIssueResult } from '../types.js';
import { extractFlaggedAsides, extractOpenItems } from '../ticket-text.js';

// ─── Account resolution ───────────────────────────────────────────────────────

interface AzureAccount {
  name: string;
  orgUrl: string;
  token: string;
}

/**
 * Returns all configured Azure DevOps accounts.
 * Reads AZURE_DEVOPS_ACCOUNTS_B64 (base64-encoded JSON) first, then falls back
 * to plain AZURE_DEVOPS_ACCOUNTS (JSON), then to single-org env vars.
 */
function getAllAzureAccounts(): AzureAccount[] {
  const b64 = process.env['AZURE_DEVOPS_ACCOUNTS_B64'];
  if (b64) {
    try {
      const json = Buffer.from(b64, 'base64').toString('utf8');
      const parsed = JSON.parse(json) as Record<string, { url: string; token: string }>;
      return Object.entries(parsed).map(([name, { url, token }]) => ({ name, orgUrl: url, token }));
    } catch {
      throw new Error('[issue-tickets/azure] AZURE_DEVOPS_ACCOUNTS_B64 is not valid base64-encoded JSON.');
    }
  }

  const json = process.env['AZURE_DEVOPS_ACCOUNTS'];
  if (json) {
    try {
      const parsed = JSON.parse(json) as Record<string, { url: string; token: string }>;
      return Object.entries(parsed).map(([name, { url, token }]) => ({ name, orgUrl: url, token }));
    } catch {
      throw new Error('[issue-tickets/azure] AZURE_DEVOPS_ACCOUNTS is not valid JSON.');
    }
  }

  // Legacy single-org fallback
  const orgUrl = process.env['AZURE_DEVOPS_ORG_URL'];
  const token = process.env['AZURE_DEVOPS_TOKEN'];
  if (orgUrl && token) {
    return [{ name: 'default', orgUrl, token }];
  }

  return [];
}

/**
 * Returns a single account by name, or throws if not found.
 * If accountName is omitted and only one account is configured, returns it.
 */
function getAzureAccount(accountName?: string): AzureAccount {
  const accounts = getAllAzureAccounts();
  if (accounts.length === 0) {
    throw new Error(
      '[issue-tickets/azure] No Azure DevOps credentials configured. ' +
      'Set AZURE_DEVOPS_ACCOUNTS (JSON) or AZURE_DEVOPS_ORG_URL + AZURE_DEVOPS_TOKEN.',
    );
  }

  if (!accountName) {
    if (accounts.length === 1) return accounts[0];
    throw new Error(
      `[issue-tickets/azure] Multiple Azure accounts configured (${accounts.map((a) => a.name).join(', ')}). ` +
      'Specify an account name.',
    );
  }

  const found = accounts.find((a) => a.name === accountName);
  if (!found) {
    throw new Error(
      `[issue-tickets/azure] Account "${accountName}" not found. ` +
      `Available: ${accounts.map((a) => a.name).join(', ')}.`,
    );
  }
  return found;
}

export function hasAzureCredentials(): boolean {
  return getAllAzureAccounts().length > 0;
}

/** Returns the names of all configured Azure accounts. */
export function getAzureAccountNames(): string[] {
  return getAllAzureAccounts().map((a) => a.name);
}

// ─── Connection ───────────────────────────────────────────────────────────────

function getConnection(orgUrl: string, token: string): azdev.WebApi {
  const authHandler = azdev.getPersonalAccessTokenHandler(token);
  return new azdev.WebApi(orgUrl, authHandler);
}

// ─── Normalisation ────────────────────────────────────────────────────────────

function normaliseWorkItem(item: any, orgUrl: string, comments?: TicketComment[]): NormalizedTicket {
  const fields = item.fields ?? {};
  const id = String(item.id ?? '');
  const description = fields['System.Description'] ?? '';
  return {
    source: 'azure',
    id,
    title: fields['System.Title'] ?? '',
    description,
    status: fields['System.State'] ?? '',
    url: item._links?.html?.href ?? `${orgUrl}/_workitems/edit/${id}`,
    assignee: fields['System.AssignedTo']?.displayName ?? null,
    labels: fields['System.Tags'] ? String(fields['System.Tags']).split(';').map((t: string) => t.trim()).filter(Boolean) : [],
    createdAt: fields['System.CreatedDate'] ?? '',
    updatedAt: fields['System.ChangedDate'] ?? '',
    comments,
    flaggedAsides: extractFlaggedAsides(description),
    openItems: extractOpenItems(description),
    raw: item,
  };
}

/** Fetches a work item's discussion comments. Best-effort — returns [] if the API is unavailable. */
async function getWorkItemComments(witClient: any, project: string, id: number): Promise<TicketComment[]> {
  try {
    const result = await witClient.getComments(project, id);
    return ((result?.comments ?? []) as any[]).map((c) => ({
      author: c.createdBy?.displayName ?? '',
      date: c.createdDate ?? '',
      text: (c.text ?? '').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim(),
    })).filter((c) => c.text);
  } catch {
    return [];
  }
}

// ─── Provider ─────────────────────────────────────────────────────────────────

class AzureProvider implements TicketProvider {
  async pullTicket(params: PullTicketParams): Promise<NormalizedTicket[]> {
    const { orgUrl, token } = getAzureAccount(params.account);
    const conn = getConnection(orgUrl, token);
    const witClient = await conn.getWorkItemTrackingApi();

    const { ticketIds, projectId, pageSize = 25 } = params;

    // Fetch by IDs (detail fetch — also pull the discussion thread per item)
    if (ticketIds && ticketIds.length > 0) {
      const ids = (ticketIds as string[]).map(Number).filter((n) => !isNaN(n));
      const items = await witClient.getWorkItems(ids, undefined, undefined, undefined, undefined);
      return Promise.all((items ?? []).map(async (item) => {
        const project = item.fields?.['System.TeamProject'];
        const comments = project && item.id != null ? await getWorkItemComments(witClient, project, item.id) : [];
        return normaliseWorkItem(item, orgUrl, comments);
      }));
    }

    // List by project (WIQL query for open items)
    if (projectId) {
      const project = String(projectId);
      const query = `SELECT [System.Id] FROM WorkItems WHERE [System.TeamProject] = '${project}' AND [System.State] <> 'Closed' ORDER BY [System.ChangedDate] DESC`;
      const queryResult = await witClient.queryByWiql({ query }, { project });
      const ids = (queryResult.workItems ?? []).slice(0, pageSize).map((wi) => wi.id!);
      if (ids.length === 0) return [];
      const items = await witClient.getWorkItems(ids, undefined, undefined, undefined, undefined);
      return (items ?? []).map((item) => normaliseWorkItem(item, orgUrl));
    }

    throw new Error('[issue-tickets/azure] Provide ticketIds or a projectId.');
  }

  async createPullRequest(params: CreatePrParams): Promise<PullRequestResult> {
    const { orgUrl, token } = getAzureAccount(params.account);
    const conn = getConnection(orgUrl, token);
    const gitClient = await conn.getGitApi();

    const { title, sourceBranch, targetBranch, repositoryId, description } = params;

    if (!repositoryId) {
      throw new Error('[issue-tickets/azure] createPullRequest requires repositoryId (repository GUID or name).');
    }
    if (!title || !sourceBranch || !targetBranch) {
      throw new Error('[issue-tickets/azure] createPullRequest requires title, sourceBranch, and targetBranch.');
    }

    const pr = await gitClient.createPullRequest(
      {
        title,
        description: description ?? '',
        sourceRefName: sourceBranch.startsWith('refs/') ? sourceBranch : `refs/heads/${sourceBranch}`,
        targetRefName: targetBranch.startsWith('refs/') ? targetBranch : `refs/heads/${targetBranch}`,
      },
      repositoryId,
    );

    return {
      source: 'azure',
      id: String(pr.pullRequestId ?? ''),
      url: pr.url ?? '',
      title: pr.title ?? title,
      sourceBranch,
      targetBranch,
    };
  }

  async createIssue(params: CreateIssueParams): Promise<CreateIssueResult> {
    const { orgUrl, token } = getAzureAccount(params.account);
    const conn = getConnection(orgUrl, token);
    const witClient = await conn.getWorkItemTrackingApi();

    const { title, description, projectId, type = 'Task', labels, assignee } = params;
    if (!projectId) {
      throw new Error('[issue-tickets/azure] createIssue requires projectId (Azure DevOps project name or ID).');
    }

    const patchDocument: { op: string; path: string; value: unknown }[] = [
      { op: 'add', path: '/fields/System.Title', value: title },
    ];
    if (description) {
      patchDocument.push({ op: 'add', path: '/fields/System.Description', value: description });
    }
    if (labels && labels.length > 0) {
      patchDocument.push({ op: 'add', path: '/fields/System.Tags', value: labels.join('; ') });
    }
    if (assignee) {
      patchDocument.push({ op: 'add', path: '/fields/System.AssignedTo', value: assignee });
    }

    const workItem = await witClient.createWorkItem(
      [],
      patchDocument as any,
      String(projectId),
      type,
    );

    const id = String(workItem?.id ?? '');
    return {
      source: 'azure',
      id,
      url: workItem?._links?.html?.href ?? `${orgUrl}/_workitems/edit/${id}`,
      title: workItem?.fields?.['System.Title'] ?? title,
    };
  }
}

export function getAzureProvider(): TicketProvider {
  return new AzureProvider();
}
