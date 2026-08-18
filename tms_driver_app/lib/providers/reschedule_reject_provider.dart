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

/// 生成改派返仓单（一次性调用，离线感知，优先级 2）。
///
/// 与签收同级：改派返仓意味着这批货没送到、要拉回仓库，
/// 调度需据此重新排线，晚回传一轮就可能重复派车。
///
/// 照片随主单一起提交，不再拆成第二个 upload-photo 请求：
/// 离线排队时 returnId 尚未生成，拆开会让照片请求缺 returnId 被后端 400 拒绝，
/// 永久卡在队列里反复重试。后端建单接口已支持可选 photos 字段。
final createRescheduleReturnProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, CreateRescheduleReturnArgs>((ref, args) async {
  final body = args.toJson();
  if (args.photos.isNotEmpty) {
    body['photos'] =
        args.photos.map((p) => {'url': p, 'bizType': 'RESCHEDULE'}).toList();
  }
  final data = await ApiService.instance.enqueueOrPost(
    actionType: 'RESCHEDULE_RETURN',
    actionKey: args.detailId,
    path: '/tms/app/reschedule-return/create',
    body: body,
    priority: 2,
  );
  return data as Map<String, dynamic>;
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

/// 改派返仓司机返仓确认（刻意保持在线提交）。
///
/// 与建单不同，确认动作发生在仓库交接台前、双方当面点数，
/// 仓库不是信号盲区；且这是「货已交给仓管」的责任转移点，
/// 必须当场看到服务端确认结果，否则司机走了、仓管系统里没这批货，
/// 货物短少时无从追溯。宁可让他重试一次，也不能给个假成功。
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

/// 生成客户拒收单（一次性调用，离线感知，优先级 2）。
///
/// 拒收必须离线可提交：客户当面拒收时司机就得登记完走人，
/// 不能因为门店信号差就让人在原地干等，或者录完一屏数据被一句「提交失败」清空。
///
/// 照片随主单一起提交，理由同改派返仓：离线排队时 rejectId 还不存在，
/// 拆成独立请求会缺 rejectId 被后端 400 拒绝并永久卡在队列。
final createCustomerRejectProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, CreateCustomerRejectArgs>((ref, args) async {
  final body = args.toJson();
  if (args.photos.isNotEmpty) {
    body['photos'] =
        args.photos.map((p) => {'url': p, 'bizType': 'REJECT'}).toList();
  }
  final data = await ApiService.instance.enqueueOrPost(
    actionType: 'CUSTOMER_REJECT',
    actionKey: args.detailId,
    path: '/tms/app/customer-reject/create',
    body: body,
    priority: 2,
  );
  return data as Map<String, dynamic>;
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

/// 客户拒收单司机返仓确认（刻意保持在线提交，理由同 rescheduleReturnConfirmProvider）。
final customerRejectConfirmProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, List<String>>((ref, ids) async {
  final data = await ApiService.instance.post('/tms/app/customer-reject/confirm', body: {
    'rejectIds': ids,
  });
  return data as Map<String, dynamic>;
});
