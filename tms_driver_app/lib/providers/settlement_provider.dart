import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/settlement.dart';
import '../services/api_service.dart';

/// 本日交账汇总预览。
final settlementSummaryProvider =
    FutureProvider.autoDispose<SettlementSummary>((ref) async {
  final data = await ApiService.instance.post('/tms/app/settlement/summary', body: {});
  return SettlementSummary.fromJson(data as Map<String, dynamic>);
});

/// 提交交账结算（刻意保持在线提交，不接离线队列）。
///
/// 其余写操作都做了离线入队，这里是唯一的例外，原因：
/// 1. 交账是「司机把现金实物交给财务」的确认动作，本就发生在公司/网点，
///    不是门店信号盲区，离线能力的实际收益极低；
/// 2. 应交金额由服务端汇总下发（settlementSummaryProvider），离线时这个数拿不到，
///    司机连自己该交多少都核对不了，让他在离线状态下「提交交账」本身就没意义；
/// 3. 涉及现金差异，必须当场拿到服务端确认的差异结果与交账单号。
///    若入队后延迟回传，司机以为交完了、财务却查不到单，责任无从划分——
///    这类分歧的代价远高于「让他挪两步到有信号的地方再交」。
final settlementSubmitProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, SettlementSubmitArgs>((ref, args) async {
  final data = await ApiService.instance
      .post('/tms/app/settlement/submit', body: args.toJson());
  return data as Map<String, dynamic>;
});
