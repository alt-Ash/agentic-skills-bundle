import { mkdirSync, writeFileSync, readFileSync, readdirSync } from 'fs';
import path from 'path';
import type { Finding } from './checks/passive.js';
import type { ScanStatus } from './http-client.js';

export interface ScanReport {
  schema: 'url-scan/v1';
  target: string;
  environment: string;
  mode: 'passive' | 'active';
  startedAt: string;
  finishedAt: string;
  status: ScanStatus;
  authorization?: string;
  requestsIssued: number;
  findings: Finding[];
}

function reportsDir(): string {
  return path.resolve(process.cwd(), 'security-scans');
}

function severityRank(sev: Finding['severity']): number {
  return { Critical: 0, High: 1, Moderate: 2, Low: 3 }[sev];
}

function renderMarkdown(report: ScanReport): string {
  const lines: string[] = [];
  lines.push(`# URL Security Scan — \`${report.target}\``);
  lines.push('');
  lines.push(`**Environment:** ${report.environment}  **Mode:** ${report.mode}  **Status:** ${report.status}`);
  lines.push(`**Started:** ${report.startedAt}  **Finished:** ${report.finishedAt}  **Requests issued:** ${report.requestsIssued}`);
  if (report.authorization) lines.push(`**Authorization:** ${report.authorization}`);
  lines.push('');

  const sorted = [...report.findings].sort((a, b) => severityRank(a.severity) - severityRank(b.severity));

  lines.push(`## Findings (${sorted.length})`);
  lines.push('');
  if (sorted.length === 0) {
    lines.push('No findings.');
  } else {
    lines.push('| ID | Severity | OWASP | Description |');
    lines.push('|---|---|---|---|');
    for (const f of sorted) {
      lines.push(`| ${f.id} | ${f.severity} | ${f.category} | ${f.description} |`);
    }
    lines.push('');
    for (const f of sorted) {
      lines.push(`### [${f.severity}] ${f.id} — ${f.description}`);
      lines.push(`**OWASP:** ${f.category}`);
      lines.push(`**Evidence:** ${f.evidence}`);
      lines.push(`**Remediation:** ${f.remediation}`);
      lines.push('');
    }
  }

  return lines.join('\n');
}

function scanIdFor(target: string, startedAt: string): string {
  const safeHost = target.replace(/[^a-z0-9.-]/gi, '_');
  const safeTime = startedAt.replace(/[^0-9]/g, '');
  return `${safeHost}-${safeTime}`;
}

export function writeReport(report: ScanReport): { scanId: string; jsonPath: string; mdPath: string } {
  const dir = reportsDir();
  mkdirSync(dir, { recursive: true });

  const scanId = scanIdFor(report.target, report.startedAt);
  const jsonPath = path.join(dir, `${scanId}.json`);
  const mdPath = path.join(dir, `${scanId}.md`);

  writeFileSync(jsonPath, JSON.stringify(report, null, 2), 'utf8');
  writeFileSync(mdPath, renderMarkdown(report), 'utf8');

  return { scanId, jsonPath, mdPath };
}

export function getScanReport(scanId: string): ScanReport | null {
  const dir = reportsDir();
  const jsonPath = path.join(dir, `${scanId}.json`);
  try {
    return JSON.parse(readFileSync(jsonPath, 'utf8')) as ScanReport;
  } catch {
    return null;
  }
}

export function listScanReports(): string[] {
  const dir = reportsDir();
  try {
    return readdirSync(dir)
      .filter((f) => f.endsWith('.json'))
      .map((f) => f.replace(/\.json$/, ''));
  } catch {
    return [];
  }
}
