import { IconX } from '@tabler/icons-react-native';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ActivityIndicator,
  Modal,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import { ProfileAvatar } from '@/components/profile-avatar';
import { formatDate } from '@/localization/formatters';
import { useAppLocale } from '@/localization/localization-provider';
import { publicProfiles } from '@/services/client';
import { createThemedStyles, resolveAccentHex, useAppTheme } from '@/theme/app-theme';

import type { PublicUserSummary } from '@novella/api-client';

export interface PublicProfileRequest {
  avatarUrl: string;
  userId: number;
  userName: string;
}

interface PublicProfileCardProps extends PublicProfileRequest {
  onClose: () => void;
}

// Imperative entry point, mirroring `showAlert`, so call sites need no card
// state of their own. Mount <PublicProfileHost /> once at the root.
let request: PublicProfileRequest | null = null;
const listeners = new Set<() => void>();

export function showPublicProfile(user: PublicProfileRequest): void {
  if (!Number.isSafeInteger(user.userId) || user.userId <= 0) return;
  request = user;
  for (const listener of listeners) listener();
}

function close(): void {
  request = null;
  for (const listener of listeners) listener();
}

export function PublicProfileHost(): React.JSX.Element | null {
  const [, setTick] = useState(0);

  useEffect(() => {
    const listener = () => setTick((tick) => tick + 1);
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  }, []);

  if (!request) return null;
  return <PublicProfileCard {...request} onClose={close} />;
}

function PublicProfileCard({ avatarUrl, userId, userName, onClose }: PublicProfileCardProps) {
  const styles = usePublicProfileStyles();
  const { colors } = useAppTheme();
  const locale = useAppLocale();
  const { t } = useTranslation('user');
  const { t: tCommon } = useTranslation('common');
  const [summary, setSummary] = useState<PublicUserSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const numberFormatter = new Intl.NumberFormat(locale);

  useEffect(() => {
    // The use case caches per user id, so reopening the card for the same
    // person resolves without another request.
    let mounted = true;
    setLoading(true);
    setFailed(false);
    void publicProfiles.load(userId)
      .then((value) => {
        if (mounted) setSummary(value);
      })
      .catch(() => {
        if (mounted) setFailed(true);
      })
      .finally(() => {
        if (mounted) setLoading(false);
      });
    return () => {
      mounted = false;
    };
  }, [attempt, userId]);

  const displayAvatar = summary?.avatarUrl ?? avatarUrl;
  const displayName = summary?.userName ?? userName;
  const stats = summary
    ? [
        { label: t('profile.stats.books'), value: summary.bookCount },
        { label: t('profile.stats.threads'), value: summary.communityThreadCount },
        { label: t('profile.stats.replies'), value: summary.communityReplyCount },
        { label: t('profile.stats.comments'), value: summary.commentCount },
      ]
    : [];

  return (
    <Modal
      animationType="fade"
      hardwareAccelerated
      navigationBarTranslucent
      onRequestClose={onClose}
      presentationStyle="overFullScreen"
      statusBarTranslucent
      transparent
      visible
    >
      <View style={styles.backdrop}>
        <Pressable
          accessibilityLabel={tCommon('actions.close')}
          accessibilityRole="button"
          onPress={onClose}
          style={StyleSheet.absoluteFill}
        />
        <View style={styles.card}>
          <Pressable
            accessibilityLabel={tCommon('actions.close')}
            accessibilityRole="button"
            hitSlop={8}
            onPress={onClose}
            style={({ pressed }) => [styles.closeButton, pressed && styles.pressed]}
          >
            <IconX color={colors.secondaryLabel as string} size={18} strokeWidth={2} />
          </Pressable>

          <View style={styles.identity}>
            <ProfileAvatar avatarUrl={displayAvatar} size={56} userName={displayName} />
            <View style={styles.identityCopy}>
              <Text numberOfLines={1} style={styles.name}>{displayName}</Text>
              <View style={styles.metaRow}>
                {summary ? (
                  <View style={styles.levelBadge}>
                    <Text style={styles.levelLabel}>
                      {t('profile.level', { level: summary.level })}
                    </Text>
                  </View>
                ) : null}
                {summary?.role ? (
                  <Text numberOfLines={1} style={styles.role}>{summary.role}</Text>
                ) : null}
              </View>
            </View>
          </View>

          <View style={styles.divider} />

          {loading ? (
            <View style={styles.state}>
              <ActivityIndicator color={resolveAccentHex(colors.accent)} />
              <Text style={styles.stateText}>{t('profile.loading')}</Text>
            </View>
          ) : null}

          {!loading && failed ? (
            <View style={styles.state}>
              <Text style={styles.stateText}>{t('profile.loadFailed')}</Text>
              <Pressable
                accessibilityLabel={tCommon('actions.retry')}
                accessibilityRole="button"
                onPress={() => setAttempt((value) => value + 1)}
                style={({ pressed }) => [styles.retryButton, pressed && styles.pressed]}
              >
                <Text style={styles.retryLabel}>{t('profile.retry')}</Text>
              </Pressable>
            </View>
          ) : null}

          {!loading && !failed && summary ? (
            <>
              <View style={styles.stats}>
                {stats.map((stat) => (
                  <View key={stat.label} style={styles.stat}>
                    <Text style={styles.statValue}>{numberFormatter.format(stat.value)}</Text>
                    <Text numberOfLines={1} style={styles.statLabel}>{stat.label}</Text>
                  </View>
                ))}
              </View>
              <View style={styles.divider} />
              <Text style={styles.joinedAt}>
                {t('profile.joinedAt', {
                  date: formatDate(summary.registeredAt, locale, { dateStyle: 'medium' }),
                })}
              </Text>
            </>
          ) : null}
        </View>
      </View>
    </Modal>
  );
}

const usePublicProfileStyles = createThemedStyles((colors) => ({
  backdrop: {
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.4)',
    flex: 1,
    justifyContent: 'center',
    padding: 24,
  },
  card: {
    backgroundColor: colors.card as string,
    borderColor: colors.separator as string,
    borderRadius: 20,
    borderWidth: 0.5,
    gap: 16,
    maxWidth: 360,
    padding: 20,
    width: '100%',
  },
  closeButton: {
    alignItems: 'center',
    alignSelf: 'flex-end',
    borderRadius: 14,
    height: 28,
    justifyContent: 'center',
    marginBottom: -8,
    marginRight: -8,
    marginTop: -8,
    width: 28,
  },
  divider: { backgroundColor: colors.separator, height: StyleSheet.hairlineWidth },
  identity: { alignItems: 'center', flexDirection: 'row', gap: 14 },
  identityCopy: { flex: 1, gap: 5 },
  joinedAt: { color: colors.secondaryLabel, fontSize: 12, lineHeight: 17 },
  levelBadge: {
    backgroundColor: colors.surfaceContainerHighest as string,
    borderRadius: 6,
    paddingHorizontal: 7,
    paddingVertical: 2,
  },
  levelLabel: { color: colors.accent, fontSize: 11, fontWeight: '600' },
  metaRow: { alignItems: 'center', flexDirection: 'row', gap: 8 },
  name: { color: colors.label, fontSize: 17, fontWeight: '700' },
  pressed: { opacity: 0.6 },
  retryButton: {
    borderColor: colors.separator as string,
    borderRadius: 10,
    borderWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: 14,
    paddingVertical: 7,
  },
  retryLabel: { color: colors.accent, fontSize: 14, fontWeight: '600' },
  role: { color: colors.secondaryLabel, flexShrink: 1, fontSize: 13 },
  stat: { alignItems: 'center', flex: 1, gap: 3 },
  statLabel: { color: colors.secondaryLabel, fontSize: 11 },
  statValue: { color: colors.label, fontSize: 16, fontWeight: '700' },
  state: { alignItems: 'center', gap: 10, justifyContent: 'center', minHeight: 96 },
  stateText: { color: colors.secondaryLabel, fontSize: 14, textAlign: 'center' },
  stats: { flexDirection: 'row', gap: 8 },
}));