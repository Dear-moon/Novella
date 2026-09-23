import { useFocusEffect } from 'expo-router';
import { useCallback } from 'react';

import { attemptProfileCheckIn } from '@/services/auto-check-in';

/**
 * Attempts the daily check-in when the settings screen gains focus. Delegates
 * to the shared attempt so a launch check-in in flight is reused, not repeated.
 */
export function useSettingsCheckIn() {
  useFocusEffect(useCallback(() => {
    void attemptProfileCheckIn();
  }, []));
}
