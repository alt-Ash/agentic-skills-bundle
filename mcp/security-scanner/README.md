# security-scanner

An MCP server that actively tests a URL for common web vulnerabilities — real
payloads, not just fingerprinting — and writes a detailed report. Built so
"full active exploitation" and "must never take a production service down"
can coexist: production is not a value this tool can be configured to accept.

## Allowlist — the safety gate

Every tool call, passive or active, is checked against a project-local
allowlist before a single request is sent. By default it's read from
`.security-scanner/allowlist.json` in the current working directory
(override with `SCANNER_ALLOWLIST_PATH`):

```json
{
  "targets": [
    { "host": "localhost:3000", "environment": "local" },
    { "host": "staging.example.com", "environment": "staging" }
  ]
}
```

`environment` must be one of `local | dev | staging | test`. **`prod` /
`production` is not a valid value** — there is no typo, override, or
configuration that authorizes scanning a production host. If the file is
missing, malformed, or contains an environment value outside that set, every
tool call is refused with a clear reason (fail closed) rather than silently
scanning nothing or skipping the bad entry.

Host matching is exact and case-insensitive (host includes port, e.g.
`localhost:3000`) — no wildcard/subdomain matching.

## Tools

- **`scan_passive({ target })`** — safe, read-only checks: missing security
  headers, cookie flags, CORS misconfiguration, exposed sensitive paths
  (`/.env`, `/.git/*`, `/admin`), server/framework fingerprinting.
- **`scan_active({ target, categories?, confirm, authorization })`** — real
  payloads: reflected XSS, SQL/NoSQL injection signatures, open redirect,
  path traversal, JWT `alg:none` / bounded IDOR auth-bypass probes, and an SSRF
  timing signal. `confirm` must be `true` and `authorization` must be a
  non-empty string (e.g. a ticket reference) — both are deliberate friction,
  and `authorization` is stamped into the report as an audit trail.
- **`get_scan_report({ scanId })`** — retrieves a previously written report.

## What this deliberately does not do

- Never scans a target not in the allowlist, and the allowlist schema cannot
  represent a production host.
- Never runs sustained-load or denial-of-service testing — availability is
  checked with a single lightweight request, not a load test.
- SSRF checks use a canary URL that fails fast (nothing listens on
  `127.0.0.1:1`), never real internal/cloud-metadata addresses.
- IDOR probing is bounded to a small fixed range (±3 IDs), never a large
  enumeration sweep.
- A shared circuit breaker (see `src/http-client.ts`) aborts the rest of a
  scan the moment the target's error rate or latency degrades sharply — active
  exploitation continues, but stops the instant it looks like it's causing
  real damage.

## Reports

Every scan writes `security-scans/<host>-<timestamp>.json` and `.md` in the
calling process's working directory.

## Development

```bash
pnpm install
pnpm run build   # tsc -> dist/
pnpm test        # vitest — allowlist + circuit-breaker unit tests
```
