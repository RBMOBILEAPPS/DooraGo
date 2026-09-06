import 'package:flutter/material.dart';
import '../services/permission_manager_service.dart';

/// First-launch startup permission onboarding sheet for DooraGo.
///
/// Presents feature-based permission cards to prepare the assistant
/// for voice commands and device actions without requesting overlay permission automatically.
class PermissionOnboardingSheet extends StatefulWidget {
  final PermissionManagerService permissionService;
  final VoidCallback? onCompleted;

  const PermissionOnboardingSheet({
    super.key,
    PermissionManagerService? permissionService,
    this.onCompleted,
  }) : permissionService = permissionService ?? const AndroidPermissionManagerService();

  @override
  State<PermissionOnboardingSheet> createState() => _PermissionOnboardingSheetState();
}

class _PermissionOnboardingSheetState extends State<PermissionOnboardingSheet> {
  bool _isProcessing = false;

  Future<void> _handleContinue() async {
    if (_isProcessing) return;
    setState(() {
      _isProcessing = true;
    });

    final service = widget.permissionService;

    // Sequence standard runtime permission requests sequentially
    await service.request('RECORD_AUDIO');
    await service.request('POST_NOTIFICATIONS');
    await service.request('READ_CONTACTS');
    await service.request('CALL_PHONE');

    // Mark startup permission onboarding as completed
    await service.setOnboardingCompleted();

    if (!mounted) return;
    Navigator.of(context).pop();
    widget.onCompleted?.call();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Drag handle
            Center(
              child: Container(
                width: 36,
                height: 4,
                decoration: BoxDecoration(
                  color: colorScheme.onSurfaceVariant.withValues(alpha: 0.4),
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 16),

            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: colorScheme.primaryContainer,
                    shape: BoxShape.circle,
                  ),
                  child: Icon(
                    Icons.security_rounded,
                    color: colorScheme.onPrimaryContainer,
                    size: 26,
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Get DooraGo Ready',
                        style: theme.textTheme.titleLarge?.copyWith(
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      Text(
                        'Offline Assistant Setup',
                        style: theme.textTheme.labelMedium?.copyWith(
                          color: colorScheme.primary,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),

            Text(
              'To use voice commands and supported device actions, DooraGo needs a few permissions.',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 20),

            // Feature cards
            _buildFeatureCard(
              context,
              icon: Icons.mic_rounded,
              title: 'Microphone',
              description: 'Used for voice commands.',
              badge: 'Required for Voice',
            ),
            _buildFeatureCard(
              context,
              icon: Icons.contacts_rounded,
              title: 'Contacts',
              description: 'Used when you ask DooraGo to find a saved contact.',
              badge: 'On-Demand',
            ),
            _buildFeatureCard(
              context,
              icon: Icons.phone_rounded,
              title: 'Phone',
              description: 'Used when you ask DooraGo to make a phone call.',
              badge: 'On-Demand',
            ),
            _buildFeatureCard(
              context,
              icon: Icons.notifications_rounded,
              title: 'Notifications',
              description: 'Used for foreground-service status when applicable.',
              badge: 'Status Info',
            ),
            _buildFeatureCard(
              context,
              icon: Icons.picture_in_picture_rounded,
              title: 'Floating Button',
              description: 'Optional — allows the Floating Doora button to appear above other apps.',
              badge: 'Optional Special Perm',
              isOptional: true,
            ),

            const SizedBox(height: 24),

            SizedBox(
              width: double.infinity,
              height: 50,
              child: FilledButton(
                onPressed: _isProcessing ? null : _handleContinue,
                style: FilledButton.styleFrom(
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                ),
                child: _isProcessing
                    ? const SizedBox(
                        width: 24,
                        height: 24,
                        child: CircularProgressIndicator(strokeWidth: 2.5),
                      )
                    : const Text(
                        'CONTINUE',
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                          letterSpacing: 1.0,
                        ),
                      ),
              ),
            ),
            const SizedBox(height: 12),
          ],
        ),
      ),
    );
  }

  Widget _buildFeatureCard(
    BuildContext context, {
    required IconData icon,
    required String title,
    required String description,
    required String badge,
    bool isOptional = false,
  }) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: isOptional
            ? colorScheme.surfaceContainerHighest.withValues(alpha: 0.5)
            : colorScheme.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: isOptional
              ? colorScheme.outlineVariant.withValues(alpha: 0.3)
              : colorScheme.outlineVariant.withValues(alpha: 0.6),
        ),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: isOptional
                  ? colorScheme.surfaceContainerHighest
                  : colorScheme.primaryContainer,
              shape: BoxShape.circle,
            ),
            child: Icon(
              icon,
              size: 20,
              color: isOptional
                  ? colorScheme.onSurfaceVariant
                  : colorScheme.onPrimaryContainer,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      title,
                      style: theme.textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                      decoration: BoxDecoration(
                        color: isOptional
                            ? colorScheme.surfaceContainerHighest
                            : colorScheme.secondaryContainer,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(
                        badge,
                        style: theme.textTheme.labelSmall?.copyWith(
                          fontSize: 10,
                          fontWeight: FontWeight.w600,
                          color: isOptional
                              ? colorScheme.onSurfaceVariant
                              : colorScheme.onSecondaryContainer,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  description,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: colorScheme.onSurfaceVariant,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
