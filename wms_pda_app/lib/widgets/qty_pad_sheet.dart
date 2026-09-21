import 'package:flutter/material.dart';
import '../theme/pda_theme.dart';
import 'common.dart';

/// 大按钮数字键盘 —— BottomSheet 弹层。
///
/// PDA 手持端使用系统软键盘有三大痛点:
///   1. 弹出后遮挡约 40% 屏幕,影响商品信息核对
///   2. 数字键过小(系统键盘 30~36px),手持按压易误触
///   3. 多单位换算无视觉对照(收 5 箱 = 多少个?)
///
/// 本组件用 4x4 大按钮(64 高 / fontSize 24)解决以上问题,
/// 顶部实时显示当前数量 + 大单位 + 换算后的基本单位数量。
///
/// 用法:
///   final v = await QtyPadSheet.show(
///     context,
///     title: '本次实收数量',
///     initialValue: 5,
///     unit: '箱',
///     convertQty: 24,        // 1 箱 = 24 个
///     baseUnit: '个',
///   );
///   if (v != null) { /* 用户点了确认 */ }
class QtyPadSheet extends StatefulWidget {
  final String title;
  final num initialValue;
  final String unit;
  final num convertQty;
  final String baseUnit;
  final int decimals;
  final num? maxValue;
  final String? hint;

  const QtyPadSheet({
    super.key,
    required this.title,
    this.initialValue = 0,
    this.unit = '',
    this.convertQty = 1,
    this.baseUnit = '',
    this.decimals = 2,
    this.maxValue,
    this.hint,
  });

  /// 弹出键盘并等待用户确认。返回 null 表示用户取消(滑掉或点取消)。
  static Future<num?> show(
    BuildContext context, {
    required String title,
    num initialValue = 0,
    String unit = '',
    num convertQty = 1,
    String baseUnit = '',
    int decimals = 2,
    num? maxValue,
    String? hint,
  }) {
    return showModalBottomSheet<num>(
      context: context,
      isScrollControlled: true,
      backgroundColor: PdaTheme.surface,
      barrierColor: Colors.black54,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (_) => QtyPadSheet(
        title: title,
        initialValue: initialValue,
        unit: unit,
        convertQty: convertQty,
        baseUnit: baseUnit,
        decimals: decimals,
        maxValue: maxValue,
        hint: hint,
      ),
    );
  }

  @override
  State<QtyPadSheet> createState() => _QtyPadSheetState();
}

class _QtyPadSheetState extends State<QtyPadSheet> {
  late String _input;

  @override
  void initState() {
    super.initState();
    _input = _formatInitial(widget.initialValue);
  }

  String _formatInitial(num v) {
    if (v == 0) return '';
    if (v == v.toInt()) return v.toInt().toString();
    return v.toString();
  }

  /// 换算后的基本单位数量 = 当前值 × convertQty
  String get _baseQtyText {
    final cur = num.tryParse(_input.isEmpty ? '0' : _input) ?? 0;
    final base = cur * widget.convertQty;
    if (base == base.toInt()) return base.toInt().toString();
    return base.toStringAsFixed(widget.decimals);
  }

  bool get _hasConvert => widget.convertQty != 1 && widget.baseUnit.isNotEmpty;

  void _append(String s) {
    setState(() {
      // 防止出现前导 0: 0 → 5(不变成 05)
      if (_input == '0' && s != '.') {
        _input = s;
        return;
      }
      // 防止两个小数点
      if (s == '.' && _input.contains('.')) return;
      // 小数位限
      if (_input.contains('.')) {
        final dec = _input.split('.').last;
        if (dec.length >= widget.decimals) return;
      }
      _input = _input + s;
    });
  }

  void _backspace() {
    setState(() {
      if (_input.isEmpty) return;
      _input = _input.substring(0, _input.length - 1);
    });
  }

  void _clear() {
    setState(() => _input = '');
  }

  /// +1 / -1 快捷键(直接修改当前值,会保留整数)
  void _bump(int delta) {
    setState(() {
      final cur = num.tryParse(_input.isEmpty ? '0' : _input) ?? 0;
      final next = cur + delta;
      if (next < 0) return;
      _input = next == next.toInt() ? next.toInt().toString() : next.toString();
    });
  }

  void _confirm() {
    final v = num.tryParse(_input.isEmpty ? '0' : _input) ?? 0;
    if (widget.maxValue != null && v > widget.maxValue!) {
      toast(context, '不能超过 ${widget.maxValue}', error: true);
      return;
    }
    Navigator.pop(context, v);
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: EdgeInsets.only(
          left: 12,
          right: 12,
          top: 14,
          bottom: MediaQuery.of(context).viewInsets.bottom + 12,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _header(),
            const SizedBox(height: 10),
            _display(),
            const SizedBox(height: 10),
            _keypad(),
            const SizedBox(height: 10),
            _bottomBar(),
          ],
        ),
      ),
    );
  }

  Widget _header() {
    return Row(children: [
      Icon(Icons.edit, size: 20, color: PdaTheme.primary),
      const SizedBox(width: 6),
      Expanded(
        child: Text(widget.title,
            style: const TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w600,
                color: PdaTheme.textPrimary)),
      ),
      GestureDetector(
        onTap: () => Navigator.pop(context, null),
        child: const Padding(
          padding: EdgeInsets.all(4),
          child: Icon(Icons.close, size: 22, color: PdaTheme.textSecondary),
        ),
      ),
    ]);
  }

  Widget _display() {
    final cur = _input.isEmpty ? '0' : _input;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: PdaTheme.surface2,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: PdaTheme.border),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(cur,
                    style: const TextStyle(
                        fontSize: 36,
                        fontWeight: FontWeight.bold,
                        height: 1.0,
                        color: PdaTheme.textPrimary)),
                if (widget.unit.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(widget.unit, style: PdaStyles.sub),
                ],
              ],
            ),
          ),
          if (_hasConvert) ...[
            const SizedBox(width: 10),
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text('= $_baseQtyText',
                    style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                        color: PdaTheme.primary)),
                if (widget.baseUnit.isNotEmpty)
                  Text(widget.baseUnit, style: PdaStyles.sub),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _keypad() {
    final keys = <List<_KeyDef>>[
      [_digit('1'), _digit('2'), _digit('3'),
       _KeyDef.icon(Icons.backspace_outlined, _backspace)],
      [_digit('4'), _digit('5'), _digit('6'),
       _KeyDef.text('+', () => _bump(1))],
      [_digit('7'), _digit('8'), _digit('9'),
       _KeyDef.text('-', () => _bump(-1))],
      [_digit('0'), _digit('.'),
       _KeyDef.text('C', _clear),
       _KeyDef.icon(Icons.check, _confirm, primary: true)],
    ];
    return Column(
      children: keys
          .map((r) => Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Row(
                  children: r
                      .map((k) => Expanded(
                          child: Padding(
                              padding: const EdgeInsets.symmetric(horizontal: 3),
                              child: _buildKey(k))))
                      .toList(),
                ),
              ))
          .toList(),
    );
  }

  _KeyDef _digit(String d) => _KeyDef.text(d, () => _append(d));

  Widget _buildKey(_KeyDef k) {
    final isPrimary = k.primary;
    final isDanger = k.danger;
    return SizedBox(
      height: 64,
      child: Material(
        color: isPrimary
            ? PdaTheme.primary
            : (isDanger ? PdaTheme.surface2 : PdaTheme.surface2),
        borderRadius: BorderRadius.circular(10),
        child: InkWell(
          borderRadius: BorderRadius.circular(10),
          onTap: k.onTap,
          child: Container(
            alignment: Alignment.center,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(10),
              border: isPrimary ? null : Border.all(color: PdaTheme.border),
            ),
            child: k.icon != null
                ? Icon(k.icon,
                    size: 26,
                    color: isPrimary ? Colors.white : PdaTheme.textPrimary)
                : Text(k.label!,
                    style: TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.w600,
                        color: isPrimary
                            ? Colors.white
                            : PdaTheme.textPrimary)),
          ),
        ),
      ),
    );
  }

  Widget _bottomBar() {
    return Row(children: [
      Expanded(
        child: SizedBox(
          height: 48,
          child: OutlinedButton(
            onPressed: () => Navigator.pop(context, null),
            child: const Text('取消'),
          ),
        ),
      ),
      const SizedBox(width: 10),
      Expanded(
        flex: 2,
        child: SizedBox(
          height: 48,
          child: ElevatedButton(
            onPressed: _confirm,
            child: const Text('确认'),
          ),
        ),
      ),
    ]);
  }
}

class _KeyDef {
  final String? label;
  final IconData? icon;
  final VoidCallback onTap;
  final bool primary;
  final bool danger;

  const _KeyDef._({
    this.label,
    this.icon,
    required this.onTap,
    this.primary = false,
    this.danger = false,
  });

  factory _KeyDef.text(String label, VoidCallback onTap,
          {bool primary = false, bool danger = false}) =>
      _KeyDef._(label: label, onTap: onTap, primary: primary, danger: danger);

  factory _KeyDef.icon(IconData icon, VoidCallback onTap,
          {bool primary = false, bool danger = true}) =>
      _KeyDef._(icon: icon, onTap: onTap, primary: primary, danger: danger);
}
