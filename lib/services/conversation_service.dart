import '../models/message_model.dart';

/// Contract for managing conversation message history
abstract class ConversationService {
  /// Stream or list of current conversation messages
  List<MessageModel> get messages;

  /// Appends a message to the conversation
  Future<void> addMessage(MessageModel message);

  /// Clears conversation session
  Future<void> clearHistory();
}

/// In-memory conversation service with clean state management
class InMemoryConversationService implements ConversationService {
  final List<MessageModel> _messages = [];

  @override
  List<MessageModel> get messages => List.unmodifiable(_messages);

  @override
  Future<void> addMessage(MessageModel message) async {
    _messages.add(message);
  }

  @override
  Future<void> clearHistory() async {
    _messages.clear();
  }
}
