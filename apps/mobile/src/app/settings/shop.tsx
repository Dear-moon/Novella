import { NativeRouteBottomSheet } from '@/components/native-route-bottom-sheet';
import { ShopSheet } from '@/screens/profile/shop-sheet';

export default function ShopRoute() {
  return (
    <NativeRouteBottomSheet snapPoints={['50%', '100%']}>
      <ShopSheet />
    </NativeRouteBottomSheet>
  );
}
