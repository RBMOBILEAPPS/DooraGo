import 'package:flutter/material.dart';
import '../core/constants/app_strings.dart';
import '../models/command_item.dart';

/// Bottom bar containing text input field, active command badge, and send button
class CommandInputBar extends StatefulWidget {
  final ValueChanged<String> onSubmit;
  final bool isProcessing;
  final TextEditingController controller;
  final CommandItem? activeCommand;
  final VoidCallback? onClearActiveCommand;

  const CommandInputBar({
    super.key,
    required this.onSubmit,
    required this.isProcessing,
    required this.controller,
    this.activeCommand,
    this.onClearActiveCommand,
  });

  @override
  State<CommandInputBar> createState() => _CommandInputBarState();
}

class _CommandInputBarState extends State<CommandInputBar> {
  bool _canSubmit = false;

  @override
  void initState() {
    super.initState();
    _canSubmit = widget.controller.text.trim().isNotEmpty;
    widget.controller.addListener(_handleTextChange);
  }

  void _handleTextChange() {
    final canSubmit = widget.controller.text.trim().isNotEmpty;
    if (canSubmit != _canSubmit) {
      setState(() {
        _canSubmit = canSubmit;
      });
    }
  }

  void _submit() {
    final text = widget.controller.text.trim();
    if (text.isNotEmpty && !widget.isProcessing) {
      widget.onSubmit(text);
      widget.controller.clear();
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final active = widget.activeCommand;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      decoration: BoxDecoration(
        color: colorScheme.surface,
        border: Border(
          top: BorderSide(
            color: colorScheme.outlineVariant.withValues(alpha: 0.3),
            width: 1,
          ),
        ),
      ),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Active command badge indicator
            if (active != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Container(
                  constraints: BoxConstraints(
                    maxWidth: MediaQuery.sizeOf(context).width - 32,
                  ),
                  padding: const EdgeInsets.fromLTRB(10, 4, 6, 4),
                  decoration: BoxDecoration(
                    color: colorScheme.primaryContainer,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(
                      color: colorScheme.primary.withValues(alpha: 0.3),
                      width: 1,
                    ),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        active.icon,
                        size: 14,
                        color: colorScheme.onPrimaryContainer,
                      ),
                      const SizedBox(width: 6),
                      Flexible(
                        child: Text(
                          'Active: ${active.title}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.labelSmall?.copyWith(
                            color: colorScheme.onPrimaryContainer,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                      const SizedBox(width: 4),
                      InkWell(
                        onTap: widget.onClearActiveCommand,
                        borderRadius: BorderRadius.circular(10),
                        child: Padding(
                          padding: const EdgeInsets.all(2),
                          child: Icon(
                            Icons.close_rounded,
                            size: 14,
                            color: colorScheme.onPrimaryContainer,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),

            // Input field and send button row
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: widget.controller,
                    enabled: !widget.isProcessing,
                    textInputAction: TextInputAction.send,
                    onSubmitted: (_) => _submit(),
                    decoration: InputDecoration(
                      hintText: AppStrings.inputHint,
                      prefixIcon: Icon(
                        Icons.terminal_rounded,
                        size: 20,
                        color: colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  tooltip: AppStrings.sendTooltip,
                  icon: widget.isProcessing
                      ? SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: colorScheme.onPrimary,
                          ),
                        )
                      : const Icon(Icons.arrow_upward_rounded),
                  onPressed: _canSubmit && !widget.isProcessing ? _submit : null,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

