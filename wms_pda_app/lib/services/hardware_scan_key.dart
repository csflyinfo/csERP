import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../widgets/global_scan_sheet.dart';

/// PDA 侧边硬件扫码键全局监听（方案 V1.1 优化项一）。
///
/// 仓库 PDA 机身两侧的扫码键在 Android 层常映射为
/// KEYCODE_BUTTON_L1 / BUTTON_R1（部分机型为 CAMERA），
/// 本服务用 [HardwareKeyboard] 全局处理器拦截按键：
/// - 按下侧边键 → 弹出全局扫码面板（与点首页 FAB 行为一致）；
/// - 面板已打开时不重复弹（防抖）；
/// - 其余按键一律放行，不影响音量/返回等系统行为。
///
/// 用法：登录后 [attach]（一般在 HomePage）；退出登录 [detach]。
class HardwareScanKey {
  HardwareScanKey._();
  static final HardwareScanKey instance = HardwareScanKey._();

  bool _attached = false;
  bool _sheetOpen = false;

  /// 侧边扫码键（不同 PDA 厂商映射有差异，这里用逻辑键统一兜底）。
  static final _codes = <LogicalKeyboardKey>{
    LogicalKeyboardKey.gameButtonLeft1,
    LogicalKeyboardKey.gameButtonRight1,
    LogicalKeyboardKey.gameButtonLeft2,
    LogicalKeyboardKey.gameButtonRight2,
    LogicalKeyboardKey.camera,
  };

  /// 挂载全局监听。重复调用安全。
  void attach() {
    if (_attached) return;
    HardwareKeyboard.instance.addHandler(_handler);
    _attached = true;
  }

  /// 移除监听。
  void detach() {
    if (!_attached) return;
    HardwareKeyboard.instance.removeHandler(_handler);
    _attached = false;
  }

  /// 全局按键回调：命中目标键的 KeyDown 即弹扫码面板，返回 true 表示已消费。
  bool _handler(KeyEvent event) {
    if (event is! KeyDownEvent) return false;
    if (!_codes.contains(event.logicalKey)) return false;
    final ctx = _rootContext();
    if (ctx == null) return false;
    if (_sheetOpen) return true;
    _sheetOpen = true;
    showGlobalScanSheet(ctx).whenComplete(() => _sheetOpen = false);
    return true;
  }

  /// 从全局 Navigator 取最上层 context。
  BuildContext? _rootContext() =>
      _navKey?.currentState?.overlay?.context;

  /// 由 main.dart 注入全局 navigatorKey，保证任何页面都能取到 context。
  static GlobalKey<NavigatorState>? _navKey;
  static void bindNavigatorKey(GlobalKey<NavigatorState> key) {
    _navKey = key;
  }
}
