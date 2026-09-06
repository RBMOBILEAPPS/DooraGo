import '../models/command_action.dart';

/// Contract for executing verified device actions on Android via platform channels
abstract class ActionExecutorService {
  /// Checks if the device and app permissions allow executing the action
  Future<bool> canExecute(CommandAction action);

  /// Dispatches the action to Android system services or apps
  Future<ActionResult> execute(CommandAction action);
}

/// Placeholder executor for Stage 1 foundation
/// Returns transparent development feedback without modifying device state
class UnconnectedActionExecutorService implements ActionExecutorService {
  @override
  Future<bool> canExecute(CommandAction action) async {
    return false;
  }

  @override
  Future<ActionResult> execute(CommandAction action) async {
    return ActionResult.unexecutedDevPlaceholder(action.actionName);
  }
}
