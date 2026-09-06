import 'package:flutter/material.dart';
import '../models/command_item.dart';
import '../providers/assistant_provider.dart';
import '../providers/theme_provider.dart';
import '../widgets/assistant_header.dart';
import '../widgets/command_input_bar.dart';
import '../widgets/commands_sheet.dart';
import '../widgets/conversation_view.dart';
import '../widgets/empty_state_view.dart';
import '../widgets/installed_app_picker_sheet.dart';
import '../widgets/permission_onboarding_sheet.dart';
import '../widgets/permissions_sheet.dart';
import '../widgets/voice_action_button.dart';

/// Primary Screen for the DooraGo Offline Assistant
class AssistantScreen extends StatefulWidget {
  final AssistantProvider assistantProvider;
  final ThemeProvider themeProvider;

  const AssistantScreen({
    super.key,
    required this.assistantProvider,
    required this.themeProvider,
  });

  @override
  State<AssistantScreen> createState() => _AssistantScreenState();
}

class _AssistantScreenState extends State<AssistantScreen> {
  final TextEditingController _inputController = TextEditingController();

  @override
  void initState() {
    super.initState();
    widget.assistantProvider.addListener(_onProviderChange);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _checkFirstLaunchPermissionOnboarding();
    });
  }

  Future<void> _checkFirstLaunchPermissionOnboarding() async {
    final service = widget.assistantProvider.permissionService;
    final hasCompleted = await service.hasCompletedOnboarding();
    if (!hasCompleted && mounted) {
      _showPermissionOnboardingSheet();
    }
  }

  void _showPermissionOnboardingSheet() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      isDismissible: false,
      enableDrag: false,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) => PermissionOnboardingSheet(
        permissionService: widget.assistantProvider.permissionService,
        onCompleted: () {
          widget.assistantProvider.checkFloatingButtonStatus();
        },
      ),
    );
  }

  @override
  void dispose() {
    widget.assistantProvider.removeListener(_onProviderChange);
    _inputController.dispose();
    super.dispose();
  }

  void _onProviderChange() {
    if (!mounted) return;
    final state = widget.assistantProvider.state;

    // Check for missing TTS voice data prompt
    if (state.isTtsVoiceDataMissing) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        _showVoiceDataSetupDialog();
      });
    }

    final notice = state.activeNotice;
    if (notice != null && notice.isNotEmpty) {
      ScaffoldMessenger.of(context).hideCurrentSnackBar();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(notice),
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 3),
          action: SnackBarAction(
            label: 'OK',
            onPressed: () => widget.assistantProvider.dismissNotice(),
          ),
        ),
      );
    }
    setState(() {});
  }

  void _showVoiceDataSetupDialog() {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        icon: const Icon(Icons.record_voice_over_rounded, size: 36),
        title: const Text('Voice Output Setup'),
        content: const SingleChildScrollView(
          child: Text(
            'Voice output needs a voice package to speak responses offline. Install it now from Android Text-to-Speech settings?',
          ),
        ),
        actionsOverflowButtonSpacing: 8,
        actionsAlignment: MainAxisAlignment.end,
        actions: [
          TextButton(
            onPressed: () {
              Navigator.of(ctx).pop();
              widget.assistantProvider.dismissTtsVoiceDataPrompt();
            },
            child: const Text('NOT NOW'),
          ),
          FilledButton.icon(
            icon: const Icon(Icons.download_rounded, size: 18),
            label: const Text('INSTALL'),
            onPressed: () {
              Navigator.of(ctx).pop();
              widget.assistantProvider.openTtsVoiceDataInstaller();
            },
          ),
        ],
      ),
    );
  }

  void _handleSelectSuggestion(String suggestion) {
    debugPrint("[COMMAND_UI_TAP] suggestion='$suggestion'");
    if (suggestion == 'Turn on flashlight' || suggestion == 'Open WiFi settings') {
      debugPrint("[COMMAND_SUBMIT] source=COMMANDS_UI text='$suggestion'");
      _inputController.clear();
      widget.assistantProvider.submitCommand(suggestion);
    } else {
      _inputController.text = suggestion;
      _inputController.selection = TextSelection.fromPosition(
        TextPosition(offset: suggestion.length),
      );
    }
  }

  void _handleSelectCommand(CommandItem command) {
    debugPrint("[COMMAND_UI_TAP] command='${command.title}'");
    if (command.executionMode == CommandExecutionMode.installedAppPicker) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        _openInstalledAppPicker();
      });
    } else if (command.executionMode == CommandExecutionMode.directAction) {
      final cmdText = command.directCommand ?? command.title;
      debugPrint("[COMMAND_SUBMIT] source=COMMANDS_UI text='$cmdText'");
      _inputController.clear();
      widget.assistantProvider.submitCommand(cmdText);
    } else {
      final template = command.templateText ?? command.example;
      widget.assistantProvider.setActiveCommand(command);
      _inputController.text = template;
      _inputController.selection = TextSelection.fromPosition(
        TextPosition(offset: template.length),
      );
    }
  }

  void _openCommandsSheet() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) => CommandsSheet(
        onSelectCommand: _handleSelectCommand,
      ),
    );
  }

  void _openInstalledAppPicker() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) => InstalledAppPickerSheet(
        assistantProvider: widget.assistantProvider,
        onSelectApp: (appLabel) {
          final canonicalCommand = 'Open $appLabel';
          debugPrint("[COMMAND_SUBMIT] source=APP_PICKER text='$canonicalCommand'");
          _inputController.clear();
          widget.assistantProvider.submitCommand(canonicalCommand);
        },
      ),
    );
  }

  // Voice Wake temporarily hidden because the current wake-word detector is not production-ready.
  // The underlying implementation is retained for future trained Doora KWS integration.
  // ignore: unused_element
  void _handleToggleVoiceWake() {
    final state = widget.assistantProvider.state;
    if (state.isVoiceWakeActive) {
      widget.assistantProvider.toggleVoiceWake();
    } else {
      showDialog(
        context: context,
        builder: (ctx) => AlertDialog(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
          icon: const Icon(Icons.hearing_rounded, size: 36),
          title: const Text('Enable Voice Wake'),
          content: const SingleChildScrollView(
            child: Text(
              "Voice Wake keeps a microphone service active to listen locally for the wake word 'Doora'. Audio is not uploaded or stored by this feature.",
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('CANCEL'),
            ),
            FilledButton(
              onPressed: () {
                Navigator.of(ctx).pop();
                widget.assistantProvider.toggleVoiceWake();
              },
              child: const Text('TURN ON'),
            ),
          ],
        ),
      );
    }
  }

  void _handleToggleFloatingButton() {
    final state = widget.assistantProvider.state;
    if (state.isFloatingButtonActive) {
      widget.assistantProvider.toggleFloatingButton();
    } else {
      showDialog(
        context: context,
        builder: (ctx) => AlertDialog(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
          icon: const Icon(Icons.picture_in_picture_rounded, size: 36),
          title: const Text('Floating Doora Button'),
          content: const SingleChildScrollView(
            child: Text(
              "Floating Doora Button lets you start a voice command by tapping the floating button above other apps. Your microphone is used only while a voice command is active.",
            ),
          ),
          actionsOverflowButtonSpacing: 8,
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('CANCEL'),
            ),
            FilledButton(
              onPressed: () {
                Navigator.of(ctx).pop();
                widget.assistantProvider.toggleFloatingButton();
              },
              child: Text(state.hasOverlayPermission ? 'ENABLE' : 'ALLOW OVERLAY'),
            ),
          ],
        ),
      );
    }
  }

  void _openPermissionsSheet() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) => const PermissionsSheet(),
    );
  }

  @override
  Widget build(BuildContext context) {
    final provider = widget.assistantProvider;
    final themeProvider = widget.themeProvider;
    final state = provider.state;

    return Scaffold(
      appBar: AssistantHeader(
        engineStatus: state.engineStatus,
        hasMessages: provider.hasMessages,
        isVoiceOutputEnabled: state.isVoiceOutputEnabled,
        isFloatingButtonActive: state.isFloatingButtonActive,
        hasOverlayPermission: state.hasOverlayPermission,
        onClear: () => provider.clearConversation(),
        onToggleTheme: () => themeProvider.toggleTheme(),
        onToggleVoiceOutput: () => provider.toggleVoiceOutput(),
        onToggleFloatingButton: _handleToggleFloatingButton,
        onOpenCommands: _openCommandsSheet,
        onOpenPermissions: _openPermissionsSheet,
        isDarkMode: themeProvider.isDarkMode,
      ),
      body: SafeArea(
        child: Column(
          children: [
            // Conversation or Empty State Area
            Expanded(
              child: provider.hasMessages
                  ? ConversationView(
                      messages: provider.messages,
                      isProcessing: state.isProcessing,
                    )
                  : EmptyStateView(
                      onSelectSuggestion: _handleSelectSuggestion,
                    ),
            ),

            // Central Voice/Action Button Row (Shown prominently above input)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Center(
                child: VoiceActionButton(
                  isListening: state.isListening,
                  onTap: () => provider.toggleVoiceListening(),
                ),
              ),
            ),

            // Text Input Command Bar
            CommandInputBar(
              controller: _inputController,
              isProcessing: state.isProcessing,
              activeCommand: state.activeCommand,
              onClearActiveCommand: () => provider.clearActiveCommand(),
              onSubmit: (command) {
                debugPrint("[COMMAND_SUBMIT] source=TYPED_TEXT text='$command'");
                provider.submitCommand(command);
              },
            ),
          ],
        ),
      ),
    );
  }
}

