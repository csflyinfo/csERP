import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/store_location.dart';
import '../services/api_service.dart';

/// 提交门店定位修正申请（离线感知，优先级 4）。
///
/// 这是最典型的离线场景：司机必须站在门店门口才能采到正确坐标，
/// 而定位偏差大的门店往往就在信号差的巷子或地下卸货区，
/// 要求联网才能提交等于这个功能在最需要它的地方用不了。
///
/// 优先级 4（低于签收类单据）：定位纠偏是主数据维护，
/// 晚几分钟回传不影响当日配送，不该跟签收、拒收抢同步窗口。
final storeLocationSubmitProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, StoreLocationSubmitArgs>((ref, args) async {
  final data = await ApiService.instance.enqueueOrPost(
    actionType: 'STORE_LOCATION',
    actionKey: args.customerId,
    path: '/tms/app/store-location/submit',
    body: args.toJson(),
    priority: 4,
  );
  return data as Map<String, dynamic>;
});
