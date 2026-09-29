/**
 * Rate-limited, budget-capped, circuit-breaking wrapper around native fetch().
 * This is the mechanism that lets "full active exploitation" coexist with
 * "must never take a target down": every request from every check routes
 * through one shared ScanSession per scan, so the breaker sees the whole
 * picture, not just one check's traffic.
 */

const DEFAULT_MAX_REQUESTS = 500;
const HARD_REQUEST_CEILING = 2000;
const DEFAULT_MIN_DELAY_MS = 200;
const DEFAULT_MAX_CONCURRENCY = 2;
const CIRCUIT_WINDOW_SIZE = 20;
const CIRCUIT_ERROR_RATE_THRESHOLD = 0.4;
const CIRCUIT_LATENCY_MULTIPLIER = 3;
const REQUEST_TIMEOUT_MS = 5000;

export type ScanStatus = 'completed' | 'aborted_instability' | 'refused';

export interface RequestRecord {
  path: string;
  status: number | 'timeout' | 'error';
  latencyMs: number;
  ok: boolean;
}

export interface ScanSessionOptions {
  target: string;
  maxRequests?: number;
  minDelayMs?: number;
  maxConcurrency?: number;
}

export interface ScanResponse {
  ok: boolean;
  status: number;
  headers: Record<string, string>;
  body: string;
  latencyMs: number;
}

export interface ScanSession {
  request(pathOrUrl: string, init?: RequestInit): Promise<ScanResponse | null>;
  getStats(): { requestsIssued: number; aborted: boolean; records: RequestRecord[] };
  isAborted(): boolean;
}

function clamp(value: number, max: number): number {
  return Math.min(value, max);
}

/**
 * Creates one scan session shared by every check in a single scan_passive or
 * scan_active call. Not safe to share across different targets/scans.
 */
export function createScanSession(opts: ScanSessionOptions): ScanSession {
  const maxRequests = clamp(opts.maxRequests ?? DEFAULT_MAX_REQUESTS, HARD_REQUEST_CEILING);
  const minDelayMs = opts.minDelayMs ?? DEFAULT_MIN_DELAY_MS;
  const maxConcurrency = opts.maxConcurrency ?? DEFAULT_MAX_CONCURRENCY;

  const records: RequestRecord[] = [];
  let inFlight = 0;
  let aborted = false;
  let baselineLatencyMs: number | null = null;
  let lastRequestAt = 0;
  const pendingQueue: Array<() => void> = [];

  function recentWindow(): RequestRecord[] {
    return records.slice(-CIRCUIT_WINDOW_SIZE);
  }

  function evaluateBreaker(): void {
    const window = recentWindow();
    if (window.length === 0) return;

    const errorCount = window.filter((r) => !r.ok).length;
    const errorRate = errorCount / window.length;

    const avgLatency = window.reduce((sum, r) => sum + r.latencyMs, 0) / window.length;

    if (errorRate >= CIRCUIT_ERROR_RATE_THRESHOLD) {
      aborted = true;
      return;
    }
    if (baselineLatencyMs !== null && avgLatency >= baselineLatencyMs * CIRCUIT_LATENCY_MULTIPLIER) {
      aborted = true;
    }
  }

  async function acquireSlot(): Promise<void> {
    if (inFlight < maxConcurrency) {
      inFlight++;
      return;
    }
    await new Promise<void>((resolve) => pendingQueue.push(resolve));
    inFlight++;
  }

  function releaseSlot(): void {
    inFlight--;
    const next = pendingQueue.shift();
    if (next) next();
  }

  async function waitForPacing(): Promise<void> {
    const elapsed = Date.now() - lastRequestAt;
    if (elapsed < minDelayMs) {
      await new Promise((resolve) => setTimeout(resolve, minDelayMs - elapsed));
    }
    lastRequestAt = Date.now();
  }

  return {
    async request(pathOrUrl: string, init?: RequestInit): Promise<ScanResponse | null> {
      if (aborted) return null;
      if (records.length >= maxRequests) {
        aborted = true;
        return null;
      }

      await acquireSlot();
      await waitForPacing();

      const url = pathOrUrl.startsWith('http') ? pathOrUrl : `http://${opts.target}${pathOrUrl}`;
      const start = Date.now();
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

      try {
        const res = await fetch(url, { ...init, signal: controller.signal });
        const latencyMs = Date.now() - start;
        const body = await res.text().catch(() => '');
        const headers: Record<string, string> = {};
        res.headers.forEach((value, key) => {
          headers[key] = value;
        });

        if (baselineLatencyMs === null) baselineLatencyMs = latencyMs;

        records.push({ path: pathOrUrl, status: res.status, latencyMs, ok: res.ok || res.status < 500 });
        evaluateBreaker();

        return { ok: res.ok, status: res.status, headers, body, latencyMs };
      } catch (err) {
        const latencyMs = Date.now() - start;
        const isTimeout = (err as Error).name === 'AbortError';
        if (baselineLatencyMs === null) baselineLatencyMs = latencyMs;
        records.push({ path: pathOrUrl, status: isTimeout ? 'timeout' : 'error', latencyMs, ok: false });
        evaluateBreaker();
        return null;
      } finally {
        clearTimeout(timer);
        releaseSlot();
      }
    },

    getStats() {
      return { requestsIssued: records.length, aborted, records: [...records] };
    },

    isAborted() {
      return aborted;
    },
  };
}
