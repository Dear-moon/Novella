import {
  IconCalendarEvent,
  IconChevronLeft,
  IconChevronRight,
} from '@tabler/icons-react-native';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';

import { showAlert } from '@/components/native-alert-dialog';
import { useProfile } from '@/hooks/use-profile';
import { points, profile } from '@/services/client';
import { useAppTheme } from '@/theme/app-theme';

/** UTC day window in which a missed day may be made up (PointService.SignMakeupWindowDays). */
const MAKEUP_WINDOW_DAYS = 30;
const DAY_MS = 86_400_000;
const WEEK_LABELS = ['日', '一', '二', '三', '四', '五', '六'];

/**
 * Check-in calendar sheet: month grid of signed days, the daily sign button,
 * and the make-up card count. A make-up-eligible missed day is tapped to spend
 * one card. All dates are handled in UTC, matching the server's day boundary.
 */
export function CheckInCalendarSheet() {
  const { t } = useTranslation('settings');
  const { t: tCommon } = useTranslation('common');
  const { colors } = useAppTheme();
  const { profile: profileState, reload: reloadProfile } = useProfile();

  const today = useMemo(() => {
    const now = new Date();
    const utcToday = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
    return {
      year: now.getUTCFullYear(),
      month: now.getUTCMonth() + 1,
      utcToday,
      makeupFloorUtc: utcToday - MAKEUP_WINDOW_DAYS * DAY_MS,
    };
  }, []);

  const [year, setYear] = useState(today.year);
  const [month, setMonth] = useState(today.month);
  const [signedDays, setSignedDays] = useState<Set<number>>(new Set());
  const [makeupCards, setMakeupCards] = useState(0);
  const [loading, setLoading] = useState(true);
  const [signing, setSigning] = useState(false);
  const [madeUpDay, setMadeUpDay] = useState<number | null>(null);

  const signedToday = profileState?.growth.signedToday ?? false;
  const streak = profileState?.growth.signInStreak ?? 0;

  const cells = useMemo(() => {
    const firstWeekday = new Date(Date.UTC(year, month - 1, 1)).getUTCDay();
    const daysInMonth = new Date(Date.UTC(year, month, 0)).getUTCDate();
    const blanks: number[] = new Array(firstWeekday).fill(0);
    return [...blanks, ...Array.from({ length: daysInMonth }, (_, index) => index + 1)];
  }, [month, year]);

  const canGoPrev = Date.UTC(year, month - 1, 1) > today.makeupFloorUtc;
  const canGoNext = Date.UTC(year, month - 1, 1)
    < Date.UTC(today.year, today.month - 1, 1);

  const dayUtc = useCallback(
    (day: number) => Date.UTC(year, month - 1, day),
    [month, year],
  );

  const loadCalendar = useCallback(async () => {
    setLoading(true);
    try {
      const calendar = await points.getSignInCalendar(year, month);
      setSignedDays(new Set(calendar.days.map((day) => Number(day.signDate.slice(8, 10)))));
    } catch (error) {
      showAlert(t('profile.checkIn.loadFailedTitle'), error instanceof Error ? error.message : '');
    } finally {
      setLoading(false);
    }
  }, [month, t, year]);

  const loadMakeupCards = useCallback(async () => {
    try {
      setMakeupCards(await points.getMakeupCardCount());
    } catch {
      // A failed count leaves the make-up rows non-interactive; sign still works.
    }
  }, []);

  useEffect(() => {
    void loadCalendar();
    void loadMakeupCards();
  }, [loadCalendar, loadMakeupCards]);

  const shiftMonth = useCallback((delta: number) => {
    const shifted = new Date(Date.UTC(year, month - 1 + delta, 1));
    setYear(shifted.getUTCFullYear());
    setMonth(shifted.getUTCMonth() + 1);
  }, [month, year]);

  const isToday = useCallback((day: number) => dayUtc(day) === today.utcToday, [dayUtc, today.utcToday]);

  const canMakeUp = useCallback((day: number) => {
    const date = dayUtc(day);
    return makeupCards > 0
      && date < today.utcToday
      && date >= today.makeupFloorUtc
      && !signedDays.has(day);
  }, [dayUtc, makeupCards, signedDays, today.makeupFloorUtc, today.utcToday]);

  const handleSignIn = useCallback(async () => {
    if (signedToday || signing) return;
    setSigning(true);
    try {
      await profile.checkIn();
      await Promise.all([loadCalendar(), loadMakeupCards(), reloadProfile()]);
    } catch (error) {
      showAlert(t('profile.checkIn.failedTitle'), error instanceof Error ? error.message : '');
    } finally {
      setSigning(false);
    }
  }, [loadCalendar, loadMakeupCards, reloadProfile, signedToday, signing, t]);

  const runMakeUp = useCallback(async (day: number) => {
    const date = new Date(dayUtc(day)).toISOString().slice(0, 10);
    setMadeUpDay(day);
    try {
      const result = await points.useSignMakeupCard(date);
      setMakeupCards(result.owned);
      await Promise.all([loadCalendar(), reloadProfile()]);
      showAlert(
        t('profile.checkIn.makeUpSuccessTitle'),
        t('profile.checkIn.makeUpSuccessMessage', { streak: result.streak }),
      );
    } catch (error) {
      showAlert(t('profile.checkIn.makeUpFailedTitle'), error instanceof Error ? error.message : '');
    } finally {
      setMadeUpDay(null);
    }
  }, [dayUtc, loadCalendar, reloadProfile, t]);

  const confirmMakeUp = useCallback((day: number) => {
    const date = new Date(dayUtc(day)).toISOString().slice(0, 10);
    showAlert(
      t('profile.checkIn.makeUpConfirmTitle'),
      t('profile.checkIn.makeUpConfirmMessage', { date }),
      [
        { text: tCommon('actions.cancel'), style: 'cancel' },
        { text: t('profile.checkIn.makeUpConfirmAction'), onPress: () => void runMakeUp(day) },
      ],
    );
  }, [dayUtc, runMakeUp, t, tCommon]);

  const monthLabel = t('profile.checkIn.monthLabel', { year, month });
  const signTitle = signing
    ? t('profile.checkIn.checking')
    : signedToday
      ? t('profile.checkIn.done')
      : t('profile.checkIn.action');

  return (
    <ScrollView
      contentContainerStyle={styles.root}
      contentInsetAdjustmentBehavior="automatic"
      showsVerticalScrollIndicator={false}
    >
      <View style={styles.content}>
        <View style={styles.header}>
          <IconCalendarEvent color={colors.accent as string} size={22} strokeWidth={2} />
          <Text style={[styles.title, { color: colors.label as string }]}>
            {t('profile.checkIn.title')}
          </Text>
        </View>

        <View style={styles.summaryRow}>
          <View>
            <Text style={[styles.streakText, { color: colors.label as string }]}>
              {t('profile.checkIn.continuousStreak', { days: streak })}
            </Text>
            <Text style={[styles.caption, { color: colors.secondaryLabel as string }]}>
              {t('profile.checkIn.calendarCaption')}
            </Text>
          </View>
          <Pressable
            accessibilityRole="button"
            disabled={signedToday || signing}
            onPress={() => void handleSignIn()}
            style={[
              styles.signButton,
              { backgroundColor: (signedToday || signing) ? colors.surfaceContainerHighest as string : colors.accent as string },
            ]}
          >
            <Text style={[styles.signButtonText, { color: (signedToday || signing) ? colors.secondaryLabel as string : colors.surface as string }]}>
              {signTitle}
            </Text>
          </Pressable>
        </View>

        <View style={styles.makeupRow}>
          <Text style={[styles.caption, { color: colors.secondaryLabel as string }]}>
            {t('profile.checkIn.makeupCount', { count: makeupCards })}
          </Text>
        </View>

        <View style={styles.monthHeader}>
          <Pressable
            accessibilityRole="button"
            disabled={!canGoPrev}
            onPress={() => shiftMonth(-1)}
            style={styles.monthButton}
          >
            <IconChevronLeft color={colors.label as string} size={20} strokeWidth={2} />
          </Pressable>
          <Text style={[styles.monthLabel, { color: colors.label as string }]}>{monthLabel}</Text>
          <Pressable
            accessibilityRole="button"
            disabled={!canGoNext}
            onPress={() => shiftMonth(1)}
            style={styles.monthButton}
          >
            <IconChevronRight color={colors.label as string} size={20} strokeWidth={2} />
          </Pressable>
        </View>

        <View style={styles.grid}>
          {WEEK_LABELS.map((label) => (
            <View key={label} style={styles.cell}>
              <Text style={[styles.weekLabel, { color: colors.secondaryLabel as string }]}>{label}</Text>
            </View>
          ))}
          {loading ? (
            <View style={styles.gridLoading}>
              <ActivityIndicator color={colors.accent as string} />
            </View>
          ) : (
            cells.map((day, index) => {
              if (day === 0) return <View key={`blank-${index}`} style={styles.cell} />;
              const signed = signedDays.has(day);
              const todayCell = isToday(day);
              const makeup = canMakeUp(day);
              return (
                <View key={day} style={styles.cell}>
                  <Pressable
                    accessibilityRole="button"
                    disabled={!makeup}
                    onPress={() => confirmMakeUp(day)}
                    style={[
                      styles.day,
                      signed && { backgroundColor: colors.accent as string },
                      todayCell && { borderWidth: 2, borderColor: colors.secondaryLabel as string },
                      makeup && { borderWidth: 1, borderStyle: 'dashed', borderColor: colors.accent as string },
                    ]}
                  >
                    {madeUpDay === day ? (
                      <ActivityIndicator size="small" color={signed ? colors.surface as string : colors.accent as string} />
                    ) : (
                      <Text style={[
                        styles.dayText,
                        { color: signed ? colors.surface as string : colors.label as string },
                      ]}>
                        {day}
                      </Text>
                    )}
                  </Pressable>
                </View>
              );
            })
          )}
        </View>

        <Text style={[styles.hint, { color: colors.secondaryLabel as string }]}>
          {t('profile.checkIn.makeupHint', { days: MAKEUP_WINDOW_DAYS })}
        </Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  root: { flexGrow: 1 },
  content: {
    paddingBottom: 24,
    paddingHorizontal: 24,
    paddingTop: 8,
  },
  header: { alignItems: 'center', flexDirection: 'row', gap: 10, marginBottom: 14 },
  title: { fontSize: 17, fontWeight: '700', lineHeight: 22 },
  summaryRow: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 10,
  },
  streakText: { fontSize: 16, fontWeight: '600' },
  caption: { fontSize: 12, marginTop: 2 },
  signButton: {
    borderRadius: 20,
    minWidth: 92,
    paddingHorizontal: 16,
    paddingVertical: 9,
  },
  signButtonText: { fontSize: 14, fontWeight: '600', textAlign: 'center' },
  makeupRow: { marginBottom: 14 },
  monthHeader: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 8,
  },
  monthButton: { padding: 8 },
  monthLabel: { fontSize: 14, fontWeight: '600' },
  grid: { flexDirection: 'row', flexWrap: 'wrap' },
  gridLoading: { minHeight: 220, width: '100%' },
  cell: { alignItems: 'center', aspectRatio: 1, width: '14.2857%' },
  weekLabel: { fontSize: 12 },
  day: {
    alignItems: 'center',
    borderRadius: 21,
    height: 38,
    justifyContent: 'center',
    width: 38,
  },
  dayText: { fontSize: 14 },
  hint: { fontSize: 11, marginTop: 14 },
});
