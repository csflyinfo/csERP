import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/reschedule_reject.dart';
import '../services/api_service.dart';

// ============================================================
// 改派返仓
// ============================================================

/// 改派返仓创建参数。
class CreateRescheduleReturnArgs {
  final String dispatchId;
  final String detailId;
  final String receiptNo;
  final String reason;
  final String reasonDetail;
  final String rescheduleDate;
  final String remark;
  final List<Map<String, dynamic>> items; // [{goodsCode, goodsName, spec, unitName, plannedQty, batchNo?}]
  final List<String> photos; // 照片 URL 列表

  CreateRescheduleReturnArgs({
    required this.dispatchId,
    required this.detailId,
    required this.receiptNo,
    required this.reason,
    this.reasonDetail = '',
    this.rescheduleDate = '',
    this.remark = '',
    this.items = const [],
    this.photos = const [],
  });

  Map<String, dynamic> toJson() => {
        'dispatchId': dispatchId,
        'detailId': detailId,
        'receiptNo': receiptNo,
        'reason': reason,
        'reasonDetail': reasonDetail,
        if (rescheduleDate.isNotEmpty) 'rescheduleDate': rescheduleDate,
        if (remark.isNotEmpty) 'remark': remark,
        'items': items,
      };
}

/// 生成改派返仓单（一次性调用）。
final createRescheduleReturnProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, CreateRescheduleReturnArgs>((ref, args) async {
  final data = await ApiService.instance
      .post('/tms/app/reschedule-return/create', body: args.toJson());
  final result = data as Map<String, dynamic>;
  // 上传照片
  final returnId = result['returnId']?.toString() ?? '';
  if (returnId.isNotEmpty && args.photos.isNotEmpty) {
    await ApiService.instance.post('/tms/app/reschedule-return/upload-photo', body: {
      'returnId': returnId,
      'photos': args.photos.map((p) => {'url': p}).toList(),
    });
  }
  return result;
});

/// 改派返仓待返仓列表（本司机）。
final rescheduleReturnListProvider =
    FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final data = await ApiService.instance.post('/tms/app/reschedule-return/list');
  final d = data as Map<String, dynamic>;
  return {
    'list': (d['list'] as List? ?? [])
        .map((e) => RescheduleReturn.fromJson(e as Map<String, dynamic>))
        .toList(),
    'count': d['count'] ?? 0,
    'totalQty': d['totalQty'] ?? 0,
  };
});

/// 改派返仓司机返仓确认。
final rescheduleReturnConfirmProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, List<String>>((ref, ids) async {
  final data = await ApiService.instance.post('/tms/app/reschedule-return/confirm', body: {
    'returnIds': ids,
  });
  return data as Map<String, dynamic>;
});

// ============================================================
// 客户拒收单
// ============================================================

/// 客户拒收单创建参数。
class CreateCustomerRejectArgs {
  final String dispatchId;
  final String detailId;
  final String receiptNo;
  final String rejectReason;
  final String reasonDetail;
  final String remark;
  final List<RejectItem> items;
  final List<String> photos; // 照片 URL 列表

  CreateCustomerRejectArgs({
    required this.dispatchId,
    required this.detailId,
    required this.receiptNo,
    required this.rejectReason,
    this.reasonDetail = '',
    this.remark = '',
    required this.items,
    this.photos = const [],
  });

  Map<String, dynamic> toJson() => {
        'dispatchId': dispatchId,
        'detailId': detailId,
        'receiptNo': receiptNo,
        'rejectReason': rejectReason,
        'reasonDetail': reasonDetail,
        if (remark.isNotEmpty) 'remark': remark,
        'items': items.map((e) => e.toJson()).toList(),
      };
}

/// 生成客户拒收单（一次性调用）。
final createCustomerRejectProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, CreateCustomerRejectArgs>((ref, args) async {
  final data = await ApiService.instance
      .post('/tms/app/customer-reject/create', body: args.toJson());
  final result = data as Map<String, dynamic>;
  // 上传照片
  final rejectId = result['rejectId']?.toString() ?? '';
  if (rejectId.isNotEmpty && args.photos.isNotEmpty) {
    await ApiService.instance.post('/tms/app/customer-reject/upload-photo', body: {
      'rejectId': rejectId,
      'photos': args.photos.map((p) => {'url': p}).toList(),
    });
  }
  return result;
});

/// 客户拒收单待返仓列表（本司机）。
final customerRejectListProvider =
    FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final data = await ApiService.instance.post('/tms/app/customer-reject/list');
  final d = data as Map<String, dynamic>;
  return {
    'list': (d['list'] as List? ?? [])
        .map((e) => CustomerReject.fromJson(e as Map<String, dynamic>))
        .toList(),
    'count': d['count'] ?? 0,
    'totalQty': d['totalQty'] ?? 0,
    'totalAmount': d['totalAmount'] ?? 0,
  };
});

/// 客户拒收单司机返仓确认。
final customerRejectConfirmProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, List<String>>((ref, ids) async {
  final data = await ApiService.instance.post('/tms/app/customer-reject/confirm', body: {
    'rejectIds': ids,
  });
  return data as Map<String, dynamic>;
});
