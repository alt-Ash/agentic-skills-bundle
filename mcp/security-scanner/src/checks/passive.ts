/**
 * Safe, read-only checks. Every request still goes through the shared
 * ScanSession (rate limiting, budget, circuit breaker) and the caller is
 * still required to have passed the allowlist gate — there is no
 * passive-only bypass of authorization, only of *payload* risk.
 */

import type { ScanSession } from '../http-client.js';

export interface Finding {
  id: string;
  category: string; // OWASP Top 10:2025 category, e.g. "A05"
  severity: 'Critical' | 'High' | 'Moderate' | 'Low';
  description: string;
  evidence: string;
  remediation: string;
}

const SECURITY_HEADERS: Array<{ header: string; category: string; severity: Finding['severity']; remediation: string }> = [
  { header: 'strict-transport-security', category: 'A05', severity: 'Moderate', remediation: 'Send `Strict-Transport-Security: max-age=31536000; includeSubDomains`.' },
  { header: 'x-content-type-options', category: 'A05', severity: 'Low', remediation: 'Send `X-Content-Type-Options: nosniff`.' },
  { header: 'x-frame-options', category: 'A05', severity: 'Moderate', remediation: 'Send `X-Frame-Options: DENY` or a CSP `frame-ancestors` directive.' },
  { header: 'content-security-policy', category: 'A05', severity: 'Moderate', remediation: 'Define a `Content-Security-Policy` appropriate to the app.' },
];

const EXPOSED_PATHS = ['/.env', '/.git/config', '/.git/HEAD', '/admin', '/.well-known/security.txt'];

function redact(value: string): string {
  return value.replace(/(password|token|secret|authorization|api[_-]?key)\s*[:=]\s*\S+/gi, '$1=[REDACTED]');
}

let findingCounter = 0;
function nextId(): string {
  findingCounter += 1;
  return `PF-${String(findingCounter).padStart(2, '0')}`;
}

export async function runPassiveChecks(session: ScanSession): Promise<Finding[]> {
  const findings: Finding[] = [];

  const root = await session.request('/');
  if (root) {
    for (const { header, category, severity, remediation } of SECURITY_HEADERS) {
      if (!root.headers[header]) {
        findings.push({
          id: nextId(),
          category,
          severity,
          description: `Missing security header: ${header}`,
          evidence: `GET / — response headers did not include '${header}'.`,
          remediation,
        });
      }
    }

    const setCookie = root.headers['set-cookie'];
    if (setCookie) {
      const missing = ['secure', 'httponly', 'samesite'].filter(
        (flag) => !setCookie.toLowerCase().includes(flag),
      );
      if (missing.length > 0) {
        findings.push({
          id: nextId(),
          category: 'A05',
          severity: 'Moderate',
          description: `Cookie missing flag(s): ${missing.join(', ')}`,
          evidence: redact(`Set-Cookie: ${setCookie}`),
          remediation: 'Set Secure, HttpOnly, and SameSite on every session cookie.',
        });
      }
    }

    const acao = root.headers['access-control-allow-origin'];
    const acac = root.headers['access-control-allow-credentials'];
    if (acao === '*' && acac?.toLowerCase() === 'true') {
      findings.push({
        id: nextId(),
        category: 'A05',
        severity: 'High',
        description: 'CORS misconfiguration: wildcard origin combined with credentials',
        evidence: `Access-Control-Allow-Origin: * with Access-Control-Allow-Credentials: true`,
        remediation: 'Never combine a wildcard origin with credentialed requests — echo a validated origin instead.',
      });
    }

    const server = root.headers['server'];
    const poweredBy = root.headers['x-powered-by'];
    if (server || poweredBy) {
      findings.push({
        id: nextId(),
        category: 'A05',
        severity: 'Low',
        description: 'Framework/version fingerprinting via response headers',
        evidence: [server && `Server: ${server}`, poweredBy && `X-Powered-By: ${poweredBy}`].filter(Boolean).join(', '),
        remediation: 'Suppress or genericize the Server/X-Powered-By headers in production.',
      });
    }
  }

  for (const path of EXPOSED_PATHS) {
    if (session.isAborted()) break;
    const res = await session.request(path);
    if (res && res.status >= 200 && res.status < 300) {
      findings.push({
        id: nextId(),
        category: 'A05',
        severity: path.includes('.env') || path.includes('.git') ? 'Critical' : 'Moderate',
        description: `Sensitive path is publicly reachable: ${path}`,
        evidence: `GET ${path} → HTTP ${res.status}`,
        remediation: `Ensure ${path} is not served by the application (deny in web-server config, remove from the public root).`,
      });
    }
  }

  return findings;
}
