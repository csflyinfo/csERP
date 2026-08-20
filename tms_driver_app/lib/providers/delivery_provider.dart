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

/// 调度任务配送点清单（首页「查看清单」与装车前调序共用）。
final loadingStoresProvider =
    FutureProvider.family<LoadingStores, String>((ref, dispatchId) async {
  final data = await ApiService.instance
      .post('/tms/app/loading/stores', body: {'dispatchId': dispatchId});
  return LoadingStores.fromJson(data as Map<String, dynamic>);
});

/// 配送点调序参数。
///
/// 与 LoadingActionArgs 同样不重写 == / hashCode：司机可能连续拖两次再提交，
/// 若按值缓存，「拖回原顺序再提交」会命中旧结果而不真正落库。
class LoadingSortArgs {
  final String dispatchId;

  /// 按新顺序排列的门店编码，必须是「全部待配送门店」且不重不漏。
  /// 已完成门店由后端自动锁在最前，前端不要提交它们。
  final List<String> customerCodes;

  LoadingSortArgs({required this.dispatchId, required this.customerCodes});
}

/// 提交配送点顺序（仅未发车可用）。
final loadingSortProvider =
    FutureProvider.family<Map<String, dynamic>, LoadingSortArgs>((ref, args) async {
  final data = await ApiService.instance.post('/tms/app/loading/sort', body: {
    'dispatchId': args.dispatchId,
    'customerCodes': args.customerCodes,
  });
  return (data as Map?)?.cast<String, dynamic>() ?? {};
});

/// 签收明细（按 detailId 拉取 SKU 明细）。
final signItemsProvider =
    FutureProvider.family<SignDetail, String>((ref, detailId) async {
  final data = await ApiService.instance
      .post('/tms/app/sign/items', body: {'detailId': detailId});
  return SignDetail.fromJson(data as Map<String, dynamic>);
});

/// 装车动作参数。
///
/// 有意不重写 == / hashCode：loadingActionProvider 是 family，
/// 若按值相等缓存，则「发车被拦下 → 司机确认 → 带 force 重试」这一步
/// 会命中上一次的结果而不真正重发。保持身份相等语义，每次调用都是新请求。
class LoadingActionArgs {
  final String dispatchId;
  final String action; // start / confirm / depart

  /// 仅对 depart 生效：确认「带未装车配送单发车」。
  /// 后端首次返回 needConfirm 时不落库，APP 弹窗让司机确认后带 true 重来。
  final bool force;

  /// 仅对 confirm 生效：要确认装车的配送点（调度明细 ID）。
  /// 空表示「全部装车」——后端会把该单所有未发车配送点一次置为已装车。
  final List<String> detailIds;

  LoadingActionArgs({
    required this.dispatchId,
    required this.action,
    this.force = false,
    this.detailIds = const [],
  });
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
  final body = <String, dynamic>{'dispatchId': args.dispatchId};
  // 只在确认后才带 force：默认不带，让后端有机会做完整性校验。
  // 图省事一律传 true 等于把这道校验废掉。
  if (args.force) body['force'] = true;
  // 只传非空 detailIds：后端把「不传/空数组」解释为全选装车，
  // 传空数组和不传等价，但显式省略更能表达「这次是全选」的意图。
  if (args.detailIds.isNotEmpty) body['detailIds'] = args.detailIds;
  final data = await ApiService.instance.enqueueOrPost(
    actionType: actionType,
    // 按配送点装车时把明细拼进 actionKey：离线队列按 actionType+actionKey 去重，
    // 只用 dispatchId 会让「先装 A 点、再装 B 点」的第二次请求被当成重复丢弃。
    actionKey: args.detailIds.isEmpty
        ? args.dispatchId
        : '${args.dispatchId}#${args.detailIds.join(",")}',
    path: path,
    body: body,
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

/// 装车扫码（写 tms_loading_check，离线感知，优先级 1）。
///
/// 优先级跟装车/发车同为 1：扫码结果是发车的前置依赖，
/// 若排在签收（2）后面同步，会出现「签收已回传但装车记录还在队列里」的时序倒置。
///
/// actionKey 用 detailId + goodsCode 组合，而不是只用 detailId：
/// 装车是「一个门店下多个商品」逐个扫，只用 detailId 会让同一门店的多条扫码记录
/// 在队列里无法区分，同步失败时排查不出是哪个商品卡住了。
final loadingScanProvider =
    FutureProvider.family<Map<String, dynamic>, LoadingScanArgs>((ref, args) async {
  final data = await ApiService.instance.enqueueOrPost(
    actionType: 'LOADING_SCAN',
    actionKey: '${args.detailId}_${args.goodsCode}',
    path: '/tms/app/loading/scan',
    body: args.toJson(),
    priority: 1,
  );
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
        if (photoUrls.isNotEmpty)
          'photos': photoUrls
              .map((url) =>
                  {'url': url, 'photoType': 'GOODS', 'bizType': 'SIGN'})
              .toList(),
      };
}

/// 发货单签收提交（离线感知，优先级 2）。
///
/// 照片随主单一起提交，不再拆成第二个 upload-photo 请求：
/// 离线排队时 signId 尚未生成，拆开会让照片请求缺 signId 被后端 400 拒绝，
/// 且 SyncService 只原样重放 body_json、不会回填 ID，该请求会永久卡在队列里反复重试。
/// 后端 /tms/app/sign 已支持可选 photos 字段，/sign/upload-photo 仅保留给在线补拍场景。
final signSubmitProvider =
    FutureProvider.family<Map<String, dynamic>, SignSubmitArgs>((ref, args) async {
  final data = await ApiService.instance.enqueueOrPost(
    actionType: 'SIGN',
    actionKey: args.detailId,
    path: '/tms/app/sign',
    body: args.toJson(),
    priority: 2,
  );
  return data as Map<String, dynamic>;
});

/// 到达打卡参数（GPS 围栏）。
///
/// 必须实现 == / hashCode：FutureProvider.family 以参数为缓存键，
/// 否则每次点击生成新实例都会命中新缓存，重复提交。
class ArriveArgs {
  final String dispatchId;
  final String detailId;
  final double? longitude;
  final double? latitude;
  final double? accuracy;
  final String abnormalReason;
  final String photoUrl;

  const ArriveArgs({
    required this.dispatchId,
    required this.detailId,
    this.longitude,
    this.latitude,
    this.accuracy,
    this.abnormalReason = '',
    this.photoUrl = '',
  });

  Map<String, dynamic> toJson() => {
        'dispatchId': dispatchId,
        'detailId': detailId,
        if (longitude != null) 'longitude': longitude,
        if (latitude != null) 'latitude': latitude,
        if (accuracy != null) 'accuracy': accuracy,
        if (abnormalReason.isNotEmpty) 'abnormalReason': abnormalReason,
        if (photoUrl.isNotEmpty) 'photoUrl': photoUrl,
      };

  @override
  bool operator ==(Object other) =>
      other is ArriveArgs &&
      other.dispatchId == dispatchId &&
      other.detailId == detailId &&
      other.longitude == longitude &&
      other.latitude == latitude &&
      other.accuracy == accuracy &&
      other.abnormalReason == abnormalReason &&
      other.photoUrl == photoUrl;

  @override
  int get hashCode => Object.hash(
      dispatchId, detailId, longitude, latitude, accuracy, abnormalReason, photoUrl);
}

/// 到达门店打卡（离线自动入队，与签收同优先级）。
///
/// 距离与异常由服务端复算，此处只负责把原始定位数据交上去。
final arriveProvider =
    FutureProvider.family<Map<String, dynamic>, ArriveArgs>((ref, args) async {
  if (args.dispatchId.isEmpty || args.detailId.isEmpty) {
    throw Exception('dispatchId、detailId 不能为空');
  }
  final data = await ApiService.instance.enqueueOrPost(
    actionType: 'ARRIVE',
    actionKey: args.detailId,
    path: '/tms/app/arrive',
    body: args.toJson(),
    priority: 2, // 与签收同级：打卡时间是签收链路的前置凭证
  );
  return data as Map<String, dynamic>;
});

/// 到达打卡参数配置（围栏阈值 / 是否强制打卡 / 异常是否必拍照）。
///
/// 由 ERP「系统参数」维护，APP 每次进入打卡页拉取；
/// 取不到时返回内置默认值，绝不因配置读取失败阻断打卡。
final arriveConfigProvider = FutureProvider<ArriveConfig>((ref) async {
  try {
    final data = await ApiService.instance.post('/tms/app/arrive/config');
    return ArriveConfig.fromJson(data as Map<String, dynamic>);
  } catch (_) {
    return const ArriveConfig();
  }
});

/// 到达打卡配置项。
class ArriveConfig {
  /// 正常范围（米）：小于此值视为正常到店。
  final double normalRadius;

  /// 告警范围（米）：超过此值判定 GPS 异常，需填原因。
  final double warnRadius;

  /// 是否要求签收前必须先打卡（默认 false，由用户在系统参数中自行开启）。
  final bool arriveRequired;

  /// GPS 异常时是否必须上传现场照片。
  final bool photoRequired;

  const ArriveConfig({
    this.normalRadius = 200,
    this.warnRadius = 1000,
    this.arriveRequired = false,
    this.photoRequired = true,
  });

  factory ArriveConfig.fromJson(Map<String, dynamic> j) => ArriveConfig(
        normalRadius: (j['normalRadius'] as num?)?.toDouble() ?? 200,
        warnRadius: (j['warnRadius'] as num?)?.toDouble() ?? 1000,
        arriveRequired: j['arriveRequired'] == true,
        photoRequired: j['photoRequired'] != false,
      );
}
