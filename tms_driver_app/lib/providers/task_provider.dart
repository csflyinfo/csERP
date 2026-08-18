import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/task.dart';
import '../models/return_order.dart';
import '../services/api_service.dart';
import '../services/connectivity_service.dart';
import '../services/local_db_service.dart';

/// 今日任务状态（使用 AsyncNotifier，支持手动刷新 + 离线缓存）。
final todayTasksProvider =
    AsyncNotifierProvider<TodayTasksNotifier, TodayTasks>(TodayTasksNotifier.new);

class TodayTasksNotifier extends AsyncNotifier<TodayTasks> {
  @override
  Future<TodayTasks> build() async {
    return await _load();
  }

  Future<TodayTasks> _load() async {
    // 在线：从后端拉取并缓存到本地
    if (ConnectivityService.instance.isOnline) {
      try {
        final data = await ApiService.instance.post('/tms/app/today-tasks');
        final tasks = TodayTasks.fromJson(data as Map<String, dynamic>);
        // 缓存到本地 SQLite
        await _cacheTasks(tasks);
        return tasks;
      } catch (e) {
        // 在线但拉取失败，尝试从本地缓存读取
        final cached = await _loadCached();
        if (cached != null) return cached;
        rethrow;
      }
    } else {
      // 离线：从本地缓存读取
      final cached = await _loadCached();
      if (cached != null) return cached;
      throw Exception('离线状态且无本地缓存，请联网后重试');
    }
  }

  /// 缓存任务到本地（Web 模式下跳过）。
  Future<void> _cacheTasks(TodayTasks tasks) async {
    if (!LocalDbService.instance.isInitialized) return;
    final dispatchList = <Map<String, dynamic>>[];
    for (final d in tasks.dispatches) {
      dispatchList.add({
        'dispatchId': d.dispatchId,
        'dispatchNo': d.dispatchNo,
        'status': d.status,
        'vehiclePlate': d.vehiclePlate,
        'routeLine': d.routeLine,
      });
    }
    if (dispatchList.isNotEmpty) {
      await LocalDbService.instance.cacheTasks(dispatchList);
    }
  }

  /// 从本地缓存读取任务（Web 模式下返回 null）。
  Future<TodayTasks?> _loadCached() async {
    if (!LocalDbService.instance.isInitialized) return null;
    final cached = await LocalDbService.instance.getCachedTasks();
    if (cached.isEmpty) return null;
    // 从缓存的 JSON 重建 TodayTasks（离线时仅展示调度单基本信息）
    return TodayTasks(
      dispatches: cached.map((d) => Dispatch.fromJson(d)).toList(),
      details: [],
      summary: TodaySummary(dispatchCount: cached.length, totalStore: 0, totalQty: 0),
    );
  }

  Future<void> refresh() async {
    state = const AsyncValue.loading();
    state = await AsyncValue.guard(_load);
  }
}

/// 退货单详情（按 applyNo 拉取）。
final returnDetailProvider =
    FutureProvider.family<ReturnOrder, String>((ref, applyNo) async {
  final data = await ApiService.instance.post('/tms/app/return/detail', body: {'applyNo': applyNo});
  return ReturnOrder.fromJson(data as Map<String, dynamic>);
});

/// 退货签收结果（离线感知，优先级 2）。
final returnSignProvider =
    FutureProvider.family<Map<String, dynamic>, ReturnSignArgs>((ref, args) async {
  final data = await ApiService.instance.enqueueOrPost(
    actionType: 'RETURN_SIGN',
    actionKey: args.applyNo,
    path: '/tms/app/return/sign',
    body: args.toJson(),
    priority: 2,
  );
  return data as Map<String, dynamic>;
});

class ReturnSignArgs {
  final String applyNo;
  final List<Map<String, dynamic>> items; // [{detailId, goodsCode, signedQty}]
  final String customerSigner;
  final String remark;
  final List<String> photos; // 照片 URL 列表
  final String? signature; // 签名图 URL
  ReturnSignArgs({
    required this.applyNo,
    required this.items,
    this.customerSigner = '',
    this.remark = '',
    this.photos = const [],
    this.signature,
  });

  Map<String, dynamic> toJson() => {
        'applyNo': applyNo,
        'items': items,
        'customerSigner': customerSigner,
        'remark': remark,
        'photos': photos,
        if (signature != null) 'signatureUrl': signature,
      };
}

/// 历史任务查询条件。
///
/// 必须实现 == / hashCode：FutureProvider.family 以参数为缓存键，
/// 若用默认引用相等，每次 build 生成新实例都会触发重复请求。
class TripHistoryArgs {
  final String status; // ALL / COMPLETED / DELIVERING ...
  final int days; // 近 N 天，dateFrom/dateTo 为空时生效
  final String dateFrom;
  final String dateTo;
  const TripHistoryArgs({
    this.status = 'ALL',
    this.days = 30,
    this.dateFrom = '',
    this.dateTo = '',
  });

  Map<String, dynamic> toJson() => {
        'status': status,
        'days': days,
        if (dateFrom.isNotEmpty) 'dateFrom': dateFrom,
        if (dateTo.isNotEmpty) 'dateTo': dateTo,
        'pageSize': 200,
      };

  @override
  bool operator ==(Object other) =>
      other is TripHistoryArgs &&
      other.status == status &&
      other.days == days &&
      other.dateFrom == dateFrom &&
      other.dateTo == dateTo;

  @override
  int get hashCode => Object.hash(status, days, dateFrom, dateTo);
}

/// 历史配送任务（仅本人，默认近 30 天）。
///
/// 离线时后端不可达：直接抛错由 UI 展示重试，历史数据不做本地缓存
/// （查询维度组合多，缓存收益低且易读到脏数据）。
final tripHistoryProvider =
    FutureProvider.family<TripHistoryPage, TripHistoryArgs>((ref, args) async {
  if (!ConnectivityService.instance.isOnline) {
    throw Exception('当前处于离线状态，历史记录需联网查询');
  }
  final data = await ApiService.instance.post('/tms/app/trip/history', body: args.toJson());
  return TripHistoryPage.fromJson(data as Map<String, dynamic>);
});

/// 司机绩效统计（「我的」页）。
///
/// days = 0 表示累计至今；与历史记录同理不做离线缓存，
/// 统计值有时效性，展示过期数字比展示「需联网」更容易误导。
final driverStatsProvider =
    FutureProvider.family<DriverStats, int>((ref, days) async {
  if (!ConnectivityService.instance.isOnline) {
    throw Exception('当前处于离线状态，统计数据需联网查询');
  }
  final data = await ApiService.instance.post('/tms/app/driver/stats', body: {'days': days});
  return DriverStats.fromJson(data as Map<String, dynamic>);
});

/// 收款记录查询条件。
///
/// 同 TripHistoryArgs：family 以参数为缓存键，必须实现 == / hashCode，
/// 否则每次 build 新实例都会重复打接口。
class CollectRecordArgs {
  final int days; // 近 N 天
  final String payMethod; // ALL / 现金 / 微信 / 支付宝 / 转账
  const CollectRecordArgs({this.days = 30, this.payMethod = 'ALL'});

  Map<String, dynamic> toJson() => {
        'days': days,
        'payMethod': payMethod,
        'pageSize': 200,
      };

  @override
  bool operator ==(Object other) =>
      other is CollectRecordArgs && other.days == days && other.payMethod == payMethod;

  @override
  int get hashCode => Object.hash(days, payMethod);
}

/// 收款记录（「我的 → 收款记录」）。
///
/// 与统计卡的「累计收款」区别：那是一个总数，这里是可逐笔核对的流水。
/// 同样不做离线缓存——收款是钱的事，宁可提示需联网也不能让司机
/// 拿一份可能过期的账去跟调度对账。
final collectRecordsProvider =
    FutureProvider.family<CollectRecordPage, CollectRecordArgs>((ref, args) async {
  if (!ConnectivityService.instance.isOnline) {
    throw Exception('当前处于离线状态，收款记录需联网查询');
  }
  final data = await ApiService.instance.post('/tms/app/collect/records', body: args.toJson());
  return CollectRecordPage.fromJson(data as Map<String, dynamic>);
});

/// 调度中心联系方式（来自 TMS_DISPATCHER_PHONE 参数，随 /profile 返回）。
///
/// 取不到时返回空号码而非抛错：这只是「我的」页一个附属入口，
/// 不该因为参数未配置就让整页报错。
final dispatcherContactProvider = FutureProvider<({String name, String phone})>((ref) async {
  try {
    final data = await ApiService.instance.post('/tms/app/profile') as Map<String, dynamic>;
    return (
      name: data['dispatcherName']?.toString() ?? '调度中心',
      phone: data['dispatcherPhone']?.toString() ?? '',
    );
  } catch (_) {
    return (name: '调度中心', phone: '');
  }
});
