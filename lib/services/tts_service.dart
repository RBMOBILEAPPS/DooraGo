import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Status model for on-device TTS engine
class TtsEngineStatus {
  final bool isAvailable;
  final bool isInitialized;
  final bool isSpeaking;
  final bool hiInAvailable;
  final bool enInAvailable;
  final bool hiDataMissing;
  final bool enDataMissing;
  final String engineName;

  const TtsEngineStatus({
    this.isAvailable = false,
    this.isInitialized = false,
    this.isSpeaking = false,
    this.hiInAvailable = false,
    this.enInAvailable = false,
    this.hiDataMissing = false,
    this.enDataMissing = false,
    this.engineName = 'unknown',
  });

  factory TtsEngineStatus.fromMap(Map<dynamic, dynamic> map) {
    return TtsEngineStatus(
      isAvailable: map['isAvailable'] as bool? ?? false,
      isInitialized: map['isInitialized'] as bool? ?? false,
      isSpeaking: map['isSpeaking'] as bool? ?? false,
      hiInAvailable: map['hiInAvailable'] as bool? ?? false,
      enInAvailable: map['enInAvailable'] as bool? ?? false,
      hiDataMissing: map['hiDataMissing'] as bool? ?? false,
      enDataMissing: map['enDataMissing'] as bool? ?? false,
      engineName: map['engineName'] as String? ?? 'unknown',
    );
  }
}

/// Abstract contract for local on-device Text-to-Speech
abstract class TtsService {
  /// Whether the TTS engine is actively speaking audio
  bool get isSpeaking;

  /// Speaks the provided text offline via Android native TextToSpeech
  Future<Map<String, dynamic>> speak(String text, {String? locale});

  /// Immediately stops any playing speech
  Future<void> stop();

  /// Gets current TTS diagnostic status (availability, missing voice data, engine)
  Future<TtsEngineStatus> getStatus();

  /// Opens the official Android TTS voice-data installer
  Future<bool> openVoiceDataInstaller();

  /// Opens Android Text-to-Speech system settings
  Future<bool> openTtsSettings();

  /// Checks if TTS engine is initialized and available
  Future<bool> isAvailable();
}

/// Production Android Native TextToSpeech Service for DooraGo
class AndroidNativeTtsService implements TtsService {
  static const MethodChannel _channel = MethodChannel('com.rbapps.doorago/tts_engine');

  bool _isSpeaking = false;

  @override
  bool get isSpeaking => _isSpeaking;

  @override
  Future<Map<String, dynamic>> speak(String text, {String? locale}) async {
    final trimmed = text.trim();
    if (trimmed.isEmpty) {
      return {'success': false, 'reason': 'EMPTY_TEXT'};
    }

    try {
      final result = await _channel.invokeMapMethod<String, dynamic>('speak', {
        'text': trimmed,
        if (locale != null) 'locale': locale,
      });
      final map = result ?? {'success': false, 'reason': 'NULL_RESPONSE'};
      _isSpeaking = map['success'] == true;
      return map;
    } on PlatformException catch (e) {
      debugPrint('[TTS_ERROR] speak PlatformException: $e');
      _isSpeaking = false;
      return {'success': false, 'error': e.message};
    } catch (e) {
      debugPrint('[TTS_ERROR] speak unexpected error: $e');
      _isSpeaking = false;
      return {'success': false, 'error': e.toString()};
    }
  }

  @override
  Future<void> stop() async {
    try {
      _isSpeaking = false;
      await _channel.invokeMethod('stop');
    } catch (e) {
      debugPrint('[TTS_ERROR] stop error: $e');
    }
  }

  @override
  Future<TtsEngineStatus> getStatus() async {
    try {
      final statusMap = await _channel.invokeMapMethod<dynamic, dynamic>('getStatus');
      if (statusMap != null) {
        return TtsEngineStatus.fromMap(statusMap);
      }
    } catch (e) {
      debugPrint('[TTS_ERROR] getStatus error: $e');
    }
    return const TtsEngineStatus();
  }

  @override
  Future<bool> openVoiceDataInstaller() async {
    try {
      final result = await _channel.invokeMethod<bool>('openInstaller');
      return result ?? false;
    } catch (e) {
      debugPrint('[TTS_ERROR] openVoiceDataInstaller error: $e');
      return false;
    }
  }

  @override
  Future<bool> openTtsSettings() async {
    try {
      final result = await _channel.invokeMethod<bool>('openSettings');
      return result ?? false;
    } catch (e) {
      debugPrint('[TTS_ERROR] openTtsSettings error: $e');
      return false;
    }
  }

  @override
  Future<bool> isAvailable() async {
    try {
      final result = await _channel.invokeMethod<bool>('isAvailable');
      return result ?? false;
    } catch (e) {
      debugPrint('[TTS_ERROR] isAvailable error: $e');
      return false;
    }
  }
}
