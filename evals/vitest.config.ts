import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    globals: true,
    testTimeout: 180_000,
    hookTimeout: 30_000,
    include: ['**/*.{test,spec,eval}.?(c|m)[jt]s?(x)'],
  },
});
