import 'api_service.dart';

/// WMS PDA 端点封装。对应后端 /api/wms/app/* (WmsAppController)。
///
/// 所有方法都通过 ApiService 走统一 JWT/错误处理；返回原始 Map/List，
/// 页面层取 camelCase 字段（后端 TmsUtil.queryCamel 已转好）。
///
/// PRD-28 卡片9：
/// - 任何方法都不允许在 body 里带 operator/warehouse，操作人取 JWT 自然人，
///   仓库由 PdaAppGuard 按 token.warehouseId 强制隔离；
/// - 页面仅做按钮显隐，真正的功能点裁决在后端 @RequirePerm。
class WmsAppService {
  WmsAppService._();
  static final WmsAppService instance = WmsAppService._();

  // ============== 我的 / 参数 ==============
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

  /// PDA 手工建其他入库/调拨入库（KEEPER/LEADER）。
  Future<Map<String, dynamic>> inboundCreate({
    String inboundType = 'OTHER',
    String supplierName = '',
    String remark = '',
    required List<Map<String, dynamic>> details,
  }) =>
      ApiService.instance.post('/wms/app/inbound/create', body: {
        'inboundType': inboundType,
        if (supplierName.isNotEmpty) 'supplierName': supplierName,
        if (remark.isNotEmpty) 'remark': remark,
        'details': details,
      }).then(_asMap);

  /// 开始收货（action=start，返回任务详情）。
  Future<Map<String, dynamic>> inboundStart(String taskId) =>
      ApiService.instance.post('/wms/app/inbound/receive', body: {
        'action': 'start',
        'taskId': taskId,
      }).then(_asMap);

  /// 扫码/逐行收货。printLabel 需要打印功能点；超收必须给原因。
  Future<Map<String, dynamic>> inboundReceive({
    required String detailId,
    required num qty,
    String batchNo = '',
    String productionDate = '',
    String expiryDate = '',
    num? actualWeight,
    String containerCode = '',
    String goodsRemark = '',
    bool printLabel = false,
    String overReceiveReason = '',
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
        if (printLabel) 'printLabel': true,
        if (overReceiveReason.isNotEmpty) 'overReceiveReason': overReceiveReason,
      }).then(_asMap);

  Future<Map<String, dynamic>> inboundFinish(String taskId) =>
      ApiService.instance.post('/wms/app/inbound/finish-receive',
          body: {'taskId': taskId}).then(_asMap);

  /// 收货复检。passed=false 时按不通过处理，remark 必填由 service 兜底。
  Future<Map<String, dynamic>> inboundRecheck({
    required String taskId,
    required bool passed,
    String remark = '',
  }) =>
      ApiService.instance.post('/wms/app/inbound/recheck', body: {
        'taskId': taskId,
        'passed': passed,
        if (remark.isNotEmpty) 'remark': remark,
      }).then(_asMap);

  // ============== 容器 ==============
  Future<Map<String, dynamic>> containerLookup(String containerCode) =>
      ApiService.instance.post('/wms/app/container/lookup',
          body: {'containerCode': containerCode}).then(_asMap);

  /// 容器建档（仓库强制登录仓，不接收 warehouse 入参）。
  Future<Map<String, dynamic>> containerCreate({
    String containerCode = '',
    String containerType = 'TOTE',
    String zoneCode = '',
    String binCode = '',
    String remark = '',
  }) =>
      ApiService.instance.post('/wms/app/container/create', body: {
        if (containerCode.isNotEmpty) 'containerCode': containerCode,
        'containerType': containerType,
        if (zoneCode.isNotEmpty) 'zoneCode': zoneCode,
        if (binCode.isNotEmpty) 'binCode': binCode,
        if (remark.isNotEmpty) 'remark': remark,
      }).then(_asMap);

  Future<List<dynamic>> containerContents(String containerCode) async {
    final r = await ApiService.instance.post('/wms/app/container/contents',
        body: {'containerCode': containerCode});
    return (r as List?) ?? const [];
  }

  Future<Map<String, dynamic>> containerRelease(String containerCode) =>
      ApiService.instance.post('/wms/app/container/release',
          body: {'containerCode': containerCode}).then(_asMap);

  // ============== 上架 ==============
  /// status 缺省=PENDING（可领取）+PUTTING（我已领）；显式 ''=全部。
  Future<List<dynamic>> putawayTasks({String status = 'PENDING'}) async {
    final r = await ApiService.instance.post(
        '/wms/app/inbound/putaway-tasks',
        body: {'status': status});
    return (r as List?) ?? const [];
  }

  Future<Map<String, dynamic>> putawayClaim(String putawayId) =>
      ApiService.instance.post('/wms/app/inbound/putaway/claim',
          body: {'putawayId': putawayId}).then(_asMap);

  /// 确认上架。actualBin 非空=人工改放库位（free_bin）；split=true=拆容器/拆批。
  Future<Map<String, dynamic>> putawayConfirm(
    String putawayId, {
    String actualBin = '',
    bool split = false,
  }) =>
      ApiService.instance.post('/wms/app/inbound/putaway/confirm', body: {
        'putawayId': putawayId,
        if (actualBin.isNotEmpty) 'actualBin': actualBin,
        if (split) 'split': true,
      }).then(_asMap);

  /// 扫库位/商品核对（非变更 ack）。
  Future<Map<String, dynamic>> putawayScanAck(String putawayId) =>
      ApiService.instance.post('/wms/app/inbound/putaway/confirm', body: {
        'action': 'scan',
        'putawayId': putawayId,
      }).then(_asMap);

  /// 批量上架到推荐库位。返回 {doneCount, skipped:[{putawayId, reason}]}。
  Future<Map<String, dynamic>> putawayBatchConfirm(List<String> putawayIds) =>
      ApiService.instance.post('/wms/app/inbound/putaway/batch-confirm',
          body: {'putawayIds': putawayIds}).then(_asMap);

  /// 查商品在各库位的当前实物库存（仓库服务端强制）。
  Future<List<dynamic>> putawayBinStock(String goodsCode) async {
    final r = await ApiService.instance
        .post('/wms/app/inbound/putaway/bin-stock', body: {
      'goodsCode': goodsCode,
    });
    return (r as List?) ?? const [];
  }

  // ============== 拣货 ==============
  /// scope=all 须具备 task_assign.view，后端会再裁一次。
  Future<List<dynamic>> pickTasks({
    String scope = 'mine',
    String zone = '',
  }) async {
    final r = await ApiService.instance.post('/wms/app/pick/tasks', body: {
      'scope': scope,
      if (zone.isNotEmpty) 'zone': zone,
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

  /// 缺货上报（原因必填）。
  Future<Map<String, dynamic>> pickShort({
    required String detailId,
    num? shortQty,
    required String reason,
  }) =>
      ApiService.instance.post('/wms/app/pick/short-pick', body: {
        'detailId': detailId,
        if (shortQty != null) 'shortQty': shortQty,
        'reason': reason,
      }).then(_asMap);

  /// 跳过商品（不改明细状态，异常留痕，原因必填）。
  Future<Map<String, dynamic>> pickSkip({
    required String detailId,
    required String reason,
  }) =>
      ApiService.instance.post('/wms/app/pick/skip', body: {
        'detailId': detailId,
        'reason': reason,
      }).then(_asMap);

  /// 转交拣货任务（KEEPER/LEADER）。
  Future<Map<String, dynamic>> pickTransfer({
    required String taskId,
    required String toAssignee,
  }) =>
      ApiService.instance.post('/wms/app/pick/transfer', body: {
        'taskId': taskId,
        'toAssignee': toAssignee,
      }).then(_asMap);

  // ============== 复核 / 装车 ==============
  /// 当前仓待复核波次，每行附 orders（复核员不再借 pick 权限拉明细）。
  Future<List<dynamic>> checkTasks() async {
    final r = await ApiService.instance.post('/wms/app/check/tasks');
    return (r as List?) ?? const [];
  }

  /// 复核工位动作：action=scan 扫码核对 / action=pack 打包封箱 / 缺省=复核通过。
  Future<Map<String, dynamic>> checkPass({
    required String waveId,
    required String orderNo,
    String action = '',
    String checkScope = '0',
    num shortQty = 0,
    String reason = '',
  }) =>
      ApiService.instance.post('/wms/app/check/pass', body: {
        if (action.isNotEmpty) 'action': action,
        'waveId': waveId,
        'orderNo': orderNo,
        'checkScope': checkScope,
        if (shortQty != 0) 'shortQty': shortQty,
        if (reason.isNotEmpty) 'reason': reason,
      }).then(_asMap);

  /// 复核不通过（差异登记，原因必填）。
  Future<Map<String, dynamic>> checkFail({
    required String waveId,
    required String orderNo,
    String checkScope = '0',
    num shortQty = 0,
    required String reason,
  }) =>
      ApiService.instance.post('/wms/app/check/fail', body: {
        'waveId': waveId,
        'orderNo': orderNo,
        'checkScope': checkScope,
        if (shortQty != 0) 'shortQty': shortQty,
        'reason': reason,
      }).then(_asMap);

  Future<List<dynamic>> loadTasks() async {
    final r = await ApiService.instance.post('/wms/app/load/tasks');
    return (r as List?) ?? const [];
  }

  /// 扫码核对 action=scan（load.scan）；缺省=装车发运确认。
  Future<Map<String, dynamic>> loadShip({
    required String waveId,
    String vehiclePlate = '',
    String action = '',
  }) =>
      ApiService.instance.post('/wms/app/load/ship', body: {
        if (action.isNotEmpty) 'action': action,
        'waveId': waveId,
        if (vehiclePlate.isNotEmpty) 'vehiclePlate': vehiclePlate,
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
      ApiService.instance.post('/wms/app/replenish/complete',
          body: {'taskId': taskId}).then(_asMap);

  /// 缺货加急补货。
  Future<Map<String, dynamic>> replenishUrgent({
    required String goodsCode,
    String batchNo = '',
    required String toBin,
    num? qty,
    String waveId = '',
  }) =>
      ApiService.instance.post('/wms/app/replenish/urgent', body: {
        'goodsCode': goodsCode,
        if (batchNo.isNotEmpty) 'batchNo': batchNo,
        'toBin': toBin,
        if (qty != null) 'qty': qty,
        if (waveId.isNotEmpty) 'waveId': waveId,
      }).then(_asMap);

  // ============== 移库 ==============
  Future<List<dynamic>> moveTasks({String status = '', String keyword = ''}) async {
    final r = await ApiService.instance.post('/wms/app/move/tasks', body: {
      if (status.isNotEmpty) 'status': status,
      if (keyword.isNotEmpty) 'keyword': keyword,
    });
    return (r as List?) ?? const [];
  }

  Future<Map<String, dynamic>> moveAdd({
    // 与 PC 端一致默认 ACTIVE（主动整理）；MANUAL 在 PC 字典里无映射会原样显示
    String moveType = 'ACTIVE',
    required String fromBin,
    required String toBin,
    required String goodsCode,
    String goodsName = '',
    String batchNo = '',
    required num qty,
    String assignee = '',
    String remark = '',
  }) =>
      ApiService.instance.post('/wms/app/move/add', body: {
        'moveType': moveType,
        'fromBin': fromBin,
        'toBin': toBin,
        'goodsCode': goodsCode,
        if (goodsName.isNotEmpty) 'goodsName': goodsName,
        if (batchNo.isNotEmpty) 'batchNo': batchNo,
        'qty': qty,
        if (assignee.isNotEmpty) 'assignee': assignee,
        if (remark.isNotEmpty) 'remark': remark,
      }).then(_asMap);

  Future<Map<String, dynamic>> moveComplete(String taskId) =>
      ApiService.instance
          .post('/wms/app/move/complete', body: {'taskId': taskId}).then(_asMap);

  // ============== 盘点 ==============
  /// PDA 扫/输盘点任务号拉明细。action=scan 需要 stocktake.scan。
  Future<List<dynamic>> stocktakeBins(String taskId, {bool scan = false}) async {
    final r = await ApiService.instance.post('/wms/app/stocktake/bins', body: {
      'taskId': taskId,
      if (scan) 'action': 'scan',
    });
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

  Future<Map<String, dynamic>> stocktakeSubmit(String taskId) =>
      ApiService.instance.post('/wms/app/stocktake/submit',
          body: {'taskId': taskId}).then(_asMap);

  Future<Map<String, dynamic>> stocktakeAudit(String taskId) =>
      ApiService.instance.post('/wms/app/stocktake/audit',
          body: {'taskId': taskId}).then(_asMap);

  // ============== 报损 ==============
  Future<List<dynamic>> damageList({String status = ''}) async {
    final r = await ApiService.instance.post('/wms/app/damage/list', body: {
      if (status.isNotEmpty) 'status': status,
    });
    return (r as List?) ?? const [];
  }

  Future<Map<String, dynamic>> damageAdd({
    required String goodsCode,
    String goodsName = '',
    String batchNo = '',
    String binCode = '',
    required num qty,
    String reason = '',
    String imageUrl = '',
  }) =>
      ApiService.instance.post('/wms/app/damage/add', body: {
        'goodsCode': goodsCode,
        if (goodsName.isNotEmpty) 'goodsName': goodsName,
        if (batchNo.isNotEmpty) 'batchNo': batchNo,
        if (binCode.isNotEmpty) 'binCode': binCode,
        'qty': qty,
        if (reason.isNotEmpty) 'reason': reason,
        if (imageUrl.isNotEmpty) 'imageUrl': imageUrl,
      }).then(_asMap);

  Future<Map<String, dynamic>> damageAudit({
    required String damageId,
    required bool approved,
  }) =>
      ApiService.instance.post('/wms/app/damage/audit', body: {
        'damageId': damageId,
        'approved': approved,
      }).then(_asMap);

  // ============== 库存查询 ==============
  Future<List<dynamic>> stockQuery({
    String keyword = '',
    bool discrepancyOnly = false,
  }) async {
    final r = await ApiService.instance.post('/wms/app/stock-query', body: {
      if (keyword.isNotEmpty) 'keyword': keyword,
      if (discrepancyOnly) 'discrepancyOnly': true,
    });
    return (r as List?) ?? const [];
  }

  // ============== 异常中心 ==============
  Future<List<dynamic>> exceptionList({
    String status = '',
    String keyword = '',
  }) async {
    final r = await ApiService.instance.post('/wms/app/exception/list', body: {
      if (status.isNotEmpty) 'status': status,
      if (keyword.isNotEmpty) 'keyword': keyword,
    });
    return (r as List?) ?? const [];
  }

  Future<Map<String, dynamic>> exceptionReport({
    required String exceptionType,
    required String description,
    num? qty,
    String goodsCode = '',
    String sourceType = '',
    String waveId = '',
    String sourceBill = '',
    String binCode = '',
    String priority = '',
  }) =>
      ApiService.instance.post('/wms/app/exception/report', body: {
        'exceptionType': exceptionType,
        'description': description,
        if (qty != null) 'qty': qty,
        if (goodsCode.isNotEmpty) 'goodsCode': goodsCode,
        if (sourceType.isNotEmpty) 'sourceType': sourceType,
        if (waveId.isNotEmpty) 'waveId': waveId,
        if (sourceBill.isNotEmpty) 'sourceBill': sourceBill,
        if (binCode.isNotEmpty) 'binCode': binCode,
        if (priority.isNotEmpty) 'priority': priority,
      }).then(_asMap);

  Future<Map<String, dynamic>> exceptionHandle({
    required String exceptionId,
    required String resolution,
  }) =>
      ApiService.instance.post('/wms/app/exception/handle', body: {
        'exceptionId': exceptionId,
        'resolution': resolution,
      }).then(_asMap);

  Future<Map<String, dynamic>> exceptionAssign({
    required String exceptionId,
    required String assignee,
  }) =>
      ApiService.instance.post('/wms/app/exception/assign', body: {
        'exceptionId': exceptionId,
        'assignee': assignee,
      }).then(_asMap);

  // ============== 主管派工 ==============
  Future<List<dynamic>> assignTasks() async {
    final r = await ApiService.instance
        .post('/wms/app/task-assign/tasks', body: {'scope': 'all'});
    return (r as List?) ?? const [];
  }

  Future<Map<String, dynamic>> assignTask({
    required String taskId,
    required String toAssignee,
  }) =>
      ApiService.instance.post('/wms/app/task-assign/assign', body: {
        'taskId': taskId,
        'toAssignee': toAssignee,
      }).then(_asMap);

  Future<Map<String, dynamic>> recallTask(String taskId) =>
      ApiService.instance.post('/wms/app/task-assign/recall',
          body: {'taskId': taskId}).then(_asMap);

  // ============== 绩效 ==============
  /// 缺省=本人当天。scope=team 须 view_team；export=true 须 performance.export。
  Future<List<dynamic>> performance({
    String scope = 'self',
    bool export = false,
    String from = '',
    String to = '',
  }) async {
    final r = await ApiService.instance.post('/wms/app/performance', body: {
      if (scope != 'self') 'scope': scope,
      if (export) 'export': true,
      if (from.isNotEmpty) 'from': from,
      if (to.isNotEmpty) 'to': to,
    });
    return (r as List?) ?? const [];
  }

  // ============== 拣货位绑定（上架类维护） ==============
  Future<List<dynamic>> bindingLookup({
    String binCode = '',
    String goodsCode = '',
  }) async {
    final r = await ApiService.instance.post('/wms/app/binding/lookup', body: {
      if (binCode.isNotEmpty) 'binCode': binCode,
      if (goodsCode.isNotEmpty) 'goodsCode': goodsCode,
    });
    return (r as List?) ?? const [];
  }

  Future<Map<String, dynamic>> bindingBind(Map<String, dynamic> req) =>
      ApiService.instance
          .post('/wms/app/binding/bind', body: req).then(_asMap);

  Future<Map<String, dynamic>> bindingUnbind(String bindingId) =>
      ApiService.instance.post('/wms/app/binding/unbind',
          body: {'bindingId': bindingId}).then(_asMap);

  Future<Map<String, dynamic>> bindingTransfer({
    required String goodsCode,
    required String fromBin,
    required String toBin,
    String toZoneCode = '',
  }) =>
      ApiService.instance.post('/wms/app/binding/transfer', body: {
        'goodsCode': goodsCode,
        'fromBin': fromBin,
        'toBin': toBin,
        if (toZoneCode.isNotEmpty) 'toZoneCode': toZoneCode,
      }).then(_asMap);

  // ============== 工具 ==============
  static Map<String, dynamic> _asMap(dynamic v) {
    if (v is Map<String, dynamic>) return v;
    if (v is Map) return Map<String, dynamic>.from(v);
    return <String, dynamic>{};
  }
}
