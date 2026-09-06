import 'package:flutter_test/flutter_test.dart';
import 'package:doorago/core/constants/app_constants.dart';
import 'package:doorago/core/utils/result.dart';
import 'package:doorago/models/assistant_state.dart';
import 'package:doorago/providers/assistant_provider.dart';
import 'package:doorago/services/ai_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  group('DooraGo AssistantProvider Tests', () {
    test('Initial assistant state starts as offline without active connection', () {
      final provider = AssistantProvider(
        aiService: UnconnectedAIService(),
        autoInitializeAi: false,
      );
      expect(provider.state.engineStatus, equals(AssistantEngineStatus.offline));
      expect(provider.messages.isEmpty, isTrue);
      expect(provider.hasMessages, isFalse);
    });

    test('Submitting a command with UnconnectedAIService yields development-mode notice', () async {
      final provider = AssistantProvider(
        aiService: UnconnectedAIService(),
        autoInitializeAi: false,
      );
      await provider.submitCommand('Turn on flashlight');

      expect(provider.messages.length, equals(2));
      expect(provider.messages.first.content, equals('Turn on flashlight'));
      expect(provider.messages.last.content, equals(AppConstants.devModeNotice));
      expect(provider.messages.last.isDevNotice, isTrue);
    });

    test('Submitting command with real AI service delivers action output', () async {
      final mockAi = _MockLoadedAiService();
      final provider = AssistantProvider(
        aiService: mockAi,
        autoInitializeAi: false,
      );

      await provider.submitCommand('Turn on flashlight');

      expect(provider.messages.length, equals(2));
      expect(provider.messages.first.content, equals('Turn on flashlight'));
      expect(provider.messages.last.content, equals('call:turn_on_flashlight()'));
      expect(provider.messages.last.isDevNotice, isFalse);
    });
  });
}

class _MockLoadedAiService implements AIService {
  @override
  bool get isModelLoaded => true;

  @override
  String get modelName => 'MobileActions-270M';

  @override
  AssistantEngineStatus get status => AssistantEngineStatus.ready;

  @override
  Stream<AssistantEngineStatus> get statusStream => Stream.value(AssistantEngineStatus.ready);

  @override
  String? get lastError => null;

  @override
  Future<Result<void, AppError>> initialize({String? customModelPath}) async {
    return const Success(null);
  }

  @override
  Future<Result<String, AppError>> processPrompt(String input) async {
    return const Success('call:turn_on_flashlight()');
  }

  @override
  Future<List<Map<String, String>>> getInstalledApps() async => [];

  @override
  Future<void> dispose() async {}
}
