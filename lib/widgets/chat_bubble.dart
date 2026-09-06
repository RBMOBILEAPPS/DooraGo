import 'package:flutter/material.dart';
import '../models/message_model.dart';

/// Material 3 Chat bubble supporting User prompts and Assistant responses
class ChatBubble extends StatelessWidget {
  final MessageModel message;

  const ChatBubble({
    super.key,
    required this.message,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final isUser = message.isUser;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: Row(
        mainAxisAlignment:
            isUser ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (!isUser) ...[
            Container(
              margin: const EdgeInsets.only(top: 4, right: 10),
              padding: const EdgeInsets.all(6),
              decoration: BoxDecoration(
                color: message.isDevNotice
                    ? colorScheme.tertiaryContainer
                    : colorScheme.surfaceContainerHigh,
                shape: BoxShape.circle,
              ),
              child: Icon(
                message.isDevNotice
                    ? Icons.developer_mode_rounded
                    : Icons.smart_toy_outlined,
                size: 16,
                color: message.isDevNotice
                    ? colorScheme.onTertiaryContainer
                    : colorScheme.onSurface,
              ),
            ),
          ],
          Flexible(
            child: Container(
              constraints: BoxConstraints(
                maxWidth: (MediaQuery.sizeOf(context).width * 0.85).clamp(200.0, 680.0),
              ),
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              decoration: BoxDecoration(
                color: isUser
                    ? colorScheme.primary
                    : (message.isDevNotice
                        ? colorScheme.surfaceContainerHigh
                        : colorScheme.surfaceContainerLow),
                borderRadius: BorderRadius.only(
                  topLeft: const Radius.circular(20),
                  topRight: const Radius.circular(20),
                  bottomLeft: Radius.circular(isUser ? 20 : 4),
                  bottomRight: Radius.circular(isUser ? 4 : 20),
                ),
                border: !isUser && message.isDevNotice
                    ? Border.all(
                        color: colorScheme.outlineVariant.withValues(alpha: 0.6),
                        width: 1,
                      )
                    : null,
              ),
              child: Column(
                crossAxisAlignment:
                    isUser ? CrossAxisAlignment.end : CrossAxisAlignment.start,
                children: [
                  if (message.isDevNotice) ...[
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          Icons.info_outline_rounded,
                          size: 13,
                          color: colorScheme.tertiary,
                        ),
                        const SizedBox(width: 4),
                        Flexible(
                          child: Text(
                            'OFFLINE NOTICE',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.labelSmall?.copyWith(
                              color: colorScheme.tertiary,
                              fontWeight: FontWeight.bold,
                              letterSpacing: 0.6,
                              fontSize: 10,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 6),
                  ],
                  Text(
                    message.content,
                    softWrap: true,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: isUser
                          ? colorScheme.onPrimary
                          : colorScheme.onSurface,
                      height: 1.4,
                      fontWeight:
                          message.isDevNotice ? FontWeight.w500 : FontWeight.normal,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    _formatTime(message.timestamp),
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: isUser
                          ? colorScheme.onPrimary.withValues(alpha: 0.7)
                          : colorScheme.onSurfaceVariant.withValues(alpha: 0.8),
                      fontSize: 10,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  String _formatTime(DateTime time) {
    final hour = time.hour.toString().padLeft(2, '0');
    final minute = time.minute.toString().padLeft(2, '0');
    return '$hour:$minute';
  }
}
