import 'package:flutter/material.dart';

/// TMS 司机端主题（对齐 driver-app-prototype.html 配色）。
class TmsTheme {
  static const Color accent = Color(0xFF2563EB);   // 主蓝
  static const Color accentDark = Color(0xFF1D4ED8);
  static const Color accent2 = Color(0xFFEA580C);  // 橙（强调/进行中）
  static const Color ok = Color(0xFF15803D);        // 绿（完成）
  static const Color bad = Color(0xFFDC2626);       // 红（异常/拒收）
  static const Color returnPurple = Color(0xFF7C3AED); // 紫（退货回收）
  static const Color bg = Color(0xFFF5F6F9);
  static const Color card = Colors.white;
  static const Color ink = Color(0xFF1A1C23);
  static const Color muted = Color(0xFF6B7280);
  static const Color rule = Color(0xFFDDE1E6);

  // 兼容旧引用
  static const Color primary = accent;
  static const Color primaryDark = accentDark;
  static const Color success = ok;
  static const Color warning = accent2;
  static const Color danger = bad;
  static const Color text = ink;
  static const Color textSecondary = muted;
  static const Color textMuted = Color(0xFFA0A7B2);
  static const Color border = rule;
  static const Color primaryLight = Color(0xFFDBEAFE);
  static const Color accentLight = primaryLight;

  static ThemeData get light => ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: accent),
        scaffoldBackgroundColor: bg,
        appBarTheme: const AppBarTheme(
          backgroundColor: accent,
          foregroundColor: Colors.white,
          elevation: 0,
          centerTitle: false,
        ),
        primaryColor: accent,
      );
}
