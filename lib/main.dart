import 'package:flutter/material.dart';
import 'core/constants/app_constants.dart';
import 'core/theme/app_theme.dart';
import 'providers/assistant_provider.dart';
import 'providers/theme_provider.dart';
import 'screens/assistant_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const DooraGoApp());
}

/// Root Application Widget for DooraGo
class DooraGoApp extends StatefulWidget {
  const DooraGoApp({super.key});

  @override
  State<DooraGoApp> createState() => _DooraGoAppState();
}

class _DooraGoAppState extends State<DooraGoApp> {
  late final ThemeProvider _themeProvider;
  late final AssistantProvider _assistantProvider;

  @override
  void initState() {
    super.initState();
    _themeProvider = ThemeProvider();
    _assistantProvider = AssistantProvider();

    _themeProvider.addListener(_handleThemeChange);
  }

  void _handleThemeChange() {
    setState(() {});
  }

  @override
  void dispose() {
    _themeProvider.removeListener(_handleThemeChange);
    _themeProvider.dispose();
    _assistantProvider.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: AppConstants.appName,
      debugShowCheckedModeBanner: false,
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      themeMode: _themeProvider.themeMode,
      home: AssistantScreen(
        assistantProvider: _assistantProvider,
        themeProvider: _themeProvider,
      ),
    );
  }
}
