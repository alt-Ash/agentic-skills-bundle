import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    globals: true,
    include: ['e2e/**/*.test.ts'],
    testTimeout: 30_000,
    hookTimeout: 15_000,
    singleFork: true,
  },
});
