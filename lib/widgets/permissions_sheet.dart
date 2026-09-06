import 'package:flutter/material.dart';
import '../services/permission_manager_service.dart';

/// Material 3 Bottom Sheet for inspecting DooraGo System Permissions and Privacy Settings
class PermissionsSheet extends StatefulWidget {
  final PermissionManagerService permissionService;

  const PermissionsSheet({
    super.key,
    PermissionManagerService? permissionService,
  }) : permissionService = permissionService ?? const AndroidPermissionManagerService();

  @override
  State<PermissionsSheet> createState() => _PermissionsSheetState();
}

class _PermissionsSheetState extends State<PermissionsSheet> {
  bool _micGranted = false;
  bool _notifGranted = false;
  bool _contactsGranted = false;
  bool _phoneGranted = false;
  bool _overlayGranted = false;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _checkPermissions();
  }

  Future<void> _checkPermissions() async {
    final service = widget.permissionService;
    final mic = await service.isGranted('RECORD_AUDIO');
    final notif = await service.isGranted('POST_NOTIFICATIONS');
    final contacts = await service.isGranted('READ_CONTACTS');
    final phone = await service.isGranted('CALL_PHONE');
    final overlay = await service.isGranted('SYSTEM_ALERT_WINDOW');

    if (!mounted) return;
    setState(() {
      _micGranted = mic;
      _notifGranted = notif;
      _contactsGranted = contacts;
      _phoneGranted = phone;
      _overlayGranted = overlay;
      _isLoading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
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
              Icon(Icons.security_rounded, color: colorScheme.primary, size: 28),
              const SizedBox(width: 12),
              Text(
                'Permissions & Privacy',
                style: theme.textTheme.titleLarge?.copyWith(
                  fontWeight: FontWeight.bold,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),

          Text(
            'DooraGo processes your voice and commands on-device. Permissions are requested only when features are activated.',
            style: theme.textTheme.bodyMedium?.copyWith(
              color: colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 20),

          if (_isLoading)
            const Padding(
              padding: EdgeInsets.all(20),
              child: Center(
                child: CircularProgressIndicator(),
              ),
            )
          else ...[
            _buildPermissionTile(
              context,
              icon: Icons.mic_rounded,
              title: 'Microphone',
              subtitle: 'Used only while a voice command is active.',
              isGranted: _micGranted,
            ),
            _buildPermissionTile(
              context,
              icon: Icons.notifications_rounded,
              title: 'Notifications',
              subtitle: 'Shows status for active background features.',
              isGranted: _notifGranted,
            ),
            _buildPermissionTile(
              context,
              icon: Icons.contacts_rounded,
              title: 'Contacts Access',
              subtitle: 'Allows calling saved contacts by name.',
              isGranted: _contactsGranted,
            ),
            _buildPermissionTile(
              context,
              icon: Icons.phone_rounded,
              title: 'Direct Calling',
              subtitle: 'Allows placing direct phone calls.',
              isGranted: _phoneGranted,
            ),
            _buildPermissionTile(
              context,
              icon: Icons.picture_in_picture_rounded,
              title: 'Display Over Other Apps',
              subtitle: 'Required for optional Floating Doora Button shortcut.',
              isGranted: _overlayGranted,
            ),
          ],

          const SizedBox(height: 20),

          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              icon: const Icon(Icons.settings_rounded, size: 18),
              label: const Text('MANAGE IN ANDROID SETTINGS'),
              onPressed: () {
                Navigator.of(context).pop();
                widget.permissionService.openAppSettings();
              },
            ),
          ),
          const SizedBox(height: 12),
        ],
      ),
    );
  }

  Widget _buildPermissionTile(
    BuildContext context, {
    required IconData icon,
    required String title,
    required String subtitle,
    required bool isGranted,
  }) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: isGranted
                  ? colorScheme.primaryContainer.withValues(alpha: 0.6)
                  : colorScheme.surfaceContainerHigh,
              shape: BoxShape.circle,
            ),
            child: Icon(
              icon,
              size: 20,
              color: isGranted ? colorScheme.primary : colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: theme.textTheme.titleSmall?.copyWith(
                    fontWeight: FontWeight.w600,
                  ),
                ),
                Text(
                  subtitle,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: colorScheme.onSurfaceVariant,
                    fontSize: 11,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: isGranted
                  ? colorScheme.primaryContainer
                  : colorScheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Text(
              isGranted ? 'Granted' : 'Disabled',
              style: theme.textTheme.labelSmall?.copyWith(
                color: isGranted ? colorScheme.onPrimaryContainer : colorScheme.onSurfaceVariant,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
