import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/api_service.dart';

// ============================================================
// 通用异常上报（P3-4）
// ============================================================

/// 异常类型选项。
class ExceptionType {
  final String code;
  final String name;

  /// 该类型是否与车辆直接相关（车辆故障/交通事故）。
  /// 命中时页面会强调车牌，避免调度员拿到一条不知道是哪台车出事的记录。
  final bool needVehicle;

  const ExceptionType({
    required this.code,
    required this.name,
    this.needVehicle = false,
  });

  factory ExceptionType.fromJson(Map<String, dynamic> j) => ExceptionType(
        code: j['code']?.toString() ?? '',
        name: j['name']?.toString() ?? '',
        needVehicle: j['needVehicle'] == true,
      );
}

/// 异常上报配置（类型字典 + 是否必须拍照 + 紧急类型）。
class ExceptionConfig {
  final List<ExceptionType> types;
  final bool photoRequired;
  final Set<String> urgentTypes;

  const ExceptionConfig({
    this.types = const [],
    this.photoRequired = true,
    this.urgentTypes = const {'TRAFFIC_ACCIDENT', 'VEHICLE_FAULT'},
  });

  bool isUrgent(String code) => urgentTypes.contains(code);
}

/// 兜底类型清单。
///
/// 为什么要在客户端留一份：异常上报的使用时机恰恰是网络最差的时候
/// （事故现场、地下车库、偏远门店）。若类型字典只能联网获取，
/// 拉不到就渲染出一个空下拉框，等于在最需要上报时把功能锁死。
/// 与后端 EXCEPTION_TYPES 保持一致，联网成功后会被服务端返回覆盖。
const _fallbackTypes = [
  ExceptionType(code: 'VEHICLE_FAULT', name: '车辆故障', needVehicle: true),
  ExceptionType(code: 'TRAFFIC_ACCIDENT', name: '交通事故', needVehicle: true),
  ExceptionType(code: 'GOODS_DAMAGE', name: '货物破损'),
  ExceptionType(code: 'STORE_CLOSED', name: '门店关门'),
  ExceptionType(code: 'WEATHER', name: '天气阻断'),
  ExceptionType(code: 'ROAD_BLOCKED', name: '道路管控'),
  ExceptionType(code: 'OTHER', name: '其他异常'),
];

/// 异常上报配置（在线拉取，失败时退回内置字典）。
final exceptionConfigProvider =
    FutureProvider.autoDispose<ExceptionConfig>((ref) async {
  try {
    final data = await ApiService.instance.post('/tms/app/exception/options');
    final d = data as Map<String, dynamic>;
    final types = (d['types'] as List? ?? [])
        .map((e) => ExceptionType.fromJson(e as Map<String, dynamic>))
        .where((t) => t.code.isNotEmpty)
        .toList();
    final urgent =
        (d['urgentTypes'] as List? ?? []).map((e) => e.toString()).toSet();
    return ExceptionConfig(
      types: types.isEmpty ? _fallbackTypes : types,
      photoRequired: d['photoRequired'] != false,
      urgentTypes: urgent.isEmpty
          ? const {'TRAFFIC_ACCIDENT', 'VEHICLE_FAULT'}
          : urgent,
    );
  } catch (_) {
    return const ExceptionConfig(types: _fallbackTypes);
  }
});

/// 异常上报提交参数。
class ReportExceptionArgs {
  final String exceptionType;
  final String description;
  final String title;
  final String dispatchId;
  final String detailId;
  final String receiptNo;
  final String customerName;
  final String vehicleNo;
  final String locationAddress;
  final String remark;
  final double? longitude;
  final double? latitude;
  final double? accuracy;

  /// 现场照片 URL（离线时是本地占位，由同步服务补传）。
  final List<String> photos;

  /// 司机点下「提交」的时刻。
  /// 离线补传时服务端的入库时间可能晚几小时，事故追溯必须用现场时间。
  final String reportedAt;

  const ReportExceptionArgs({
    required this.exceptionType,
    required this.description,
    this.title = '',
    this.dispatchId = '',
    this.detailId = '',
    this.receiptNo = '',
    this.customerName = '',
    this.vehicleNo = '',
    this.locationAddress = '',
    this.remark = '',
    this.longitude,
    this.latitude,
    this.accuracy,
    this.photos = const [],
    required this.reportedAt,
  });

  Map<String, dynamic> toJson() => {
        'exceptionType': exceptionType,
        'description': description,
        if (title.isNotEmpty) 'title': title,
        if (dispatchId.isNotEmpty) 'dispatchId': dispatchId,
        if (detailId.isNotEmpty) 'detailId': detailId,
        if (receiptNo.isNotEmpty) 'receiptNo': receiptNo,
        if (customerName.isNotEmpty) 'customerName': customerName,
        if (vehicleNo.isNotEmpty) 'vehicleNo': vehicleNo,
        if (locationAddress.isNotEmpty) 'locationAddress': locationAddress,
        if (remark.isNotEmpty) 'remark': remark,
        if (longitude != null) 'longitude': longitude,
        if (latitude != null) 'latitude': latitude,
        if (accuracy != null) 'accuracy': accuracy,
        'reportedAt': reportedAt,
      };
}

/// 提交异常上报（离线感知，优先级 1）。
///
/// 优先级高于签收（2）：车辆故障、交通事故属于「越早知道越能减损」的信息，
/// 调度员据此改派后续门店、安排救援。一批签收晚传半小时只是数据滞后，
/// 一起事故晚报半小时可能让后面五家门店全部空等。
///
/// 照片随主单一起提交，理由同改派返仓/客户拒收：
/// 离线排队时 reportId 尚未生成，拆成独立请求会缺 reportId 被后端 400 拒绝，
/// 永久卡在队列里反复重试。
final reportExceptionProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, ReportExceptionArgs>((ref, args) async {
  final body = args.toJson();
  if (args.photos.isNotEmpty) {
    body['photos'] =
        args.photos.map((p) => {'url': p, 'bizType': 'EXCEPTION'}).toList();
  }
  // actionKey 用「类型+上报时刻」而不是 detailId：
  // 同一门店可能先报门店关门、再报货物破损，用 detailId 会被去重覆盖掉一条。
  final data = await ApiService.instance.enqueueOrPost(
    actionType: 'EXCEPTION_REPORT',
    actionKey: '${args.exceptionType}_${args.reportedAt}',
    path: '/tms/app/exception/create',
    body: body,
    priority: 1,
  );
  return data as Map<String, dynamic>;
});

/// 本司机异常上报记录（含调度员处理进度）。
final exceptionListProvider =
    FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final data = await ApiService.instance.post('/tms/app/exception/list');
  final d = data as Map<String, dynamic>;
  return {
    'list': (d['list'] as List? ?? []).cast<Map<String, dynamic>>(),
    'count': d['count'] ?? 0,
    'pendingCount': d['pendingCount'] ?? 0,
  };
});
