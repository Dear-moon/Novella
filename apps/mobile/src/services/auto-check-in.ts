import { Platform } from 'react-native';

import { authentication, profile } from '@/services/client';
import { getSnapshot as getAppSettings } from '@/services/settings';

let inFlight = false;

export interface AutoCheckInOutcome {
  reward: number;
  streak: number;
}

/**
 * Auto daily check-in on app launch (Android only).
 *
 * Best-effort and idempotent: skips when already signed today, when the
 * setting is off, or when the session is not yet authenticated. Never throws;
 * returns the reward on a successful sign-in (null otherwise) so the caller
 * can choose how to surface it.
 */
export async function autoCheckInOnLaunch(): Promise<AutoCheckInOutcome | null> {
  if (inFlight) return null;
  inFlight = true;
  try {
    if (Platform.OS !== 'android') return null;
    if (!getAppSettings().autoCheckIn) return null;
    if (authentication.getSnapshot().status !== 'authenticated') return null;

    // Refresh profile so `signedToday` reflects the latest server state.
    await profile.load();
    const current = profile.getSnapshot();
    if (!current || current.growth.signedToday) return null;

    const outcome = await profile.checkIn();
    return outcome.result ?? null;
  } catch {
    // Silent: a failed background check-in should not disturb the user.
    return null;
  } finally {
    inFlight = false;
  }
}
