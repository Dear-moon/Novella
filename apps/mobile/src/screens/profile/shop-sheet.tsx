import { router } from 'expo-router';
import { SERVICE_ENDPOINTS } from '@novella/api-client';
import { IconCoins, IconHistory, IconShoppingBag } from '@tabler/icons-react-native';
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, Image, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';

import { showAlert } from '@/components/native-alert-dialog';
import { useAppLocale } from '@/localization/localization-provider';
import { points, type ShopItem, type ShopOwnedItem } from '@/services/client';
import { useAppTheme } from '@/theme/app-theme';

const SIGN_MAKEUP_KEY = 'sign_makeup';
const COMIC_QUOTA_50_KEY = 'comic_quota_50';

/** Shop images are relative paths; the API serves them from its own origin. */
function resolveItemImage(url: string): string {
  return url ? new URL(url, `${SERVICE_ENDPOINTS.apiOrigin}/`).toString() : '';
}

export function ShopSheet() {
  const { t } = useTranslation('settings');
  const { colors } = useAppTheme();
  const locale = useAppLocale();
  const [items, setItems] = useState<ShopItem[]>([]);
  const [ownedItems, setOwnedItems] = useState<ShopOwnedItem[]>([]);
  const [coin, setCoin] = useState(0);
  const [loading, setLoading] = useState(true);
  const [buyingKey, setBuyingKey] = useState<string | null>(null);
  const [usingQuota, setUsingQuota] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [shop, mine] = await Promise.all([points.getShop(), points.getMyItems()]);
      setCoin(shop.coin);
      setItems(shop.items);
      setOwnedItems(mine.items);
    } catch (error) {
      showAlert(t('profile.shop.loadFailedTitle'), error instanceof Error ? error.message : '');
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  const remaining = useCallback((item: ShopItem) =>
    item.monthlyLimit === null
      ? Infinity
      : Math.max(0, item.monthlyLimit - item.monthlyPurchased), []);

  const buy = useCallback(async (item: ShopItem) => {
    setBuyingKey(item.key);
    try {
      const result = await points.buyShopItem(item.key, 1);
      setCoin(result.coin);
      showAlert(
        t('profile.shop.buySuccessTitle'),
        t('profile.shop.buySuccessMessage', { count: result.owned }),
      );
      await load();
    } catch (error) {
      showAlert(t('profile.shop.buyFailedTitle'), error instanceof Error ? error.message : '');
    } finally {
      setBuyingKey(null);
    }
  }, [load, t]);

  const confirmBuy = useCallback((item: ShopItem) => {
    if (remaining(item) <= 0 || buyingKey) return;
    showAlert(
      t('profile.shop.buyConfirmTitle'),
      t('profile.shop.buyConfirmMessage', { name: item.name, price: item.price }),
      [
        { text: t('profile.shop.buyConfirmCancel'), style: 'cancel' },
        { text: t('profile.shop.buyConfirmAction'), onPress: () => void buy(item) },
      ],
    );
  }, [buy, buyingKey, remaining, t]);

  const useQuota = useCallback(async () => {
    setUsingQuota(true);
    try {
      const result = await points.useComicQuotaCard();
      showAlert(
        t('profile.shop.useQuotaSuccessTitle'),
        t('profile.shop.useQuotaSuccessMessage', { granted: result.granted, quota: result.quota }),
      );
      await load();
    } catch (error) {
      showAlert(t('profile.shop.useQuotaFailedTitle'), error instanceof Error ? error.message : '');
    } finally {
      setUsingQuota(false);
    }
  }, [load, t]);

  const confirmUseQuota = useCallback(() => {
    if (usingQuota) return;
    showAlert(
      t('profile.shop.useQuotaConfirmTitle'),
      t('profile.shop.useQuotaConfirmMessage'),
      [
        { text: t('profile.shop.buyConfirmCancel'), style: 'cancel' },
        { text: t('profile.shop.use'), onPress: () => void useQuota() },
      ],
    );
  }, [t, useQuota, usingQuota]);

  const numberLabel = useCallback((value: number) => new Intl.NumberFormat(locale).format(value), [locale]);

  const renderShelfItem = useCallback((item: ShopItem) => {
    const buyable = remaining(item) > 0;
    const imageUri = resolveItemImage(item.image);
    return (
      <View key={item.key} style={styles.itemCard}>
        {imageUri ? <Image source={{ uri: imageUri }} style={styles.itemImage} /> : null}
        <View style={styles.itemBody}>
          <Text style={[styles.itemName, { color: colors.label as string }]}>{item.name}</Text>
          <Text style={[styles.itemDesc, { color: colors.secondaryLabel as string }]}>{item.description}</Text>
          <View style={styles.itemBottom}>
            <View style={styles.priceRow}>
              <IconCoins color={colors.accent as string} size={16} strokeWidth={2} />
              <Text style={[styles.itemPrice, { color: colors.label as string }]}>{numberLabel(item.price)}</Text>
            </View>
            <Pressable
              accessibilityRole="button"
              disabled={!buyable || buyingKey === item.key}
              onPress={() => confirmBuy(item)}
              style={[
                styles.buyButton,
                { backgroundColor: !buyable ? colors.surfaceContainerHighest as string : colors.accent as string },
              ]}
            >
              {buyingKey === item.key ? (
                <ActivityIndicator size="small" color={colors.surface as string} />
              ) : (
                <Text
                  style={[styles.buyButtonText, { color: !buyable ? colors.secondaryLabel as string : colors.surface as string }]}
                >
                  {buyable
                    ? t('profile.shop.buy')
                    : item.monthlyLimit === 0
                      ? t('profile.shop.unavailable')
                      : t('profile.shop.monthlyLimitReached')}
                </Text>
              )}
            </Pressable>
          </View>
          <Text style={[styles.itemOwned, { color: colors.secondaryLabel as string }]}>
            {t('profile.shop.owned', { count: item.owned })} · {item.monthlyLimit === null
              ? t('profile.shop.unlimited')
              : t('profile.shop.remaining', { count: remaining(item), limit: item.monthlyLimit })}
          </Text>
        </View>
      </View>
    );
  }, [buyingKey, colors, confirmBuy, numberLabel, remaining, t]);

  return (
    <ScrollView
      contentContainerStyle={styles.root}
      contentInsetAdjustmentBehavior="automatic"
      showsVerticalScrollIndicator={false}
    >
      <View style={styles.content}>
        <View style={styles.header}>
          <IconShoppingBag color={colors.accent as string} size={22} strokeWidth={2} />
          <Text style={[styles.title, { color: colors.label as string }]}>{t('profile.shop.title')}</Text>
          <View style={styles.headerSpacer} />
          <View style={styles.coinRow}>
            <IconCoins color={colors.accent as string} size={18} strokeWidth={2} />
            <Text style={[styles.coinValue, { color: colors.label as string }]}>{numberLabel(coin)}</Text>
          </View>
          <Pressable
            accessibilityRole="button"
            onPress={() => router.push({ pathname: '/settings/point-log', params: { kind: 'coin' } })}
            style={styles.flowButton}
          >
            <IconHistory color={colors.secondaryLabel as string} size={18} strokeWidth={2} />
            <Text style={[styles.flowText, { color: colors.secondaryLabel as string }]}>{t('profile.shop.flow')}</Text>
          </Pressable>
        </View>

        {loading ? (
          <View style={styles.loading}>
            <ActivityIndicator color={colors.accent as string} />
          </View>
        ) : (
          <>
            {items.map(renderShelfItem)}

            <Text style={[styles.sectionTitle, { color: colors.label as string }]}>
              {t('profile.shop.myItems')}
            </Text>
            {ownedItems.length ? (
              ownedItems.map((item) => (
                <View key={item.key} style={styles.ownedRow}>
                  {resolveItemImage(item.image) ? (
                    <Image source={{ uri: resolveItemImage(item.image) }} style={styles.ownedImage} />
                  ) : null}
                  <View style={styles.ownedBody}>
                    <Text style={[styles.itemName, { color: colors.label as string }]}>{item.name}</Text>
                    <Text style={[styles.itemDesc, { color: colors.secondaryLabel as string }]}>{item.description}</Text>
                  </View>
                  <View style={styles.ownedSide}>
                    <Text style={[styles.itemOwned, { color: colors.label as string }]}>x{item.quantity}</Text>
                    {item.key === SIGN_MAKEUP_KEY ? (
                      <Pressable
                        accessibilityRole="button"
                        onPress={() => router.push('/settings/check-in-calendar')}
                        style={[styles.itemActionButton, { borderColor: colors.accent as string }]}
                      >
                        <Text style={[styles.itemActionText, { color: colors.accent as string }]}>{t('profile.shop.goMakeUp')}</Text>
                      </Pressable>
                    ) : null}
                    {item.key === COMIC_QUOTA_50_KEY ? (
                      <Pressable
                        accessibilityRole="button"
                        disabled={usingQuota}
                        onPress={confirmUseQuota}
                        style={[styles.itemActionButton, { borderColor: colors.accent as string }]}
                      >
                        {usingQuota ? (
                          <ActivityIndicator color={colors.accent as string} size="small" />
                        ) : (
                          <Text style={[styles.itemActionText, { color: colors.accent as string }]}>{t('profile.shop.use')}</Text>
                        )}
                      </Pressable>
                    ) : null}
                  </View>
                </View>
              ))
            ) : (
              <Text style={[styles.empty, { color: colors.secondaryLabel as string }]}>{t('profile.shop.noItems')}</Text>
            )}
          </>
        )}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  root: { flexGrow: 1 },
  content: { paddingBottom: 24, paddingHorizontal: 24, paddingTop: 8 },
  header: { alignItems: 'center', flexDirection: 'row', gap: 10, marginBottom: 14 },
  headerSpacer: { flex: 1 },
  title: { fontSize: 17, fontWeight: '700', lineHeight: 22 },
  coinRow: { alignItems: 'center', flexDirection: 'row', gap: 4 },
  coinValue: { fontSize: 15, fontWeight: '600' },
  flowButton: { alignItems: 'center', flexDirection: 'row', gap: 3, marginLeft: 8, padding: 6 },
  flowText: { fontSize: 12 },
  loading: { paddingVertical: 48 },
  itemCard: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 12,
    marginBottom: 12,
  },
  itemImage: { borderRadius: 8, height: 72, width: 72 },
  itemBody: { flex: 1 },
  itemName: { fontSize: 15, fontWeight: '600' },
  itemDesc: { fontSize: 12, marginTop: 2 },
  itemBottom: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between', marginTop: 6 },
  priceRow: { alignItems: 'center', flexDirection: 'row', gap: 4 },
  itemPrice: { fontSize: 14, fontWeight: '600' },
  buyButton: { borderRadius: 16, minWidth: 84, paddingHorizontal: 12, paddingVertical: 7 },
  buyButtonText: { fontSize: 13, fontWeight: '600', textAlign: 'center' },
  itemOwned: { fontSize: 11, marginTop: 4 },
  sectionTitle: { fontSize: 16, fontWeight: '700', marginBottom: 8, marginTop: 12 },
  ownedRow: {
    alignItems: 'center',
    borderBottomColor: 'transparent',
    flexDirection: 'row',
    gap: 12,
    paddingVertical: 10,
  },
  ownedImage: { borderRadius: 8, height: 48, width: 48 },
  ownedBody: { flex: 1 },
  ownedSide: { alignItems: 'flex-end', gap: 6 },
  itemActionButton: { borderRadius: 14, borderWidth: 1, paddingHorizontal: 12, paddingVertical: 5 },
  itemActionText: { fontSize: 12, fontWeight: '600' },
  empty: { fontSize: 13, paddingVertical: 24, textAlign: 'center' },
});
