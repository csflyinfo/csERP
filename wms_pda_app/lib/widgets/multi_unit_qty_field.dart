import 'package:flutter/material.dart';
import '../models/unit_breakdown.dart';
import '../theme/pda_theme.dart';
import 'qty_pad_sheet.dart';

/// 多单位数量输入字段（方案：整件收货友好）。
///
/// 把最小单位总量按商品单位层级拆成"大件 + 零头"显示，
/// 如 39 瓶 / 1件=12瓶 → [3 件] [3 瓶]；
/// 点击任意一段数字弹出大按钮键盘修改该级数量，
/// 其它段联动，最终仍以最小单位总量回调 [onChanged]。
///
/// 两种数据来源（二选一）：
/// - [unitConfig]：商品 unit_config 原始 JSON（推荐，含完整层级）；
/// - [unit] + [convertQty]：旧的单一大单位入参，自动兼容。
class MultiUnitQtyField extends StatefulWidget {
  /// 当前最小单位总量。
  final num value;

  /// 商品 unit_config（JSON 字符串或 List）。
  final dynamic unitConfig;

  /// 商品最小单位名（base_unit）。
  final String baseUnit;

  /// 旧入参：大单位名 / 到大单位换算率。
  final String unit;
  final num convertQty;

  final ValueChanged<num> onChanged;
  final String? label;
  final num? maxValue;
  final bool enabled;

  const MultiUnitQtyField({
    super.key,
    required this.value,
    required this.onChanged,
    this.unitConfig,
    this.baseUnit = '',
    this.unit = '',
    this.convertQty = 1,
    this.label,
    this.maxValue,
    this.enabled = true,
  });

  @override
  State<MultiUnitQtyField> createState() => _MultiUnitQtyFieldState();
}

class _MultiUnitQtyFieldState extends State<MultiUnitQtyField> {
  List<UnitLevel> _resolveUnits() {
    final cfg = widget.unitConfig;
    final fallback =
        widget.baseUnit.isNotEmpty ? widget.baseUnit : widget.unit;
    if (cfg != null && !(cfg is String && cfg.isEmpty)) {
      final levels = UnitBreakdown.parseUnits(cfg, baseUnit: widget.baseUnit);
      if (levels.length > 1) {
        return UnitBreakdown.fillEmptyNames(levels, fallback);
      }
    }
    final levels = <UnitLevel>[];
    if (widget.unit.isNotEmpty && widget.convertQty > 1) {
      levels.add(UnitLevel(name: widget.unit, convertQty: widget.convertQty));
    }
    levels.add(UnitLevel(
        name: widget.baseUnit.isNotEmpty ? widget.baseUnit : widget.unit,
        convertQty: 1));
    return UnitBreakdown.fillEmptyNames(levels, fallback);
  }

  late final List<UnitLevel> _units = _resolveUnits();

  Future<void> _editSegment(int index) async {
    if (!widget.enabled) return;
    final current = UnitBreakdown.split(widget.value, _units);
    final segVal = current.amounts[index];
    final lv = _units[index];
    num? segMax;
    if (widget.maxValue != null) {
      segMax = lv.convertQty <= 0
          ? widget.maxValue
          : (widget.maxValue! / lv.convertQty).floor();
    }
    final v = await QtyPadSheet.show(
      context,
      title: '${lv.name} 数量',
      initialValue: segVal,
      unit: lv.name,
      convertQty: 1,
      baseUnit: '',
      decimals: index == _units.length - 1 ? 2 : 0,
      maxValue: segMax,
    );
    if (v == null) return;
    final amounts = List<num>.from(current.amounts);
    amounts[index] = v;
    final next = UnitBreakdown(amounts: amounts, units: _units);
    widget.onChanged(next.total);
  }

  @override
  Widget build(BuildContext context) {
    final bd = UnitBreakdown.split(widget.value, _units);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: PdaTheme.surface2,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: PdaTheme.border),
      ),
      child: Row(children: [
        Expanded(
          child: Wrap(
            spacing: 10,
            runSpacing: 6,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              for (var i = 0; i < _units.length; i++)
                _Segment(
                  amount: bd.amounts[i],
                  unitName: _units[i].name,
                  enabled: widget.enabled,
                  onTap: () => _editSegment(i),
                ),
            ],
          ),
        ),
        Icon(Icons.keyboard,
            size: 18,
            color: widget.enabled
                ? PdaTheme.primary
                : PdaTheme.textSecondary),
      ]),
    );
  }
}

/// 一段"数字 + 单位"，数字可点。
class _Segment extends StatelessWidget {
  final num amount;
  final String unitName;
  final bool enabled;
  final VoidCallback onTap;

  const _Segment({
    required this.amount,
    required this.unitName,
    required this.enabled,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final numText = amount == amount.toInt()
        ? amount.toInt().toString()
        : amount.toString();
    return InkWell(
      borderRadius: BorderRadius.circular(8),
      onTap: enabled ? onTap : null,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(numText,
                style: const TextStyle(
                    fontSize: 26,
                    fontWeight: FontWeight.bold,
                    color: PdaTheme.textPrimary)),
            const SizedBox(width: 4),
            Text(unitName, style: PdaStyles.sub),
          ],
        ),
      ),
    );
  }
}
