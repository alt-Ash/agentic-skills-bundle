/**
 * Real-payload checks. Callers must already have passed the allowlist +
 * confirm/authorization gate in index.ts before this module runs — every
 * function here assumes it's authorized and focuses purely on detection
 * logic. Every request still routes through the shared ScanSession, so the
 * budget/circuit-breaker from http-client.ts applies uniformly across every
 * category below.
 *
 * Deliberately NOT implemented, regardless of category selection: real SSRF
 * against internal/cloud-metadata addresses, sustained-load/DoS testing, or
 * unbounded IDOR enumeration. See ssrfProbe() and idorProbe() for the
 * specific, conservative substitutes and why.
 */

import type { ScanSession } from '../http-client.js';
import type { Finding } from './passive.js';

export type ActiveCategory =
  | 'xss'
  | 'injection'
  | 'open_redirect'
  | 'path_traversal'
  | 'auth_bypass'
  | 'ssrf';

const ALL_CATEGORIES: ActiveCategory[] = ['xss', 'injection', 'open_redirect', 'path_traversal', 'auth_bypass', 'ssrf'];

// A small, fixed set of common param names — this is a simple tool, not a
// crawler. It does not discover params from HTML/JS; it only probes the
// handful of names that most commonly carry these vulnerability classes.
const COMMON_PARAMS = ['id', 'q', 'search', 'redirect', 'next', 'url', 'path', 'file', 'page'];

let findingCounter = 0;
function nextId(): string {
  findingCounter += 1;
  return `AF-${String(findingCounter).padStart(2, '0')}`;
}

function finding(partial: Omit<Finding, 'id'>): Finding {
  return { id: nextId(), ...partial };
}

async function xssProbe(session: ScanSession, findings: Finding[]): Promise<void> {
  const marker = '__scanner_xss_marker__';
  const payload = `<script>/*${marker}*/</script>`;
  for (const param of COMMON_PARAMS) {
    if (session.isAborted()) return;
    const res = await session.request(`/?${param}=${encodeURIComponent(payload)}`);
    if (res && res.body.includes(payload)) {
      findings.push(
        finding({
          category: 'A03',
          severity: 'High',
          description: `Reflected XSS: '${param}' parameter is reflected unescaped`,
          evidence: `GET /?${param}=<script>...${marker}...</script> — payload reflected verbatim in response body`,
          remediation: `HTML-encode ${param} before rendering it, or use a templating engine that auto-escapes by default.`,
        }),
      );
    }
  }
}

async function injectionProbe(session: ScanSession, findings: Finding[]): Promise<void> {
  const sqlPayloads = [`' OR '1'='1`, `' OR 1=1--`];
  const noSqlPayload = `[$gt]=`;
  for (const param of COMMON_PARAMS) {
    if (session.isAborted()) return;
    for (const payload of sqlPayloads) {
      const res = await session.request(`/?${param}=${encodeURIComponent(payload)}`);
      if (res && /sql syntax|sqlite|postgres|ORA-\d{5}|mysql_fetch/i.test(res.body)) {
        findings.push(
          finding({
            category: 'A03',
            severity: 'Critical',
            description: `Possible SQL injection via '${param}' (database error signature in response)`,
            evidence: `GET /?${param}=${payload} — response body contained a database error signature`,
            remediation: 'Use parameterized queries / an ORM query builder — never string-concatenate user input into SQL.',
          }),
        );
        break;
      }
    }
    const noSqlRes = await session.request(`/?${param}${encodeURIComponent(noSqlPayload)}`);
    if (noSqlRes && noSqlRes.status >= 200 && noSqlRes.status < 300) {
      // Detection-only signal — a 2xx on an operator-injection-shaped query is
      // a weak signal on its own; recorded as Moderate pending manual review.
      findings.push(
        finding({
          category: 'A03',
          severity: 'Moderate',
          description: `'${param}' accepts a MongoDB-operator-shaped query string without error`,
          evidence: `GET /?${param}[$gt]= → HTTP ${noSqlRes.status} (needs manual confirmation)`,
          remediation: 'Reject non-scalar values for query params that feed directly into a NoSQL query.',
        }),
      );
    }
  }
}

async function openRedirectProbe(session: ScanSession, findings: Finding[]): Promise<void> {
  const externalTarget = 'http://example.com/scanner-redirect-check';
  for (const param of ['redirect', 'next', 'url', 'return', 'returnUrl']) {
    if (session.isAborted()) return;
    const res = await session.request(`/?${param}=${encodeURIComponent(externalTarget)}`, { redirect: 'manual' });
    const location = res?.headers['location'];
    if (res && res.status >= 300 && res.status < 400 && location?.includes('example.com')) {
      findings.push(
        finding({
          category: 'A01',
          severity: 'Moderate',
          description: `Open redirect via '${param}' parameter`,
          evidence: `GET /?${param}=${externalTarget} → HTTP ${res.status} Location: ${location}`,
          remediation: `Validate ${param} against an allowlist of internal paths before redirecting.`,
        }),
      );
    }
  }
}

async function pathTraversalProbe(session: ScanSession, findings: Finding[]): Promise<void> {
  const payload = '../../../../../../etc/passwd';
  for (const param of ['file', 'path', 'page', 'template']) {
    if (session.isAborted()) return;
    const res = await session.request(`/?${param}=${encodeURIComponent(payload)}`);
    if (res && /root:.*:0:0:/.test(res.body)) {
      findings.push(
        finding({
          category: 'A03',
          severity: 'Critical',
          description: `Path traversal via '${param}' parameter (read /etc/passwd contents)`,
          evidence: `GET /?${param}=${payload} — response body contained /etc/passwd content`,
          remediation: `Resolve ${param} against an allowlisted base directory and reject any path containing '..'.`,
        }),
      );
    }
  }
}

function jwtAlgNoneVariant(token: string): string | null {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  try {
    const header = JSON.parse(Buffer.from(parts[0], 'base64url').toString('utf8'));
    header.alg = 'none';
    const newHeader = Buffer.from(JSON.stringify(header)).toString('base64url');
    return `${newHeader}.${parts[1]}.`;
  } catch {
    return null;
  }
}

async function authBypassProbe(session: ScanSession, findings: Finding[], observedJwt?: string): Promise<void> {
  if (observedJwt) {
    const forged = jwtAlgNoneVariant(observedJwt);
    if (forged) {
      const res = await session.request('/', { headers: { authorization: `Bearer ${forged}` } });
      if (res && res.status >= 200 && res.status < 300) {
        findings.push(
          finding({
            category: 'A07',
            severity: 'Critical',
            description: `JWT 'alg:none' forged token accepted`,
            evidence: `A token with header.alg rewritten to 'none' and signature stripped returned HTTP ${res.status}`,
            remediation: `Pin \`algorithms\` explicitly in every jwt.verify() call — never accept 'none'.`,
          }),
        );
      }
    }
  }

  // Bounded IDOR probing: a small fixed range (±3), never a large enumeration
  // sweep — this respects the same request budget as everything else and
  // avoids turning a detection check into a scraping operation.
  const idParam = 'id';
  for (const delta of [-3, -2, -1, 1, 2, 3]) {
    if (session.isAborted()) return;
    const res = await session.request(`/?${idParam}=${1000 + delta}`);
    if (res && res.status >= 200 && res.status < 300) {
      findings.push(
        finding({
          category: 'A01',
          severity: 'Moderate',
          description: `'${idParam}' parameter accepts adjacent IDs without an observable ownership check (needs manual confirmation)`,
          evidence: `GET /?${idParam}=${1000 + delta} → HTTP ${res.status}`,
          remediation: 'Verify resource ownership server-side before returning data, independent of whether the ID resolves.',
        }),
      );
      break;
    }
  }
}

/**
 * SSRF probe is deliberately conservative: it uses a canary URL that fails
 * fast (nothing is listening on 127.0.0.1:1) rather than ever pointing at
 * real internal/cloud-metadata addresses (e.g. 169.254.169.254) — this tool
 * has no out-of-band callback infrastructure to confirm a true blind SSRF, so
 * pretending otherwise would either produce false negatives or require
 * touching addresses this tool must never touch. Treat any hit here as a
 * strong signal that outbound requests aren't being filtered, not proof of
 * exploitable SSRF.
 */
async function ssrfProbe(session: ScanSession, findings: Finding[]): Promise<void> {
  const canary = process.env.SCANNER_SSRF_CANARY_URL ?? 'http://127.0.0.1:1/ssrf-canary-unreachable';
  for (const param of ['url', 'callback', 'webhook', 'fetch']) {
    if (session.isAborted()) return;
    const start = Date.now();
    const res = await session.request(`/?${param}=${encodeURIComponent(canary)}`);
    const elapsed = Date.now() - start;
    // A connection-refused round trip is fast; a server that *attempted* the
    // outbound fetch typically takes noticeably longer even on failure.
    if (res && elapsed > 1000) {
      findings.push(
        finding({
          category: 'A10',
          severity: 'Moderate',
          description: `'${param}' parameter appears to trigger a server-side outbound request (needs manual confirmation)`,
          evidence: `GET /?${param}=${canary} took ${elapsed}ms — consistent with the server attempting the fetch`,
          remediation: `Validate and allowlist any URL taken from user input before the server fetches it.`,
        }),
      );
    }
  }
}

export interface ActiveCheckOptions {
  categories?: ActiveCategory[];
  observedJwt?: string;
}

export async function runActiveChecks(session: ScanSession, opts: ActiveCheckOptions = {}): Promise<Finding[]> {
  const categories = opts.categories && opts.categories.length > 0 ? opts.categories : ALL_CATEGORIES;
  const findings: Finding[] = [];

  const runners: Record<ActiveCategory, () => Promise<void>> = {
    xss: () => xssProbe(session, findings),
    injection: () => injectionProbe(session, findings),
    open_redirect: () => openRedirectProbe(session, findings),
    path_traversal: () => pathTraversalProbe(session, findings),
    auth_bypass: () => authBypassProbe(session, findings, opts.observedJwt),
    ssrf: () => ssrfProbe(session, findings),
  };

  for (const category of categories) {
    if (session.isAborted()) break;
    await runners[category]();
  }

  return findings;
}
