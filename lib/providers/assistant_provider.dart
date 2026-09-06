import 'dart:async';
import 'package:flutter/foundation.dart';
import '../models/assistant_state.dart';
import '../models/command_item.dart';
import '../models/message_model.dart';
import '../services/action_executor_service.dart';
import '../services/ai_service.dart';
import '../services/command_parser_service.dart';
import '../services/conversation_service.dart';
import '../services/offline_ai_service.dart';
import '../services/permission_manager_service.dart';
import '../services/tts_service.dart';
import '../services/voice_service.dart';

/// Central state manager for the DooraGo assistant screen
class AssistantProvider extends ChangeNotifier {
  final AIService _aiService;
  final CommandParserService _commandParser;
  final ActionExecutorService _actionExecutor;
  final VoiceService _voiceService;
  final TtsService _ttsService;
  final ConversationService _conversationService;
  final PermissionManagerService _permissionService;

  bool _ttsPromptDismissedThisSession = false;

  AssistantState _state = AssistantState.initial;
  StreamSubscription<AssistantEngineStatus>? _statusSubscription;
  StreamSubscription<bool>? _voiceWakeStateSubscription;
  StreamSubscription<String>? _voiceWakeCommandSubscription;

  AssistantProvider({
    AIService? aiService,
    CommandParserService? commandParser,
    ActionExecutorService? actionExecutor,
    VoiceService? voiceService,
    TtsService? ttsService,
    ConversationService? conversationService,
    PermissionManagerService? permissionService,
    bool autoInitializeAi = true,
  })  : _aiService = aiService ?? OfflineAiService(),
        _commandParser = commandParser ?? UnconnectedCommandParserService(),
        _actionExecutor = actionExecutor ?? UnconnectedActionExecutorService(),
        _voiceService = voiceService ?? AndroidNativeVoiceService(),
        _ttsService = ttsService ?? AndroidNativeTtsService(),
        _conversationService = conversationService ?? InMemoryConversationService(),
        _permissionService = permissionService ?? const AndroidPermissionManagerService() {
    // Reflect initial status
    _state = _state.copyWith(
      engineStatus: _aiService.status,
      isVoiceWakeActive: _voiceService.isVoiceWakeActive,
    );

    // Initial floating button & overlay permission checks
    checkFloatingButtonStatus();

    // Subscribe to live status transitions from LiteRT-LM engine
    _statusSubscription = _aiService.statusStream.listen((newStatus) {
      _state = _state.copyWith(
        engineStatus: newStatus,
        errorMessage: _aiService.lastError,
      );
      notifyListeners();
    });

    // Subscribe to Voice Wake foreground service status changes
    _voiceWakeStateSubscription = _voiceService.voiceWakeStateStream.listen((isActive) {
      _state = _state.copyWith(isVoiceWakeActive: isActive);
      notifyListeners();
    });

    // Pipe offline wake-word detected commands into existing command pipeline
    _voiceWakeCommandSubscription = _voiceService.voiceWakeCommandStream.listen((command) {
      debugPrint("[COMMAND_SUBMIT] source=VOICE_WAKE text='$command'");
      submitCommand(command);
    });

    if (autoInitializeAi) {
      initializeAi();
    }
  }

  AssistantState get state => _state;
  List<MessageModel> get messages => _conversationService.messages;
  bool get hasMessages => _conversationService.messages.isNotEmpty;

  /// Sets the currently active command from the Commands bottom sheet
  void setActiveCommand(CommandItem? command) {
    _state = _state.copyWith(
      activeCommand: command,
      clearActiveCommand: command == null,
    );
    notifyListeners();
  }

  /// Clears the active command
  void clearActiveCommand() {
    _state = _state.copyWith(clearActiveCommand: true);
    notifyListeners();
  }

  /// Initializes the on-device LiteRT-LM model weights
  Future<void> initializeAi({String? customModelPath}) async {
    _state = _state.copyWith(
      engineStatus: AssistantEngineStatus.initializing,
      errorMessage: null,
    );
    notifyListeners();

    final result = await _aiService.initialize(customModelPath: customModelPath);
    if (result.isFailure) {
      final errorMsg = result.failureOrNull?.message ?? 'Failed to initialize AI engine';
      _state = _state.copyWith(
        engineStatus: AssistantEngineStatus.error,
        errorMessage: errorMsg,
      );
      notifyListeners();
    }
  }

  /// Submits a user command text to the assistant pipeline
  Future<void> submitCommand(String rawText) async {
    final trimmed = rawText.trim();
    if (trimmed.isEmpty) return;

    final userMessage = MessageModel(
      id: DateTime.now().microsecondsSinceEpoch.toString(),
      content: trimmed,
      role: MessageRole.user,
      timestamp: DateTime.now(),
      status: MessageStatus.sent,
    );

    await _conversationService.addMessage(userMessage);
    _state = _state.copyWith(
      isProcessing: true,
      engineStatus: AssistantEngineStatus.processing,
      activeNotice: null,
      errorMessage: null,
      clearActiveCommand: true, // Reset active command state after submission
    );
    notifyListeners();

    try {
      // Run on-device inference via LiteRT-LM.
      // Each call creates a fresh stateless inference session to prevent
      // KV-cache accumulation and the 1024-token prefill limit error.
      final aiResult = await _aiService.processPrompt(trimmed);

      final isDevNotice = _aiService is UnconnectedAIService;

      // Fold result into user-visible text.
      // On failure: show a friendly message. The raw technical error stays in logs only.
      final responseText = aiResult.fold(
        (success) => success.isNotEmpty ? success : '[Model returned empty response]',
        (error) {
          // Log full technical error for debugging
          debugPrint('[DooraGo] Inference error (not shown to user): ${error.message}');
          // Show friendly message in chat
          return "Couldn't process that command. Please try again.";
        },
      );

      final assistantMessage = MessageModel(
        id: (DateTime.now().microsecondsSinceEpoch + 1).toString(),
        content: responseText,
        role: MessageRole.assistant,
        timestamp: DateTime.now(),
        status: aiResult.isSuccess ? MessageStatus.received : MessageStatus.error,
        isDevNotice: isDevNotice,
      );

      await _conversationService.addMessage(assistantMessage);

      // Speak response asynchronously via local Android TTS without blocking command pipeline
      if (_state.isVoiceOutputEnabled && aiResult.isSuccess && responseText.isNotEmpty) {
        _speakResponse(responseText);
      }
    } catch (e) {
      debugPrint('[DooraGo] Unexpected error in submitCommand: $e');
      final errorMessage = MessageModel(
        id: (DateTime.now().microsecondsSinceEpoch + 1).toString(),
        content: "Couldn't process that command. Please try again.",
        role: MessageRole.assistant,
        timestamp: DateTime.now(),
        status: MessageStatus.error,
      );
      await _conversationService.addMessage(errorMessage);
    } finally {
      _state = _state.copyWith(
        isProcessing: false,
        engineStatus: _aiService.status,
      );
      notifyListeners();
    }
  }

  /// Asynchronously speaks response text using local offline TTS
  Future<void> _speakResponse(String text) async {
    if (!_state.isVoiceOutputEnabled) return;
    try {
      final result = await _ttsService.speak(text);
      if (result['reason'] == 'VOICE_DATA_MISSING') {
        if (!_ttsPromptDismissedThisSession) {
          _state = _state.copyWith(isTtsVoiceDataMissing: true);
          notifyListeners();
        }
      }
    } catch (e) {
      debugPrint('[TTS_ERROR] _speakResponse error: $e');
    }
  }

  /// Toggles voice output (spoken responses) ON/OFF
  void toggleVoiceOutput() {
    final next = !_state.isVoiceOutputEnabled;
    _state = _state.copyWith(isVoiceOutputEnabled: next);
    if (!next) {
      _ttsService.stop();
    }
    notifyListeners();
  }

  /// Explicitly sets voice output enabled state
  void setVoiceOutput(bool enabled) {
    _state = _state.copyWith(isVoiceOutputEnabled: enabled);
    if (!enabled) {
      _ttsService.stop();
    }
    notifyListeners();
  }

  /// Dismisses the voice data missing setup prompt for this session
  void dismissTtsVoiceDataPrompt() {
    _ttsPromptDismissedThisSession = true;
    _state = _state.copyWith(isTtsVoiceDataMissing: false);
    notifyListeners();
  }

  /// Opens Android native TTS voice-data installer
  Future<bool> openTtsVoiceDataInstaller() async {
    dismissTtsVoiceDataPrompt();
    return _ttsService.openVoiceDataInstaller();
  }

  /// Opens Android Text-to-Speech system settings
  Future<bool> openTtsSettings() async {
    dismissTtsVoiceDataPrompt();
    return _ttsService.openTtsSettings();
  }

  /// Triggers voice interaction (Speech-to-Text)
  Future<void> toggleVoiceListening() async {
    // Stop any active TTS audio output immediately when mic is triggered
    await _ttsService.stop();

    if (_state.isListening) {
      await _voiceService.stopListening();
      _state = _state.copyWith(
        isListening: false,
        engineStatus: _aiService.status,
      );
      notifyListeners();
      return;
    }

    _state = _state.copyWith(
      isListening: true,
      engineStatus: AssistantEngineStatus.listening,
    );
    notifyListeners();

    final result = await _voiceService.startListening(
      onResult: (words) {
        _state = _state.copyWith(
          isListening: false,
        );
        notifyListeners();
        debugPrint("[COMMAND_SUBMIT] source=VOICE_STT text='$words'");
        submitCommand(words);
      },
      onError: (err) {
        _state = _state.copyWith(
          isListening: false,
          engineStatus: _aiService.status,
          activeNotice: err,
        );
        notifyListeners();
      },
    );

    if (result.isFailure) {
      final errorMsg = result.failureOrNull?.message ?? 'Speech recognition is not available.';
      _state = _state.copyWith(
        isListening: false,
        engineStatus: _aiService.status,
        activeNotice: errorMsg,
      );
      notifyListeners();
    }
  }

  /// Toggles Voice Wake offline background listening
  Future<void> toggleVoiceWake() async {
    if (_state.isVoiceWakeActive) {
      await _voiceService.stopVoiceWake();
      _state = _state.copyWith(isVoiceWakeActive: false);
      notifyListeners();
    } else {
      final success = await _voiceService.startVoiceWake();
      _state = _state.copyWith(isVoiceWakeActive: success);
      notifyListeners();
    }
  }

  /// Explicitly sets Voice Wake active state
  Future<void> setVoiceWake(bool enabled) async {
    if (enabled) {
      final success = await _voiceService.startVoiceWake();
      _state = _state.copyWith(isVoiceWakeActive: success);
    } else {
      await _voiceService.stopVoiceWake();
      _state = _state.copyWith(isVoiceWakeActive: false);
    }
    notifyListeners();
  }

  /// Checks active status of Floating Doora Button and overlay permission
  Future<void> checkFloatingButtonStatus() async {
    final hasOverlay = await _voiceService.checkOverlayPermission();
    final isActive = await _voiceService.checkIsFloatingButtonActive();
    _state = _state.copyWith(
      hasOverlayPermission: hasOverlay,
      isFloatingButtonActive: isActive,
    );
    notifyListeners();
  }

  /// Toggles Floating Doora Button overlay service
  Future<void> toggleFloatingButton() async {
    final hasOverlay = await _voiceService.checkOverlayPermission();
    if (!hasOverlay) {
      _state = _state.copyWith(
        hasOverlayPermission: false,
        activeNotice: 'Allow display over other apps to enable Floating Doora Button.',
      );
      notifyListeners();
      await _voiceService.requestOverlayPermission();
      return;
    }

    if (_state.isFloatingButtonActive) {
      await _voiceService.stopFloatingButton();
      _state = _state.copyWith(isFloatingButtonActive: false);
    } else {
      final success = await _voiceService.startFloatingButton();
      _state = _state.copyWith(
        isFloatingButtonActive: success,
        hasOverlayPermission: true,
      );
    }
    notifyListeners();
  }

  /// Requests overlay permission from system settings
  Future<void> requestOverlayPermission() async {
    await _voiceService.requestOverlayPermission();
  }

  /// Clears current conversation history
  Future<void> clearConversation() async {
    await _ttsService.stop();
    await _conversationService.clearHistory();
    _state = _state.copyWith(activeNotice: null, errorMessage: null);
    notifyListeners();
  }

  /// Dismisses active transient notice
  void dismissNotice() {
    _state = _state.copyWith(activeNotice: null);
    notifyListeners();
  }

  @override
  void dispose() {
    _statusSubscription?.cancel();
    _voiceWakeStateSubscription?.cancel();
    _voiceWakeCommandSubscription?.cancel();
    _ttsService.stop();
    super.dispose();
  }

  /// Retrieves launchable installed apps discovered dynamically via Android PackageManager
  Future<List<Map<String, String>>> getInstalledApps() async {
    return _aiService.getInstalledApps();
  }

  // Exposed services for testing and architectural inspection
  AIService get aiService => _aiService;
  CommandParserService get commandParser => _commandParser;
  ActionExecutorService get actionExecutor => _actionExecutor;
  VoiceService get voiceService => _voiceService;
  TtsService get ttsService => _ttsService;
  PermissionManagerService get permissionService => _permissionService;
}
