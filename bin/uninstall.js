#!/usr/bin/env node
// Thin shim — forces uninstall mode regardless of how it is invoked.
process.argv.push('--uninstall');
await import('./install.js');
