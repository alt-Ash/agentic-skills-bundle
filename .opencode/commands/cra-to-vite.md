---
description: "Migrate a Create React App (react-scripts) project to Vite and Jest tests to Vitest. Usage: /cra-to-vite"
subtask: true
---

Use the `cra-to-vite` skill to migrate this project from Create React App to Vite.

Follow the full 15-step migration checklist from the skill: audit the project, upgrade Node.js to 20, swap dependencies, convert `require()` calls to ES imports, create `vite.config`, restructure `index.html`, rename env variables (`REACT_APP_*` → `VITE_*`), update `package.json` scripts, set up TypeScript (if applicable), migrate tests from Jest to Vitest, remove CRA artifacts, verify the build, update `.gitignore`, check for license violations, and update CI/CD pipelines, Dockerfiles, and Helm/Kubernetes templates.
