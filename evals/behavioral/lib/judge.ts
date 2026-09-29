import type { LLMProvider } from './provider.js';

export interface JudgeResult {
  score: number;
  dimensions: Record<string, number>;
  rationale: string;
  reasoning: string;
  passed: boolean;
}

const JUDGE_SYSTEM = `You are an expert evaluator of AI agent outputs.
Rate the agent response on each provided criterion with a score from 1 to 5, where 1 = fails the criterion, 3 = acceptable, 5 = fully meets it.
First reason through the evidence in the response against each criterion, THEN assign scores — scoring before reasoning produces unreliable grades.
Reply with ONLY a JSON object wrapped in <result> tags — no markdown, no explanation outside it:
<result>{"reasoning": "<step-by-step assessment of each criterion citing evidence>", "dimensions": {"criterion_key": <integer 1-5>, ...}, "rationale": "<one sentence overall assessment>"}</result>`;

// Matches raw ASCII control characters (newlines, tabs, etc.). Built from a string
// so the source file stays plain ASCII.
const CONTROL_CHARS = new RegExp('[\\u0000-\\u001F]+', 'g');

function buildCriteriaList(criteria: Record<string, string>): string {
  return Object.entries(criteria)
    .map(([key, desc], index) => `${index + 1}. ${key}: ${desc}`)
    .join('\n');
}

export async function judgeResponse(
  judgeProvider: LLMProvider,
  agentDescription: string,
  scenario: string,
  response: string,
  criteria: Record<string, string>,
  passingThreshold = 3,
  referenceAnswer?: string,
): Promise<JudgeResult> {
  const referenceSection = referenceAnswer
    ? `\n## Reference (notes on what an ideal answer contains)
${referenceAnswer}\n`
    : '';
  const prompt = `## Agent description
${agentDescription}

## Scenario given to agent
${scenario}

## Agent response
${response}
${referenceSection}
## Scoring criteria (rate each 1-5)
${buildCriteriaList(criteria)}`;

  const raw = (await judgeProvider.chat(JUDGE_SYSTEM, prompt)).response;

  // Primary: XML tags wrapping the JSON
  const xmlMatch = raw.match(/<result>([\s\S]*?)<\/result>/);
  // Fallback: slice from first { to last } (handles JSON embedded in prose)
  const jsonStr = xmlMatch
    ? xmlMatch[1].trim()
    : (() => {
        const first = raw.indexOf('{');
        const last = raw.lastIndexOf('}');
        return first !== -1 && last > first ? raw.slice(first, last + 1) : '';
      })();

  if (!jsonStr) {
    throw new Error(`Judge returned no parseable JSON.\nRaw response: ${raw}`);
  }

  // Judges often emit multi-line reasoning with raw newlines/tabs inside string
  // literals, which JSON.parse rejects. Flatten control chars to spaces first.
  const cleanedJson = jsonStr.replace(CONTROL_CHARS, ' ');

  try {
    const parsed = JSON.parse(cleanedJson);
    const dimensions: Record<string, number> = {};
    for (const [dimKey, dimValue] of Object.entries(parsed.dimensions ?? {})) {
      dimensions[dimKey] = Number(dimValue);
    }
    const scores = Object.values(dimensions);
    const avg = scores.length > 0 ? scores.reduce((sum, score) => sum + score, 0) / scores.length : 0;
    const score = Math.round(avg * 10) / 10;
    return {
      score,
      dimensions,
      rationale: String(parsed.rationale ?? ''),
      reasoning: String(parsed.reasoning ?? ''),
      passed: avg >= passingThreshold,
    };
  } catch (err) {
    throw new Error(`Judge JSON parse error: ${err}\nRaw response: ${raw}`);
  }
}
