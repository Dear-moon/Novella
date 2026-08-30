import { useLocalSearchParams } from 'expo-router';

import { NativeRouteBottomSheet } from '@/components/native-route-bottom-sheet';
import { PointLogSheet, type PointLogKind } from '@/screens/profile/point-log-sheet';

export default function PointLogRoute() {
  const params = useLocalSearchParams<{ kind?: string }>();
  const kind: PointLogKind = params.kind === 'coin' ? 'coin' : 'exp';
  return (
    <NativeRouteBottomSheet snapPoints={['50%', '100%']}>
      <PointLogSheet kind={kind} />
    </NativeRouteBottomSheet>
  );
}
