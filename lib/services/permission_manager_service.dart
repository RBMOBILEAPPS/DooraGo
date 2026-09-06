import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Contract for inspecting and requesting Android system permissions
/// for offline actions (microphone, phone calls, alarms)
abstract class PermissionManagerService {
  /// Checks whether a specific Android permission is currently granted
  Future<bool> isGranted(String permission);

  /// Requests permission from the user via Android runtime dialogs
  Future<bool> request(String permission);

  /// Opens the Android system settings page for DooraGo
  Future<void> openAppSettings();

  /// Checks whether startup permission onboarding has already been completed
  Future<bool> hasCompletedOnboarding();

  /// Sets startup permission onboarding as completed
  Future<void> setOnboardingCompleted();
}

/// Real Android Native implementation for DooraGo System Permissions
class AndroidPermissionManagerService implements PermissionManagerService {
  static const MethodChannel _methodChannel = MethodChannel('com.rbapps.doorago/voice_engine');

  const AndroidPermissionManagerService();

  @override
  Future<bool> isGranted(String permission) async {
    try {
      if (permission == 'SYSTEM_ALERT_WINDOW') {
        return await _methodChannel.invokeMethod<bool>('checkOverlayPermission') ?? false;
      }
      return await _methodChannel.invokeMethod<bool>(
        'checkPermission',
        {'permission': permission},
      ) ?? false;
    } catch (e) {
      debugPrint('[PermissionManager] isGranted error for $permission: $e');
      return false;
    }
  }

  @override
  Future<bool> request(String permission) async {
    try {
      if (permission == 'SYSTEM_ALERT_WINDOW') {
        return await _methodChannel.invokeMethod<bool>('requestOverlayPermission') ?? false;
      }
      return await _methodChannel.invokeMethod<bool>(
        'requestPermission',
        {'permission': permission},
      ) ?? false;
    } catch (e) {
      debugPrint('[PermissionManager] request error for $permission: $e');
      return false;
    }
  }

  @override
  Future<void> openAppSettings() async {
    try {
      await _methodChannel.invokeMethod('openAppSettings');
    } catch (e) {
      debugPrint('[PermissionManager] openAppSettings error: $e');
    }
  }

  @override
  Future<bool> hasCompletedOnboarding() async {
    try {
      return await _methodChannel.invokeMethod<bool>('hasCompletedPermissionOnboarding') ?? false;
    } catch (e) {
      debugPrint('[PermissionManager] hasCompletedOnboarding error: $e');
      return false;
    }
  }

  @override
  Future<void> setOnboardingCompleted() async {
    try {
      await _methodChannel.invokeMethod('setPermissionOnboardingCompleted');
    } catch (e) {
      debugPrint('[PermissionManager] setOnboardingCompleted error: $e');
    }
  }
}
