import 'command_item.dart';

/// Status indicator for the offline assistant engine
enum AssistantEngineStatus {
  offline,
  initializing,
  ready,
  processing,
  listening,
  error,
}

/// Immutable UI state for the DooraGo assistant screen
class AssistantState {
  final AssistantEngineStatus engineStatus;
  final bool isListening;
  final bool isProcessing;
  final bool isVoiceOutputEnabled;
  final bool isTtsVoiceDataMissing;
  final bool isVoiceWakeActive;
  final bool isFloatingButtonActive;
  final bool hasOverlayPermission;
  final String? activeNotice;
  final String? errorMessage;
  final CommandItem? activeCommand;

  const AssistantState({
    this.engineStatus = AssistantEngineStatus.offline,
    this.isListening = false,
    this.isProcessing = false,
    this.isVoiceOutputEnabled = true,
    this.isTtsVoiceDataMissing = false,
    this.isVoiceWakeActive = false,
    this.isFloatingButtonActive = false,
    this.hasOverlayPermission = false,
    this.activeNotice,
    this.errorMessage,
    this.activeCommand,
  });

  AssistantState copyWith({
    AssistantEngineStatus? engineStatus,
    bool? isListening,
    bool? isProcessing,
    bool? isVoiceOutputEnabled,
    bool? isTtsVoiceDataMissing,
    bool? isVoiceWakeActive,
    bool? isFloatingButtonActive,
    bool? hasOverlayPermission,
    String? activeNotice,
    String? errorMessage,
    CommandItem? activeCommand,
    bool clearActiveCommand = false,
  }) {
    return AssistantState(
      engineStatus: engineStatus ?? this.engineStatus,
      isListening: isListening ?? this.isListening,
      isProcessing: isProcessing ?? this.isProcessing,
      isVoiceOutputEnabled: isVoiceOutputEnabled ?? this.isVoiceOutputEnabled,
      isTtsVoiceDataMissing: isTtsVoiceDataMissing ?? this.isTtsVoiceDataMissing,
      isVoiceWakeActive: isVoiceWakeActive ?? this.isVoiceWakeActive,
      isFloatingButtonActive: isFloatingButtonActive ?? this.isFloatingButtonActive,
      hasOverlayPermission: hasOverlayPermission ?? this.hasOverlayPermission,
      activeNotice: activeNotice,
      errorMessage: errorMessage,
      activeCommand: clearActiveCommand ? null : (activeCommand ?? this.activeCommand),
    );
  }

  static const AssistantState initial = AssistantState(
    engineStatus: AssistantEngineStatus.offline,
    isListening: false,
    isProcessing: false,
    isVoiceOutputEnabled: true,
    isTtsVoiceDataMissing: false,
    isVoiceWakeActive: false,
    isFloatingButtonActive: false,
    hasOverlayPermission: false,
  );
}
