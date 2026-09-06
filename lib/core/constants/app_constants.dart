/// Application constants for DooraGo
class AppConstants {
  AppConstants._();

  static const String appName = 'DooraGo';
  static const String appId = 'com.rbapps.doorago';
  static const String appVersion = '1.0.0';

  // AI & Engine Architecture Metadata
  static const String targetAiModel = 'MobileActions-270M (FunctionGemma)';
  static const String targetModelRepo = 'litert-community/functiongemma-270m-ft-mobile-actions';
  static const String targetModelFilename = 'mobile_actions_q8_ekv1024.litertlm';
  static const String targetModelAliasFilename = 'mobile-actions_q8_ekv1024.litertlm';
  static const int expectedModelSizeBytes = 288964608;
  static const String expectedModelSha256 = '33E295CBD996B419BB1DE8F3F85C5B6B01EE058A2C89BDB2173CF3E6FF4CE9D0';
  static const String preferredModelStoragePath = 'filesDir/models/mobile_actions_q8_ekv1024.litertlm';
  static const String targetRuntime = 'LiteRT-LM 0.16.1 (On-Device)';
  static const String errorCodeModelNotFound = 'MODEL_NOT_FOUND';
  static const bool isEngineConnected = true;

  // Development Notices
  static const String devModeNotice = 'AI engine is not connected yet.';
  static const String offlineStatusLabel = 'Offline';

  // Animation & Layout Timings
  static const Duration defaultAnimationDuration = Duration(milliseconds: 300);
  static const Duration pulseAnimationDuration = Duration(milliseconds: 1400);
}
