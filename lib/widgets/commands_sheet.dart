import 'package:flutter/material.dart';
import '../models/command_item.dart';

/// Clean, responsive Material 3 Command Center bottom sheet with Search & Category filtering
class CommandsSheet extends StatefulWidget {
  final ValueChanged<CommandItem> onSelectCommand;

  const CommandsSheet({
    super.key,
    required this.onSelectCommand,
  });

  @override
  State<CommandsSheet> createState() => _CommandsSheetState();
}

class _CommandsSheetState extends State<CommandsSheet> {
  final TextEditingController _searchController = TextEditingController();
  String _selectedCategory = 'All';
  String _searchQuery = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<CommandItem> get _filteredCommands {
    return CommandItem.allCommands.where((cmd) {
      final matchesCategory = _selectedCategory == 'All' || cmd.category == _selectedCategory;
      final query = _searchQuery.trim().toLowerCase();
      if (query.isEmpty) return matchesCategory;

      final matchesSearch = cmd.title.toLowerCase().contains(query) ||
          cmd.description.toLowerCase().contains(query) ||
          cmd.example.toLowerCase().contains(query) ||
          cmd.category.toLowerCase().contains(query);

      return matchesCategory && matchesSearch;
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final filtered = _filteredCommands;

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
              // Drag handle
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

              // Title and close button row
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
                        Icons.grid_view_rounded,
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
                            'Offline Commands',
                            style: theme.textTheme.titleLarge?.copyWith(
                              fontWeight: FontWeight.bold,
                              letterSpacing: -0.3,
                            ),
                          ),
                          Text(
                            '${CommandItem.allCommands.length} device actions across 14 categories',
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
                  decoration: InputDecoration(
                    hintText: 'Search commands (wifi, camera, alarm, youtube)...',
                    prefixIcon: const Icon(Icons.search_rounded),
                    suffixIcon: _searchQuery.isNotEmpty
                        ? IconButton(
                            icon: const Icon(Icons.clear_rounded),
                            onPressed: () {
                              _searchController.clear();
                              setState(() {
                                _searchQuery = '';
                              });
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
                  },
                ),
              ),

              const SizedBox(height: 10),

              // Category horizontal filter chips
              SizedBox(
                height: 38,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  itemCount: CommandItem.categories.length,
                  separatorBuilder: (_, __) => const SizedBox(width: 8),
                  itemBuilder: (context, index) {
                    final cat = CommandItem.categories[index];
                    final isSelected = _selectedCategory == cat;
                    return FilterChip(
                      label: Text(cat),
                      selected: isSelected,
                      showCheckmark: false,
                      labelStyle: theme.textTheme.labelMedium?.copyWith(
                        color: isSelected ? colorScheme.onPrimary : colorScheme.onSurfaceVariant,
                        fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                      ),
                      backgroundColor: colorScheme.surfaceContainerLow,
                      selectedColor: colorScheme.primary,
                      side: BorderSide(
                        color: isSelected ? colorScheme.primary : colorScheme.outlineVariant.withValues(alpha: 0.4),
                      ),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                      onSelected: (_) {
                        setState(() {
                          _selectedCategory = cat;
                        });
                      },
                    );
                  },
                ),
              ),

              const Divider(height: 18),

              // List of command cards
              Expanded(
                child: filtered.isEmpty
                    ? Center(
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Icon(Icons.search_off_rounded, size: 48, color: colorScheme.onSurfaceVariant.withValues(alpha: 0.5)),
                            const SizedBox(height: 12),
                            Text(
                              'No matching commands found',
                              style: theme.textTheme.titleMedium?.copyWith(
                                color: colorScheme.onSurfaceVariant,
                              ),
                            ),
                          ],
                        ),
                      )
                    : ListView.separated(
                        controller: scrollController,
                        padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
                        itemCount: filtered.length,
                        separatorBuilder: (_, __) => const SizedBox(height: 8),
                        itemBuilder: (context, index) {
                          final cmd = filtered[index];
                          return _CommandCard(
                            command: cmd,
                            onTap: () {
                              Navigator.of(context).pop();
                              widget.onSelectCommand(cmd);
                            },
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

class _CommandCard extends StatelessWidget {
  final CommandItem command;
  final VoidCallback onTap;

  const _CommandCard({
    required this.command,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Material(
      color: colorScheme.surfaceContainerLow,
      borderRadius: BorderRadius.circular(16),
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Icon container
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(
                  command.icon,
                  color: colorScheme.primary,
                  size: 24,
                ),
              ),
              const SizedBox(width: 14),

              // Command details
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      command.title,
                      style: theme.textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.w600,
                        color: colorScheme.onSurface,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Wrap(
                      spacing: 4,
                      runSpacing: 4,
                      children: [
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                          decoration: BoxDecoration(
                            color: command.executionMode == CommandExecutionMode.directAction
                                ? colorScheme.primaryContainer.withValues(alpha: 0.8)
                                : command.executionMode == CommandExecutionMode.installedAppPicker
                                    ? colorScheme.tertiaryContainer.withValues(alpha: 0.8)
                                    : colorScheme.secondaryContainer.withValues(alpha: 0.6),
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(
                                command.executionMode == CommandExecutionMode.directAction
                                    ? Icons.bolt_rounded
                                    : command.executionMode == CommandExecutionMode.installedAppPicker
                                        ? Icons.apps_rounded
                                        : Icons.edit_note_rounded,
                                size: 11,
                                color: command.executionMode == CommandExecutionMode.directAction
                                    ? colorScheme.onPrimaryContainer
                                    : command.executionMode == CommandExecutionMode.installedAppPicker
                                        ? colorScheme.onTertiaryContainer
                                        : colorScheme.onSecondaryContainer,
                              ),
                              const SizedBox(width: 2),
                              Text(
                                command.executionMode == CommandExecutionMode.directAction
                                    ? 'Instant'
                                    : command.executionMode == CommandExecutionMode.installedAppPicker
                                        ? 'Choose App'
                                        : 'Template',
                                style: theme.textTheme.labelSmall?.copyWith(
                                  color: command.executionMode == CommandExecutionMode.directAction
                                      ? colorScheme.onPrimaryContainer
                                      : command.executionMode == CommandExecutionMode.installedAppPicker
                                          ? colorScheme.onTertiaryContainer
                                          : colorScheme.onSecondaryContainer,
                                  fontSize: 10,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                            ],
                          ),
                        ),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                          decoration: BoxDecoration(
                            color: colorScheme.surfaceContainerHighest.withValues(alpha: 0.6),
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(
                            command.category,
                            style: theme.textTheme.labelSmall?.copyWith(
                              color: colorScheme.onSurfaceVariant,
                              fontSize: 10,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(
                      command.description,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: colorScheme.onSurfaceVariant,
                        fontSize: 12,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                      decoration: BoxDecoration(
                        color: colorScheme.surfaceContainerHighest.withValues(alpha: 0.5),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(
                        'Try: "${command.example}"',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: colorScheme.primary,
                          fontStyle: FontStyle.italic,
                          fontSize: 11,
                        ),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),

              // Action trailing icon
              Padding(
                padding: const EdgeInsets.only(top: 10),
                child: Container(
                  padding: const EdgeInsets.all(6),
                  decoration: BoxDecoration(
                    color: command.executionMode == CommandExecutionMode.directAction
                        ? colorScheme.primary.withValues(alpha: 0.1)
                        : command.executionMode == CommandExecutionMode.installedAppPicker
                            ? colorScheme.tertiary.withValues(alpha: 0.12)
                            : colorScheme.surfaceContainerHighest.withValues(alpha: 0.5),
                    shape: BoxShape.circle,
                  ),
                  child: Icon(
                    command.executionMode == CommandExecutionMode.directAction
                        ? Icons.play_arrow_rounded
                        : command.executionMode == CommandExecutionMode.installedAppPicker
                            ? Icons.search_rounded
                            : Icons.arrow_forward_ios_rounded,
                    size: 16,
                    color: command.executionMode == CommandExecutionMode.directAction
                        ? colorScheme.primary
                        : command.executionMode == CommandExecutionMode.installedAppPicker
                            ? colorScheme.tertiary
                            : colorScheme.onSurfaceVariant.withValues(alpha: 0.7),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
