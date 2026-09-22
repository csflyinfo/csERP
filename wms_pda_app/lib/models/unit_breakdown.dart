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
}

/// 多单位拆分结果：一个最小单位总量被拆成"大件 + 零头"。
///
/// 如最小单位 39 瓶、1 件=12 瓶 → 3 件 + 3 瓶。
/// 支持任意级数（件 / 中包装 / 最小单位）。
class UnitBreakdown {
  /// 从大到小的单位数量（与 [units] 同序，末位为最小单位零头）。
  final List<num> amounts;

  /// 从大到小的单位定义（末位为最小单位，convertQty=1）。
  final List<UnitLevel> units;

  const UnitBreakdown({required this.amounts, required this.units});

  /// 解析商品 unit_config（JSON 字符串或 List），返回从大到小、
  /// 仅含启用单位的层级，并自动补全最小单位。
  ///
  /// [baseUnit] 为商品 base_unit，用于 unit_config 缺失最小单位时兜底；
  /// 返回的列表末位一定是 convertQty=1 的最小单位。
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
    for (final e in list) {
      if (e is Map) {
        if (e['enabled'] == false) continue;
        levels.add(UnitLevel.fromJson(Map<String, dynamic>.from(e)));
      }
    }
    // unit_config 按"小→大"存储，统一反转为"大→小"便于拆分
    levels.sort((a, b) => b.convertQty.compareTo(a.convertQty));
    // 确保末位是最小单位（convertQty=1）
    if (levels.isEmpty || levels.last.convertQty != 1) {
      final smallest = baseUnit.isNotEmpty
          ? baseUnit
          : (levels.isNotEmpty ? levels.last.name : '');
      levels.add(UnitLevel(name: smallest, convertQty: 1));
    }
    return levels;
  }

  /// 把最小单位总量拆成"大单位 + 零头"。
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
