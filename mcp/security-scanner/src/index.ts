#!/usr/bin/env node
/**
 * security-scanner — an MCP server that actively tests an authorized,
 * non-production URL for common web vulnerabilities and writes a detailed
 * report. Every scan (passive or active) is gated by a project-local
 * allowlist (.security-scanner/allowlist.json) whose schema has no "prod"
 * value at all — see src/allowlist.ts. Active scans additionally require an
 * explicit confirm flag and an authorization string.
 *
 * Tools exposed:
 *   scan_passive     — safe fingerprinting only (headers, cookies, CORS,
 *                       exposed paths, version fingerprinting)
 *   scan_active      — real payloads (XSS, injection, open redirect, path
 *                       traversal, auth bypass, SSRF signal)
 *   get_scan_report  — retrieve a prior report by scanId
 */

import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { authorizeTarget } from './allowlist.js';
import { createScanSession } from './http-client.js';
import { runPassiveChecks } from './checks/passive.js';
import { runActiveChecks, type ActiveCategory } from './checks/active.js';
import { writeReport, getScanReport } from './report.js';

const server = new McpServer({ name: 'security-scanner', version: '1.0.0' });

function refused(reason: string) {
  return {
    content: [{ type: 'text' as const, text: JSON.stringify({ status: 'refused', reason }, null, 2) }],
  };
}

server.registerTool(
  'scan_passive',
  {
    description:
      'Runs safe, read-only vulnerability fingerprinting against an allowlisted target: ' +
      'missing security headers, cookie flags, CORS misconfiguration, exposed sensitive paths ' +
      '(.env, .git, /admin), and server/framework fingerprinting. Refuses any target not present ' +
      'in .security-scanner/allowlist.json.',
    inputSchema: {
      target: z.string().describe('Host (and port if non-default), e.g. "localhost:3000" or "staging.example.com".'),
    },
  },
  async ({ target }) => {
    const auth = authorizeTarget(target);
    if (auth.configError) return refused(auth.configError);
    if (!auth.allowed) return refused(auth.reason ?? 'not allowlisted');

    const startedAt = new Date().toISOString();
    const session = createScanSession({ target });
    const findings = await runPassiveChecks(session);
    const stats = session.getStats();
    const finishedAt = new Date().toISOString();

    const { scanId, mdPath } = writeReport({
      schema: 'url-scan/v1',
      target,
      environment: auth.environment!,
      mode: 'passive',
      startedAt,
      finishedAt,
      status: stats.aborted ? 'aborted_instability' : 'completed',
      requestsIssued: stats.requestsIssued,
      findings,
    });

    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify(
            { status: 'completed', scanId, reportPath: mdPath, findingCount: findings.length, findings },
            null,
            2,
          ),
        },
      ],
    };
  },
);

server.registerTool(
  'scan_active',
  {
    description:
      'Runs real-payload vulnerability checks (XSS, SQL/NoSQL injection, open redirect, path ' +
      'traversal, JWT/IDOR auth-bypass probes, SSRF signal) against an allowlisted target. ' +
      'Requires confirm: true and a non-empty authorization string. Refuses any target not present ' +
      'in .security-scanner/allowlist.json. A shared circuit breaker aborts the scan if the target ' +
      'starts erroring or slowing down significantly.',
    inputSchema: {
      target: z.string().describe('Host (and port if non-default), e.g. "localhost:3000" or "staging.example.com".'),
      categories: z
        .array(z.enum(['xss', 'injection', 'open_redirect', 'path_traversal', 'auth_bypass', 'ssrf']))
        .optional()
        .describe('Subset of check categories to run. Omit to run all.'),
      confirm: z.boolean().describe('Must be true — deliberate friction so this never fires by accident.'),
      authorization: z.string().min(1).describe('Free-text authorization record (e.g. ticket ref), stamped into the report as an audit trail.'),
    },
  },
  async ({ target, categories, confirm, authorization }) => {
    const auth = authorizeTarget(target);
    if (auth.configError) return refused(auth.configError);
    if (!auth.allowed) return refused(auth.reason ?? 'not allowlisted');
    if (!confirm) return refused('confirm must be explicitly set to true to run active exploitation checks.');
    if (!authorization || authorization.trim().length === 0) {
      return refused('authorization must be a non-empty string (e.g. a ticket reference) — recorded in the report as an audit trail.');
    }

    const startedAt = new Date().toISOString();
    const session = createScanSession({ target });
    const findings = await runActiveChecks(session, { categories: categories as ActiveCategory[] | undefined });
    const stats = session.getStats();
    const finishedAt = new Date().toISOString();

    const { scanId, mdPath } = writeReport({
      schema: 'url-scan/v1',
      target,
      environment: auth.environment!,
      mode: 'active',
      startedAt,
      finishedAt,
      status: stats.aborted ? 'aborted_instability' : 'completed',
      authorization,
      requestsIssued: stats.requestsIssued,
      findings,
    });

    return {
      content: [
        {
          type: 'text',
          text: JSON.stringify(
            {
              status: stats.aborted ? 'aborted_instability' : 'completed',
              scanId,
              reportPath: mdPath,
              requestsIssued: stats.requestsIssued,
              findingCount: findings.length,
              findings,
            },
            null,
            2,
          ),
        },
      ],
    };
  },
);

server.registerTool(
  'get_scan_report',
  {
    description: 'Retrieves a previously written scan report by its scanId.',
    inputSchema: {
      scanId: z.string().describe('The scanId returned by scan_passive or scan_active.'),
    },
  },
  async ({ scanId }) => {
    const report = getScanReport(scanId);
    if (!report) {
      throw new Error(`No report found for scanId '${scanId}'.`);
    }
    return { content: [{ type: 'text', text: JSON.stringify(report, null, 2) }] };
  },
);

const transport = new StdioServerTransport();
await server.connect(transport);
