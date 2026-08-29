import type { NovelReaderBlock } from '@novella/reader-engine';

/**
 * Codepoints of the rendered text, mined from laid-out blocks (excluding markup).
 *
 * The server substitutes content characters; the book font's cmap maps each
 * substituted codepoint to the real glyph, so the subset must cover exactly
 * these codepoints.
 */
export function collectReaderCodepoints(blocks: NovelReaderBlock[]): number[] {
  const set = new Set<number>();
  for (const block of blocks) {
    addTextCodepoints(set, stripHtml(block.html));
    if (block.listMarker) addTextCodepoints(set, block.listMarker);
  }
  return Array.from(set);
}

function addTextCodepoints(set: Set<number>, text: string): void {
  for (const char of text) {
    const cp = char.codePointAt(0);
    if (cp !== undefined && cp >= 0) set.add(cp);
  }
}

/** Strips markup and decodes common/numeric HTML entities to plain text. */
function stripHtml(html: string): string {
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/<style[\s\S]*?<\/style>/gi, '')
    .replace(/<(?:br|hr)\s*\/?>/gi, '\n')
    .replace(/<[^>]*>/g, '')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .replace(/&#x([0-9a-f]+);/gi, (_m, h: string) => fixedCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_m, d: string) => fixedCodePoint(parseInt(d, 10)));
}

function fixedCodePoint(cp: number): string {
  return Number.isFinite(cp) && cp >= 0 && cp <= 0x10ffff ? String.fromCodePoint(cp) : '';
}
