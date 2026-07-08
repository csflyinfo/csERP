import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/driver_return.dart';
import '../services/api_service.dart';

/// 司机回收任务列表（待回收 + 已回收待返仓）。
final returnTaskListProvider =
    FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final data = await ApiService.instance.post('/tms/app/return/list');
  final d = data as Map<String, dynamic>;
  return {
    'pendingRecycle': (d['pendingRecycle'] as List? ?? [])
        .map((e) => PendingRecycleTask.fromJson(e as Map<String, dynamic>))
        .toList(),
    'loadedReturn': (d['loadedReturn'] as List? ?? [])
        .map((e) => LoadedReturn.fromJson(e as Map<String, dynamic>))
        .toList(),
    'summary': d['summary'] ?? {},
  };
});

/// 商品搜索（按关键字模糊查询）。
final goodsSearchProvider =
    FutureProvider.autoDispose.family<List<GoodsSearchResult>, String>((ref, keyword) async {
  if (keyword.isEmpty) return [];
  final data = await ApiService.instance.post('/tms/app/return/goods-search', body: {'keyword': keyword});
  final list = data as List? ?? [];
  return list
      .map((e) => GoodsSearchResult.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// 创建司机现场退货参数。
class CreateReturnArgs {
  final String customerCode;
  final String customerName;
  final String warehouse;
  final String returnReason;
  final String remark;
  final String? dispatchId;
  final String? tripId;
  final List<ReturnGoodsItem> items;
  final List<String> photos; // 照片 URL 列表

  CreateReturnArgs({
    required this.customerCode,
    required this.customerName,
    required this.warehouse,
    this.returnReason = '',
    this.remark = '',
    this.dispatchId,
    this.tripId,
    required this.items,
    this.photos = const [],
  });

  Map<String, dynamic> toJson() => {
        'customerCode': customerCode,
        'customerName': customerName,
        'warehouse': warehouse,
        'returnReason': returnReason,
        'remark': remark,
        if (dispatchId != null) 'dispatchId': dispatchId,
        if (tripId != null) 'tripId': tripId,
        'items': items.map((e) => e.toJson()).toList(),
      };
}

/// 创建司机现场退货（一次性调用）。
final createReturnProvider =
    FutureProvider.autoDispose.family<Map<String, dynamic>, CreateReturnArgs>((ref, args) async {
  final data = await ApiService.instance.post('/tms/app/return/create', body: args.toJson());
  final result = data as Map<String, dynamic>;
  // 创建成功后上传照片
  final driverReturnId = result['driverReturnId']?.toString() ?? '';
  if (driverReturnId.isNotEmpty && args.photos.isNotEmpty) {
    await ApiService.instance.post('/tms/app/return/upload-photo', body: {
      'driverReturnId': driverReturnId,
      'photos': args.photos.map((p) => {'url': p, 'photoType': 'GOODS'}).toList(),
    });
  }
  return result;
});

/// 返仓交接清单。
final warehouseReturnListProvider =
    FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final data = await ApiService.instance.post('/tms/app/warehouse-return/list');
  final d = data as Map<String, dynamic>;
  return {
    'list': (d['list'] as List? ?? [])
        .map((e) => LoadedReturn.fromJson(e as Map<String, dynamic>))
        .toList(),
    'count': d['count'] ?? 0,
    'totalQty': d['totalQty'] ?? 0,
  };
});

/// 返仓交接确认。
final warehouseReturnConfirmProvider =
    FutureProvider.autoDispose.family<Map<String, dynamic>, List<String>>((ref, ids) async {
  final data = await ApiService.instance.post('/tms/app/warehouse-return/confirm', body: {
    'driverReturnIds': ids,
  });
  return data as Map<String, dynamic>;
});
