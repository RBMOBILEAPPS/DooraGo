import '../core/utils/result.dart';
import '../models/command_action.dart';

/// Contract for turning natural language prompts into executable on-device actions
/// Targeted for MobileActions-270M function calling schema
abstract class CommandParserService {
  /// Parses natural language into a structured CommandAction
  Future<Result<CommandAction, AppError>> parse(String rawText);

  /// Validates if an action schema is supported by the current app version
  bool isActionSupported(String actionName);
}

/// Placeholder parser for Stage 1 foundation
class UnconnectedCommandParserService implements CommandParserService {
  @override
  Future<Result<CommandAction, AppError>> parse(String rawText) async {
    // Stage 1: Returns a clean unsupported/pending action model
    // Preserves raw prompt without executing fake actions
    final action = CommandAction(
      actionName: 'pending_ai_connection',
      category: ActionCategory.unsupported,
      parameters: {'raw_prompt': rawText},
      confidence: 0.0,
      requiresUserConfirmation: false,
    );
    return Success(action);
  }

  @override
  bool isActionSupported(String actionName) {
    return false;
  }
}
