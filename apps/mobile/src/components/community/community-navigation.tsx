export function CommunityHomeNavigation() {
  return null;
}

export function CommunityThreadNavigation(_props: {
  disabled: boolean;
  locked: boolean;
  onDelete(): void;
  onEdit(): void;
  onToggleLocked(): void;
}) {
  return null;
}

export function CommunityPublishNavigation(_props: {
  disabled: boolean;
  onPublish(): void;
}) {
  return null;
}

export function CommunityNotificationsNavigation(_props: {
  hidden: boolean;
  onMarkAll(): void;
}) {
  return null;
}
