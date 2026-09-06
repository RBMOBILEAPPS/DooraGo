import 'dart:async';
import 'package:flutter/services.dart';
import '../core/constants/app_constants.dart';
import '../core/utils/result.dart';
import '../models/assistant_state.dart';
import 'ai_service.dart';

/// Concrete production implementation of [AIService] connecting to native
/// Google AI Edge LiteRT-LM runtime on Android via MethodChannel and EventChannel.
///
/// Runs the real MobileActions-270M (FunctionGemma) model fully offline.
class OfflineAiService implements AIService {
  static const String defaultMethodChannelName = 'com.rbapps.doorago/ai_engine';
  static const String defaultEventChannelName = 'com.rbapps.doorago/ai_engine_events';

  final MethodChannel _methodChannel;
  final EventChannel? _eventChannel;

  final StreamController<AssistantEngineStatus> _statusController =
      StreamController<AssistantEngineStatus>.broadcast();

  AssistantEngineStatus _status = AssistantEngineStatus.offline;
  String? _lastError;
  String? _activeModelPath;
  StreamSubscription<dynamic>? _eventSubscription;

  OfflineAiService({
    MethodChannel? methodChannel,
    EventChannel? eventChannel,
  })  : _methodChannel = methodChannel ?? const MethodChannel(defaultMethodChannelName),
        _eventChannel = eventChannel ?? const EventChannel(defaultEventChannelName) {
    _subscribeToNativeEvents();
  }

  @override
  bool get isModelLoaded => _status == AssistantEngineStatus.ready;

  @override
  String get modelName => AppConstants.targetAiModel;

  @override
  AssistantEngineStatus get status => _status;

  @override
  Stream<AssistantEngineStatus> get statusStream => _statusController.stream;

  @override
  String? get lastError => _lastError;

  String? get activeModelPath => _activeModelPath;
  String get expectedModelFilename => AppConstants.targetModelFilename;
  String get preferredModelStoragePath => AppConstants.preferredModelStoragePath;

  /// Verifies model existence, size, and optional SHA-256 hash on device
  Future<Map<String, dynamic>?> verifyModel({bool checkHash = false}) async {
    try {
      final res = await _methodChannel.invokeMethod<dynamic>(
        'verifyModel',
        {'checkHash': checkHash},
      );
      if (res is Map) {
        return Map<String, dynamic>.from(res);
      }
    } catch (e) {
      _lastError = e.toString();
    }
    return null;
  }

  /// Copies/installs model from a local staging path into app internal storage
  Future<Result<String, AppError>> installModel(String sourcePath) async {
    try {
      final res = await _methodChannel.invokeMethod<dynamic>(
        'installModel',
        {'sourcePath': sourcePath},
      );
      if (res is Map && res['installed'] == true) {
        final path = res['path']?.toString() ?? '';
        _activeModelPath = path;
        return Success(path);
      }
      return Failure(AppError(
        code: 'INSTALL_FAILED',
        message: res is Map ? res['error']?.toString() ?? 'Install failed' : 'Install failed',
      ));
    } on PlatformException catch (pe) {
      return Failure(AppError(code: pe.code, message: pe.message ?? 'Install failed'));
    } catch (e) {
      return Failure(AppError(code: 'INSTALL_FAILED', message: e.toString()));
    }
  }

  /// Retrieves dynamically discovered launchable apps from Android PackageManager
  @override
  Future<List<Map<String, String>>> getInstalledApps() async {
    try {
      final res = await _methodChannel.invokeMethod<dynamic>('getInstalledLaunchableApps') ??
          await _methodChannel.invokeMethod<dynamic>('getInstalledApps');
      if (res is List) {
        return res.map((item) {
          if (item is Map) {
            return {
              'label': item['label']?.toString() ?? '',
              'packageName': item['packageName']?.toString() ?? '',
            };
          }
          return <String, String>{};
        }).where((m) => m.isNotEmpty && (m['label']?.isNotEmpty ?? false)).toList();
      }
    } catch (e) {
      _lastError = e.toString();
    }
    return [];
  }

  void _subscribeToNativeEvents() {
    if (_eventChannel == null) return;
    try {
      _eventSubscription = _eventChannel.receiveBroadcastStream().listen(
        (dynamic event) {
          if (event is Map) {
            final statusStr = event['status']?.toString();
            final message = event['message']?.toString();
            final modelPath = event['modelPath']?.toString();

            if (modelPath != null) {
              _activeModelPath = modelPath;
            }

            if (statusStr != null) {
              _updateStatusFromNative(statusStr, message: message);
            }
          }
        },
        onError: (dynamic error) {
          _lastError = error.toString();
          _updateStatus(AssistantEngineStatus.error);
        },
      );
    } catch (_) {
      // EventChannel may not be supported in test harness or non-Android environments
    }
  }

  void _updateStatusFromNative(String nativeStatus, {String? message}) {
    switch (nativeStatus.toLowerCase()) {
      case 'initializing':
        _updateStatus(AssistantEngineStatus.initializing);
        break;
      case 'ready':
        _lastError = null;
        _updateStatus(AssistantEngineStatus.ready);
        break;
      case 'processing':
        _updateStatus(AssistantEngineStatus.processing);
        break;
      case 'error':
        _lastError = message ?? 'An error occurred in LiteRT-LM';
        _updateStatus(AssistantEngineStatus.error);
        break;
      case 'uninitialized':
      default:
        _updateStatus(AssistantEngineStatus.offline);
        break;
    }
  }

  void _updateStatus(AssistantEngineStatus newStatus) {
    if (_status != newStatus) {
      _status = newStatus;
      _statusController.add(newStatus);
    }
  }

  @override
  Future<Result<void, AppError>> initialize({String? customModelPath}) async {
    _updateStatus(AssistantEngineStatus.initializing);

    try {
      final response = await _methodChannel.invokeMethod<dynamic>(
        'initialize',
        {
          if (customModelPath != null) 'modelPath': customModelPath,
        },
      );

      if (response is Map) {
        final success = response['success'] == true;
        _activeModelPath = response['modelPath']?.toString();

        if (success) {
          _lastError = null;
          _updateStatus(AssistantEngineStatus.ready);
          return const Success(null);
        }
      }

      _lastError = 'Native engine reported initialization failure';
      _updateStatus(AssistantEngineStatus.error);
      return Failure(AppError(
        code: 'INIT_FAILED',
        message: _lastError!,
      ));
    } on PlatformException catch (pe) {
      _lastError = pe.message ?? pe.code;
      _updateStatus(AssistantEngineStatus.error);
      return Failure(AppError(
        code: pe.code,
        message: 'LiteRT-LM Init Failed: ${pe.message ?? pe.details ?? pe.code}',
      ));
    } catch (e) {
      _lastError = e.toString();
      _updateStatus(AssistantEngineStatus.error);
      return Failure(AppError(
        code: 'UNEXPECTED_ERROR',
        message: 'Failed to communicate with LiteRT-LM native service: $e',
      ));
    }
  }

  @override
  Future<Result<String, AppError>> processPrompt(String input) async {
    final trimmed = input.trim();
    if (trimmed.isEmpty) {
      return const Failure(AppError(
        code: 'EMPTY_PROMPT',
        message: 'Prompt cannot be empty.',
      ));
    }

    _updateStatus(AssistantEngineStatus.processing);

    try {
      final response = await _methodChannel.invokeMethod<dynamic>(
        'processPrompt',
        {'prompt': trimmed},
      );

      _updateStatus(AssistantEngineStatus.ready);

      if (response is Map && response.containsKey('result')) {
        return Success(response['result']?.toString() ?? '');
      } else if (response is String) {
        return Success(response);
      }

      return const Success('');
    } on PlatformException catch (pe) {
      _lastError = pe.message ?? pe.code;
      // Recoverable prompt failure -- do NOT mark the whole engine as broken.
      _updateStatus(AssistantEngineStatus.ready);
      return Failure(AppError(
        code: pe.code,
        message: 'Inference Error: ${pe.message ?? pe.details ?? pe.code}',
      ));
    } catch (e) {
      _lastError = e.toString();
      // Recoverable prompt failure -- do NOT mark the whole engine as broken.
      _updateStatus(AssistantEngineStatus.ready);
      return Failure(AppError(
        code: 'INFERENCE_FAILED',
        message: 'Failed to process prompt via LiteRT-LM: $e',
      ));
    }
  }

  @override
  Future<void> dispose() async {
    try {
      await _eventSubscription?.cancel();
      _eventSubscription = null;
      await _methodChannel.invokeMethod<dynamic>('dispose');
    } catch (_) {
      // Ignore channel teardown issues
    } finally {
      _updateStatus(AssistantEngineStatus.offline);
      await _statusController.close();
    }
  }
}
