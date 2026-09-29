/**
 * ticket-text.ts — Deterministic (non-LLM) extraction of author-flagged content
 * from raw ticket description text.
 *
 * These are pattern-matches, not judgment calls: an explicit color-styled span, or an
 * explicit "To be elaborated:" marker, is something the ticket author already flagged
 * themselves. Semantic classification of everything else (is an unmarked comment
 * scope-relevant or noise?) belongs in the `ticket-scope-extraction` skill, not here.
 */

const DEFAULT_COLORS = new Set(['#000000', 'rgb(0,0,0)', 'black', 'inherit', 'initial']);

/**
 * Extracts inner text from `<span style="color:...">...</span>` (or any element with an
 * inline non-default color) in an HTML description — the Azure DevOps rich-text
 * convention some authors use to mark asides/caveats as distinct from the requirement.
 */
export function extractFlaggedAsides(html: string): string[] {
  if (!html) return [];
  const asides: string[] = [];
  const spanRegex = /<[^>]+style="[^"]*color\s*:\s*([^;"]+)[^"]*"[^>]*>(.*?)<\/[^>]+>/gis;
  let match: RegExpExecArray | null;
  while ((match = spanRegex.exec(html)) !== null) {
    const color = match[1].trim().toLowerCase().replace(/\s+/g, '');
    if (DEFAULT_COLORS.has(color)) continue;
    const text = stripTags(match[2]).trim();
    if (text) asides.push(text);
  }
  return asides;
}

/** Extracts explicit "To be elaborated: [...]", "TBD:", "TODO:" markers from plain or HTML text. */
export function extractOpenItems(text: string): string[] {
  if (!text) return [];
  const plain = stripTags(text);
  const items: string[] = [];
  const markerRegex = /\b(?:to be elaborated|tbd|todo)\s*:\s*(\[[^\]]*\]|[^\n<]*)/gi;
  let match: RegExpExecArray | null;
  while ((match = markerRegex.exec(plain)) !== null) {
    const value = match[1].trim();
    if (value) items.push(value);
  }
  return items;
}

function stripTags(html: string): string {
  return html
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&#8203;|&zwsp;/gi, '')
    .replace(/\s+/g, ' ')
    .trim();
}
