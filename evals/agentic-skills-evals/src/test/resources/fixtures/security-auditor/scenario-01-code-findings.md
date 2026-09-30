# Scenario 01: Code Findings Audit (Vortex API)

## Request

Audit the Vortex Node.js API for security vulnerabilities. Exploit all confirmed findings. Produce the full report including the HANDOFF BLOCK.

## Pre-scanned project (Steps 1–3 complete)

> Steps 1–3 have been fully executed. Treat the output below as complete and accurate. Do NOT run any Bash, Grep, or filesystem commands.

### Step 1 — Project shape

```
# Framework / auth / security libs present:
express
jsonwebtoken
pg

# NOT present (no matches):
helmet
express-rate-limit
rateLimit
ThrottlerModule

# Entry point
{"main":"src/index.ts","start":"ts-node src/index.ts"}

# Node version: 18.17.0
# Lockfile: package-lock.json present
```

`package.json` (abridged):
```json
{
  "name": "vortex-api",
  "version": "1.0.0",
  "dependencies": {
    "express": "^4.18.2",
    "jsonwebtoken": "^9.0.0",
    "pg": "^8.11.0"
  }
}
```

### Step 2 — npm audit triage output

```json
{
  "confirmed": [],
  "suppressed": [],
  "summary": {
    "total": 0,
    "confirmed": 0,
    "suppressed": 0
  }
}
```

No confirmed vulnerable dependencies. No dependency findings.

### Step 3 — OWASP A01–A10 grep results

**A01 — Broken Access Control**
```
# Routes without auth guard — no matches
# CORS wildcard — no matches
# JWT missing algorithms — no matches
```

**A02 — Cryptographic Failures**
```
# Math.random() in security context
src/routes/auth.ts:47:  const token = Math.random().toString(36).substring(2);

# Hardcoded JWT secret — no matches (uses process.env.JWT_SECRET)
# Weak hash — no matches
# TLS disabled — no matches
```

`src/routes/auth.ts` (lines 42–54, full context):
```typescript
router.post('/forgot-password', async (req, res) => {
  const { email } = req.body;
  const user = await db.query('SELECT id FROM users WHERE email = $1', [email]);
  if (!user.rows.length) return res.status(404).json({ error: 'Not found' });

  const token = Math.random().toString(36).substring(2);   // line 47
  await db.query(
    'UPDATE users SET reset_token = $1 WHERE email = $2',
    [token, email],
  );
  res.json({ message: 'Reset email sent' });
});
```

**A03 — Injection**
```
# SQL injection — raw concatenation
src/routes/users.ts:23:  const result = await db.query('SELECT * FROM users WHERE id = ' + req.params.id);

# NoSQL injection — no matches (uses pg, not MongoDB)
# Command injection — no matches
# Path traversal — no matches
```

`src/routes/users.ts` (lines 19–29, full context):
```typescript
router.get('/:id', async (req, res) => {
  try {
    const result = await db.query(   // line 23
      'SELECT * FROM users WHERE id = ' + req.params.id
    );
    res.json(result.rows[0]);
  } catch (err: any) {
    res.status(500).json({ error: err.message });   // also leaks stack
  }
});
```

**A04 — Insecure Design**
```
# Rate limiting — no matches (express-rate-limit, rateLimit, ThrottlerModule — none present)
# File upload — no matches
# Password reset entropy — see A02 above (Math.random token)
```

**A05 — Security Misconfiguration**
```
# helmet() — no matches in src/index.ts or any entry point
# CORS — no matches (no cors() call)
# Stack trace leaked — see src/routes/users.ts:27 (err.message sent in res.json)
# .env git tracking — git ls-files .env: no output (correctly gitignored)
# Hardcoded secrets — no matches
```

`src/index.ts` (full file):
```typescript
import express from 'express';
import usersRouter from './routes/users';
import authRouter from './routes/auth';

const app = express();
// No helmet() call
app.use(express.json());
app.use('/api/users', usersRouter);
app.use('/api/auth', authRouter);

app.listen(3000, () => console.log('Vortex API running on :3000'));
```

**A06 — Vulnerable & Outdated Components**: covered in Step 2. No confirmed findings.

**A07 — Authentication Failures**
```
# jwt.verify without algorithms — no matches (jwt.verify not used; only jwt.sign)
# Session config — no matches (no express-session)
```

**A08 — Data Integrity**
```
# Unsafe deserialization — no matches
# Unsigned cookies — no matches
```

**A09 — Logging Failures**
```
# Sensitive data in logs — no matches
# Swallowed catch blocks — src/routes/users.ts:27 sends err.message (see A05)
```

**A10 — SSRF**
```
# Outbound URL from user input — no matches
```

## Pre-baked exploitation results (Step 5)

> User confirmed: **A — run all findings**.
> The curl/script results below ARE the Step 5 exploitation output. Do NOT run any network commands. Use these results verbatim.

### Exploit — C-01 — `Math.random()` used for cryptographic randomness
**Status:** Confirmed (code review)
**Payload:** N/A — internal token generation; no HTTP endpoint exposes the raw token for direct injection
**Response:** N/A
**Impact:** Reset tokens generated via `Math.random()` have ~54 bits of entropy from a non-CSPRNG. An attacker can predict or brute-force tokens within minutes using statistical analysis of the PRNG state, enabling account takeover without knowing the victim's password.

### Exploit — C-02 — SQL injection via string concatenation
**Status:** Confirmed
**Payload:** `curl -s "http://localhost:3000/api/users/1'"`
**Response:** `HTTP 500 {"error":"syntax error at or near \"'\" at character 38"}`
**Impact:** Full read access to any row in the `users` table. Union-based extraction possible: `GET /api/users/0 UNION SELECT null,email,password_hash FROM users--`

### Exploit — C-03 — No Helmet — security headers absent
**Status:** Confirmed
**Payload:** `curl -sI http://localhost:3000/api/users/1`
**Response:**
```
HTTP/1.1 200 OK
X-Powered-By: Express
Content-Type: application/json
```
**Impact:** No `X-Frame-Options`, `X-Content-Type-Options`, `Strict-Transport-Security`, or `Content-Security-Policy` headers. Browser-based attacks (clickjacking, MIME sniffing, XSS amplification) are unmitigated.

### Exploit — C-04 — No rate limiting on any endpoint
**Status:** Confirmed
**Payload:** `for i in $(seq 1 100); do curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3000/api/auth/forgot-password -H 'Content-Type: application/json' -d '{"email":"victim@example.com"}'; done`
**Response:** 100 × `200` — all requests succeed without throttling
**Impact:** `/api/auth/forgot-password` is brute-forceable without limit. Combined with C-01 (weak token entropy), an attacker can enumerate tokens by flooding the endpoint and testing predictions.

## Instructions

⚠️ **CRITICAL: This is a TEST SCENARIO. Do NOT run any commands.**

**Steps 1–3**: SKIP entirely. The "## Pre-scanned project" section above IS your complete Steps 1–3 output. Treat it as fact.

**Step 4**: Assign severity from the fixed catalog for each finding identified in the grep results.

**Step 5 (Exploitation)**: SKIP running any curl commands. The "## Pre-baked exploitation results" section above IS your complete Step 5 output. Incorporate those results verbatim into your report.

**Step 6**: Produce the complete security audit report following your canonical format.
