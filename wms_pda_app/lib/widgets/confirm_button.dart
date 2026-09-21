import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../config/glove_mode.dart';
import '../theme/pda_theme.dart';

/// 关键操作确认按钮（方案 V1.1 优化项三）。
///
/// 防误触两段式交互：
/// 1. 第一次点击 → 按钮变为 [armLabel]（如"再点确认"），
///    并启动 [armWindow] 倒计时；
/// 2. 倒计时窗口内再次点击 → 触发 [onConfirm]；
/// 3. 超时未点 → 自动恢复，需重新开始；
/// 4. [onConfirm] 执行（返回 Future）期间按钮显示 loading 且禁用，
///    天然防重复提交。
///
/// 用于报损、装车发运、提交审核等"一旦执行代价高"的操作。
class ConfirmButton extends StatefulWidget {
  /// 按钮初始文案。
  final String label;

  /// 首次点击后的文案。
  final String armLabel;

  /// 确认回调；返回的 Future 完成前按钮保持 loading。
  final FutureOr<void> Function() onConfirm;

  /// 等待二次确认的时间窗。
  final Duration armWindow;

  /// 视觉样式。
  final Color color;

  final IconData icon;

  /// 是否撑满宽度。
  final bool fullWidth;

  const ConfirmButton({
    super.key,
    required this.label,
    required this.onConfirm,
    this.armLabel = '再点一次确认',
    this.armWindow = const Duration(seconds: 3),
    this.color = PdaTheme.danger,
    this.icon = Icons.warning_amber_rounded,
    this.fullWidth = true,
  });

  @override
  State<ConfirmButton> createState() => _ConfirmButtonState();
}

class _ConfirmButtonState extends State<ConfirmButton> {
  bool _armed = false;
  bool _busy = false;
  Timer? _timer;

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  void _tap() {
    if (_busy) return;
    if (!_armed) {
      // 第一次：进入待确认态，震动提示，启动超时回退
      HapticFeedback.mediumImpact();
      setState(() => _armed = true);
      _timer?.cancel();
      _timer = Timer(widget.armWindow, () {
        if (mounted) setState(() => _armed = false);
      });
      return;
    }
    // 第二次：确认执行
    _timer?.cancel();
    setState(() {
      _armed = false;
      _busy = true;
    });
    Future<void> exec() async {
      await widget.onConfirm();
    }

    exec().whenComplete(() {
      if (mounted) setState(() => _busy = false);
    });
  }

  @override
  Widget build(BuildContext context) {
    final glove = GloveMode.instance;
    final armed = _armed;
    final Color bg = armed ? PdaTheme.warning : widget.color;
    final String text = _busy
        ? '处理中...'
        : armed
            ? widget.armLabel
            : widget.label;
    final btn = ElevatedButton.icon(
      style: ElevatedButton.styleFrom(
        backgroundColor: bg,
        minimumSize: Size.fromHeight(glove.buttonMinHeight),
      ),
      onPressed: _busy ? null : _tap,
      icon: _busy
          ? const SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(
                  strokeWidth: 2, color: Colors.white),
            )
          : Icon(armed ? Icons.help_outline_rounded : widget.icon),
      label: Text(text,
          style: const TextStyle(fontWeight: FontWeight.w600)),
    );
    return widget.fullWidth ? btn : btn;
  }
}
