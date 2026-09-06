import 'package:flutter/material.dart';
import '../core/constants/app_strings.dart';

/// Clean, friendly Material 3 Empty State with greeting and command suggestions
class EmptyStateView extends StatelessWidget {
  final ValueChanged<String> onSelectSuggestion;

  const EmptyStateView({
    super.key,
    required this.onSelectSuggestion,
  });

  String _getDynamicGreeting() {
    final hour = DateTime.now().hour;
    if (hour < 12) {
      return 'Good morning';
    } else if (hour < 17) {
      return 'Good afternoon';
    } else {
      return 'Good evening';
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final greeting = _getDynamicGreeting();

    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            // Friendly visual icon
            Container(
              width: 72,
              height: 72,
              decoration: BoxDecoration(
                color: colorScheme.primaryContainer,
                shape: BoxShape.circle,
                boxShadow: [
                  BoxShadow(
                    color: colorScheme.primary.withValues(alpha: 0.15),
                    blurRadius: 20,
                    offset: const Offset(0, 8),
                  ),
                ],
              ),
              child: Icon(
                Icons.bolt_rounded,
                size: 38,
                color: colorScheme.onPrimaryContainer,
              ),
            ),
            const SizedBox(height: 20),

            // Large Friendly Greeting
            Text(
              '$greeting, I am ${AppStrings.appName}',
              textAlign: TextAlign.center,
              style: theme.textTheme.headlineSmall?.copyWith(
                fontWeight: FontWeight.bold,
                letterSpacing: -0.5,
                color: colorScheme.onSurface,
              ),
            ),
            const SizedBox(height: 8),

            // Subtitle
            Text(
              AppStrings.greetingSubtitle,
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: colorScheme.onSurfaceVariant,
                height: 1.4,
              ),
            ),
            const SizedBox(height: 28),

            // Card explaining on-device privacy & commands
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: colorScheme.surfaceContainerLow,
                borderRadius: BorderRadius.circular(16),
                border: Border.all(
                  color: colorScheme.outlineVariant.withValues(alpha: 0.4),
                  width: 1,
                ),
              ),
              child: Column(
                children: [
                  Row(
                    children: [
                      Icon(
                        Icons.shield_outlined,
                        size: 20,
                        color: colorScheme.primary,
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          AppStrings.emptyStateTitle,
                          style: theme.textTheme.titleSmall?.copyWith(
                            fontWeight: FontWeight.w600,
                            color: colorScheme.onSurface,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text(
                    AppStrings.emptyStateDescription,
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: colorScheme.onSurfaceVariant,
                      height: 1.4,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 28),

            // Suggested commands
            Align(
              alignment: Alignment.centerLeft,
              child: Text(
                'Try asking for device actions:',
                style: theme.textTheme.labelMedium?.copyWith(
                  color: colorScheme.onSurfaceVariant,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
            const SizedBox(height: 12),

            Wrap(
              spacing: 8,
              runSpacing: 8,
              alignment: WrapAlignment.start,
              children: [
                _SuggestionChip(
                  label: AppStrings.sampleCommand1,
                  icon: Icons.flashlight_on_outlined,
                  onTap: () => onSelectSuggestion(AppStrings.sampleCommand1),
                ),
                _SuggestionChip(
                  label: AppStrings.sampleCommand2,
                  icon: Icons.map_outlined,
                  onTap: () => onSelectSuggestion(AppStrings.sampleCommand2),
                ),
                _SuggestionChip(
                  label: AppStrings.sampleCommand3,
                  icon: Icons.wifi_outlined,
                  onTap: () => onSelectSuggestion(AppStrings.sampleCommand3),
                ),
                _SuggestionChip(
                  label: AppStrings.sampleCommand4,
                  icon: Icons.person_add_outlined,
                  onTap: () => onSelectSuggestion(AppStrings.sampleCommand4),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _SuggestionChip extends StatelessWidget {
  final String label;
  final IconData icon;
  final VoidCallback onTap;

  const _SuggestionChip({
    required this.label,
    required this.icon,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return ActionChip(
      avatar: Icon(icon, size: 16, color: colorScheme.primary),
      label: Text(label),
      labelStyle: theme.textTheme.labelMedium?.copyWith(
        color: colorScheme.onSurface,
      ),
      backgroundColor: colorScheme.surfaceContainerHigh,
      side: BorderSide(
        color: colorScheme.outlineVariant.withValues(alpha: 0.5),
        width: 1,
      ),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      onPressed: onTap,
    );
  }
}
