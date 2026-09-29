# NestJS v9 → v10 Migration Guide

Source: https://docs.nestjs.com/v10/migration-guide

Full list of breaking changes: https://github.com/nestjs/nest/pull/11517

## Upgrading packages

Use [npm-check-updates (ncu)](https://npmjs.com/package/npm-check-updates) for a streamlined upgrade:

```bash
npx npm-check-updates -u
npm install
```

Or upgrade manually:

```bash
npm install @nestjs/common@10 @nestjs/core@10 @nestjs/platform-express@10
# repeat for all @nestjs/* packages in use
```

## Breaking changes

### Cache module moved to standalone package

`CacheModule` has been removed from `@nestjs/common` and is now a separate package.

```bash
npm install @nestjs/cache-manager
npm uninstall cache-manager   # if you were using it directly
```

Update imports:

```typescript
// Before
import { CacheModule } from '@nestjs/common';

// After
import { CacheModule } from '@nestjs/cache-manager';
```

See the [caching docs](https://docs.nestjs.com/techniques/caching) for the full API.

### Deprecated APIs removed

All methods and modules deprecated in v9 have been removed.

### CLI Plugins require TypeScript >= 4.8

`@nestjs/swagger` and `@nestjs/graphql` CLI plugins now require TypeScript v4.8 or higher due to breaking AST changes in that TypeScript release. Upgrade TypeScript if needed:

```bash
npm install typescript@latest --save-dev
```

### Node.js v12 dropped

NestJS v10 requires **Node.js v16 or higher**. Node.js v12 is no longer supported.

All official NestJS packages are now compiled to `ES2021` (previously `ES2017`). This reduces bundle sizes and may yield minor performance improvements, but requires a modern runtime.
