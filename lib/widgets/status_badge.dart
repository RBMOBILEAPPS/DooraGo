import 'package:flutter/material.dart';
import '../core/constants/app_strings.dart';
import '../models/assistant_state.dart';

/// Material 3 Status Badge indicating on-device offline status
class StatusBadge extends StatelessWidget {
  final AssistantEngineStatus status;

  const StatusBadge({
    super.key,
    required this.status,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    final (label, icon, fgColor, bgColor, borderColor) = switch (status) {
      AssistantEngineStatus.initializing => (
          'Initializing AI...',
          Icons.hourglass_top_rounded,
          const Color(0xFFE65100),
          const Color(0xFFFFF3E0),
          const Color(0xFFFFCC80),
        ),
      AssistantEngineStatus.ready => (
          'Model Ready',
          Icons.check_circle_outline_rounded,
          const Color(0xFF1B5E20),
          const Color(0xFFE8F5E9),
          const Color(0xFFA5D6A7),
        ),
      AssistantEngineStatus.processing => (
          AppStrings.statusProcessing,
          Icons.memory_rounded,
          colorScheme.primary,
          colorScheme.primaryContainer.withValues(alpha: 0.5),
          colorScheme.primary.withValues(alpha: 0.3),
        ),
      AssistantEngineStatus.listening => (
          'Listening',
          Icons.graphic_eq_rounded,
          colorScheme.error,
          colorScheme.errorContainer.withValues(alpha: 0.6),
          colorScheme.error.withValues(alpha: 0.4),
        ),
      AssistantEngineStatus.error => (
          'Engine Error',
          Icons.error_outline_rounded,
          const Color(0xFFC62828),
          const Color(0xFFFFEBEE),
          const Color(0xFFEF9A9A),
        ),
      AssistantEngineStatus.offline => (
          AppStrings.statusOffline,
          Icons.cloud_off_rounded,
          const Color(0xFF37474F),
          const Color(0xFFECEFF1),
          const Color(0xFFCFD8DC),
        ),
    };

    final isDark = theme.brightness == Brightness.dark;
    final effectiveFg = isDark ? const Color(0xFF81C784) : fgColor;
    final effectiveBg = isDark ? const Color(0xFF1B3320) : bgColor;
    final effectiveBorder = isDark ? const Color(0xFF2E7D32) : borderColor;

    return Tooltip(
      message: AppStrings.offlineBadgeTooltip,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
          color: effectiveBg,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: effectiveBorder, width: 1),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              icon,
              size: 14,
              color: effectiveFg,
            ),
            const SizedBox(width: 5),
            Flexible(
              child: Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.labelMedium?.copyWith(
                  color: effectiveFg,
                  fontWeight: FontWeight.w600,
                  letterSpacing: 0.3,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
