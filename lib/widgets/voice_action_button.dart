import 'package:flutter/material.dart';
import '../core/constants/app_strings.dart';

/// Central floating Voice & Action Button
class VoiceActionButton extends StatelessWidget {
  final bool isListening;
  final VoidCallback onTap;

  const VoiceActionButton({
    super.key,
    required this.isListening,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Semantics(
      button: true,
      label: isListening ? AppStrings.micActiveTooltip : AppStrings.micTooltip,
      child: Tooltip(
        message: isListening ? AppStrings.micActiveTooltip : AppStrings.micTooltip,
        child: GestureDetector(
          onTap: onTap,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 300),
            curve: Curves.easeInOut,
            width: isListening ? 68 : 60,
            height: isListening ? 68 : 60,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: isListening ? colorScheme.error : colorScheme.primary,
              boxShadow: [
                BoxShadow(
                  color: (isListening ? colorScheme.error : colorScheme.primary)
                      .withValues(alpha: 0.35),
                  blurRadius: isListening ? 20 : 12,
                  offset: const Offset(0, 4),
                  spreadRadius: isListening ? 4 : 0,
                ),
              ],
            ),
            child: Center(
              child: Icon(
                isListening ? Icons.graphic_eq_rounded : Icons.mic_rounded,
                size: isListening ? 32 : 28,
                color: isListening ? colorScheme.onError : colorScheme.onPrimary,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
