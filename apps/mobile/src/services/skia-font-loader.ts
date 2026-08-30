import { Skia } from '@shopify/react-native-skia';

import { readerFontFile } from '@/services/reader-font-loader';
import { decodeWoff2, subsetFont } from '../../modules/novella-ui';

/**
 * Font conversion and registration service for the Skia renderer.
 *
 * When `codepoints` are provided the decoded SFNT is subset to them, so the
 * registered typeface stays small (per-chapter mode). With no codepoints the
 * full decoded SFNT is used and cached once per font URL (per-book mode). The
 * WOFF2 is decoded to an SFNT at most once and cached regardless.
 */

interface TypefaceEntry {
  typeface: ReturnType<typeof Skia.Typeface.MakeFreeTypeFaceFromData> | null;
  familyName: string;
}

interface CustomFontInput {
  fontUrl: string;
  familyName: string;
  codepoints?: number[];
}

const typefaceCache = new Map<string, TypefaceEntry>();
const sfntCache = new Map<string, Uint8Array | null>();

/**
 * Load and register a WOFF2 book font, subset to `codepoints`, for Skia Paragraph.
 *
 * @param fontUrl - URL to the WOFF2 font file
 * @param familyName - Font family name to register (e.g., 'NovelFont')
 * @param keepCodepoints - Characters present in the content being rendered
 * @returns Typeface instance, or null when the font cannot be loaded
 */
export async function loadAndRegisterFont(
  fontUrl: string,
  familyName: string,
  keepCodepoints?: number[],
): Promise<ReturnType<typeof Skia.Typeface.MakeFreeTypeFaceFromData> | null> {
  const codepoints = keepCodepoints ? dedupeCodepoints(keepCodepoints) : [];
  const key = cacheKeyForTypeface(fontUrl, codepoints);
  const cached = typefaceCache.get(key);
  if (cached) return cached.typeface;

  let bytes: Uint8Array | null;
  if (codepoints.length > 0) {
    const fullSfnt = await decodedSfnt(fontUrl);
    bytes = fullSfnt ? await subsetFont(fullSfnt, codepoints) : null;
    if (!bytes) {
      bytes = fullSfnt; // fall back so the reader still opens.
      console.log(`[readerfont] subset failed, falling back url=${fontUrl}`);
    }
  } else {
    bytes = await decodedSfnt(fontUrl);
  }

  if (!bytes) {
    console.log(`[readerfont] decodeWoff2 -> null (fallback) url=${fontUrl}`);
    typefaceCache.set(key, { typeface: null, familyName });
    return null;
  }
  const sv = ((bytes[0]! << 24) | (bytes[1]! << 16) | (bytes[2]! << 8) | bytes[3]!) >>> 0;
  console.log(`[readerfont] font bytes=${bytes.length}B sfntVersion=0x${sv.toString(16)} glyphs=${codepoints.length} url=${fontUrl}`);
  const typeface = createTypeface(bytes);
  if (!typeface) {
    throw new Error('Skia rejected the decoded font data');
  }

  typefaceCache.set(key, { typeface, familyName });
  return typeface;
}

/**
 * Register a typeface with a TypefaceFontProvider.
 *
 * @param fontProvider - Skia TypefaceFontProvider instance
 * @param typeface - Typeface to register
 * @param familyName - Font family name
 */
export function registerTypefaceWithProvider(
  fontProvider: any,
  typeface: any,
  familyName: string,
): void {
  try {
    // RN Skia's TypefaceFontProvider.registerFont() method
    if (typeof fontProvider.registerFont === 'function') {
      fontProvider.registerFont(typeface, familyName);
      return;
    }
    if (typeof fontProvider.registerTypeface === 'function') {
      fontProvider.registerTypeface(typeface, familyName);
      return;
    }
    throw new Error('No registration method found on TypefaceFontProvider');
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new Error(`Failed to register reader font ${familyName}: ${message}`, {
      cause: error,
    });
  }
}

/**
 * Create a FontManager with custom fonts registered.
 * This is used by Skia Paragraph for text layout.
 *
 * @param customFonts - Array of { fontUrl, familyName, codepoints } to load
 * @returns FontManager instance
 */
export async function createFontManager(
  customFonts: CustomFontInput[] = [],
): Promise<ReturnType<typeof Skia.TypefaceFontProvider.Make> | null> {
  if (customFonts.length === 0) return null;

  const fontProvider = Skia.TypefaceFontProvider.Make();
  let registeredAny = false;
  for (const { fontUrl, familyName, codepoints } of customFonts) {
    const typeface = await loadAndRegisterFont(fontUrl, familyName, codepoints);
    if (!typeface) continue;
    registerTypefaceWithProvider(fontProvider, typeface, familyName);
    registeredAny = true;
  }

  // No custom typeface could be loaded; let the reader use the system font.
  return registeredAny ? fontProvider : null;
}

/** Clear the in-memory font caches. */
export function clearFontCache(): void {
  typefaceCache.clear();
  sfntCache.clear();
}

/** Decode the full WOFF2 font once and cache the SFNT per font URL. */
async function decodedSfnt(fontUrl: string): Promise<Uint8Array | null> {
  if (sfntCache.has(fontUrl)) return sfntCache.get(fontUrl)!;
  const woff2Bytes = await readWoff2Bytes(fontUrl);
  const bytes = await decodeWoff2(woff2Bytes);
  // Re-passing a ByteArray-returned view to a ByteArray arg reads its whole
  // `.buffer`; copy to a tight, offset-0 array first.
  const normalized = bytes ? new Uint8Array(bytes) : null;
  sfntCache.set(fontUrl, normalized);
  return normalized;
}

function cacheKeyForTypeface(fontUrl: string, codepoints: number[]): string {
  if (codepoints.length === 0) return fontUrl;
  // Cache key follows the exact subset requested.
  let hash = 2166136261;
  for (const cp of codepoints) {
    hash ^= cp;
    hash = Math.imul(hash, 16777619);
  }
  return `${fontUrl}:${hash >>> 0}`;
}

function dedupeCodepoints(codepoints: number[]): number[] {
  const set = new Set(codepoints);
  return Array.from(set).sort((a, b) => a - b);
}

async function readWoff2Bytes(fontUrl: string): Promise<Uint8Array> {
  const cachedFile = readerFontFile(fontUrl);
  if (cachedFile) {
    try {
      const bytes = cachedFile.bytesSync();
      if (isWoff2Bytes(bytes)) return bytes;
    } catch {
      // Retry through the network when the filesystem cache is unavailable.
    }
  }

  const response = await fetch(fontUrl);
  if (!response.ok) {
    throw new Error(`Failed to fetch font: ${response.status} ${response.statusText}`);
  }
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (!isWoff2Bytes(bytes)) {
    throw new Error('Font response is not a WOFF2 file');
  }
  return bytes;
}

function createTypeface(bytes: Uint8Array) {
  // The expo bridge may deliver a plain number[]; Skia.Data.fromBytes needs a
  // real typed array with a .buffer. Normalize to a Uint8Array first.
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const data = Skia.Data.fromBytes(u8);
  // Do NOT dispose the SkData here: MakeFreeTypeFaceFromData may hand the
  // typeface a reference to it, so keep the data alive as long as the typeface.
  return Skia.Typeface.MakeFreeTypeFaceFromData(data);
}

function isWoff2Bytes(bytes: Uint8Array): boolean {
  return bytes.byteLength >= 4
    && bytes[0] === 0x77
    && bytes[1] === 0x4f
    && bytes[2] === 0x46
    && bytes[3] === 0x32;
}
