import 'dart:convert';

/// 后端下发的 APP 运行参数快照（PRD-26 §5.5）。
///
/// APP 不直连参数表，参数随司机登录响应下发、也可通过 `/tms/app/params` 单独刷新。
/// 本类刻意做成不可变对象：参数会被签收、退货、结算等多个页面同时读取，
/// 若允许就地改字段，某个页面改了值会静默影响其它页面的校验行为。
class AppParams {
  /// 签收现场照片张数下限，0 表示不校验。
  final int signPhotoCount;

  /// 退货现场照片张数下限，0 表示不校验。
  final int returnPhotoCount;

  /// 门店结算是否必须拍照。
  final bool settlePhotoRequired;

  /// 是否开放【现场退货】入口。
  final bool onsiteReturnEnabled;

  /// 是否允许送货单与退货单合并结算。
  final bool returnMergeSettle;

  /// 是否允许向已发车的调度单追加任务。
  final bool appendAfterDepart;

  /// 司机作业流程总开关。
  final bool driverFlowEnabled;

  /// 签收页是否展示并强制电子签名（false 时整块签名区不渲染）。
  final bool signEsignRequired;

  /// 交账页是否展示并强制电子签名（false 时整块签名区不渲染）。
  final bool handoverEsignRequired;

  const AppParams({
    this.signPhotoCount = 2,
    this.returnPhotoCount = 2,
    this.settlePhotoRequired = false,
    this.onsiteReturnEnabled = true,
    this.returnMergeSettle = true,
    this.appendAfterDepart = true,
    this.driverFlowEnabled = true,
    this.signEsignRequired = false,
    this.handoverEsignRequired = false,
  });

  /// 默认值必须与后端 `TmsAuthService.appParamSnapshot()` 完全一致。
  ///
  /// 首次登录前、或旧版本缓存里缺字段时都会落到这份默认值上。
  /// 若两端默认值不同，会出现「APP 只要 1 张、后端要 2 张」这类
  /// 拍完才被拒的错位体验。
  static const AppParams defaults = AppParams();

  /// 照片张数取值范围，与后端 `getInt(key, 2, 0, 5)` 的钳制区间一致。
  static const int _photoMin = 0;
  static const int _photoMax = 5;

  factory AppParams.fromJson(Map<String, dynamic> j) => AppParams(
        signPhotoCount: _photoCount(j['signPhotoCount'], defaults.signPhotoCount),
        returnPhotoCount: _photoCount(j['returnPhotoCount'], defaults.returnPhotoCount),
        settlePhotoRequired: _bool(j['settlePhotoRequired'], defaults.settlePhotoRequired),
        onsiteReturnEnabled: _bool(j['onsiteReturnEnabled'], defaults.onsiteReturnEnabled),
        returnMergeSettle: _bool(j['returnMergeSettle'], defaults.returnMergeSettle),
        appendAfterDepart: _bool(j['appendAfterDepart'], defaults.appendAfterDepart),
        driverFlowEnabled: _bool(j['driverFlowEnabled'], defaults.driverFlowEnabled),
        signEsignRequired: _bool(j['signEsignRequired'], defaults.signEsignRequired),
        handoverEsignRequired: _bool(j['handoverEsignRequired'], defaults.handoverEsignRequired),
      );

  Map<String, dynamic> toJson() => {
        'signPhotoCount': signPhotoCount,
        'returnPhotoCount': returnPhotoCount,
        'settlePhotoRequired': settlePhotoRequired,
        'onsiteReturnEnabled': onsiteReturnEnabled,
        'returnMergeSettle': returnMergeSettle,
        'appendAfterDepart': appendAfterDepart,
        'driverFlowEnabled': driverFlowEnabled,
        'signEsignRequired': signEsignRequired,
        'handoverEsignRequired': handoverEsignRequired,
      };

  String encode() => jsonEncode(toJson());

  /// 从本地缓存字符串还原，任何解析异常都回落默认值。
  ///
  /// 缓存是上一版本 APP 写下的、或被手工改坏都可能出现结构不符，
  /// 这里绝不抛异常——参数读不出来只该退化成默认行为，不能让 APP 起不来。
  static AppParams decode(String? raw) {
    if (raw == null || raw.isEmpty) return defaults;
    try {
      final obj = jsonDecode(raw);
      if (obj is Map<String, dynamic>) return AppParams.fromJson(obj);
    } catch (_) {
      // 忽略：结构不符时按默认参数运行
    }
    return defaults;
  }

  /// 后端 bool 走 JSON 布尔下发，但兼容字符串形式的 `Y`/`true`。
  ///
  /// 兼容而非只认布尔的原因：参数值在库里存的是 `Y`/`N` 字符，
  /// 若后续有接口漏了转换直接把原值透传出来，这里能兜住而不是整片参数失效。
  static bool _bool(Object? v, bool fallback) {
    if (v == null) return fallback;
    if (v is bool) return v;
    final s = v.toString().trim();
    if (s.isEmpty) return fallback;
    return s == 'Y' || s == 'y' || s.toLowerCase() == 'true' || s == '1';
  }

  static int _photoCount(Object? v, int fallback) {
    if (v == null) return fallback;
    final n = v is num ? v.toInt() : int.tryParse(v.toString().trim());
    if (n == null) return fallback;
    if (n < _photoMin) return _photoMin;
    if (n > _photoMax) return _photoMax;
    return n;
  }
}
