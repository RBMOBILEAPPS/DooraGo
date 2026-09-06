import 'package:flutter/material.dart';
import '../providers/assistant_provider.dart';

/// Clean, responsive Material 3 Installed App Picker Sheet
/// Dynamically queries PackageManager on device and lets the user choose an app to launch.
class InstalledAppPickerSheet extends StatefulWidget {
  final AssistantProvider assistantProvider;
  final ValueChanged<String> onSelectApp;

  const InstalledAppPickerSheet({
    super.key,
    required this.assistantProvider,
    required this.onSelectApp,
  });

  @override
  State<InstalledAppPickerSheet> createState() => _InstalledAppPickerSheetState();
}

class _InstalledAppPickerSheetState extends State<InstalledAppPickerSheet> {
  final TextEditingController _searchController = TextEditingController();
  List<Map<String, String>> _allApps = [];
  bool _isLoading = true;
  String _searchQuery = '';
  bool _isSubmitting = false;

  @override
  void initState() {
    super.initState();
    debugPrint('[APP_PICKER] opened');
    _loadInstalledApps();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _loadInstalledApps() async {
    try {
      final apps = await widget.assistantProvider.getInstalledApps();
      if (!mounted) return;
      setState(() {
        _allApps = apps;
        _isLoading = false;
      });
      debugPrint('[APP_PICKER] apps_count=${apps.length}');
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _allApps = [];
        _isLoading = false;
      });
      debugPrint('[APP_PICKER] error loading apps: $e');
    }
  }

  List<Map<String, String>> get _filteredApps {
    final query = _searchQuery.trim().toLowerCase();
    if (query.isEmpty) {
      return _allApps;
    }
    return _allApps.where((app) {
      final label = (app['label'] ?? '').toLowerCase();
      final pkg = (app['packageName'] ?? '').toLowerCase();
      return label.contains(query) || pkg.contains(query);
    }).toList();
  }

  void _handleAppSelected(String appLabel) {
    if (_isSubmitting) return;
    _isSubmitting = true;
    debugPrint('[APP_PICKER] selected=$appLabel');
    Navigator.of(context).pop();
    widget.onSelectApp(appLabel);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final filtered = _filteredApps;

    return DraggableScrollableSheet(
      initialChildSize: 0.85,
      minChildSize: 0.5,
      maxChildSize: 0.95,
      expand: false,
      builder: (context, scrollController) {
        return Container(
          decoration: BoxDecoration(
            color: colorScheme.surface,
            borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
          ),
          child: Column(
            children: [
              // Drag Handle
              const SizedBox(height: 12),
              Container(
                width: 36,
                height: 4,
                decoration: BoxDecoration(
                  color: colorScheme.outlineVariant,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
              const SizedBox(height: 12),

              // Title and close button
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        color: colorScheme.primaryContainer,
                        shape: BoxShape.circle,
                      ),
                      child: Icon(
                        Icons.apps_rounded,
                        color: colorScheme.onPrimaryContainer,
                        size: 20,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Choose an installed app',
                            style: theme.textTheme.titleLarge?.copyWith(
                              fontWeight: FontWeight.bold,
                              letterSpacing: -0.3,
                            ),
                          ),
                          Text(
                            _isLoading
                                ? 'Scanning installed applications...'
                                : '${_allApps.length} launchable apps on this device',
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: colorScheme.onSurfaceVariant,
                            ),
                          ),
                        ],
                      ),
                    ),
                    IconButton(
                      icon: const Icon(Icons.close_rounded),
                      tooltip: 'Close',
                      onPressed: () => Navigator.of(context).pop(),
                    ),
                  ],
                ),
              ),

              const SizedBox(height: 12),

              // Search Bar
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: TextField(
                  controller: _searchController,
                  autofocus: false,
                  decoration: InputDecoration(
                    hintText: 'Search apps (Spotify, Instagram, Uber)...',
                    prefixIcon: const Icon(Icons.search_rounded),
                    suffixIcon: _searchQuery.isNotEmpty
                        ? IconButton(
                            icon: const Icon(Icons.clear_rounded),
                            onPressed: () {
                              _searchController.clear();
                              setState(() {
                                _searchQuery = '';
                              });
                              debugPrint('[APP_PICKER] search=');
                            },
                          )
                        : null,
                    filled: true,
                    fillColor: colorScheme.surfaceContainerHigh,
                    contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(16),
                      borderSide: BorderSide.none,
                    ),
                  ),
                  onChanged: (val) {
                    setState(() {
                      _searchQuery = val;
                    });
                    debugPrint('[APP_PICKER] search=$val');
                  },
                ),
              ),

              const Divider(height: 18),

              // Apps List / Loading / Empty States
              Expanded(
                child: _isLoading
                    ? Center(
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            const CircularProgressIndicator(),
                            const SizedBox(height: 16),
                            Text(
                              'Discovering installed apps...',
                              style: theme.textTheme.bodyMedium?.copyWith(
                                color: colorScheme.onSurfaceVariant,
                              ),
                            ),
                          ],
                        ),
                      )
                    : _allApps.isEmpty
                        ? Center(
                            child: Column(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                Icon(
                                  Icons.apps_outage_rounded,
                                  size: 48,
                                  color: colorScheme.onSurfaceVariant.withValues(alpha: 0.5),
                                ),
                                const SizedBox(height: 12),
                                Text(
                                  "Couldn't find any launchable apps",
                                  style: theme.textTheme.titleMedium?.copyWith(
                                    color: colorScheme.onSurfaceVariant,
                                  ),
                                ),
                              ],
                            ),
                          )
                        : filtered.isEmpty
                            ? Center(
                                child: Column(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  children: [
                                    Icon(
                                      Icons.search_off_rounded,
                                      size: 48,
                                      color: colorScheme.onSurfaceVariant.withValues(alpha: 0.5),
                                    ),
                                    const SizedBox(height: 12),
                                    Text(
                                      'No matching apps',
                                      style: theme.textTheme.titleMedium?.copyWith(
                                        color: colorScheme.onSurfaceVariant,
                                      ),
                                    ),
                                    const SizedBox(height: 4),
                                    Text(
                                      'Try a different app name',
                                      style: theme.textTheme.bodySmall?.copyWith(
                                        color: colorScheme.onSurfaceVariant.withValues(alpha: 0.7),
                                      ),
                                    ),
                                  ],
                                ),
                              )
                            : ListView.separated(
                                controller: scrollController,
                                padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
                                itemCount: filtered.length,
                                separatorBuilder: (_, __) => const SizedBox(height: 6),
                                itemBuilder: (context, index) {
                                  final app = filtered[index];
                                  final label = app['label'] ?? 'App';
                                  final initial = label.isNotEmpty ? label[0].toUpperCase() : 'A';

                                  return Material(
                                    color: colorScheme.surfaceContainerLow,
                                    borderRadius: BorderRadius.circular(14),
                                    child: InkWell(
                                      borderRadius: BorderRadius.circular(14),
                                      onTap: () => _handleAppSelected(label),
                                      child: Padding(
                                        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                                        child: Row(
                                          children: [
                                            // Styled App Avatar with first letter
                                            Container(
                                              width: 40,
                                              height: 40,
                                              decoration: BoxDecoration(
                                                gradient: LinearGradient(
                                                  colors: [
                                                    colorScheme.primaryContainer,
                                                    colorScheme.secondaryContainer,
                                                  ],
                                                  begin: Alignment.topLeft,
                                                  end: Alignment.bottomRight,
                                                ),
                                                borderRadius: BorderRadius.circular(10),
                                              ),
                                              child: Center(
                                                child: Text(
                                                  initial,
                                                  style: theme.textTheme.titleMedium?.copyWith(
                                                    fontWeight: FontWeight.bold,
                                                    color: colorScheme.onPrimaryContainer,
                                                  ),
                                                ),
                                              ),
                                            ),
                                            const SizedBox(width: 14),

                                            // App Label
                                            Expanded(
                                              child: Text(
                                                label,
                                                style: theme.textTheme.titleSmall?.copyWith(
                                                  fontWeight: FontWeight.w600,
                                                  color: colorScheme.onSurface,
                                                  fontSize: 15,
                                                ),
                                                maxLines: 2,
                                                overflow: TextOverflow.ellipsis,
                                                softWrap: true,
                                              ),
                                            ),

                                            // Open Action Indicator
                                            Container(
                                              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                                              decoration: BoxDecoration(
                                                color: colorScheme.primary.withValues(alpha: 0.1),
                                                borderRadius: BorderRadius.circular(8),
                                              ),
                                              child: Row(
                                                mainAxisSize: MainAxisSize.min,
                                                children: [
                                                  Icon(
                                                    Icons.play_arrow_rounded,
                                                    size: 14,
                                                    color: colorScheme.primary,
                                                  ),
                                                  const SizedBox(width: 2),
                                                  Text(
                                                    'Open',
                                                    style: theme.textTheme.labelSmall?.copyWith(
                                                      color: colorScheme.primary,
                                                      fontWeight: FontWeight.w600,
                                                    ),
                                                  ),
                                                ],
                                              ),
                                            ),
                                          ],
                                        ),
                                      ),
                                    ),
                                  );
                                },
                              ),
              ),
            ],
          ),
        );
      },
    );
  }
}
