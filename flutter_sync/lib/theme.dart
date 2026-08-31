import 'package:flutter/material.dart';

/// 复刻 Sync.vue 强制暗色主题的配色：
/// 页面 #1a1d24 / 卡片 #24272e / 输入框 #2c3138 / 链接 #4a9eff
abstract final class SyncColors {
  static const page = Color(0xFF1A1D24);
  static const card = Color(0xFF24272E);
  static const field = Color(0xFF2C3138);
  static const border = Color(0x667F7F7F);
  static const link = Color(0xFF4A9EFF);
  static const success = Color(0xFF28A781);
  static const warning = Color(0xFFFFC107);
  static const danger = Color(0xFFDC3545);
}

ThemeData buildSyncTheme() {
  final scheme = ColorScheme.fromSeed(
    seedColor: SyncColors.link,
    brightness: Brightness.dark,
    surface: SyncColors.card,
    primary: SyncColors.link,
    error: SyncColors.danger,
  );
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: SyncColors.page,
    cardTheme: CardThemeData(
      color: SyncColors.card,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(8),
        side: const BorderSide(color: SyncColors.border, width: 0.5),
      ),
      margin: EdgeInsets.zero,
    ),
    expansionTileTheme: const ExpansionTileThemeData(
      backgroundColor: SyncColors.card,
      collapsedBackgroundColor: SyncColors.page,
      collapsedIconColor: Colors.white70,
      iconColor: Colors.white70,
      textColor: Colors.white,
      collapsedTextColor: Colors.white,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(8)),
        side: BorderSide(color: SyncColors.border, width: 0.5),
      ),
      collapsedShape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(8)),
        side: BorderSide(color: SyncColors.border, width: 0.5),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: SyncColors.field,
      contentPadding:
          const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(6),
        borderSide: const BorderSide(color: SyncColors.border),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(6),
        borderSide: const BorderSide(color: SyncColors.border),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(6),
        borderSide: const BorderSide(color: SyncColors.link),
      ),
      isDense: true,
    ),
    snackBarTheme: const SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
    ),
  );
}
