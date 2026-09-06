import 'package:flutter/material.dart';
import '../core/constants/app_strings.dart';
import '../models/assistant_state.dart';
import 'action_preview_sheet.dart';
import 'status_badge.dart';

/// Top App Bar header for DooraGo assistant screen.
///
/// Layout (fixed to prevent StatusBadge overlap):
///   Row (title): [robot icon] [DooraGo / tagline]    [info] [theme] [clear]
///   Bottom row:  [StatusBadge]
///
/// StatusBadge is intentionally placed in a separate bottom PreferredSize
/// row so it never overlaps the title text, action icons, or status bar.
class AssistantHeader extends StatelessWidget implements PreferredSizeWidget {
  final AssistantEngineStatus engineStatus;
  final bool hasMessages;
  final bool isVoiceOutputEnabled;
  final bool isVoiceWakeActive;
  final bool isFloatingButtonActive;
  final bool hasOverlayPermission;
  final VoidCallback onClear;
  final VoidCallback onToggleTheme;
  final VoidCallback onToggleVoiceOutput;
  final VoidCallback? onToggleVoiceWake;
  final VoidCallback onToggleFloatingButton;
  final VoidCallback onOpenCommands;
  final VoidCallback onOpenPermissions;
  final bool isDarkMode;

  const AssistantHeader({
    super.key,
    required this.engineStatus,
    required this.hasMessages,
    required this.isVoiceOutputEnabled,
    this.isVoiceWakeActive = false,
    required this.isFloatingButtonActive,
    required this.hasOverlayPermission,
    required this.onClear,
    required this.onToggleTheme,
    required this.onToggleVoiceOutput,
    this.onToggleVoiceWake,
    required this.onToggleFloatingButton,
    required this.onOpenCommands,
    required this.onOpenPermissions,
    required this.isDarkMode,
  });

  /// Height = toolbar + 36px status-badge row
  @override
  Size get preferredSize => const Size.fromHeight(kToolbarHeight + 36);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return AppBar(
      titleSpacing: 12,
      // Title row: icon + name/tagline with Flexible wrappers to prevent overflow
      title: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            padding: const EdgeInsets.all(6),
            decoration: BoxDecoration(
              color: colorScheme.primaryContainer,
              shape: BoxShape.circle,
            ),
            child: Icon(
              Icons.smart_toy_rounded,
              size: 20,
              color: colorScheme.onPrimaryContainer,
            ),
          ),
          const SizedBox(width: 8),
          Flexible(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  AppStrings.appName,
                  overflow: TextOverflow.ellipsis,
                  maxLines: 1,
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                    letterSpacing: -0.2,
                  ),
                ),
                Text(
                  AppStrings.tagline,
                  overflow: TextOverflow.ellipsis,
                  maxLines: 1,
                  style: theme.textTheme.labelSmall?.copyWith(
                    color: colorScheme.onSurfaceVariant,
                    fontSize: 10,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
      actions: [
        IconButton(
          tooltip: 'Commands',
          icon: const Icon(Icons.grid_view_rounded),
          onPressed: onOpenCommands,
        ),
        IconButton(
          tooltip: 'Permissions & Privacy',
          icon: const Icon(Icons.security_rounded),
          onPressed: onOpenPermissions,
        ),
        PopupMenuButton<String>(
          icon: const Icon(Icons.more_vert_rounded),
          tooltip: 'More options',
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          onSelected: (value) {
            switch (value) {
              case 'floating':
                onToggleFloatingButton();
                break;
              // Voice Wake temporarily hidden because the current wake-word detector is not production-ready.
              // The underlying implementation is retained for future trained Doora KWS integration.
              case 'voicewake':
                onToggleVoiceWake?.call();
                break;
              case 'voiceoutput':
                onToggleVoiceOutput();
                break;
              case 'permissions':
                onOpenPermissions();
                break;
              case 'info':
                showModalBottomSheet(
                  context: context,
                  isScrollControlled: true,
                  useSafeArea: true,
                  shape: const RoundedRectangleBorder(
                    borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
                  ),
                  builder: (ctx) => const ActionPreviewSheet(),
                );
                break;
              case 'theme':
                onToggleTheme();
                break;
              case 'clear':
                onClear();
                break;
            }
          },
          itemBuilder: (ctx) => [
            PopupMenuItem(
              value: 'floating',
              child: Row(
                children: [
                  Icon(
                    isFloatingButtonActive ? Icons.picture_in_picture_rounded : Icons.picture_in_picture_outlined,
                    size: 20,
                    color: isFloatingButtonActive ? colorScheme.primary : null,
                  ),
                  const SizedBox(width: 12),
                  Text(isFloatingButtonActive ? 'Floating Doora: ON' : 'Floating Doora: OFF'),
                ],
              ),
            ),
            // Voice Wake PopupMenuItem temporarily removed from user UI.
            // Underlying wake-word service preserved in backend.
            PopupMenuItem(
              value: 'voiceoutput',
              child: Row(
                children: [
                  Icon(
                    isVoiceOutputEnabled ? Icons.volume_up_rounded : Icons.volume_off_rounded,
                    size: 20,
                    color: isVoiceOutputEnabled ? colorScheme.primary : null,
                  ),
                  const SizedBox(width: 12),
                  Text(isVoiceOutputEnabled ? 'Voice Output: ON' : 'Voice Output: OFF'),
                ],
              ),
            ),
            const PopupMenuItem(
              value: 'permissions',
              child: Row(
                children: [
                  Icon(Icons.security_rounded, size: 20),
                  SizedBox(width: 12),
                  Text('Permissions & Privacy'),
                ],
              ),
            ),
            const PopupMenuItem(
              value: 'info',
              child: Row(
                children: [
                  Icon(Icons.info_outline_rounded, size: 20),
                  SizedBox(width: 12),
                  Text('About DooraGo'),
                ],
              ),
            ),
            PopupMenuItem(
              value: 'theme',
              child: Row(
                children: [
                  Icon(
                    isDarkMode ? Icons.light_mode_rounded : Icons.dark_mode_rounded,
                    size: 20,
                  ),
                  const SizedBox(width: 12),
                  Text(isDarkMode ? 'Light mode' : 'Dark mode'),
                ],
              ),
            ),
            if (hasMessages)
              const PopupMenuItem(
                value: 'clear',
                child: Row(
                  children: [
                    Icon(Icons.delete_sweep_outlined, size: 20),
                    SizedBox(width: 12),
                    Text('Clear conversation'),
                  ],
                ),
              ),
          ],
        ),
        const SizedBox(width: 4),
      ],
      // Status badge lives in a dedicated bottom bar, completely separated
      // from the title row to prevent any overlap or clipping.
      bottom: PreferredSize(
        preferredSize: const Size.fromHeight(36),
        child: Padding(
          padding: const EdgeInsets.only(left: 16, bottom: 8, right: 16),
          child: Align(
            alignment: Alignment.centerLeft,
            child: StatusBadge(status: engineStatus),
          ),
        ),
      ),
    );
  }
}
