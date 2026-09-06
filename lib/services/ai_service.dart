import '../core/constants/app_constants.dart';
import '../core/utils/result.dart';
import '../models/assistant_state.dart';

/// Abstract contract for offline AI engine
/// Implemented by LiteRT-LM with MobileActions-270M (FunctionGemma)
abstract class AIService {
  /// Whether the on-device AI model is loaded and ready for inference
  bool get isModelLoaded;

  /// Name and version of the targeted on-device model
  String get modelName;

  /// Current engine status
  AssistantEngineStatus get status;

  /// Stream of engine status transitions (initializing, ready, processing, error, offline)
  Stream<AssistantEngineStatus> get statusStream;

  /// Last error message, if any
  String? get lastError;

  /// Initializes the on-device LiteRT-LM runtime and loads model weights
  Future<Result<void, AppError>> initialize({String? customModelPath});

  /// Processes natural language text using local on-device inference
  Future<Result<String, AppError>> processPrompt(String input);

  /// Retrieves list of launchable apps discovered dynamically
  Future<List<Map<String, String>>> getInstalledApps() async => [];

  /// Releases native memory and delegates allocated for the model
  Future<void> dispose();
}

/// Fallback unconnected service for testing/mocking
class UnconnectedAIService implements AIService {
  @override
  bool get isModelLoaded => false;

  @override
  String get modelName => AppConstants.targetAiModel;

  @override
  AssistantEngineStatus get status => AssistantEngineStatus.offline;

  @override
  Stream<AssistantEngineStatus> get statusStream => const Stream.empty();

  @override
  String? get lastError => null;

  @override
  Future<Result<void, AppError>> initialize({String? customModelPath}) async {
    return const Success(null);
  }

  @override
  Future<Result<String, AppError>> processPrompt(String input) async {
    return const Success(AppConstants.devModeNotice);
  }

  @override
  Future<List<Map<String, String>>> getInstalledApps() async => [];

  @override
  Future<void> dispose() async {}
}
