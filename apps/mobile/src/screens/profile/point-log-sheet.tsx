import { IconCoins, IconHistory } from '@tabler/icons-react-native';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, FlatList, StyleSheet, Text, View } from 'react-native';

import { showAlert } from '@/components/native-alert-dialog';
import { formatRelativeTime } from '@/localization/formatters';
import { useAppLocale } from '@/localization/localization-provider';
import { points, type PointLogItem } from '@/services/client';
import { useAppTheme } from '@/theme/app-theme';

export type PointLogKind = 'exp' | 'coin';

const PAGE_SIZE = 20;

/** Spend-type sources already read as negative, so they are never flagged as recoveries. */
const SPEND_SOURCES = new Set(['DownloadNovel', 'DownloadComic', 'ShopPurchase']);

export function PointLogSheet({ kind }: { kind: PointLogKind }) {
  const { t } = useTranslation('settings');
  const { colors } = useAppTheme();
  const locale = useAppLocale();
  const [items, setItems] = useState<PointLogItem[]>([]);
  const [nextPage, setNextPage] = useState(1);
  const [finished, setFinished] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [loading, setLoading] = useState(false);
  const busyRef = useRef(false);

  const title = kind === 'coin' ? t('profile.pointLog.coinTitle') : t('profile.pointLog.expTitle');

  const loadPage = useCallback(async (page: number, append: boolean) => {
    if (busyRef.current || finished) return;
    busyRef.current = true;
    setLoading(true);
    try {
      const result = await points.getPointLog(kind, page, PAGE_SIZE);
      setItems((previous) => append ? [...previous, ...result.data] : result.data);
      setLoaded(true);
      setNextPage(page + 1);
      setFinished(page >= result.totalPages || result.data.length === 0);
    } catch (error) {
      setFinished(true);
      showAlert(t('profile.pointLog.loadFailedTitle'), error instanceof Error ? error.message : '');
    } finally {
      busyRef.current = false;
      setLoading(false);
    }
  }, [finished, kind, t]);

  useEffect(() => {
    void loadPage(1, false);
  }, [loadPage]);

  const iconColor = colors.accent as string;
  const titleIcon = kind === 'coin' ? <IconCoins color={iconColor} size={22} strokeWidth={2} /> : <IconHistory color={iconColor} size={22} strokeWidth={2} />;

  const empty = loaded && items.length === 0;

  const sourceLabels = useMemo<Record<string, string>>(() => ({
    SignIn: t('profile.pointSource.signIn'),
    Read: t('profile.pointSource.read'),
    PublishNovel: t('profile.pointSource.publishNovel'),
    PublishComic: t('profile.pointSource.publishComic'),
    Thread: t('profile.pointSource.thread'),
    Reply: t('profile.pointSource.reply'),
    BookComment: t('profile.pointSource.bookComment'),
    Invite: t('profile.pointSource.invite'),
    DownloadNovel: t('profile.pointSource.downloadNovel'),
    DownloadComic: t('profile.pointSource.downloadComic'),
    ShareNovel: t('profile.pointSource.shareNovel'),
    ShareComic: t('profile.pointSource.shareComic'),
    ShopPurchase: t('profile.pointSource.shopPurchase'),
    Admin: t('profile.pointSource.admin'),
  }), [t]);

  const renderItem = useCallback(({ item }: { item: PointLogItem }) => {
    const label = sourceLabels[item.source] ?? item.source;
    const displayLabel = item.amount < 0 && !SPEND_SOURCES.has(item.source)
      ? `${label}${t('profile.pointLog.recycleSuffix')}`
      : label;
    const amountColor = item.amount >= 0 ? colors.accent as string : colors.error as string;
    const amountText = item.amount >= 0 ? `+${item.amount}` : String(item.amount);
    return (
      <View style={styles.row}>
        <View style={styles.rowLeft}>
          <Text style={[styles.source, { color: colors.label as string }]}>{displayLabel}</Text>
          <Text style={[styles.time, { color: colors.secondaryLabel as string }]}>
            {formatRelativeTime(item.occurredAt, locale) || ' '}
          </Text>
        </View>
        <View style={styles.rowRight}>
          <Text style={[styles.amount, { color: amountColor }]}>{amountText}</Text>
          <Text style={[styles.time, { color: colors.secondaryLabel as string }]}>
            {t('profile.pointLog.balance', { balance: item.balance })}
          </Text>
        </View>
      </View>
    );
  }, [colors, locale, sourceLabels, t]);

  const listEmpty = useMemo(() => (
    <View style={styles.empty}>
      <IconHistory color={colors.secondaryLabel as string} size={40} strokeWidth={1.5} />
      <Text style={[styles.emptyText, { color: colors.secondaryLabel as string }]}>
        {t('profile.pointLog.empty')}
      </Text>
    </View>
  ), [colors.secondaryLabel, t]);

  return (
    <View style={styles.root}>
      <View style={styles.header}>
        {titleIcon}
        <Text style={[styles.title, { color: colors.label as string }]}>{title}</Text>
      </View>
      <FlatList
        contentContainerStyle={styles.listContent}
        data={items}
        keyExtractor={(item, index) => `${item.occurredAt}-${item.source}-${index}`}
        ListEmptyComponent={empty ? listEmpty : null}
        ListFooterComponent={loading ? (
          <View style={styles.footer}>
            <ActivityIndicator color={colors.accent as string} />
          </View>
        ) : null}
        onEndReached={() => void loadPage(nextPage, true)}
        onEndReachedThreshold={0.4}
        renderItem={renderItem}
        showsVerticalScrollIndicator={false}
        style={styles.list}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  header: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 10,
    paddingBottom: 12,
    paddingHorizontal: 24,
    paddingTop: 8,
  },
  title: { fontSize: 17, fontWeight: '700', lineHeight: 22 },
  list: { flex: 1 },
  listContent: { paddingBottom: 24, paddingHorizontal: 24 },
  row: {
    alignItems: 'center',
    borderBottomColor: 'transparent',
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 14,
  },
  rowLeft: { flex: 1, paddingRight: 12 },
  rowRight: { alignItems: 'flex-end' },
  source: { fontSize: 15 },
  amount: { fontSize: 15, fontWeight: '600' },
  time: { fontSize: 12, marginTop: 2 },
  footer: { paddingVertical: 16 },
  empty: { alignItems: 'center', paddingVertical: 64 },
  emptyText: { fontSize: 13, marginTop: 10 },
});
