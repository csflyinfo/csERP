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

/// 商品搜索（名称/编码/条码/拼音码模糊；条码精确命中排最前）。
final goodsSearchProvider =
    FutureProvider.autoDispose.family<List<GoodsSearchResult>, String>((ref, keyword) async {
  if (keyword.isEmpty) return [];
  final data = await ApiService.instance.post('/tms/app/return/goods-search', body: {'keyword': keyword});
  final list = data as List? ?? [];
  return list
      .map((e) => GoodsSearchResult.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// 客户下拉查询参数。
/// family 靠 == 判重缓存：关键字 + 司机经纬度相同就不重复请求。
class CustomerSearchArgs {
  final String keyword;
  final double? longitude;
  final double? latitude;

  const CustomerSearchArgs({this.keyword = '', this.longitude, this.latitude});

  @override
  bool operator ==(Object other) =>
      other is CustomerSearchArgs &&
      other.keyword == keyword &&
      other.longitude == longitude &&
      other.latitude == latitude;

  @override
  int get hashCode => Object.hash(keyword, longitude, latitude);
}

/// 客户下拉：关键字模糊查名称/地址/电话；无关键字时后端按经纬度返回最近 10 个。
final returnCustomerSearchProvider = FutureProvider.autoDispose
    .family<List<CustomerSearchResult>, CustomerSearchArgs>((ref, args) async {
  final data = await ApiService.instance.post('/tms/app/return/customer-search', body: {
    'keyword': args.keyword,
    if (args.longitude != null) 'longitude': args.longitude,
    if (args.latitude != null) 'latitude': args.latitude,
  });
  final list = data as List? ?? [];
  return list
      .map((e) => CustomerSearchResult.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// 收货仓库下拉（正常实物仓）。
final returnWarehouseListProvider =
    FutureProvider.autoDispose<List<WarehouseOption>>((ref) async {
  final data = await ApiService.instance.post('/tms/app/return/warehouse-list');
  final list = data as List? ?? [];
  return list
      .map((e) => WarehouseOption.fromJson(e as Map<String, dynamic>))
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
        if (photos.isNotEmpty)
          'photos': photos
              .map((url) =>
                  {'url': url, 'photoType': 'GOODS', 'bizType': 'RETURN'})
              .toList(),
      };
}

/// 创建司机现场退货（离线感知，优先级 2）。
///
/// 必须离线可提交：现场退货是司机在客户门口清点后当场登记的，
/// 货已经装回车上了，若因信号差提交失败就得靠人脑记着回公司补录，极易漏单错单。
///
/// 照片随主单一起提交，理由同改派返仓/客户拒收：
/// 离线排队时 driverReturnId 尚未生成，拆成独立请求会缺 ID 被后端 400 拒绝并永久卡在队列。
///
/// actionKey 用客户编码：司机现场退货没有前置单号可用（无预开单场景），
/// 客户编码是队列里唯一能辨认「这条是给哪家店退的」的标识。
final createReturnProvider =
    FutureProvider.autoDispose.family<Map<String, dynamic>, CreateReturnArgs>((ref, args) async {
  final data = await ApiService.instance.enqueueOrPost(
    actionType: 'DRIVER_RETURN',
    actionKey: args.customerCode,
    path: '/tms/app/return/create',
    body: args.toJson(),
    priority: 2,
  );
  return data as Map<String, dynamic>;
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

/// 返仓交接确认（刻意保持在线提交）。
///
/// 交接确认是「货已交给仓管」的责任转移点，发生在仓库交接台前（有网），
/// 必须当场拿到服务端确认，不能给假成功后让货物短少时无从追溯。
final warehouseReturnConfirmProvider =
    FutureProvider.autoDispose.family<Map<String, dynamic>, List<String>>((ref, ids) async {
  final data = await ApiService.instance.post('/tms/app/warehouse-return/confirm', body: {
    'driverReturnIds': ids,
  });
  return data as Map<String, dynamic>;
});
