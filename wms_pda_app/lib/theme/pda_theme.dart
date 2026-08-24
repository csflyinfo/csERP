import 'package:flutter/material.dart';

/// PDA 暗色主题，与 prototype/wms-pda/index.html 视觉对齐。
class PdaTheme {
  PdaTheme._();

  static const Color bg = Color(0xFF0D1117);
  static const Color surface = Color(0xFF1A1F2E);
  static const Color surface2 = Color(0xFF232940);
  static const Color surface3 = Color(0xFF2A3050);
  static const Color border = Color(0xFF37405A);
  static const Color textPrimary = Color(0xFFE8ECF1);
  static const Color textSecondary = Color(0xFF8B95A8);
  static const Color primary = Color(0xFF00C48C);
  static const Color warning = Color(0xFFFF8F3F);
  static const Color danger = Color(0xFFFF5252);
  static const Color info = Color(0xFF4FC3F7);

  static ThemeData get dark {
    final base = ThemeData.dark(useMaterial3: true);
    return base.copyWith(
      scaffoldBackgroundColor: bg,
      colorScheme: base.colorScheme.copyWith(
        primary: primary,
        secondary: info,
        error: danger,
        surface: surface,
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: surface,
        foregroundColor: textPrimary,
        elevation: 0,
        centerTitle: true,
        titleTextStyle: TextStyle(
          fontSize: 18,
          fontWeight: FontWeight.w600,
          color: textPrimary,
        ),
      ),
      textTheme: base.textTheme.apply(
        bodyColor: textPrimary,
        displayColor: textPrimary,
        fontFamily: 'PingFang SC',
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: primary,
          foregroundColor: Colors.white,
          minimumSize: const Size(double.infinity, 48),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(10),
          ),
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: textPrimary,
          side: const BorderSide(color: border),
          minimumSize: const Size(double.infinity, 48),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(10),
          ),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: surface2,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: const BorderSide(color: border),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: const BorderSide(color: border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: const BorderSide(color: primary, width: 1.5),
        ),
        labelStyle: const TextStyle(color: textSecondary),
        hintStyle: const TextStyle(color: textSecondary),
      ),
      cardTheme: CardThemeData(
        color: surface,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: const BorderSide(color: border),
        ),
      ),
      dividerTheme: const DividerThemeData(color: border, thickness: 1),
    );
  }
}

/// 快捷取色 / 文本样式
class PdaStyles {
  PdaStyles._();
  static const title = TextStyle(
    fontSize: 16,
    fontWeight: FontWeight.w600,
    color: PdaTheme.textPrimary,
  );
  static const sub = TextStyle(fontSize: 12, color: PdaTheme.textSecondary);
  static const numBig = TextStyle(
    fontSize: 20,
    fontWeight: FontWeight.bold,
    color: PdaTheme.textPrimary,
  );
  static const numHuge = TextStyle(
    fontSize: 28,
    fontWeight: FontWeight.bold,
    color: PdaTheme.textPrimary,
    height: 1.1,
  );
  static const label = TextStyle(
    fontSize: 13,
    color: PdaTheme.textSecondary,
  );
  static const chip = TextStyle(
    fontSize: 11,
    fontWeight: FontWeight.w600,
  );
}

/// 间距 / 圆角常量，全 PDA 共用，避免散落字面量。
class PdaSpacing {
  PdaSpacing._();
  static const double xs = 4;
  static const double sm = 8;
  static const double md = 12;
  static const double lg = 16;
  static const double xl = 20;
  static const double radiusSm = 8;
  static const double radius = 12;
  static const double radiusLg = 16;
}
