import { requireNativeModule } from 'expo-modules-core';

const NovellaUiModule = requireNativeModule('NovellaUi');

/**
 * Decodes WOFF2 font bytes to SFNT (TTF/OTF) bytes for the Skia reader.
 * Returns null when the font's tables use an unsupported transform.
 */
export async function decodeWoff2(bytes: Uint8Array): Promise<Uint8Array | null> {
  return NovellaUiModule.decodeWoff2(bytes);
}
