/// User-facing text strings for DooraGo
class AppStrings {
  AppStrings._();

  static const String appName = 'DooraGo';
  static const String tagline = 'Offline Android Assistant';
  
  // Status Labels
  static const String statusOffline = 'Offline';
  static const String statusReady = 'Ready';
  static const String statusProcessing = 'Processing...';

  // Greeting Strings
  static const String greetingPrefix = 'Hello,';
  static const String greetingSubtitle = 'How can I assist you on your device today?';
  
  // Empty State Strings
  static const String emptyStateTitle = 'Give me a command';
  static const String emptyStateDescription =
      'DooraGo processes supported commands directly on your device without relying on cloud AI services.';
  static const String sampleCommand1 = 'Turn on flashlight';
  static const String sampleCommand2 = 'Show the map';
  static const String sampleCommand3 = 'Open WiFi settings';
  static const String sampleCommand4 = 'Create a contact';

  // Input Field
  static const String inputHint = 'Type a device command...';
  static const String sendTooltip = 'Send command';
  static const String micTooltip = 'Tap to speak';
  static const String micActiveTooltip = 'Listening...';

  // System & Dev Notice
  static const String devModeResponse = 'AI engine is not connected yet.';
  static const String voiceNotConnectedNotice = 'Voice input service is not connected yet.';
  static const String offlineBadgeTooltip = 'Runs 100% locally on your device without internet';
}
