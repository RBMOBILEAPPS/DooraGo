import 'package:flutter/material.dart';

/// Material 3 Color Schemes for DooraGo
/// Android-first dynamic tonal palette with sophisticated teal/emerald & slate accents
class AppColorSchemes {
  AppColorSchemes._();

  // Light Color Scheme
  static const ColorScheme lightColorScheme = ColorScheme(
    brightness: Brightness.light,
    primary: Color(0xFF006A60),
    onPrimary: Color(0xFFFFFFFF),
    primaryContainer: Color(0xFF73F8E6),
    onPrimaryContainer: Color(0xFF00201C),
    secondary: Color(0xFF4A635F),
    onSecondary: Color(0xFFFFFFFF),
    secondaryContainer: Color(0xFFCCE8E3),
    onSecondaryContainer: Color(0xFF05201C),
    tertiary: Color(0xFF456179),
    onTertiary: Color(0xFFFFFFFF),
    tertiaryContainer: Color(0xFFCCE5FF),
    onTertiaryContainer: Color(0xFF001E31),
    error: Color(0xFFBA1A1A),
    onError: Color(0xFFFFFFFF),
    errorContainer: Color(0xFFFFDAD6),
    onErrorContainer: Color(0xFF410002),
    surface: Color(0xFFF4FAF8),
    onSurface: Color(0xFF161D1C),
    surfaceContainerLowest: Color(0xFFFFFFFF),
    surfaceContainerLow: Color(0xFFEEF5F3),
    surfaceContainer: Color(0xFFE8EFED),
    surfaceContainerHigh: Color(0xFFE2E9E7),
    surfaceContainerHighest: Color(0xFFDCE4E2),
    outline: Color(0xFF6F7977),
    outlineVariant: Color(0xFFBEC9C6),
  );

  // Dark Color Scheme
  static const ColorScheme darkColorScheme = ColorScheme(
    brightness: Brightness.dark,
    primary: Color(0xFF52DBC9),
    onPrimary: Color(0xFF003731),
    primaryContainer: Color(0xFF005048),
    onPrimaryContainer: Color(0xFF73F8E6),
    secondary: Color(0xFFB0CCC7),
    onSecondary: Color(0xFF1B3531),
    secondaryContainer: Color(0xFF324B47),
    onSecondaryContainer: Color(0xFFCCE8E3),
    tertiary: Color(0xFFAECBE6),
    onTertiary: Color(0xFF143349),
    tertiaryContainer: Color(0xFF2D4A60),
    onTertiaryContainer: Color(0xFFCCE5FF),
    error: Color(0xFFFFB4AB),
    onError: Color(0xFF690005),
    errorContainer: Color(0xFF93000A),
    onErrorContainer: Color(0xFFFFDAD6),
    surface: Color(0xFF0E1514),
    onSurface: Color(0xFFDEE4E2),
    surfaceContainerLowest: Color(0xFF09100F),
    surfaceContainerLow: Color(0xFF161D1C),
    surfaceContainer: Color(0xFF1A2120),
    surfaceContainerHigh: Color(0xFF242C2A),
    surfaceContainerHighest: Color(0xFF2F3735),
    outline: Color(0xFF889390),
    outlineVariant: Color(0xFF3F4947),
  );

  // Status Colors
  static const Color offlineGreen = Color(0xFF2E7D32);
  static const Color offlineGreenDark = Color(0xFF81C784);
}
