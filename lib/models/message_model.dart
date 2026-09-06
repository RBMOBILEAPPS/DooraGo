/// Message role in assistant conversation
enum MessageRole {
  user,
  assistant,
  system,
}

/// Status of the conversation message
enum MessageStatus {
  sending,
  sent,
  received,
  error,
  devPlaceholder,
}

/// Immutable data model representing a message in DooraGo
class MessageModel {
  final String id;
  final String content;
  final MessageRole role;
  final DateTime timestamp;
  final MessageStatus status;
  final bool isDevNotice;
  final String? actionTag;

  const MessageModel({
    required this.id,
    required this.content,
    required this.role,
    required this.timestamp,
    this.status = MessageStatus.received,
    this.isDevNotice = false,
    this.actionTag,
  });

  MessageModel copyWith({
    String? id,
    String? content,
    MessageRole? role,
    DateTime? timestamp,
    MessageStatus? status,
    bool? isDevNotice,
    String? actionTag,
  }) {
    return MessageModel(
      id: id ?? this.id,
      content: content ?? this.content,
      role: role ?? this.role,
      timestamp: timestamp ?? this.timestamp,
      status: status ?? this.status,
      isDevNotice: isDevNotice ?? this.isDevNotice,
      actionTag: actionTag ?? this.actionTag,
    );
  }

  bool get isUser => role == MessageRole.user;
  bool get isAssistant => role == MessageRole.assistant;
  bool get isSystem => role == MessageRole.system;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is MessageModel &&
          runtimeType == other.runtimeType &&
          id == other.id;

  @override
  int get hashCode => id.hashCode;
}
