import { requireNativeModule } from 'expo-modules-core';

const NovellaUiModule = requireNativeModule('NovellaUi');

/**
 * Decodes WOFF2 font bytes to SFNT (TTF/OTF) bytes for the Skia reader.
 * Returns null when the font's tables use an unsupported transform.
 */
export async function decodeWoff2(bytes: Uint8Array): Promise<Uint8Array | null> {
  return NovellaUiModule.decodeWoff2(bytes);
}

/**
 * Subsets a decoded SFNT to the given codepoints (per-chapter book-font subset)
 * so the registered typeface stays small. Returns null when it cannot be subset.
 */
export async function subsetFont(
  sfnt: Uint8Array,
  keepCodepoints: number[],
): Promise<Uint8Array | null> {
  return NovellaUiModule.subsetFont(sfnt, keepCodepoints);
}
