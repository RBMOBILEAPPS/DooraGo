import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import '../core/constants/app_strings.dart';
import '../core/utils/result.dart';

/// Callback signatures for voice events
typedef VoiceResultCallback = void Function(String recognizedWords);
typedef VoiceErrorCallback = void Function(String error);

/// Contract for on-device voice capture and speech recognition
abstract class VoiceService {
  /// Whether the microphone is currently capturing audio
  bool get isListening;

  /// Whether offline Voice Wake foreground service is currently running
  bool get isVoiceWakeActive;

  /// Stream of Voice Wake active status changes
  Stream<bool> get voiceWakeStateStream;

  /// Stream of recognized commands triggered by the "Doora" wake word
  Stream<String> get voiceWakeCommandStream;

  /// Starts listening for voice commands
  Future<Result<void, AppError>> startListening({
    required VoiceResultCallback onResult,
    VoiceErrorCallback? onError,
  });

  /// Stops capturing voice input
  Future<void> stopListening();

  /// Cancels and resets audio session
  Future<void> cancel();

  /// Starts the offline Voice Wake microphone foreground service
  Future<bool> startVoiceWake();

  /// Stops the offline Voice Wake microphone foreground service
  Future<bool> stopVoiceWake();

  /// Queries native service for Voice Wake active state
  Future<bool> checkIsVoiceWakeActive();

  /// Prompts system Assistant role request or opens Assistant settings
  Future<bool> requestAssistantRole();

  /// Opens Android Voice Input Settings page
  Future<bool> openAssistantSettings();

  /// Returns Assistant role status map
  Future<Map<String, dynamic>> getAssistantStatus();

  /// Starts the optional Floating Doora Button overlay service
  Future<bool> startFloatingButton();

  /// Stops the optional Floating Doora Button overlay service
  Future<bool> stopFloatingButton();

  /// Queries native service for Floating Doora Button active state
  Future<bool> checkIsFloatingButtonActive();

  /// Checks if SYSTEM_ALERT_WINDOW permission is granted
  Future<bool> checkOverlayPermission();

  /// Opens Android System Settings page to allow display over other apps
  Future<bool> requestOverlayPermission();
}

/// Real Android Native SpeechRecognizer Service for DooraGo
class AndroidNativeVoiceService implements VoiceService {
  static const MethodChannel _methodChannel = MethodChannel('com.rbapps.doorago/voice_engine');
  static const EventChannel _eventChannel = EventChannel('com.rbapps.doorago/voice_events');

  bool _isListening = false;
  bool _isVoiceWakeActive = false;
  VoiceResultCallback? _currentOnResult;
  VoiceErrorCallback? _currentOnError;
  StreamSubscription? _subscription;

  final _voiceWakeStateController = StreamController<bool>.broadcast();
  final _voiceWakeCommandController = StreamController<String>.broadcast();

  AndroidNativeVoiceService() {
    _subscription = _eventChannel.receiveBroadcastStream().listen(
      (event) {
        if (event is Map) {
          final type = event['event'] as String?;
          switch (type) {
            case 'ready':
            case 'beginSpeech':
              _isListening = true;
              break;
            case 'endSpeech':
              _isListening = false;
              break;
            case 'result':
              _isListening = false;
              final text = event['text'] as String? ?? '';
              if (text.isNotEmpty) {
                _currentOnResult?.call(text);
              }
              break;
            case 'error':
              _isListening = false;
              final message = event['message'] as String? ?? 'Voice recognition failed.';
              _currentOnError?.call(message);
              break;
            case 'listening':
              _isListening = event['isListening'] as bool? ?? false;
              if (event.containsKey('isVoiceWakeActive')) {
                final wakeActive = event['isVoiceWakeActive'] as bool? ?? false;
                _isVoiceWakeActive = wakeActive;
                _voiceWakeStateController.add(wakeActive);
              }
              break;
            case 'voiceWakeState':
              _isVoiceWakeActive = event['isActive'] as bool? ?? false;
              _voiceWakeStateController.add(_isVoiceWakeActive);
              break;
            case 'wakeWordDetected':
              debugPrint('[VoiceWake] Wake word "Doora" detected by offline service');
              break;
            case 'wakeWordCommand':
              final cmd = event['text'] as String? ?? '';
              if (cmd.isNotEmpty) {
                _voiceWakeCommandController.add(cmd);
              }
              break;
          }
        }
      },
      onError: (err) {
        _isListening = false;
        _currentOnError?.call('Voice recognition error: $err');
      },
    );

    // Initial check
    checkIsVoiceWakeActive();
  }

  @override
  bool get isListening => _isListening;

  @override
  bool get isVoiceWakeActive => _isVoiceWakeActive;

  @override
  Stream<bool> get voiceWakeStateStream => _voiceWakeStateController.stream;

  @override
  Stream<String> get voiceWakeCommandStream => _voiceWakeCommandController.stream;

  @override
  Future<Result<void, AppError>> startListening({
    required VoiceResultCallback onResult,
    VoiceErrorCallback? onError,
  }) async {
    _currentOnResult = onResult;
    _currentOnError = onError;

    try {
      final isAvailable = await _methodChannel.invokeMethod<bool>('isAvailable') ?? false;
      if (!isAvailable) {
        const errorMsg = 'Voice recognition is unavailable on this device.';
        onError?.call(errorMsg);
        return const Failure(AppError(message: errorMsg, code: 'VOICE_UNAVAILABLE'));
      }

      await _methodChannel.invokeMethod('startListening');
      _isListening = true;
      return const Success(null);
    } on PlatformException catch (e) {
      _isListening = false;
      final errorMsg = e.message ?? 'Microphone permission or speech recognition error.';
      onError?.call(errorMsg);
      return Failure(AppError(message: errorMsg, code: e.code));
    } catch (e) {
      _isListening = false;
      final errorMsg = 'Failed to start voice recognition: $e';
      onError?.call(errorMsg);
      return Failure(AppError(message: errorMsg, code: 'VOICE_START_FAILED'));
    }
  }

  @override
  Future<void> stopListening() async {
    try {
      await _methodChannel.invokeMethod('stopListening');
    } catch (e) {
      debugPrint('[VoiceService] stopListening error: $e');
    } finally {
      _isListening = false;
    }
  }

  @override
  Future<void> cancel() async {
    try {
      await _methodChannel.invokeMethod('cancel');
    } catch (e) {
      debugPrint('[VoiceService] cancel error: $e');
    } finally {
      _isListening = false;
    }
  }

  @override
  Future<bool> startVoiceWake() async {
    try {
      final success = await _methodChannel.invokeMethod<bool>('startVoiceWake') ?? false;
      _isVoiceWakeActive = success;
      _voiceWakeStateController.add(success);
      return success;
    } catch (e) {
      debugPrint('[VoiceService] startVoiceWake error: $e');
      return false;
    }
  }

  @override
  Future<bool> stopVoiceWake() async {
    try {
      final success = await _methodChannel.invokeMethod<bool>('stopVoiceWake') ?? false;
      _isVoiceWakeActive = false;
      _voiceWakeStateController.add(false);
      return success;
    } catch (e) {
      debugPrint('[VoiceService] stopVoiceWake error: $e');
      return false;
    }
  }

  @override
  Future<bool> checkIsVoiceWakeActive() async {
    try {
      final active = await _methodChannel.invokeMethod<bool>('isVoiceWakeActive') ?? false;
      _isVoiceWakeActive = active;
      _voiceWakeStateController.add(active);
      return active;
    } catch (e) {
      debugPrint('[VoiceService] checkIsVoiceWakeActive error: $e');
      return false;
    }
  }

  @override
  Future<bool> requestAssistantRole() async {
    try {
      return await _methodChannel.invokeMethod<bool>('requestAssistantRole') ?? false;
    } catch (e) {
      debugPrint('[VoiceService] requestAssistantRole error: $e');
      return false;
    }
  }

  @override
  Future<bool> openAssistantSettings() async {
    try {
      return await _methodChannel.invokeMethod<bool>('openAssistantSettings') ?? false;
    } catch (e) {
      debugPrint('[VoiceService] openAssistantSettings error: $e');
      return false;
    }
  }

  @override
  Future<Map<String, dynamic>> getAssistantStatus() async {
    try {
      final status = await _methodChannel.invokeMapMethod<String, dynamic>('getAssistantStatus');
      return status ?? <String, dynamic>{};
    } catch (e) {
      debugPrint('[VoiceService] getAssistantStatus error: $e');
      return <String, dynamic>{};
    }
  }

  @override
  Future<bool> startFloatingButton() async {
    try {
      return await _methodChannel.invokeMethod<bool>('startFloatingButton') ?? false;
    } catch (e) {
      debugPrint('[VoiceService] startFloatingButton error: $e');
      return false;
    }
  }

  @override
  Future<bool> stopFloatingButton() async {
    try {
      return await _methodChannel.invokeMethod<bool>('stopFloatingButton') ?? false;
    } catch (e) {
      debugPrint('[VoiceService] stopFloatingButton error: $e');
      return false;
    }
  }

  @override
  Future<bool> checkIsFloatingButtonActive() async {
    try {
      return await _methodChannel.invokeMethod<bool>('isFloatingButtonActive') ?? false;
    } catch (e) {
      debugPrint('[VoiceService] checkIsFloatingButtonActive error: $e');
      return false;
    }
  }

  @override
  Future<bool> checkOverlayPermission() async {
    try {
      return await _methodChannel.invokeMethod<bool>('checkOverlayPermission') ?? false;
    } catch (e) {
      debugPrint('[VoiceService] checkOverlayPermission error: $e');
      return false;
    }
  }

  @override
  Future<bool> requestOverlayPermission() async {
    try {
      return await _methodChannel.invokeMethod<bool>('requestOverlayPermission') ?? false;
    } catch (e) {
      debugPrint('[VoiceService] requestOverlayPermission error: $e');
      return false;
    }
  }

  void dispose() {
    _subscription?.cancel();
    _voiceWakeStateController.close();
    _voiceWakeCommandController.close();
  }
}

/// Fallback / Unconnected VoiceService
class UnconnectedVoiceService implements VoiceService {
  final bool _isListening = false;
  final bool _isVoiceWakeActive = false;

  @override
  bool get isListening => _isListening;

  @override
  bool get isVoiceWakeActive => _isVoiceWakeActive;

  @override
  Stream<bool> get voiceWakeStateStream => const Stream.empty();

  @override
  Stream<String> get voiceWakeCommandStream => const Stream.empty();

  @override
  Future<Result<void, AppError>> startListening({
    required VoiceResultCallback onResult,
    VoiceErrorCallback? onError,
  }) async {
    onError?.call(AppStrings.voiceNotConnectedNotice);
    return const Failure(
      AppError(
        message: AppStrings.voiceNotConnectedNotice,
        code: 'VOICE_NOT_CONNECTED',
      ),
    );
  }

  @override
  Future<void> stopListening() async {}

  @override
  Future<void> cancel() async {}

  @override
  Future<bool> startVoiceWake() async {
    return false;
  }

  @override
  Future<bool> stopVoiceWake() async {
    return true;
  }

  @override
  Future<bool> checkIsVoiceWakeActive() async {
    return false;
  }

  @override
  Future<bool> requestAssistantRole() async {
    return false;
  }

  @override
  Future<bool> openAssistantSettings() async {
    return false;
  }

  @override
  Future<Map<String, dynamic>> getAssistantStatus() async {
    return <String, dynamic>{
      'roleAvailable': false,
      'roleHeld': false,
      'serviceRunning': false,
      'voiceWakeEnabled': false,
    };
  }

  @override
  Future<bool> startFloatingButton() async => false;

  @override
  Future<bool> stopFloatingButton() async => true;

  @override
  Future<bool> checkIsFloatingButtonActive() async => false;

  @override
  Future<bool> checkOverlayPermission() async => false;

  @override
  Future<bool> requestOverlayPermission() async => false;
}
