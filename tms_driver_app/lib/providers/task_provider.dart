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
