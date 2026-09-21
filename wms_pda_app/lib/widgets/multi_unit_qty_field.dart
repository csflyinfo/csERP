import 'package:flutter/material.dart';
import '../theme/pda_theme.dart';
import 'qty_pad_sheet.dart';

/// 多单位数量输入字段 —— 点击弹 QtyPadSheet 大按钮键盘,
/// 显示当前值 + 大单位 + 换算后基本单位数量(对照展示)。
///
/// 替代原 Row(-, TextField, +) 方案,优势:
///   1. 不弹系统软键盘,避免遮挡商品信息
///   2. 大按钮(64 高 / fontSize 24)防误触
///   3. 大单位+基本单位双行对照,库管员一眼核对
///
/// 用法:
///   MultiUnitQtyField(
///     value: qty,
///     unit: '箱',
///     convertQty: 24,
///     baseUnit: '个',
///     onChanged: (v) => setState(() => qty = v),
///   )
class MultiUnitQtyField extends StatelessWidget {
  final num value;
  final String unit;
  final num convertQty;
  final String baseUnit;
  final ValueChanged<num> onChanged;
  final String? label;
  final num? maxValue;
  final bool enabled;

  const MultiUnitQtyField({
    super.key,
    required this.value,
    required this.onChanged,
    this.unit = '',
    this.convertQty = 1,
    this.baseUnit = '',
    this.label,
    this.maxValue,
    this.enabled = true,
  });

  bool get _hasConvert => convertQty != 1 && baseUnit.isNotEmpty;

  String get _valueText {
    if (value == 0) return '0';
    if (value == value.toInt()) return value.toInt().toString();
    return value.toString();
  }

  String get _baseText {
    final base = value * convertQty;
    if (base == base.toInt()) return base.toInt().toString();
    return base.toStringAsFixed(2);
  }

  Future<void> _open(BuildContext context) async {
    if (!enabled) return;
    final v = await QtyPadSheet.show(
      context,
      title: label ?? '输入数量',
      initialValue: value,
      unit: unit,
      convertQty: convertQty,
      baseUnit: baseUnit,
      maxValue: maxValue,
    );
    if (v != null) onChanged(v);
  }

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(10),
      onTap: enabled ? () => _open(context) : null,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: PdaTheme.surface2,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: PdaTheme.border),
        ),
        child: Row(children: [
          if (unit.isNotEmpty) ...[
            Text(_valueText,
                style: const TextStyle(
                    fontSize: 26,
                    fontWeight: FontWeight.bold,
                    color: PdaTheme.textPrimary)),
            const SizedBox(width: 6),
            Text(unit, style: PdaStyles.sub),
          ] else ...[
            Text(_valueText,
                style: const TextStyle(
                    fontSize: 26,
                    fontWeight: FontWeight.bold,
                    color: PdaTheme.textPrimary)),
          ],
          const Spacer(),
          if (_hasConvert) ...[
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text('= $_baseText',
                    style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: PdaTheme.primary)),
                if (baseUnit.isNotEmpty)
                  Text(baseUnit, style: PdaStyles.sub),
              ],
            ),
            const SizedBox(width: 6),
          ],
          Icon(Icons.keyboard,
              size: 18, color: enabled ? PdaTheme.primary : PdaTheme.textSecondary),
        ]),
      ),
    );
  }
}
