import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/delivery.dart';
import '../services/api_service.dart';

/// 装车清单（按 dispatchId 拉取 SKU 明细）。
final loadingItemsProvider =
    FutureProvider.family<LoadingDispatch, String>((ref, dispatchId) async {
  final data = await ApiService.instance
      .post('/tms/app/loading/items', body: {'dispatchId': dispatchId});
  return LoadingDispatch.fromJson(data as Map<String, dynamic>);
});

/// 签收明细（按 detailId 拉取 SKU 明细）。
final signItemsProvider =
    FutureProvider.family<SignDetail, String>((ref, detailId) async {
  final data = await ApiService.instance
      .post('/tms/app/sign/items', body: {'detailId': detailId});
  return SignDetail.fromJson(data as Map<String, dynamic>);
});

/// 装车动作参数。
class LoadingActionArgs {
  final String dispatchId;
  final String action; // start / confirm / depart
  LoadingActionArgs({required this.dispatchId, required this.action});
}

/// 装车 / 发车 动作（一次性调用，离线时自动入队）。
final loadingActionProvider =
    FutureProvider.family<Map<String, dynamic>, LoadingActionArgs>((ref, args) async {
  final path = switch (args.action) {
    'start' => '/tms/app/loading/start',
    'confirm' => '/tms/app/loading/confirm',
    'depart' => '/tms/app/depart',
    _ => throw Exception('未知装车动作：${args.action}'),
  };
  final actionType = switch (args.action) {
    'start' => 'LOADING_START',
    'confirm' => 'LOADING_CONFIRM',
    'depart' => 'DEPART',
    _ => 'LOADING_ACTION',
  };
  final data = await ApiService.instance.enqueueOrPost(
    actionType: actionType,
    actionKey: args.dispatchId,
    path: path,
    body: {'dispatchId': args.dispatchId},
    priority: 1, // 装车/发车优先级最高
  );
  return data as Map<String, dynamic>;
});

/// 装车扫码核对参数。
class LoadingScanArgs {
  final String dispatchId;
  final String detailId;
  final String sourceBillNo;
  final String goodsCode;
  final String goodsName;
  final num requiredQty;
  final num loadedQty;
  LoadingScanArgs({
    required this.dispatchId,
    required this.detailId,
    required this.sourceBillNo,
    required this.goodsCode,
    this.goodsName = '',
    required this.requiredQty,
    required this.loadedQty,
  });

  Map<String, dynamic> toJson() => {
        'dispatchId': dispatchId,
        'detailId': detailId,
        'sourceBillNo': sourceBillNo,
        'goodsCode': goodsCode,
        'goodsName': goodsName,
        'requiredQty': requiredQty,
        'loadedQty': loadedQty,
      };
}

/// 装车扫码（写 tms_loading_check）。
final loadingScanProvider =
    FutureProvider.family<Map<String, dynamic>, LoadingScanArgs>((ref, args) async {
  final data = await ApiService.instance.post('/tms/app/loading/scan', body: args.toJson());
  return data as Map<String, dynamic>;
});

/// 签收提交参数。
class SignSubmitArgs {
  final String dispatchId;
  final String detailId;
  final String sourceBillNo;
  final List<Map<String, dynamic>> items; // [{goodsCode, signedQty, rejectQty}]
  final num collectAmount;
  final String payMethod;
  final String customerSigner;
  final String remark;
  final List<String> photoUrls; // 照片 URL 列表（先调 uploadImage 上传获得）
  final String? signatureUrl; // 签名图 URL（先调 uploadImage 上传获得）
  SignSubmitArgs({
    required this.dispatchId,
    required this.detailId,
    required this.sourceBillNo,
    required this.items,
    this.collectAmount = 0,
    this.payMethod = '',
    this.customerSigner = '',
    this.remark = '',
    this.photoUrls = const [],
    this.signatureUrl,
  });

  Map<String, dynamic> toJson() => {
        'dispatchId': dispatchId,
        'detailId': detailId,
        'sourceBillNo': sourceBillNo,
        'items': items,
        'collectAmount': collectAmount,
        'payMethod': payMethod,
        'customerSigner': customerSigner,
        'remark': remark,
        if (signatureUrl != null && signatureUrl!.isNotEmpty) 'signatureUrl': signatureUrl,
      };
}

/// 发货单签收提交（离线时自动入队，在线时直接提交+上传照片）。
final signSubmitProvider =
    FutureProvider.family<Map<String, dynamic>, SignSubmitArgs>((ref, args) async {
  // 签收提交（离线感知，优先级 2）
  final data = await ApiService.instance.enqueueOrPost(
    actionType: 'SIGN',
    actionKey: args.detailId,
    path: '/tms/app/sign',
    body: args.toJson(),
    priority: 2,
  );
  final result = data as Map<String, dynamic>;

  // 离线入队时不会有 signId，照片上传也会被入队
  final signId = result['signId']?.toString() ?? '';
  final isOffline = result['_offline'] == true;

  if (!isOffline && signId.isNotEmpty && args.photoUrls.isNotEmpty) {
    // 在线成功：立即上传照片
    await ApiService.instance.post('/tms/app/sign/upload-photo', body: {
      'signId': signId,
      'photos': args.photoUrls
          .map((url) => {'url': url, 'photoType': 'GOODS'})
          .toList(),
    });
  } else if (isOffline && args.photoUrls.isNotEmpty) {
    // 离线：照片上传也入队（优先级 3）
    // 注意：离线时 signId 未知，同步时会先执行签收入队，再执行照片入队
    // 照片入队的 body 需要在签收成功后才能确定 signId
    // 这里简化处理：照片 URL 在离线时已在本地（uploadImage 也入队了），
    // 同步服务会按优先级先执行签收，再执行照片上传
    await ApiService.instance.enqueueOrPost(
      actionType: 'SIGN_PHOTO',
      actionKey: args.detailId,
      path: '/tms/app/sign/upload-photo',
      body: {
        'photos': args.photoUrls
            .map((url) => {'url': url, 'photoType': 'GOODS'})
            .toList(),
      },
      priority: 3,
    );
  }
  return result;
});

/// 到达门店。
final arriveProvider =
    FutureProvider.family<Map<String, dynamic>, String>((ref, args) async {
  // args 格式：dispatchId|detailId
  final parts = args.split('|');
  if (parts.length < 2) throw Exception('args 格式错误：dispatchId|detailId');
  final data = await ApiService.instance.post('/tms/app/arrive', body: {
    'dispatchId': parts[0],
    'detailId': parts[1],
  });
  return data as Map<String, dynamic>;
});
