import { NativeRouteBottomSheet } from '@/components/native-route-bottom-sheet';
import { CheckInCalendarSheet } from '@/screens/profile/check-in-calendar-sheet';

export default function CheckInCalendarRoute() {
  return (
    <NativeRouteBottomSheet>
      <CheckInCalendarSheet />
    </NativeRouteBottomSheet>
  );
}
