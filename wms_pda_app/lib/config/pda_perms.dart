/// PDA RBAC 编码常量（PRD-28 卡片9，方案 §7.2.1）。
///
/// 与后端 WmsAppController 的功能点编码逐字一致；菜单码与 MenuConfig 的
/// wms_pda.* 菜单一致。前端只做按钮/卡片的显隐裁剪，真正的安全边界在后端
/// （@RequirePerm + PdaAppGuardInterceptor），任何隐藏按钮直调仍会被 403 拒绝。
class PdaMenu {
  PdaMenu._();
  static const home = 'wms_pda.home';
  static const receive = 'wms_pda.receive';
  static const receiveReturn = 'wms_pda.receive_return';
  static const otherInbound = 'wms_pda.other_inbound';
  static const putaway = 'wms_pda.putaway';
  static const replenish = 'wms_pda.replenish';
  static const pick = 'wms_pda.pick';
  static const check = 'wms_pda.check';
  static const load = 'wms_pda.load';
  static const stocktake = 'wms_pda.stocktake';
  static const move = 'wms_pda.move';
  static const damage = 'wms_pda.damage';
  static const stockQuery = 'wms_pda.stock_query';
  static const exception = 'wms_pda.exception';
  static const taskAssign = 'wms_pda.task_assign';
  static const performance = 'wms_pda.performance';
  static const profile = 'wms_pda.profile';
}

class PdaPerm {
  PdaPerm._();

  // 我的 / 首页
  static const profileView = 'wms_pda.profile.view';
  static const homeScan = 'wms_pda.home.scan';
  static const switchWarehouse = 'wms_pda.profile.switch_warehouse';
  static const changePwd = 'wms_pda.profile.change_pwd';

  // 拣货
  static const pickView = 'wms_pda.pick.view';
  static const pickStart = 'wms_pda.pick.start';
  static const pickScan = 'wms_pda.pick.scan';
  static const pickConfirm = 'wms_pda.pick.confirm';
  static const pickShort = 'wms_pda.pick.short_pick';
  static const pickSkip = 'wms_pda.pick.skip';
  static const pickTransfer = 'wms_pda.pick.transfer';

  // 复核 / 装车
  static const checkView = 'wms_pda.check.view';
  static const checkScan = 'wms_pda.check.scan';
  static const checkConfirm = 'wms_pda.check.confirm';
  static const checkException = 'wms_pda.check.exception';
  static const checkPack = 'wms_pda.check.pack';
  static const loadView = 'wms_pda.load.view';
  static const loadScan = 'wms_pda.load.scan';
  static const loadConfirm = 'wms_pda.load.confirm';

  // 收货（采购 / 退货 / 其他）
  static const receiveView = 'wms_pda.receive.view';
  static const receiveStart = 'wms_pda.receive.start';
  static const receiveScan = 'wms_pda.receive.scan';
  static const receiveConfirm = 'wms_pda.receive.confirm';
  static const receiveRecheck = 'wms_pda.receive.recheck';
  static const receivePrint = 'wms_pda.receive.print_label';
  static const receiveOver = 'wms_pda.receive.over_receive';
  static const returnView = 'wms_pda.receive_return.view';
  static const returnScan = 'wms_pda.receive_return.scan';
  static const returnConfirm = 'wms_pda.receive_return.confirm';
  static const otherView = 'wms_pda.other_inbound.view';
  static const otherAdd = 'wms_pda.other_inbound.add';
  static const otherConfirm = 'wms_pda.other_inbound.confirm';

  // 上架
  static const putawayView = 'wms_pda.putaway.view';
  static const putawayStart = 'wms_pda.putaway.start';
  static const putawayScan = 'wms_pda.putaway.scan';
  static const putawayConfirm = 'wms_pda.putaway.confirm';
  static const putawayFreeBin = 'wms_pda.putaway.free_bin';
  static const putawaySplit = 'wms_pda.putaway.split';

  // 补货 / 移库
  static const replenishView = 'wms_pda.replenish.view';
  static const replenishConfirm = 'wms_pda.replenish.confirm';
  static const replenishUrgent = 'wms_pda.replenish.urgent';
  static const moveView = 'wms_pda.move.view';
  static const moveAdd = 'wms_pda.move.add';
  static const moveConfirm = 'wms_pda.move.confirm';

  // 盘点
  static const takeView = 'wms_pda.stocktake.view';
  static const takeScan = 'wms_pda.stocktake.scan';
  static const takeInput = 'wms_pda.stocktake.input';
  static const takeSubmit = 'wms_pda.stocktake.submit';
  static const takeAudit = 'wms_pda.stocktake.audit';

  // 报损
  static const damageView = 'wms_pda.damage.view';
  static const damageAdd = 'wms_pda.damage.add';
  static const damageAudit = 'wms_pda.damage.audit';

  // 库存查询
  static const stockView = 'wms_pda.stock_query.view';
  static const stockBatch = 'wms_pda.stock_query.view_batch';
  static const stockCost = 'wms_pda.stock_query.view_cost';

  // 异常中心
  static const excView = 'wms_pda.exception.view';
  static const excReport = 'wms_pda.exception.report';
  static const excHandle = 'wms_pda.exception.handle';
  static const excAssign = 'wms_pda.exception.assign';

  // 主管派工
  static const assignView = 'wms_pda.task_assign.view';
  static const assignDo = 'wms_pda.task_assign.assign';
  static const assignRecall = 'wms_pda.task_assign.recall';

  // 绩效
  static const perfSelf = 'wms_pda.performance.view_self';
  static const perfTeam = 'wms_pda.performance.view_team';
  static const perfExport = 'wms_pda.performance.export';

  /// 入库类型 → 对应"查看"功能点（与后端 receiveGroupView 同口径）。
  static String receiveGroupView(String inboundType) => switch (inboundType) {
        'SALES_RETURN' || 'RETURN' || 'REJECT' => returnView,
        'OTHER' || 'TRANSFER' => otherView,
        _ => receiveView,
      };

  /// 入库类型 → 扫码收货/逐行登记功能点（OTHER/TRANSFER 组用 confirm 收口）。
  static String receiveGroupScan(String inboundType) => switch (inboundType) {
        'SALES_RETURN' || 'RETURN' || 'REJECT' => returnScan,
        'OTHER' || 'TRANSFER' => otherConfirm,
        _ => receiveScan,
      };

  /// 入库类型 → 完成收货功能点。
  static String receiveGroupConfirm(String inboundType) => switch (inboundType) {
        'SALES_RETURN' || 'RETURN' || 'REJECT' => returnConfirm,
        'OTHER' || 'TRANSFER' => otherConfirm,
        _ => receiveConfirm,
      };
}
