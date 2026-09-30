# Scenario 01: Apply Code Fixes (Helios API)

## Request

Apply all open findings from the security audit report below for the Helios Node.js API. The HANDOFF BLOCK contains 10 code findings. Produce the corrected code for each finding and emit the updated audit report.

## Pre-scanned source code

> The project "Helios" has been fully scanned. Treat the code below as complete and accurate.
> **Do not call Read, Glob, Bash, or any filesystem tools.**

### package.json (abridged)

```json
{
  "name": "helios-api",
  "version": "1.0.0",
  "dependencies": {
    "bcrypt": "^5.1.0",
    "cors": "^2.8.5",
    "express": "^4.18.2",
    "jsonwebtoken": "^9.0.0",
    "pg": "^8.11.0"
  }
}
```

### src/index.ts (full file)

```typescript
import express from 'express';
import cors from 'cors';
import { json } from 'express';
import authRouter from './routes/auth';
import ordersRouter from './routes/orders';
import calcRouter from './routes/calc';
import filesRouter from './routes/files';
import { errorHandler } from './middleware/error';

const app = express();
app.use(cors({ origin: '*' }));   // line 11
app.use(json());
app.use('/api/auth', authRouter);
app.use('/api/orders', ordersRouter);
app.use('/api/calc', calcRouter);
app.use('/api/files', filesRouter);
app.use(errorHandler);

app.listen(4000, () => console.log('Helios API running on :4000'));
```

### src/config/jwt.ts (full file)

```typescript
// JWT configuration
export const JWT_SECRET = 'helios-dev-secret-key-do-not-use-in-prod';   // line 3
export const JWT_EXPIRY = '24h';
```

### src/middleware/auth.ts (full file)

```typescript
import jwt from 'jsonwebtoken';
import { JWT_SECRET } from '../config/jwt';
import type { Request, Response, NextFunction } from 'express';

export function authenticate(req: Request, res: Response, next: NextFunction) {
  const token = req.headers.authorization?.split(' ')[1];
  if (!token) return res.status(401).json({ error: 'No token' });
  try {
    const decoded = jwt.verify(token, JWT_SECRET);   // line 12
    (req as any).user = decoded;
    next();
  } catch {
    res.status(401).json({ error: 'Invalid token' });
  }
}
```

### src/middleware/error.ts (full file)

```typescript
import type { Request, Response, NextFunction } from 'express';

export function errorHandler(
  err: Error,
  req: Request,
  res: Response,
  next: NextFunction,
) {
  res.status(500).json({ error: err.stack });   // line 9
}
```

### src/routes/auth.ts (full file)

```typescript
import { Router } from 'express';
import bcrypt from 'bcrypt';
import jwt from 'jsonwebtoken';
import { db } from '../db';
import { JWT_SECRET, JWT_EXPIRY } from '../config/jwt';

const router = Router();

router.post('/login', async (req, res) => {   // line 20 — no rate limiting applied here
  const { email, password } = req.body;
  const user = await db.query('SELECT * FROM users WHERE email = $1', [email]);
  if (!user.rows.length) return res.status(404).json({ error: 'Not found' });
  const valid = await bcrypt.compare(password, user.rows[0].password_hash);
  if (!valid) return res.status(401).json({ error: 'Invalid credentials' });
  const token = jwt.sign({ userId: user.rows[0].id }, JWT_SECRET, { expiresIn: JWT_EXPIRY });
  res.json({ token });
});

router.post('/forgot-password', async (req, res) => {
  const { email } = req.body;
  const user = await db.query('SELECT id FROM users WHERE email = $1', [email]);
  if (!user.rows.length) return res.status(404).json({ error: 'Not found' });
  const resetToken = Math.random().toString(36).substring(2);   // line 54
  await db.query(
    'UPDATE users SET reset_token = $1 WHERE email = $2',
    [resetToken, email],
  );
  res.json({ message: 'Reset email sent' });
});

export default router;
```

### src/routes/orders.ts (full file)

```typescript
import { Router } from 'express';
import { db } from '../db';
import { authenticate } from '../middleware/auth';

const router = Router();

router.get('/', authenticate, async (req, res) => {
  const userId = (req as any).user.userId;
  const result = await db.query('SELECT * FROM orders WHERE user_id = $1', [userId]);
  res.json(result.rows);
});

router.get('/:orderId', authenticate, async (req, res) => {
  try {
    const result = await db.query(   // line 31
      'SELECT * FROM orders WHERE id = ' + req.params.orderId
    );
    res.json(result.rows[0]);
  } catch (err: any) {
    res.status(500).json({ error: err.message });
  }
});

export default router;
```

### src/routes/calc.ts (full file)

```typescript
import { Router } from 'express';
import { authenticate } from '../middleware/auth';

const router = Router();

router.post('/evaluate', authenticate, async (req, res) => {
  const { formula } = req.body;
  try {
    // eslint-disable-next-line no-eval
    const result = eval(formula);   // line 18
    res.json({ result });
  } catch (err: any) {
    res.status(400).json({ error: 'Invalid formula' });
  }
});

export default router;
```

### src/routes/files.ts (full file)

```typescript
import { Router } from 'express';
import { readFileSync } from 'fs';
import { join, normalize } from 'path';
import { authenticate } from '../middleware/auth';

const router = Router();

const UPLOADS_DIR = join(process.cwd(), 'uploads');

router.get('/download/:filename', authenticate, async (req, res) => {
  try {
    const content = readFileSync('./uploads/' + req.params.filename);   // line 27
    res.send(content);
  } catch {
    res.status(404).json({ error: 'File not found' });
  }
});

export default router;
```

## Pre-baked execution results

> Steps 2, 3, and 5 have been pre-run. Treat the results below as complete and accurate.

### Step 2 — npm audit baseline

```json
{ "critical": 0, "high": 0, "moderate": 0, "low": 0 }
```

All 10 findings are code vulnerabilities, not dependency vulnerabilities.

### Step 3 — Package installs done

```
+ helmet@7.2.0
+ express-rate-limit@7.4.0
added 2 packages, and audited 318 packages in 4s
found 0 vulnerabilities
```

`package.json` now includes `"helmet": "^7.2.0"` and `"express-rate-limit": "^7.4.0"`.

### Step 5 — Post-fix npm audit

```json
{ "critical": 0, "high": 0, "moderate": 0, "low": 0 }
```

## Handoff Block

```json
{
  "schema": "security-handoff/v1",
  "targetProject": "helios-api",
  "auditDate": "2026-06-01",
  "codeFindings": [
    {
      "id": "C-01",
      "title": "SQL Injection via String Concatenation",
      "severity": "Critical",
      "cwe": "CWE-89",
      "owasp": "A03",
      "status": "open",
      "type": "code",
      "location": "src/routes/orders.ts:31"
    },
    {
      "id": "C-02",
      "title": "Cryptographically Weak PRNG for Password Reset Token",
      "severity": "Critical",
      "cwe": "CWE-338",
      "owasp": "A02",
      "status": "open",
      "type": "code",
      "location": "src/routes/auth.ts:54"
    },
    {
      "id": "C-03",
      "title": "Hardcoded JWT Secret",
      "severity": "Critical",
      "cwe": "CWE-798",
      "owasp": "A02",
      "status": "open",
      "type": "code",
      "location": "src/config/jwt.ts:3"
    },
    {
      "id": "C-04",
      "title": "Code Injection via eval() with User Input",
      "severity": "Critical",
      "cwe": "CWE-1336",
      "owasp": "A03",
      "status": "open",
      "type": "code",
      "location": "src/routes/calc.ts:18"
    },
    {
      "id": "C-05",
      "title": "Path Traversal in File Download",
      "severity": "High",
      "cwe": "CWE-22",
      "owasp": "A01",
      "status": "open",
      "type": "code",
      "location": "src/routes/files.ts:27"
    },
    {
      "id": "C-06",
      "title": "JWT Verified Without Algorithm Constraint",
      "severity": "High",
      "cwe": "CWE-287",
      "owasp": "A07",
      "status": "open",
      "type": "code",
      "location": "src/middleware/auth.ts:12"
    },
    {
      "id": "C-07",
      "title": "CORS Wildcard Origin Allows Any Domain",
      "severity": "High",
      "cwe": "CWE-942",
      "owasp": "A05",
      "status": "open",
      "type": "code",
      "location": "src/index.ts:11"
    },
    {
      "id": "C-08",
      "title": "Missing Security Headers (No Helmet)",
      "severity": "High",
      "cwe": "CWE-693",
      "owasp": "A05",
      "status": "open",
      "type": "code",
      "location": "src/index.ts:8"
    },
    {
      "id": "C-09",
      "title": "No Rate Limiting on Login Endpoint",
      "severity": "High",
      "cwe": "CWE-770",
      "owasp": "A04",
      "status": "open",
      "type": "code",
      "location": "src/routes/auth.ts:20"
    },
    {
      "id": "C-10",
      "title": "Stack Trace Leaked in Error Response",
      "severity": "Medium",
      "cwe": "CWE-209",
      "owasp": "A09",
      "status": "open",
      "type": "code",
      "location": "src/middleware/error.ts:9"
    }
  ],
  "dependencyFindings": [],
  "verifyCommand": "npm audit --json | jq '.metadata.vulnerabilities'"
}
```

## Instructions

⚠️ **CRITICAL: This is a TEST SCENARIO. Do NOT call any tools.**

**Steps 2, 3, 5**: SKIP. The "## Pre-baked execution results" section above ARE those steps' outputs. Do not call Bash or npm.

**Step 4**: For each finding in the HANDOFF BLOCK, determine the correct fix and show it as a before/after code block in your response. Do not call Edit or Write.

**Step 6**: Follow your Step 6 instructions.
