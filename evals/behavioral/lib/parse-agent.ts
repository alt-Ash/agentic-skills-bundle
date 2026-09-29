import { readFileSync } from 'fs';
import matter from 'gray-matter';

export interface AgentFrontmatter {
  description: string;
  mode: 'subagent' | 'primary';
  temperature: number;
  color: string;
  permission: Record<string, unknown>;
  name?: string;
}

export interface ParsedAgent {
  frontmatter: AgentFrontmatter;
  systemPrompt: string;
  filePath: string;
}

export function parseAgent(filePath: string): ParsedAgent {
  const raw = readFileSync(filePath, 'utf-8');
  const { data, content } = matter(raw);
  return {
    frontmatter: data as AgentFrontmatter,
    systemPrompt: content.trim(),
    filePath,
  };
}
