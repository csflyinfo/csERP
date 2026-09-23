import 'dart:convert';

/// 一级单位定义（从商品 unit_config 解析）。
class UnitLevel {
  /// 单位名称，如 瓶 / 件。
  final String name;

  /// 到最小单位的换算率：1 个本单位 = [convertQty] 个最小单位。
  final num convertQty;

  const UnitLevel({required this.name, required this.convertQty});

  factory UnitLevel.fromJson(Map<String, dynamic> j) {
    final name = (j['unitName'] ?? j['name'] ?? '').toString();
    final cq = num.tryParse('${j['convertQty'] ?? 1}') ?? 1;
    return UnitLevel(name: name, convertQty: cq <= 0 ? 1 : cq);
  }

  UnitLevel copyWith({String? name}) =>
      UnitLevel(name: name ?? this.name, convertQty: convertQty);
}

/// 多单位拆分结果：一个最小单位总量被拆成"大件 + 零头"。
///
/// 如最小单位 39 瓶、1 件=12 瓶 → 3 件 + 3 瓶。
/// 支持任意级数与任意存储顺序（按换算率识别层级，不依赖数组物理顺序）。
class UnitBreakdown {
  /// 从大到小的单位数量（与 [units] 同序，末位为最小单位零头）。
  final List<num> amounts;

  /// 从大到小的单位定义（末位为最小单位，convertQty=1）。
  final List<UnitLevel> units;

  const UnitBreakdown({required this.amounts, required this.units});

  /// 解析商品 unit_config（JSON 字符串或 List），返回从大到小、
  /// 仅含启用单位的层级，并自动补全最小单位。
  ///
  /// 规则（健壮性）：
  /// - `enabled == false` 显式禁用的单位跳过；
  /// - 换算率 < 1 的非法单位跳过；
  /// - 按换算率从大到小识别层级，故「小/大/中」「小/中/大」均兼容；
  /// - 相同换算率只保留一个（去重）；
  /// - 末位一定补一个 convertQty=1 的最小单位。
  ///
  /// [baseUnit] 为商品 base_unit，用于最小单位缺失/无名时兜底。
  static List<UnitLevel> parseUnits(dynamic raw, {String baseUnit = ''}) {
    List list;
    if (raw is List) {
      list = raw;
    } else if (raw is String && raw.trim().isNotEmpty) {
      try {
        final d = json.decode(raw);
        list = d is List ? d : const [];
      } catch (_) {
        list = const [];
      }
    } else {
      list = const [];
    }

    final levels = <UnitLevel>[];
    final seenRates = <num>{};
    for (final e in list) {
      if (e is! Map) continue;
      if (e['enabled'] == false) continue;
      final lv = UnitLevel.fromJson(Map<String, dynamic>.from(e));
      if (lv.convertQty < 1) continue;
      // 相同换算率去重（保留先出现的）
      if (!seenRates.add(lv.convertQty)) continue;
      levels.add(lv);
    }

    // 按换算率降序：统一为 大→小，兼容任意存储顺序
    levels.sort((a, b) => b.convertQty.compareTo(a.convertQty));

    // 去掉末尾已存在的最小单位后，保证末位为 convertQty=1
    levels.removeWhere((l) => l.convertQty == 1);
    final smallestName = baseUnit.isNotEmpty
        ? baseUnit
        : (levels.isNotEmpty ? '' : '');
    levels.add(UnitLevel(name: smallestName, convertQty: 1));
    return levels;
  }

  /// 用 [fallback] 填充所有空单位名（典型：明细行 unit_name），
  /// 避免出现"39 "后无单位的光秃显示。不会覆盖已有名称。
  static List<UnitLevel> fillEmptyNames(
      List<UnitLevel> units, String fallback) {
    final fb = fallback.trim();
    return [
      for (final u in units)
        u.name.trim().isEmpty ? u.copyWith(name: fb) : u,
    ];
  }

  /// 把最小单位总量拆成"大单位 + 零头"。换算率为整数倍时逐级取整。
  static UnitBreakdown split(num total, List<UnitLevel> units) {
    var remain = total;
    final amounts = <num>[];
    for (var i = 0; i < units.length; i++) {
      final rate = units[i].convertQty;
      if (i == units.length - 1) {
        amounts.add(_trim(remain));
      } else if (rate > 0 && remain >= rate) {
        final count = (remain / rate).floor();
        amounts.add(count);
        remain = _trim(remain - count * rate);
      } else {
        amounts.add(0);
      }
    }
    return UnitBreakdown(amounts: amounts, units: units);
  }

  /// 把各级数量组合回最小单位总量。
  num get total {
    num sum = 0;
    for (var i = 0; i < units.length; i++) {
      sum += amounts[i] * units[i].convertQty;
    }
    return _trim(sum);
  }

  /// 显示文本，如 "3 件 3 瓶"；全 0 时显示 "0{最小单位}"。
  String format({String separator = ' '}) {
    final parts = <String>[];
    var any = false;
    for (var i = 0; i < units.length; i++) {
      final a = amounts[i];
      if (a == 0) continue;
      any = true;
      parts.add('${_trim(a)}${units[i].name}');
    }
    if (!any) return '0${units.isNotEmpty ? units.last.name : ""}';
    return parts.join(separator);
  }
}

/// 去掉整数的小数尾（3.0 → 3），便于显示。
num _trim(num v) {
  if (v == v.toInt()) return v.toInt();
  return v;
}
