import 'package:flutter/material.dart';
import '../models/unit_breakdown.dart';
import '../theme/pda_theme.dart';

/// 只读多单位数量展示。
///
/// 把最小单位总量按层级拆成"大件 + 零头"内联展示，
/// 用于应收/已收等不可编辑数字，如 "3 件 3 瓶"。
class UnitQtyText extends StatelessWidget {
  final num value;
  final dynamic unitConfig;
  final String baseUnit;

  /// 旧入参兼容：大单位 + 换算率。
  final String unit;
  final num convertQty;

  /// 数字样式（默认醒目绿色大字）。
  final TextStyle? numberStyle;

  /// 单位样式。
  final TextStyle? unitStyle;

  const UnitQtyText({
    super.key,
    required this.value,
    this.unitConfig,
    this.baseUnit = '',
    this.unit = '',
    this.convertQty = 1,
    this.numberStyle,
    this.unitStyle,
  });

  List<UnitLevel> _units() {
    if (unitConfig != null && !(unitConfig is String && (unitConfig as String).isEmpty)) {
      final l = UnitBreakdown.parseUnits(unitConfig, baseUnit: baseUnit);
      if (l.length > 1) return l;
    }
    final list = <UnitLevel>[];
    if (unit.isNotEmpty && convertQty > 1) {
      list.add(UnitLevel(name: unit, convertQty: convertQty));
    }
    list.add(UnitLevel(
        name: baseUnit.isNotEmpty ? baseUnit : unit, convertQty: 1));
    // ????????????? unit_name???"39 "????
    final fallback = baseUnit.isNotEmpty ? baseUnit : unit;
    return UnitBreakdown.fillEmptyNames(list, fallback);
  }

  @override
  Widget build(BuildContext context) {
    final units = _units();
    final bd = UnitBreakdown.split(value, units);
    final ns = numberStyle ??
        PdaStyles.numHuge.copyWith(color: PdaTheme.primary);
    final us = unitStyle ?? PdaStyles.sub;
    final parts = <Widget>[];
    var any = false;
    for (var i = 0; i < units.length; i++) {
      final a = bd.amounts[i];
      if (a != 0) any = true;
      final qtyText = a == a.toInt() ? a.toInt().toString() : a.toString();
      if (parts.isNotEmpty) parts.add(const SizedBox(width: 8));
      parts.add(Text(qtyText, style: ns));
      parts.add(const SizedBox(width: 2));
      parts.add(Text(units[i].name, style: us));
    }
    if (!any) {
      parts
        ..add(Text('0', style: ns))
        ..add(const SizedBox(width: 2))
        ..add(Text(units.isNotEmpty ? units.last.name : '', style: us));
    }
    return Wrap(
      crossAxisAlignment: WrapCrossAlignment.center,
      children: parts,
    );
  }
}
