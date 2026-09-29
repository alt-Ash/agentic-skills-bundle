import { existsSync, readdirSync } from 'fs';
import { homedir } from 'os';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const MOCK_SERVER_PATH = join(__dirname, '../../mocks/mcp-mock-server.ts');

export interface McpStdioConfig {
  type?: 'stdio';
  command: string;
  args?: string[];
  env?: Record<string, string>;
}

export interface ChatOptions {
  allowedTools?: string[];
  agentName?: string;
  mcpServers?: Record<string, McpStdioConfig>;
}

export function mcpMockFromFixture(
  fixturePath: string,
  serverName = 'mock',
): Record<string, McpStdioConfig> {
  return {
    [serverName]: { type: 'stdio', command: 'tsx', args: [MOCK_SERVER_PATH, fixturePath] },
  };
}

export interface LLMUsage {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  estimatedCostUsd?: number;
}

export interface TranscriptTurn {
  role: 'user' | 'assistant';
  content: string;
  toolName?: string;
}

export interface ChatResult {
  response: string;
  usage: LLMUsage;
  toolCalls: Record<string, number>;
  durationMs: number;
  transcript: TranscriptTurn[];
  agent: string;
}

export interface LLMProvider {
  name: string;
  chat(system: string, user: string, options?: ChatOptions): Promise<ChatResult>;
}

function hasClaudeCodeAuth(): boolean {
  const base = join(homedir(), '.claude');
  if (
    existsSync(join(base, 'credentials')) ||
    existsSync(join(base, '.credentials.json')) ||
    existsSync(join(base, 'auth.json'))
  ) return true;
  const sessionsDir = join(base, 'sessions');
  if (!existsSync(sessionsDir)) return false;
  try {
    return readdirSync(sessionsDir).some(sessionFile => sessionFile.endsWith('.json'));
  } catch {
    return false;
  }
}

class ClaudeCodeProvider implements LLMProvider {
  name = 'claude-code';

  async chat(system: string, user: string, options?: ChatOptions): Promise<ChatResult> {
    const { query } = await import('@anthropic-ai/claude-code');
    const chunks: string[] = [];
    const toolCalls: Record<string, number> = {};
    const usage: LLMUsage = { inputTokens: 0, outputTokens: 0, totalTokens: 0 };
    const transcript: TranscriptTurn[] = [{ role: 'user', content: user }];
    const startMs = Date.now();
    const model = process.env.EVAL_MODEL ?? 'claude-haiku-4-5-20251001';
    let agent = options?.agentName;

    for await (const event of query({
      prompt: user,
      options: {
        customSystemPrompt: system,
        allowedTools: options?.allowedTools ?? [],
        model: process.env.EVAL_MODEL ?? 'claude-haiku-4-5-20251001',
        ...(options?.mcpServers ? { mcpServers: options.mcpServers, strictMcpConfig: true } : {}),
      },
    })) {
      if (event.type === 'system' && event.subtype === 'init') {
        const systemMsg = event as Record<string, unknown>;
        if (!agent && Array.isArray(systemMsg.agents) && systemMsg.agents.length > 0) {
          agent = (systemMsg.agents as string[])[0];
        }
      }
      if (event.type === 'result' && event.subtype === 'success') {
        const resultEvent = event as Record<string, unknown>;
        if (resultEvent.total_cost_usd != null) {
          usage.estimatedCostUsd = resultEvent.total_cost_usd as number;
        }
        const resultUsage = resultEvent.usage as Record<string, number> | undefined;
        if (resultUsage) {
          usage.inputTokens = (resultUsage.input_tokens ?? 0)
            + (resultUsage.cache_read_input_tokens ?? 0)
            + (resultUsage.cache_creation_input_tokens ?? 0);
          usage.outputTokens = resultUsage.output_tokens ?? usage.outputTokens;
          usage.totalTokens = usage.inputTokens + usage.outputTokens;
        }
        return {
          response: chunks.length > 0 ? chunks.join('') : (event.result ?? ''),
          usage,
          toolCalls,
          durationMs: Date.now() - startMs,
          transcript,
          agent: agent,
        };
      }
      if (event.type === 'assistant') {
        const textParts: string[] = [];
        for (const block of event.message.content) {
          if (block.type === 'text') {
            chunks.push(block.text);
            textParts.push(block.text);
          }
          if (block.type === 'tool_use') {
            toolCalls[block.name] = (toolCalls[block.name] ?? 0) + 1;
            transcript.push({ role: 'assistant', content: '', toolName: block.name });
          }
        }
        if (textParts.length > 0) {
          transcript.push({ role: 'assistant', content: textParts.join('') });
        }
        const msgUsage = (event.message as Record<string, unknown>).usage as Record<string, number> | undefined;
        if (msgUsage) {
          usage.inputTokens = msgUsage.input_tokens ?? 0;
          usage.outputTokens = msgUsage.output_tokens ?? 0;
          usage.totalTokens = usage.inputTokens + usage.outputTokens;
        }
      }
    }

    return { response: chunks.join(''), usage, toolCalls, durationMs: Date.now() - startMs, transcript, agent: agent || model };
  }
}

export async function createJudgeProvider(): Promise<LLMProvider> {
  return new ClaudeCodeProvider();
}

export async function createProvider(): Promise<LLMProvider> {
  if (hasClaudeCodeAuth()) return new ClaudeCodeProvider();
  throw new Error(
    'No Claude Code auth found. Run `claude login` to authenticate.\n' +
    'Override model with EVAL_MODEL=<model-name>',
  );
}
