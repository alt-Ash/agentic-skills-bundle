/**
 * types.ts — Shared interfaces for issue-tickets providers.
 */

export type TicketSource = 'azure' | 'github';

// ─── Normalised ticket shape ──────────────────────────────────────────────────

export interface NormalizedTicket {
  source: TicketSource;
  id: string;
  title: string;
  description: string;
  status: string;
  url: string;
  assignee: string | null;
  labels: string[];
  createdAt: string;
  updatedAt: string;
  /**
   * The comment/discussion thread, when fetched (detail fetches via `ticketIds` only —
   * never populated on list/allProjects results, to avoid N+1 API calls).
   */
  comments?: TicketComment[];
  /**
   * Author-flagged asides within the description — e.g. Azure DevOps rich-text spans
   * with an explicit non-default color, used as an authoring convention for "this is a
   * caveat/note, not a firm requirement". Empty where the source has no such convention.
   */
  flaggedAsides?: string[];
  /**
   * Explicit "To be elaborated:", "TBD:", or "TODO:" markers extracted from the
   * description — genuinely unresolved items, distinct from both scope and noise.
   */
  openItems?: string[];
  /** Provider-specific raw response — access fields not covered by the normalised shape. */
  raw: unknown;
}

export interface TicketComment {
  author: string;
  date: string;
  text: string;
}

// ─── Normalised pull-request result ──────────────────────────────────────────

export interface PullRequestResult {
  source: TicketSource;
  id: string;
  url: string;
  title: string;
  sourceBranch: string;
  targetBranch: string;
  /** True when the PR already existed (GitHub idempotent create). */
  alreadyExisted?: boolean;
}

// ─── Provider interface ───────────────────────────────────────────────────────

export interface PullTicketParams {
  ticketIds?: number[] | string[];
  projectId?: string | number;
  allProjects?: boolean;
  pageSize?: number;
  /**
   * For multi-account setups: the account name to use (as configured in
   * AZURE_DEVOPS_ACCOUNTS or GITHUB_ACCOUNTS). Omit when only one account
   * is configured or to auto-detect from project config.
   */
  account?: string;
}

export interface CreatePrParams {
  title: string;
  sourceBranch: string;
  targetBranch: string;
  /** Azure DevOps: required. The repository GUID or name. */
  repositoryId?: string;
  /** GitHub: required. owner/repo format. */
  repo?: string;
  description?: string;
  /**
   * For multi-account setups: the account name to use (as configured in
   * AZURE_DEVOPS_ACCOUNTS or GITHUB_ACCOUNTS).
   */
  account?: string;
}

export interface CreateIssueParams {
  title: string;
  description?: string;
  /**
   * Azure DevOps: project name or ID (required).
   * GitHub: "owner/repo" (required).
   */
  projectId: string | number;
  /**
   * Issue type / work item type.
   * Azure DevOps: work item type (e.g. "Bug", "User Story", "Task", "Feature"). Defaults to "Task".
   * GitHub: ignored (GitHub issues have no type).
   */
  type?: string;
  /**
   * Labels to apply.
   * Azure DevOps: used as tags (semicolon-separated internally).
   * GitHub: array of label names.
   */
  labels?: string[];
  /**
   * Assignee.
   * Azure DevOps: display name or email of the assignee.
   * GitHub: GitHub username.
   */
  assignee?: string;
  /**
   * For multi-account setups: the account name to use.
   */
  account?: string;
}

export interface CreateIssueResult {
  source: TicketSource;
  id: string;
  url: string;
  title: string;
}

export interface TicketProvider {
  pullTicket(params: PullTicketParams): Promise<NormalizedTicket[]>;
  createPullRequest(params: CreatePrParams): Promise<PullRequestResult>;
  createIssue(params: CreateIssueParams): Promise<CreateIssueResult>;
}
