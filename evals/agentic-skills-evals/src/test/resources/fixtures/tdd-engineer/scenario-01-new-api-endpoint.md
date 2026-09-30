# Scenario 01: New API Endpoint (POST /api/users)

## Request

Implement a `POST /api/users` endpoint in the Prism API using TDD. The endpoint must:
- Accept a JSON body with `name` (string) and `email` (string)
- Return `200` with `{ id, name, email }` on valid input (use `crypto.randomUUID()` for the id)
- Return `400` with `{ error: 'Name is required' }` if `name` is missing or empty
- Return `400` with `{ error: 'Email is required' }` if `email` is missing or empty
- Return `400` with `{ error: 'Invalid email format' }` if `email` does not match a basic email pattern

## Pre-scanned project (Phase 0 complete)

> Phase 0 has been fully executed. Treat the information below as complete and accurate.
> **Do not call Read, Glob, Bash, or any filesystem tools.**

### Directory structure

```
prism-api/
├── src/
│   ├── app.ts
│   ├── routes/
│   │   ├── health.ts
│   │   ├── health.test.ts
│   │   └── users.ts         ← does not exist yet
│   └── index.ts
├── package.json
├── tsconfig.json
└── jest.config.js
```

### package.json (abridged)

```json
{
  "name": "prism-api",
  "version": "1.0.0",
  "scripts": {
    "test": "jest",
    "lint": "eslint src",
    "typecheck": "tsc --noEmit",
    "build": "tsc"
  },
  "dependencies": {
    "express": "^4.18.2"
  },
  "devDependencies": {
    "@types/express": "^4.17.21",
    "@types/jest": "^29.5.12",
    "@types/supertest": "^6.0.2",
    "jest": "^29.7.0",
    "supertest": "^7.0.0",
    "ts-jest": "^29.1.4",
    "typescript": "^5.4.5"
  }
}
```

### src/app.ts (full file)

```typescript
import express from 'express';
import { healthRouter } from './routes/health';

const app = express();
app.use(express.json());
app.use('/api', healthRouter);

export { app };
```

### src/routes/health.ts (full file)

```typescript
import { Router } from 'express';

export const healthRouter = Router();

healthRouter.get('/health', (_req, res) => {
  res.json({ status: 'ok' });
});
```

### src/routes/health.test.ts (full file — test pattern reference)

```typescript
import request from 'supertest';
import { app } from '../app';

describe('health', () => {
  it('GET /api/health returns 200 with status ok', async () => {
    const response = await request(app).get('/api/health');
    expect(response.status).toBe(200);
    expect(response.body).toEqual({ status: 'ok' });
  });
});
```

## Pre-baked execution results

> Phase 1, Phase 3, and Phase 5 have been pre-run. Treat the results below as complete and accurate.

### Phase 1 — Test run (test suite fails to run — production module missing)

```
FAIL src/routes/users.test.ts
  ● Test suite failed to run

    Cannot find module '../routes/users' from 'src/routes/users.test.ts'

Test Suites: 1 failed, 1 total
Tests:       0 failed, 0 total
```

### Phase 3 — Full test suite (all tests pass)

```
PASS src/routes/health.test.ts
PASS src/routes/users.test.ts
  users
    POST /api/users
      ✓ creates a user with valid body (11ms)
      ✓ returns 400 when name is missing (4ms)
      ✓ returns 400 when email is missing (3ms)
      ✓ returns 400 when email format is invalid (3ms)

Test Suites: 2 passed, 2 total
Tests:       5 passed, 5 total
```

### Phase 5 — Project checks

```
$ eslint src
(no output — exit 0)

$ tsc --noEmit
(no output — exit 0)

$ tsc
(no output — exit 0)
```

## Instructions

⚠️ **CRITICAL: This is a TEST SCENARIO. Do NOT call any tools.**

**Phase 0**: SKIP. The "## Pre-scanned project" section above IS your complete Phase 0 output.

**Phase 1**: Write the test file (`src/routes/users.test.ts`) as a code block in your response. Do not call Write or Edit.

**Phase 2**: The pre-baked Phase 1 run IS your test run output. State why the test fails using that output.

**Phase 3**: Write the production code (`src/routes/users.ts` and any required changes to `src/app.ts`) as code blocks in your response. Do not call Write or Edit.

**Phase 4**: Skip.

**Phase 5**: The pre-baked Phase 5 checks ARE your check outputs. All pass.

**Phase 6**: Follow your Phase 6 instructions.
