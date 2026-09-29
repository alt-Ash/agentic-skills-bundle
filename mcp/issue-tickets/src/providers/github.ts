/**
 * github.ts — GitHub issue/PR provider for issue-tickets.
 *
 * Credentials — two modes (multi-account takes precedence):
 *
 *   Multi-account (recommended):
 *     GITHUB_ACCOUNTS  JSON: {"work": "ghp_token1", "personal": "ghp_token2", ...}
 *
 *   Single-account (legacy / backwards compat):
 *     GITHUB_TOKEN  Personal Access Token or GitHub App installation token
 */

import { Octokit } from 'octokit';
import type { TicketProvider, NormalizedTicket, TicketComment, PullRequestResult, PullTicketParams, CreatePrParams, CreateIssueParams, CreateIssueResult } from '../types.js';
import { extractOpenItems } from '../ticket-text.js';

// ─── Account resolution ───────────────────────────────────────────────────────

interface GithubAccount {
  name: string;
  token: string;
}

/**
 * Returns all configured GitHub accounts.
 * Reads GITHUB_ACCOUNTS_B64 (base64-encoded JSON) first, then falls back
 * to plain GITHUB_ACCOUNTS (JSON), then to GITHUB_TOKEN.
 */
function getAllGithubAccounts(): GithubAccount[] {
  const b64 = process.env['GITHUB_ACCOUNTS_B64'];
  if (b64) {
    try {
      const json = Buffer.from(b64, 'base64').toString('utf8');
      const parsed = JSON.parse(json) as Record<string, string>;
      return Object.entries(parsed).map(([name, token]) => ({ name, token }));
    } catch {
      throw new Error('[issue-tickets/github] GITHUB_ACCOUNTS_B64 is not valid base64-encoded JSON.');
    }
  }

  const json = process.env['GITHUB_ACCOUNTS'];
  if (json) {
    try {
      const parsed = JSON.parse(json) as Record<string, string>;
      return Object.entries(parsed).map(([name, token]) => ({ name, token }));
    } catch {
      throw new Error('[issue-tickets/github] GITHUB_ACCOUNTS is not valid JSON.');
    }
  }

  // Legacy single-account fallback
  const token = process.env['GITHUB_TOKEN'];
  if (token) {
    return [{ name: 'default', token }];
  }

  return [];
}

/**
 * Returns a single account by name, or throws if not found.
 * If accountName is omitted and only one account is configured, returns it.
 */
function getGithubAccount(accountName?: string): GithubAccount {
  const accounts = getAllGithubAccounts();
  if (accounts.length === 0) {
    throw new Error(
      '[issue-tickets/github] No GitHub credentials configured. ' +
      'Set GITHUB_ACCOUNTS (JSON) or GITHUB_TOKEN.',
    );
  }

  if (!accountName) {
    if (accounts.length === 1) return accounts[0];
    throw new Error(
      `[issue-tickets/github] Multiple GitHub accounts configured (${accounts.map((a) => a.name).join(', ')}). ` +
      'Specify an account name.',
    );
  }

  const found = accounts.find((a) => a.name === accountName);
  if (!found) {
    throw new Error(
      `[issue-tickets/github] Account "${accountName}" not found. ` +
      `Available: ${accounts.map((a) => a.name).join(', ')}.`,
    );
  }
  return found;
}

export function hasGithubCredentials(): boolean {
  return getAllGithubAccounts().length > 0;
}

/** Returns the names of all configured GitHub accounts. */
export function getGithubAccountNames(): string[] {
  return getAllGithubAccounts().map((a) => a.name);
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** Parse "owner/repo" string. */
function parseRepo(repo: string): { owner: string; repo: string } {
  const parts = repo.split('/');
  if (parts.length !== 2 || !parts[0] || !parts[1]) {
    throw new Error(`[issue-tickets/github] Invalid repo format "${repo}". Expected "owner/repo".`);
  }
  return { owner: parts[0], repo: parts[1] };
}

// ─── Normalisation ────────────────────────────────────────────────────────────

function normaliseIssue(issue: any, comments?: TicketComment[]): NormalizedTicket {
  const description = issue.body ?? '';
  return {
    source: 'github',
    id: String(issue.number ?? issue.id ?? ''),
    title: issue.title ?? '',
    description,
    status: issue.state ?? '',
    url: issue.html_url ?? '',
    assignee: issue.assignee?.login ?? null,
    labels: (issue.labels ?? []).map((l: any) => (typeof l === 'string' ? l : l.name ?? '')),
    createdAt: issue.created_at ?? '',
    updatedAt: issue.updated_at ?? '',
    comments,
    openItems: extractOpenItems(description),
    raw: issue,
  };
}

function mapGithubComments(comments: any[]): TicketComment[] {
  return (comments ?? [])
    .map((c) => ({ author: c.user?.login ?? '', date: c.created_at ?? '', text: c.body ?? '' }))
    .filter((c) => c.text);
}

// ─── Provider ─────────────────────────────────────────────────────────────────

class GithubProvider implements TicketProvider {
  async pullTicket(params: PullTicketParams): Promise<NormalizedTicket[]> {
    const { token } = getGithubAccount(params.account);
    const octokit = new Octokit({ auth: token });
    const { ticketIds, projectId, pageSize = 25 } = params;

    // Fetch by issue numbers — projectId must be "owner/repo" (detail fetch — also pull comments)
    if (ticketIds && ticketIds.length > 0) {
      if (!projectId) {
        throw new Error('[issue-tickets/github] Provide projectId ("owner/repo") when using ticketIds.');
      }
      const { owner, repo } = parseRepo(String(projectId));
      const results = await Promise.all(
        (ticketIds as number[]).map(async (number) => {
          const issue_number = Number(number);
          const [issue, comments] = await Promise.all([
            octokit.rest.issues.get({ owner, repo, issue_number }).then((r) => r.data),
            octokit.rest.issues.listComments({ owner, repo, issue_number }).then((r) => r.data).catch(() => []),
          ]);
          return { issue, comments };
        })
      );
      return results.map(({ issue, comments }) => normaliseIssue(issue, mapGithubComments(comments)));
    }

    // List issues by repo
    if (projectId) {
      const { owner, repo } = parseRepo(String(projectId));
      const { data: issues } = await octokit.rest.issues.listForRepo({
        owner,
        repo,
        state: 'open',
        sort: 'updated',
        direction: 'desc',
        per_page: pageSize,
      });
      // Filter out pull requests (GitHub returns PRs in issues endpoint)
      return issues.filter((i) => !i.pull_request).map((i) => normaliseIssue(i));
    }

    throw new Error('[issue-tickets/github] Provide ticketIds or a projectId ("owner/repo").');
  }

  async createPullRequest(params: CreatePrParams): Promise<PullRequestResult> {
    const { token } = getGithubAccount(params.account);
    const octokit = new Octokit({ auth: token });
    const { title, sourceBranch, targetBranch, repo, description } = params;

    if (!repo) {
      throw new Error('[issue-tickets/github] createPullRequest requires repo ("owner/repo").');
    }
    if (!title || !sourceBranch || !targetBranch) {
      throw new Error('[issue-tickets/github] createPullRequest requires title, sourceBranch, and targetBranch.');
    }

    const { owner, repo: repoName } = parseRepo(repo);

    try {
      const { data: pr } = await octokit.rest.pulls.create({
        owner,
        repo: repoName,
        title,
        head: sourceBranch,
        base: targetBranch,
        body: description ?? '',
      });

      return {
        source: 'github',
        id: String(pr.number),
        url: pr.html_url,
        title: pr.title,
        sourceBranch,
        targetBranch,
      };
    } catch (err: any) {
      // If a PR already exists for this head→base, return the existing one
      if (err?.status === 422) {
        const { data: prs } = await octokit.rest.pulls.list({
          owner,
          repo: repoName,
          head: `${owner}:${sourceBranch}`,
          base: targetBranch,
          state: 'open',
        });
        if (prs.length > 0) {
          const existing = prs[0];
          return {
            source: 'github',
            id: String(existing.number),
            url: existing.html_url,
            title: existing.title,
            sourceBranch,
            targetBranch,
            alreadyExisted: true,
          };
        }
      }
      throw err;
    }
  }

  async createIssue(params: CreateIssueParams): Promise<CreateIssueResult> {
    const { token } = getGithubAccount(params.account);
    const octokit = new Octokit({ auth: token });
    const { title, description, projectId, labels, assignee } = params;

    if (!projectId) {
      throw new Error('[issue-tickets/github] createIssue requires projectId ("owner/repo").');
    }
    const { owner, repo } = parseRepo(String(projectId));

    const { data: issue } = await octokit.rest.issues.create({
      owner,
      repo,
      title,
      body: description,
      labels: labels,
      assignees: assignee ? [assignee] : undefined,
    });

    return {
      source: 'github',
      id: String(issue.number),
      url: issue.html_url,
      title: issue.title,
    };
  }
}

export function getGithubProvider(): TicketProvider {
  return new GithubProvider();
}
