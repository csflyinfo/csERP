import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/store_location.dart';
import '../services/api_service.dart';

/// 提交门店定位修正申请（一次性调用）。
final storeLocationSubmitProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, StoreLocationSubmitArgs>((ref, args) async {
  final data = await ApiService.instance
      .post('/tms/app/store-location/submit', body: args.toJson());
  return data as Map<String, dynamic>;
});
