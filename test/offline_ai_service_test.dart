import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:doorago/models/assistant_state.dart';
import 'package:doorago/services/offline_ai_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channelName = OfflineAiService.defaultMethodChannelName;
  const channel = MethodChannel(channelName);

  late OfflineAiService service;
  late List<MethodCall> methodCalls;
  dynamic mockResponse;

  setUp(() {
    methodCalls = [];
    mockResponse = null;

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      methodCalls.add(methodCall);
      if (mockResponse is Exception) {
        throw mockResponse as Exception;
      }
      return mockResponse;
    });

    service = OfflineAiService(
      methodChannel: channel,
      eventChannel: null, // Avoid event channel native hooks during unit testing
    );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    service.dispose();
  });

  group('OfflineAiService LiteRT-LM MethodChannel Tests', () {
    test('Initial status starts offline/unloaded', () {
      expect(service.isModelLoaded, isFalse);
      expect(service.status, equals(AssistantEngineStatus.offline));
      expect(service.modelName, contains('MobileActions-270M'));
    });

    test('Successful initialize loads model and transitions to ready', () async {
      mockResponse = {
        'success': true,
        'status': 'ready',
        'modelPath': '/data/user/0/com.rbapps.doorago/files/models/mobile_actions_q8_ekv1024.litertlm',
      };

      final states = <AssistantEngineStatus>[];
      final sub = service.statusStream.listen(states.add);

      final result = await service.initialize();
      await Future.delayed(Duration.zero);

      expect(result.isSuccess, isTrue);
      expect(service.isModelLoaded, isTrue);
      expect(service.status, equals(AssistantEngineStatus.ready));
      expect(service.activeModelPath, contains('mobile_actions_q8_ekv1024.litertlm'));
      expect(service.expectedModelFilename, equals('mobile_actions_q8_ekv1024.litertlm'));
      expect(methodCalls.length, equals(1));
      expect(methodCalls.first.method, equals('initialize'));
      expect(states, contains(AssistantEngineStatus.ready));

      await sub.cancel();
    });

    test('Failed initialize transitions to error with MODEL_NOT_FOUND code', () async {
      mockResponse = PlatformException(
        code: 'MODEL_NOT_FOUND',
        message: 'Model artifact \'mobile_actions_q8_ekv1024.litertlm\' not found at /data/user/0/com.rbapps.doorago/files/models/mobile_actions_q8_ekv1024.litertlm',
      );

      final states = <AssistantEngineStatus>[];
      final sub = service.statusStream.listen(states.add);

      final result = await service.initialize();
      await Future.delayed(Duration.zero);

      expect(result.isFailure, isTrue);
      expect(result.failureOrNull?.code, equals('MODEL_NOT_FOUND'));
      expect(service.isModelLoaded, isFalse);
      expect(service.status, equals(AssistantEngineStatus.error));
      expect(service.lastError, contains('mobile_actions_q8_ekv1024.litertlm'));
      expect(states, contains(AssistantEngineStatus.error));

      await sub.cancel();
    });

    test('verifyModel queries native model artifact status and properties', () async {
      mockResponse = {
        'exists': true,
        'filename': 'mobile_actions_q8_ekv1024.litertlm',
        'size': 288964608,
        'expectedSize': 288964608,
        'sizeMatches': true,
      };

      final info = await service.verifyModel(checkHash: false);

      expect(info, isNotNull);
      expect(info!['exists'], isTrue);
      expect(info['size'], equals(288964608));
      expect(info['sizeMatches'], isTrue);
      expect(methodCalls.any((c) => c.method == 'verifyModel'), isTrue);
    });

    test('installModel copies staging model into internal storage', () async {
      mockResponse = {
        'installed': true,
        'path': '/data/user/0/com.rbapps.doorago/files/models/mobile_actions_q8_ekv1024.litertlm',
        'size': 288964608,
      };

      final result = await service.installModel('/data/local/tmp/mobile_actions_q8_ekv1024.litertlm');

      expect(result.isSuccess, isTrue);
      expect(result.successOrNull, contains('mobile_actions_q8_ekv1024.litertlm'));
      expect(methodCalls.any((c) => c.method == 'installModel'), isTrue);
    });

    test('Successful processPrompt executes on-device inference', () async {
      // First initialize
      mockResponse = {'success': true, 'status': 'ready'};
      await service.initialize();

      // Setup prompt response
      mockResponse = {
        'success': true,
        'result': 'turn_on_flashlight()',
      };

      final states = <AssistantEngineStatus>[];
      final sub = service.statusStream.listen(states.add);

      final result = await service.processPrompt('Turn on the flashlight');

      expect(result.isSuccess, isTrue);
      expect(result.successOrNull, equals('turn_on_flashlight()'));
      expect(methodCalls.any((call) => call.method == 'processPrompt'), isTrue);
      expect(states, contains(AssistantEngineStatus.processing));

      await sub.cancel();
    });

    test('Empty prompt is rejected without invoking native channel', () async {
      final result = await service.processPrompt('   ');

      expect(result.isFailure, isTrue);
      expect(result.failureOrNull?.code, equals('EMPTY_PROMPT'));
      expect(methodCalls.isEmpty, isTrue);
    });

    test('Native inference exception returns failure while preserving ready engine status', () async {
      mockResponse = PlatformException(
        code: 'INFERENCE_ERROR',
        message: 'LiteRT runtime memory exhausted',
      );

      final result = await service.processPrompt('Open camera');

      expect(result.isFailure, isTrue);
      expect(service.status, equals(AssistantEngineStatus.ready));
      expect(service.lastError, contains('LiteRT runtime memory exhausted'));
    });
  });
}
