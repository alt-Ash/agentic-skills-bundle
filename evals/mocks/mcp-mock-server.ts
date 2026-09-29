#!/usr/bin/env tsx
// Minimal MCP (Model Context Protocol) mock server for evals.
// Reads a JSON fixture file that defines a set of tools and their canned responses,
// then speaks the MCP JSON-RPC 2.0 protocol over stdin/stdout so an agent under test
// can call "tools" without hitting real external services.
import { readFileSync } from 'fs';
import { createInterface } from 'readline';

// A single tool exposed by this mock server, loaded from the fixture file.
interface ToolFixture {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  // The canned response returned when this tool is called.
  // Use the special string '{{dynamic}}' to generate a response from call arguments
  // instead of returning a static value.
  response: unknown;
}

// Top-level shape of the JSON fixture file passed as the first CLI argument.
interface Fixture {
  tools: ToolFixture[];
}

// Require a fixture path as the first CLI argument.
const fixturePath = process.argv[2];
if (!fixturePath) {
  process.stderr.write('Usage: mcp-mock-server.ts <fixture.json>\n');
  process.exit(1);
}

const fixture: Fixture = JSON.parse(readFileSync(fixturePath, 'utf-8'));

// Writes a JSON-RPC message to stdout (newline-delimited, as MCP expects).
function send(msg: unknown): void {
  process.stdout.write(JSON.stringify(msg) + '\n');
}

// Sends a successful JSON-RPC response for the given request id.
function reply(id: unknown, result: unknown): void {
  send({ jsonrpc: '2.0', id, result });
}

// Sends a JSON-RPC error response for the given request id.
function replyError(id: unknown, code: number, message: string): void {
  send({ jsonrpc: '2.0', id, error: { code, message } });
}

// Resolves the response payload for a tool call.
// If response is '{{dynamic}}', a minimal realistic payload is generated from
// the call arguments; otherwise the static fixture response is returned as-is.
function buildToolResponse(tool: ToolFixture, args: Record<string, unknown>): unknown {
  if (tool.response === '{{dynamic}}') {
    if (tool.name === 'create_issue') {
      const repo = (args.repo as string) ?? 'org/repo';
      const title = (args.title as string) ?? '';
      return { url: `https://github.com/${repo}/issues/1`, number: 1, title, state: 'open' };
    }
    return {};
  }
  return tool.response;
}

// Read newline-delimited JSON-RPC messages from stdin.
const rl = createInterface({ input: process.stdin, terminal: false });

rl.on('line', (line) => {
  if (!line.trim()) return;
  let msg: { jsonrpc: string; id?: unknown; method?: string; params?: unknown };
  try {
    msg = JSON.parse(line);
  } catch {
    // Silently ignore malformed lines.
    return;
  }

  const { id, method, params } = msg;

  if (method === 'initialize') {
    // MCP handshake: advertise protocol version and capability to serve tools.
    reply(id, {
      protocolVersion: '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: { name: 'mcp-mock', version: '1.0.0' },
    });
  } else if (method === 'notifications/initialized') {
    // One-way notification from the client — no response required.
  } else if (method === 'tools/list') {
    // Return the list of available tools (name, description, schema) from the fixture.
    reply(id, {
      tools: fixture.tools.map(({ name, description, inputSchema }) => ({
        name,
        description,
        inputSchema,
      })),
    });
  } else if (method === 'tools/call') {
    // Dispatch a tool call: look up the tool by name and return its canned response.
    const callParams = params as { name: string; arguments?: Record<string, unknown> };
    const tool = fixture.tools.find(t => t.name === callParams.name);
    if (!tool) {
      replyError(id, -32601, `Tool not found: ${callParams.name}`);
      return;
    }
    const responseData = buildToolResponse(tool, callParams.arguments ?? {});
    // MCP tool results are wrapped in a content array of typed blocks.
    reply(id, {
      content: [{ type: 'text', text: JSON.stringify(responseData) }],
    });
  } else if (id !== undefined) {
    // Unknown method with a request id — return a standard "method not found" error.
    replyError(id, -32601, `Method not found: ${method}`);
  }
});

// Exit cleanly when stdin is closed (i.e. the agent process terminates).
rl.on('close', () => process.exit(0));
