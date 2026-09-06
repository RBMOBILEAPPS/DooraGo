export interface FlutterFileInfo {
  path: string;
  category: 'core' | 'models' | 'services' | 'providers' | 'screens' | 'widgets' | 'android' | 'config';
  description: string;
}

export const FLUTTER_PROJECT_TREE: FlutterFileInfo[] = [
  {
    path: 'pubspec.yaml',
    category: 'config',
    description: 'Flutter 3.44.9 / Dart 3.12.2 configuration with zero unnecessary dependencies',
  },
  {
    path: 'analysis_options.yaml',
    category: 'config',
    description: 'Strict Dart lints enforcing null safety and deprecation prevention',
  },
  {
    path: 'android/app/build.gradle',
    category: 'android',
    description: 'Application ID com.rbapps.doorago, LiteRT-LM 0.16.1, Coroutines 1.8.1, compileSdk 36, targetSdk 36, minSdk 26',
  },
  {
    path: 'android/app/src/main/AndroidManifest.xml',
    category: 'android',
    description: 'Android manifest with minimal permissions and Android 15+ intent queries',
  },
  {
    path: 'android/app/src/main/kotlin/com/rbapps/doorago/MainActivity.kt',
    category: 'android',
    description: 'Bridges LiteRT-LM runtime to Flutter via MethodChannel & EventChannel',
  },
  {
    path: 'android/app/src/main/kotlin/com/rbapps/doorago/LiteRtLmAiEngine.kt',
    category: 'android',
    description: 'Native LiteRT-LM engine running MobileActions-270M on Dispatchers.IO with state machine',
  },
  {
    path: 'assets/models/README.md',
    category: 'config',
    description: 'MobileActions-270M (FunctionGemma) LiteRT-LM model deployment and adb setup guide',
  },
  {
    path: 'scripts/install_model.bat',
    category: 'config',
    description: 'Windows automated script to push D:\\DooraGo\\mobile_actions_q8_ekv1024.litertlm to device via ADB',
  },
  {
    path: 'scripts/install_model.sh',
    category: 'config',
    description: 'POSIX shell script to push mobile_actions_q8_ekv1024.litertlm to device via ADB',
  },
  {
    path: 'docs/MODEL_INSTALLATION.md',
    category: 'config',
    description: 'Comprehensive Stage 3 installation, verification, and testing guide for real model file',
  },
  {
    path: 'lib/main.dart',
    category: 'core',
    description: 'Root DooraGoApp Material 3 configuration and provider bootstrapping',
  },
  {
    path: 'lib/core/constants/app_constants.dart',
    category: 'core',
    description: 'App ID com.rbapps.doorago, target models metadata, and constants',
  },
  {
    path: 'lib/core/constants/app_strings.dart',
    category: 'core',
    description: 'All user-facing strings, greeting templates, and dev mode messages',
  },
  {
    path: 'lib/core/theme/color_schemes.dart',
    category: 'core',
    description: 'Material 3 dynamic color schemes for both Light and Dark modes',
  },
  {
    path: 'lib/core/theme/app_theme.dart',
    category: 'core',
    description: 'ThemeData configurations without any deprecated Flutter APIs',
  },
  {
    path: 'lib/core/utils/result.dart',
    category: 'core',
    description: 'Functional Result<S, E> sealed class with pattern matching',
  },
  {
    path: 'lib/models/message_model.dart',
    category: 'models',
    description: 'Immutable chat message model with role, status, and devNotice flags',
  },
  {
    path: 'lib/models/command_action.dart',
    category: 'models',
    description: 'Schema prepared for MobileActions-270M and FunctionGemma actions',
  },
  {
    path: 'lib/models/assistant_state.dart',
    category: 'models',
    description: 'Assistant engine state (offline, listening, processing, devNotConnected)',
  },
  {
    path: 'lib/services/ai_service.dart',
    category: 'services',
    description: 'AIService interface & UnconnectedAIService fallback for LiteRT-LM',
  },
  {
    path: 'lib/services/offline_ai_service.dart',
    category: 'services',
    description: 'Production implementation of AIService connecting to LiteRT-LM via MethodChannel & EventChannel',
  },
  {
    path: 'lib/services/command_parser_service.dart',
    category: 'services',
    description: 'CommandParserService interface for MobileActions-270M function calling',
  },
  {
    path: 'lib/services/action_executor_service.dart',
    category: 'services',
    description: 'ActionExecutorService interface for Android platform channel execution',
  },
  {
    path: 'lib/services/permission_manager_service.dart',
    category: 'services',
    description: 'PermissionManagerService interface for Android system permissions',
  },
  {
    path: 'lib/services/voice_service.dart',
    category: 'services',
    description: 'VoiceService interface for on-device voice capture and STT',
  },
  {
    path: 'lib/services/conversation_service.dart',
    category: 'services',
    description: 'ConversationService interface and in-memory history repository',
  },
  {
    path: 'lib/providers/assistant_provider.dart',
    category: 'providers',
    description: 'ChangeNotifier state controller orchestrating services and messages',
  },
  {
    path: 'lib/providers/theme_provider.dart',
    category: 'providers',
    description: 'Theme controller managing light/dark mode transitions',
  },
  {
    path: 'lib/screens/assistant_screen.dart',
    category: 'screens',
    description: 'Primary assistant view integrating header, conversation, voice, and input',
  },
  {
    path: 'lib/widgets/assistant_header.dart',
    category: 'widgets',
    description: 'Material 3 Top App Bar with status badge, theme toggle, and info sheet',
  },
  {
    path: 'lib/widgets/status_badge.dart',
    category: 'widgets',
    description: 'Material 3 offline status badge with emerald accent and tooltip',
  },
  {
    path: 'lib/widgets/chat_bubble.dart',
    category: 'widgets',
    description: 'Message bubbles with clear Stage 1 dev notice badges',
  },
  {
    path: 'lib/widgets/conversation_view.dart',
    category: 'widgets',
    description: 'Auto-scrolling conversation list with processing indicators',
  },
  {
    path: 'lib/widgets/empty_state_view.dart',
    category: 'widgets',
    description: 'Friendly time-based greeting, offline privacy badge, and suggestion chips',
  },
  {
    path: 'lib/widgets/voice_action_button.dart',
    category: 'widgets',
    description: 'Central action button with elevated ripple and listening states',
  },
  {
    path: 'lib/widgets/command_input_bar.dart',
    category: 'widgets',
    description: 'Text command input field with send button and null-safe validation',
  },
  {
    path: 'lib/widgets/action_preview_sheet.dart',
    category: 'widgets',
    description: 'Modal sheet detailing LiteRT-LM, SDK 36, and architecture specifications',
  },
];
