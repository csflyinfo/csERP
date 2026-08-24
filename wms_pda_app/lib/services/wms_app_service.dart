import 'api_service.dart';

/// WMS PDA 端点封装。对应后端 /api/wms/app/* (WmsAppController)。
///
/// 所有方法都通过 ApiService 走统一 JWT/错误处理；返回原始 Map/List，
/// 页面层取 camelCase 字段（后端 TmsUtil.queryCamel 已转好）。
class WmsAppService {
  WmsAppService._();
  static final WmsAppService instance = WmsAppService._();

  // ============== 操作员与参数 ==============
  Future<Map<String, dynamic>> profile() =>
      ApiService.instance.post('/wms/app/profile').then(_asMap);

  // ============== 入库 / 收货 ==============
  Future<List<dynamic>> inboundTasks({
    String status = '',
    String inboundType = '',
    String keyword = '',
  }) async {
    final r = await ApiService.instance.post('/wms/app/inbound/tasks', body: {
      if (status.isNotEmpty) 'status': status,
      if (inboundType.isNotEmpty) 'inboundType': inboundType,
      if (keyword.isNotEmpty) 'keyword': keyword,
    });
    return (r as List?) ?? const [];
  }

  Future<Map<String, dynamic>> inboundDetail(String taskId) =>
      ApiService.instance
          .post('/wms/app/inbound/detail', body: {'taskId': taskId}).then(_asMap);

  Future<Map<String, dynamic>> inboundReceive({
    required String detailId,
    required num qty,
    String batchNo = '',
    String productionDate = '',
    String expiryDate = '',
    num? actualWeight,
    String containerCode = '',
    String goodsRemark = '',
  }) =>
      ApiService.instance.post('/wms/app/inbound/receive', body: {
        'detailId': detailId,
        'qty': qty,
        if (batchNo.isNotEmpty) 'batchNo': batchNo,
        if (productionDate.isNotEmpty) 'productionDate': productionDate,
        if (expiryDate.isNotEmpty) 'expiryDate': expiryDate,
        if (actualWeight != null) 'actualWeight': actualWeight,
        if (containerCode.isNotEmpty) 'containerCode': containerCode,
        if (goodsRemark.isNotEmpty) 'goodsRemark': goodsRemark,
      }).then(_asMap);

  // ============== 容器 ==============
  Future<Map<String, dynamic>> containerLookup(String containerCode) =>
      ApiService.instance.post('/wms/app/container/lookup',
          body: {'containerCode': containerCode}).then(_asMap);

  Future<Map<String, dynamic>> containerCreate({
    String containerCode = '',
    String containerType = 'TOTE',
    String warehouse = '',
    String zoneCode = '',
    String binCode = '',
    String remark = '',
  }) =>
      ApiService.instance.post('/wms/app/container/create', body: {
        if (containerCode.isNotEmpty) 'containerCode': containerCode,
        'containerType': containerType,
        if (warehouse.isNotEmpty) 'warehouse': warehouse,
        if (zoneCode.isNotEmpty) 'zoneCode': zoneCode,
        if (binCode.isNotEmpty) 'binCode': binCode,
        if (remark.isNotEmpty) 'remark': remark,
      }).then(_asMap);

  Future<List<dynamic>> containerContents(String containerCode) async {
    final r = await ApiService.instance
        .post('/wms/app/container/contents', body: {'containerCode': containerCode});
    return (r as List?) ?? const [];
  }

  Future<Map<String, dynamic>> containerRelease(String containerCode) =>
      ApiService.instance.post('/wms/app/container/release',
          body: {'containerCode': containerCode}).then(_asMap);

  Future<Map<String, dynamic>> inboundFinish(String taskId) =>
      ApiService.instance
          .post('/wms/app/inbound/finish-receive', body: {'taskId': taskId}).then(_asMap);

  // ============== 上架 ==============
  /// status 传 '' 时拿全部状态（PDA 列表再客户端过滤 PENDING/PUTTING）；
  /// 传具体状态时只返回该状态。
  Future<List<dynamic>> putawayTasks({String status = 'PENDING'}) async {
    final r = await ApiService.instance.post(
        '/wms/app/inbound/putaway-tasks',
        body: {'status': status});
    return (r as List?) ?? const [];
  }

  Future<Map<String, dynamic>> putawayClaim(String putawayId) =>
      ApiService.instance
          .post('/wms/app/inbound/putaway/claim', body: {'putawayId': putawayId}).then(_asMap);

  Future<Map<String, dynamic>> putawayConfirm(String putawayId, {String actualBin = ''}) =>
      ApiService.instance.post('/wms/app/inbound/putaway/confirm', body: {
        'putawayId': putawayId,
        if (actualBin.isNotEmpty) 'actualBin': actualBin,
      }).then(_asMap);

  /// 批量上架到推荐库位。返回 {doneCount, skipped:[{putawayId, reason}]}。
  Future<Map<String, dynamic>> putawayBatchConfirm(List<String> putawayIds) =>
      ApiService.instance.post('/wms/app/inbound/putaway/batch-confirm',
          body: {'putawayIds': putawayIds}).then(_asMap);

  /// 查商品在各库位的当前实物库存。
  Future<List<dynamic>> putawayBinStock(String goodsCode, {String warehouse = ''}) async {
    final r = await ApiService.instance
        .post('/wms/app/inbound/putaway/bin-stock', body: {
      'goodsCode': goodsCode,
      if (warehouse.isNotEmpty) 'warehouse': warehouse,
    });
    return (r as List?) ?? const [];
  }

  // ============== 拣货 ==============
  Future<List<dynamic>> pickTasks({
    String scope = 'mine',
    String zone = '',
    String operator = '',
  }) async {
    final r = await ApiService.instance.post('/wms/app/pick/tasks', body: {
      'scope': scope,
      if (zone.isNotEmpty) 'zone': zone,
      if (operator.isNotEmpty) 'operator': operator,
    });
    return (r as List?) ?? const [];
  }

  Future<Map<String, dynamic>> pickTaskDetail(String taskId) =>
      ApiService.instance
          .post('/wms/app/pick/task-detail', body: {'taskId': taskId}).then(_asMap);

  Future<Map<String, dynamic>> pickClaim(String taskId, {bool help = false}) =>
      ApiService.instance.post('/wms/app/pick/claim', body: {
        'taskId': taskId,
        'help': help,
      }).then(_asMap);

  Future<Map<String, dynamic>> pickItem({
    required String taskId,
    String detailId = '',
    String goodsCode = '',
    num qty = 1,
    String actualBatchNo = '',
    String actualBin = '',
  }) =>
      ApiService.instance.post('/wms/app/pick/item', body: {
        'taskId': taskId,
        if (detailId.isNotEmpty) 'detailId': detailId,
        if (goodsCode.isNotEmpty) 'goodsCode': goodsCode,
        'qty': qty,
        if (actualBatchNo.isNotEmpty) 'actualBatchNo': actualBatchNo,
        if (actualBin.isNotEmpty) 'actualBin': actualBin,
      }).then(_asMap);

  Future<Map<String, dynamic>> pickComplete(String taskId) =>
      ApiService.instance
          .post('/wms/app/pick/complete', body: {'taskId': taskId}).then(_asMap);

  // ============== 复核 / 装车 ==============
  Future<Map<String, dynamic>> checkPass({
    required String waveId,
    required String orderNo,
    String checkScope = '0',
    num shortQty = 0,
  }) =>
      ApiService.instance.post('/wms/app/check/pass', body: {
        'waveId': waveId,
        'orderNo': orderNo,
        'checkScope': checkScope,
        'shortQty': shortQty,
      }).then(_asMap);

  Future<Map<String, dynamic>> checkFail({
    required String waveId,
    required String orderNo,
    String checkScope = '0',
    num shortQty = 0,
  }) =>
      ApiService.instance.post('/wms/app/check/fail', body: {
        'waveId': waveId,
        'orderNo': orderNo,
        'checkScope': checkScope,
        'shortQty': shortQty,
      }).then(_asMap);

  Future<Map<String, dynamic>> loadShip({
    required String waveId,
    required String vehiclePlate,
  }) =>
      ApiService.instance.post('/wms/app/load/ship', body: {
        'waveId': waveId,
        'vehiclePlate': vehiclePlate,
      }).then(_asMap);

  // ============== 补货 ==============
  Future<List<dynamic>> replenishTasks({String status = 'PENDING'}) async {
    final r = await ApiService.instance
        .post('/wms/app/replenish/tasks', body: {'status': status});
    return (r as List?) ?? const [];
  }

  Future<Map<String, dynamic>> replenishClaim(String taskId) =>
      ApiService.instance
          .post('/wms/app/replenish/claim', body: {'taskId': taskId}).then(_asMap);

  Future<Map<String, dynamic>> replenishComplete(String taskId) =>
      ApiService.instance
          .post('/wms/app/replenish/complete', body: {'taskId': taskId}).then(_asMap);

  // ============== 移库 ==============
  Future<List<dynamic>> moveTasks({String status = '', String keyword = ''}) async {
    final r = await ApiService.instance.post('/wms/app/move/tasks', body: {
      if (status.isNotEmpty) 'status': status,
      if (keyword.isNotEmpty) 'keyword': keyword,
    });
    return (r as List?) ?? const [];
  }

  Future<Map<String, dynamic>> moveComplete(String taskId) =>
      ApiService.instance
          .post('/wms/app/move/complete', body: {'taskId': taskId}).then(_asMap);

  // ============== 盘点 ==============
  /// 注意：盘点任务列表当前没有 PDA 端点，需要 PC 端先建单后，
  /// PDA 直接输入/扫描盘点任务号拉明细。
  Future<List<dynamic>> stocktakeBins(String taskId) async {
    final r = await ApiService.instance
        .post('/wms/app/stocktake/bins', body: {'taskId': taskId});
    return (r as List?) ?? const [];
  }

  Future<Map<String, dynamic>> stocktakeCount(String id, num realQty) =>
      ApiService.instance.post('/wms/app/stocktake/count', body: {
        'id': id,
        'realQty': realQty,
      }).then(_asMap);

  Future<Map<String, dynamic>> stocktakeRecount(String id, num realQty) =>
      ApiService.instance.post('/wms/app/stocktake/recount', body: {
        'id': id,
        'realQty': realQty,
      }).then(_asMap);

  // ============== 工具 ==============
  static Map<String, dynamic> _asMap(dynamic v) {
    if (v is Map<String, dynamic>) return v;
    if (v is Map) return Map<String, dynamic>.from(v);
    return <String, dynamic>{};
  }
}
