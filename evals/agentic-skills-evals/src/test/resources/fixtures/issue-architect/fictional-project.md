# Lumio — Fictional Project Reference

> This document describes a realistic fictional React + TypeScript project used for eval testing.
> Use this as a substitute for actual project context. Treat it as Phase 2 output (full project scan).

## Project Overview

**Lumio** is a task management SPA built with React 18, TypeScript 5, Vite, and Vitest. No UI component library. Plain CSS with CSS variables for theming.

## Stack

- **Runtime**: Node 18+
- **Frontend**: React 18.2, TypeScript 5.0, React Router 7
- **Build**: Vite 5, SWC transpilation
- **Testing**: Vitest, @testing-library/react
- **Styling**: Plain CSS with CSS variables, no Tailwind/MUI/Radix
- **State**: React Context only, no Redux/Zustand
- **API**: fetch-based, no GraphQL

## Directory Structure

```
lumio/
├── src/
│   ├── App.tsx              (root router setup)
│   ├── main.tsx             (entry point, createRoot)
│   ├── index.css            (global reset)
│   ├── api/
│   │   ├── tasks.ts         (GET/POST/PATCH /api/tasks)
│   │   └── auth.ts          (POST /api/auth/login, /logout)
│   ├── components/
│   │   ├── Navigation.tsx    (navbar, logout button, no dark mode toggle yet)
│   │   ├── TaskList/
│   │   │   ├── TaskList.tsx  (main list view, renders TaskCard)
│   │   │   └── TaskCard.tsx  (individual task row, edit/delete inline)
│   │   └── Common/
│   │       └── ErrorBoundary.tsx
│   ├── features/
│   │   ├── auth/
│   │   │   ├── LoginForm.tsx (form with email/password, no client validation yet)
│   │   │   └── useAuth.ts    (custom hook, useState for user + token)
│   │   └── tasks/
│   │       ├── CreateTask.tsx (modal, adds new task)
│   │       └── useTaskList.ts (custom hook, fetch/refetch)
│   ├── styles/
│   │   ├── theme.css        (CSS variables, colors, spacing)
│   │   └── forms.css        (input, button, form base styles)
│   ├── types/
│   │   └── index.ts         (Task, User, AuthToken interfaces)
│   └── utils/
│       └── storage.ts       (localStorage helpers)
├── public/
│   └── index.html
├── package.json
├── tsconfig.json
├── vite.config.ts
├── vitest.config.ts
└── README.md
```

## Key Files — Content Snapshots

### `package.json`

```json
{
  "name": "lumio",
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview",
    "test": "vitest",
    "test:ui": "vitest --ui"
  },
  "dependencies": {
    "react": "^18.2.0",
    "react-dom": "^18.2.0",
    "react-router": "^7.0.0"
  },
  "devDependencies": {
    "@testing-library/react": "^14.0.0",
    "@types/react": "^18.0.0",
    "@types/react-dom": "^18.0.0",
    "@vitejs/plugin-react-swc": "^3.0.0",
    "typescript": "^5.0.0",
    "vite": "^5.0.0",
    "vitest": "^1.0.0"
  }
}
```

### `src/App.tsx`

```typescript
import React from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router';
import Navigation from './components/Navigation';
import TaskList from './components/TaskList/TaskList';
import LoginForm from './features/auth/LoginForm';
import { useAuth } from './features/auth/useAuth';
import './index.css';

export default function App() {
  const { user } = useAuth();

  return (
    <Router>
      <Navigation />
      <main className="container">
        <Routes>
          <Route path="/login" element={<LoginForm />} />
          <Route path="/" element={user ? <TaskList /> : <LoginForm />} />
        </Routes>
      </main>
    </Router>
  );
}
```

### `src/components/Navigation.tsx`

```typescript
import React from 'react';
import { useAuth } from '../features/auth/useAuth';
import './Navigation.css';

export default function Navigation() {
  const { user, logout } = useAuth();

  return (
    <nav className="navbar">
      <div className="nav-brand">
        <h1>Lumio</h1>
      </div>
      {user && (
        <div className="nav-actions">
          <span className="user-email">{user.email}</span>
          <button onClick={logout} className="btn-logout">
            Logout
          </button>
        </div>
      )}
    </nav>
  );
}
```

### `src/styles/theme.css`

```css
:root {
  --color-primary: #2563eb;
  --color-primary-dark: #1e40af;
  --color-text: #1f2937;
  --color-text-light: #6b7280;
  --color-bg: #ffffff;
  --color-bg-light: #f9fafb;
  --color-border: #e5e7eb;
  --color-error: #dc2626;
  --color-success: #16a34a;

  --spacing-xs: 0.25rem;
  --spacing-sm: 0.5rem;
  --spacing-md: 1rem;
  --spacing-lg: 1.5rem;
  --spacing-xl: 2rem;

  --font-size-sm: 0.875rem;
  --font-size-md: 1rem;
  --font-size-lg: 1.125rem;

  --shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.05);
  --shadow-md: 0 4px 6px rgba(0, 0, 0, 0.1);
}

* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background-color: var(--color-bg);
  color: var(--color-text);
  line-height: 1.5;
}

.container {
  max-width: 1200px;
  margin: 0 auto;
  padding: var(--spacing-md);
}
```

### `src/features/auth/LoginForm.tsx`

```typescript
import React, { useState } from 'react';
import { useNavigate } from 'react-router';
import { login } from '../../api/auth';
import { useAuth } from './useAuth';

export default function LoginForm() {
  const navigate = useNavigate();
  const { setUser, setToken } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');

    // BUG: No client-side validation. Empty password is submitted to API.
    // Expected: block submission if password is empty.
    // Current: form sends { email, password: '' } → API rejects but UX is poor.

    try {
      const { token, user } = await login(email, password);
      setToken(token);
      setUser(user);
      navigate('/');
    } catch (err: any) {
      setError(err.message || 'Login failed');
    } finally {
      setLoading(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="login-form">
      <h2>Login</h2>
      {error && <div className="error-message">{error}</div>}
      <div className="form-group">
        <label htmlFor="email">Email</label>
        <input
          id="email"
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />
      </div>
      <div className="form-group">
        <label htmlFor="password">Password</label>
        <input
          id="password"
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
      </div>
      <button type="submit" disabled={loading}>
        {loading ? 'Logging in...' : 'Login'}
      </button>
    </form>
  );
}
```

### `src/components/TaskList/TaskList.tsx`

```typescript
import React, { useEffect } from 'react';
import { useTaskList } from '../../features/tasks/useTaskList';
import TaskCard from './TaskCard';
import CreateTask from '../../features/tasks/CreateTask';
import './TaskList.css';

export default function TaskList() {
  const { tasks, loading, error, refetch } = useTaskList();

  useEffect(() => {
    refetch();
  }, []);

  if (loading) return <div>Loading tasks...</div>;
  if (error) return <div className="error">{error}</div>;

  return (
    <div className="task-list-container">
      <div className="task-list-header">
        <h2>My Tasks</h2>
        <CreateTask onCreated={refetch} />
      </div>
      <div className="task-list">
        {tasks.length === 0 ? (
          <p className="empty-state">No tasks yet. Create one to get started!</p>
        ) : (
          tasks.map((task) => <TaskCard key={task.id} task={task} onUpdate={refetch} />)
        )}
      </div>
    </div>
  );
}
```

### `tsconfig.json`

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "esModuleInterop": true,
    "allowSyntheticDefaultImports": true,
    "strict": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "noEmit": true,
    "jsx": "react-jsx"
  },
  "include": ["src"],
  "exclude": ["node_modules"]
}
```

### `README.md`

```markdown
# Lumio

A task management SPA built with React 18 and TypeScript. Simple, fast, no external UI libraries.

## Getting Started

```bash
npm install
npm run dev
```

Open [http://localhost:5173](http://localhost:5173) in your browser.

## Development

- `npm run test` — run Vitest suite
- `npm run build` — build for production
- `npm run preview` — preview production build locally

## Architecture

- **React Router** for client-side navigation
- **React Context** for global state (auth, theme)
- **Custom hooks** for data fetching (tasks, auth)
- **Plain CSS with CSS variables** for styling (no build-time CSS-in-JS)

## Known Issues

- Login form lacks client-side validation (password field can be empty)
- Navigation bar has no dark mode toggle
- Task list doesn't support filtering/sorting
```

---

## Usage in Eval Scenarios

Each scenario `.md` will reference this fictional project. Example:

```markdown
## Project context (pre-scanned)

> The project has been fully scanned. Use the context below as Phase 2 output.
> Do not call Read, Glob, Bash, or any filesystem tools.

**Project**: Lumio (task management SPA, React 18 + TypeScript)

### Key files:
- src/features/auth/LoginForm.tsx — form component with BUG noted below
- src/components/Navigation.tsx — navbar component
- src/styles/theme.css — CSS variables, no dark mode yet
```

