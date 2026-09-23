import { Host } from '@expo/ui';
import { useEffect, useState } from 'react';
import { useWindowDimensions } from 'react-native';

import { NativeReaderProgressBar } from '../../modules/novella-ui';

import type { ReaderNativeProgressBarProps } from '@/components/reader-progress-bar.types';
import { snapReaderProgress } from '@/services/reader-page-progress';
import { useAppColorScheme, useAppTheme } from '@/theme/app-theme';

/**
 * Android renders the progress bar with a Compose view, so it has to live
 * inside an Expo UI host; the host measures the bar's own height.
 */
export function ReaderNativeProgressBar({
  direction,
  disabled,
  onProgressChange,
  pageCurrent,
  pageTotal,
  progress,
  remainingText,
}: ReaderNativeProgressBarProps) {
  const { colors } = useAppTheme();
  const colorScheme = useAppColorScheme();
  const { width } = useWindowDimensions();
  const [draft, setDraft] = useState(progress);
  const isReversed = direction === 'rtl';

  useEffect(() => setDraft(progress), [progress]);

  // The native track is left-to-right; mirroring and page snapping stay here so
  // the reader's progress semantics live in one place.
  const displayedProgress = disabled ? 1 : isReversed ? 1 - draft : draft;
  const handleChange = (value: number) => {
    if (disabled) return;
    const next = isReversed ? 1 - value : value;
    const snapped = snapReaderProgress(next, pageTotal);
    setDraft(snapped);
    onProgressChange(snapped);
  };

  return (
    <Host
      colorScheme={colorScheme}
      matchContents={{ vertical: true }}
      style={{ width: Math.max(1, width - 32) }}
      useViewportSizeMeasurement
    >
      <NativeReaderProgressBar
        accentColor={colors.accent}
        currentPage={pageCurrent}
        direction={direction}
        disabled={disabled}
        onProgressChange={(event) => handleChange(event.nativeEvent.value)}
        progress={displayedProgress}
        remainingText={remainingText}
        totalPages={pageTotal}
      />
    </Host>
  );
}

