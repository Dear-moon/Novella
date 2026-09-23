import { Platform } from 'react-native';

import { authentication, profile } from '@/services/client';
import { shouldAttemptSettingsCheckIn } from '@/services/profile-growth';
import { getSnapshot as getAppSettings } from '@/services/settings';

let inFlight: Promise<AutoCheckInOutcome | null> | null = null;

export interface AutoCheckInOutcome {
  reward: number;
  streak: number;
}

/**
 * Best-effort daily check-in shared by the launch path and the settings screen
 * so the two never issue duplicate requests. Never throws; returns the reward
 * on a real sign-in (null otherwise) so callers choose how to surface it.
 */
export function attemptProfileCheckIn(): Promise<AutoCheckInOutcome | null> {
  if (inFlight) return inFlight;
  const attempt = runProfileCheckIn().finally(() => {
    if (inFlight === attempt) inFlight = null;
  });
  inFlight = attempt;
  return attempt;
}

/** Auto daily check-in on app launch (Android only). */
export function autoCheckInOnLaunch(): Promise<AutoCheckInOutcome | null> {
  if (Platform.OS !== 'android') return Promise.resolve(null);
  return attemptProfileCheckIn();
}

async function runProfileCheckIn(): Promise<AutoCheckInOutcome | null> {
  try {
    if (!getAppSettings().autoCheckIn) return null;
    if (authentication.getSnapshot().status !== 'authenticated') return null;

    // Refresh profile so `signedToday` reflects the latest server state.
    const current = await profile.load();
    if (!shouldAttemptSettingsCheckIn(current)) return null;

    const outcome = await profile.checkIn();
    return outcome.result ?? null;
  } catch {
    // Silent: a failed background check-in should not disturb the user.
    return null;
  }
}
