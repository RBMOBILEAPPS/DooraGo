import 'package:flutter/material.dart';
import '../models/message_model.dart';
import 'chat_bubble.dart';

/// Scrollable container rendering the conversation message thread
class ConversationView extends StatefulWidget {
  final List<MessageModel> messages;
  final bool isProcessing;

  const ConversationView({
    super.key,
    required this.messages,
    this.isProcessing = false,
  });

  @override
  State<ConversationView> createState() => _ConversationViewState();
}

class _ConversationViewState extends State<ConversationView> {
  final ScrollController _scrollController = ScrollController();

  @override
  void didUpdateWidget(covariant ConversationView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.messages.length != oldWidget.messages.length ||
        widget.isProcessing != oldWidget.isProcessing) {
      _scrollToBottom();
    }
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;

    return ListView.builder(
      controller: _scrollController,
      padding: const EdgeInsets.only(top: 12, bottom: 20),
      itemCount: widget.messages.length + (widget.isProcessing ? 1 : 0),
      itemBuilder: (context, index) {
        if (index < widget.messages.length) {
          return ChatBubble(message: widget.messages[index]);
        }

        // Processing indicator item
        return Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 8),
          child: Row(
            children: [
              SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: colorScheme.primary,
                ),
              ),
              const SizedBox(width: 10),
              Flexible(
                child: Text(
                  'Processing on-device...',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: colorScheme.onSurfaceVariant,
                        fontStyle: FontStyle.italic,
                      ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}
