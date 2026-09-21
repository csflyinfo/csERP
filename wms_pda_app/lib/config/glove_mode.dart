import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 手套模式偏好（方案 V1.1 优化项三）。
///
/// 仓库作业默认佩戴手套，开启后：
/// - 增大按钮/可点击热区（见 [scaledMinHeight]）；
/// - 放大正文字号（见 [scaledFontSize]）；
/// - 提供全局开关状态，各页面据此禁用长按/多点等易误触手势。
///
/// 状态存在 SharedPreferences，并提供 [ChangeNotifier]，
/// 设置页切换后所有监听页面立即重建。
class GloveMode extends ChangeNotifier {
  GloveMode._();
  static final GloveMode instance = GloveMode._();

  static const _key = 'wms_pda_glove_mode';

  bool _on = true;

  /// 是否开启手套模式（默认开启）。
  bool get on => _on;

  /// 从本地存储加载。
  Future<void> load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      _on = prefs.getBool(_key) ?? true;
      notifyListeners();
    } catch (_) {
      _on = true;
    }
  }

  /// 设置开关并持久化。
  Future<void> setOn(bool value) async {
    _on = value;
    notifyListeners();
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool(_key, value);
    } catch (_) {}
  }

  /// 按钮最小高度：手套模式加大热区（约 18mm），普通模式 48（约 15mm）。
  double get buttonMinHeight => _on ? 58 : 48;

  /// 正文字号基准：手套模式 16，普通 14。
  double get scaledFontSize => _on ? 16 : 14;
}

/// GloveMode 的便捷读取扩展：context.glove。
extension GloveContext on BuildContext {
  GloveMode get glove => GloveMode.instance;
}
