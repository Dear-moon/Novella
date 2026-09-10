import { memo } from 'react';
import { Pressable, View, type StyleProp, type ViewStyle } from 'react-native';

import { ProfileAvatar, type ProfileAvatarProps } from '@/components/profile-avatar';
import { showPublicProfile } from '@/components/public-profile-card';

export interface PublicUserAvatarProps
  extends Pick<ProfileAvatarProps, 'avatarUrl' | 'fallbackBackground' | 'fallbackColor' | 'size' | 'userName'> {
  style?: StyleProp<ViewStyle>;
  /** Server identity; ids <= 0 (deleted accounts) disable the profile card. */
  userId: number;
}

/**
 * Avatar for server identities. Tapping opens the public profile card, which
 * mirrors the web reader. Deleted accounts carry no id and stay display-only.
 */
export const PublicUserAvatar = memo(function PublicUserAvatar({
  avatarUrl,
  fallbackBackground,
  fallbackColor,
  size = 'md',
  style,
  userId,
  userName,
}: PublicUserAvatarProps) {
  const avatar = (
    <ProfileAvatar
      avatarUrl={avatarUrl}
      size={size}
      userName={userName}
      {...(fallbackBackground === undefined ? {} : { fallbackBackground })}
      {...(fallbackColor === undefined ? {} : { fallbackColor })}
    />
  );

  if (!Number.isSafeInteger(userId) || userId <= 0) {
    return <View style={style}>{avatar}</View>;
  }

  return (
    <Pressable
      accessibilityLabel={userName}
      accessibilityRole="button"
      hitSlop={6}
      onPress={() => showPublicProfile({ avatarUrl, userId, userName })}
      style={style}
    >
      {avatar}
    </Pressable>
  );
});