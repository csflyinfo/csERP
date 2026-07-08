import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/settlement.dart';
import '../services/api_service.dart';

/// 本日交账汇总预览。
final settlementSummaryProvider =
    FutureProvider.autoDispose<SettlementSummary>((ref) async {
  final data = await ApiService.instance.post('/tms/app/settlement/summary', body: {});
  return SettlementSummary.fromJson(data as Map<String, dynamic>);
});

/// 提交交账（一次性调用）。
final settlementSubmitProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, SettlementSubmitArgs>((ref, args) async {
  final data = await ApiService.instance
      .post('/tms/app/settlement/submit', body: args.toJson());
  return data as Map<String, dynamic>;
});
