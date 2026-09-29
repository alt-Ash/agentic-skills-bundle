# NestJS v8 → v9 Migration Guide

Source: https://docs.nestjs.com/v9/migration-guide

## Upgrading packages

Use [npm-check-updates (ncu)](https://npmjs.com/package/npm-check-updates) for a streamlined upgrade:

```bash
npx npm-check-updates -u
npm install
```

Or upgrade manually:

```bash
npm install @nestjs/common@9 @nestjs/core@9 @nestjs/platform-express@9
# repeat for all @nestjs/* packages in use
```

## Breaking changes

### Redis strategy (microservices)

The Redis microservice strategy now uses [ioredis](https://github.com/luin/ioredis) instead of the `redis` package.

**Before:**
```typescript
const app = await NestFactory.createMicroservice<MicroserviceOptions>(AppModule, {
  transport: Transport.REDIS,
  options: {
    url: 'redis://localhost:6379',
  },
});
```

**After:**
```typescript
const app = await NestFactory.createMicroservice<MicroserviceOptions>(AppModule, {
  transport: Transport.REDIS,
  options: {
    host: 'localhost',
    port: 6379,
  },
});
```

Install the new driver:

```bash
npm install ioredis
npm uninstall redis
```

### gRPC client interceptors

The `interceptors` configuration property must now be passed inside the `channelOptions` object instead of at the top level.

### Fastify upgraded to v4

`@nestjs/platform-fastify` now requires Fastify v4. All core Fastify plugins were renamed and moved under the `@fastify` scope:

| Old package | New package |
|---|---|
| `fastify-cookie` | `@fastify/cookie` |
| `fastify-helmet` | `@fastify/helmet` |
| `fastify-multipart` | `@fastify/multipart` |
| `fastify-static` | `@fastify/static` |

Update all `fastify-*` dependencies to their `@fastify/*` equivalents and bump Fastify to v4.

Full rename list: https://github.com/fastify/fastify/issues/3856

### `@nestjs/swagger` package

`swagger-ui-express` and `fastify-swagger` are no longer required as peer dependencies. See the [breaking changes PR](https://github.com/nestjs/swagger/pull/1886) for details.

### Deprecated APIs removed

All methods and modules that were deprecated in v8 have been removed. Notable example: the `listenAsync()` method — use `listen()` instead.

### Node.js v10 dropped

NestJS v9 requires **Node.js v12 or higher**. Node.js v10 is no longer supported.
