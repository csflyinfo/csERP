/// 司机端功能权限码（PRD-28 卡片10，方案 §7.3）。
///
/// 必须与后端 PermissionRegistry.DRIVER_ROLE_FUNCS / @RequirePerm 注解
/// 使用的编码逐字一致：APP 只做按钮/入口裁剪，真正的拦截在服务端
/// （RequirePermInterceptor 403）。编码不一致的后果是按钮被错误隐藏或显示，
/// 不会造成越权（越权由后端兜住），但仍应把这里当成服务端契约。
///
/// 三个内置角色的默认分配（详见开发计划卡片10）：
///   R_TMS_DRIVER  司机：全部功能（不含仅班长的 team_view/export）；
///   R_TMS_LOADER  装车员：仅 loading.* 与 profile.*；
///   R_TMS_LEADER  班长：司机全部 + collect_records.export + profile.team_view。
class DriverPerms {
  DriverPerms._();

  // 首页/接单
  static const String homeView = 'driver.home.view';
  static const String homeAccept = 'driver.home.accept';
  static const String homeRefuse = 'driver.home.refuse';

  // 装车
  static const String loadingView = 'driver.loading.view';
  static const String loadingConfirmPoint = 'driver.loading.confirm_point';
  static const String loadingConfirmAll = 'driver.loading.confirm_all';
  static const String loadingViewBills = 'driver.loading.view_bills';
  static const String loadingReturnPoint = 'driver.loading.return_point';
  static const String loadingScan = 'driver.loading.scan';

  // 发车
  static const String departConfirm = 'driver.depart.confirm';
  static const String departMileage = 'driver.depart.mileage';
  static const String departAppendAccept = 'driver.depart.append_accept';

  // 配送中
  static const String deliveringView = 'driver.delivering.view';
  static const String deliveringNavigation = 'driver.delivering.navigation';

  // 到店
  static const String arriveConfirm = 'driver.arrive.confirm';

  // 签收
  static const String signView = 'driver.sign.view';
  static const String signNormal = 'driver.sign.normal';
  static const String signPartial = 'driver.sign.partial';
  static const String signReject = 'driver.sign.reject';
  static const String signPhoto = 'driver.sign.photo';
  static const String signEsign = 'driver.sign.esign';
  static const String signSaveDraft = 'driver.sign.save_draft';

  // 门店结算/收款
  static const String settlementView = 'driver.settlement.view';
  static const String settlementSettle = 'driver.settlement.settle';
  static const String settlementMerge = 'driver.settlement.merge';
  static const String settlementOnCredit = 'driver.settlement.on_credit';
  static const String settlementPhoto = 'driver.settlement.photo';
  static const String settlementSelectAccount = 'driver.settlement.select_account';

  // 交账
  static const String handoverView = 'driver.handover.view';
  static const String handoverSubmit = 'driver.handover.submit';
  static const String handoverEsign = 'driver.handover.esign';
  static const String handoverPrint = 'driver.handover.print';

  // 退货
  static const String returnView = 'driver.return.view';
  static const String returnOnsite = 'driver.return.onsite';
  static const String returnConfirm = 'driver.return.confirm';
  static const String returnWarehouse = 'driver.return.warehouse';
  static const String returnPhoto = 'driver.return.photo';

  // 异常上报
  static const String exceptionView = 'driver.exception.view';
  static const String exceptionReport = 'driver.exception.report';

  // 历史行程
  static const String historyView = 'driver.history.view';

  // 收款记录
  static const String collectRecordsView = 'driver.collect_records.view';
  static const String collectRecordsExport = 'driver.collect_records.export';

  // 门店定位修正
  static const String storeLocationEdit = 'driver.store_location.edit';

  // 消息
  static const String notificationView = 'driver.notification.view';

  // 我的
  static const String profileView = 'driver.profile.view';
  static const String profileChangePwd = 'driver.profile.change_pwd';
  static const String profileChangeServer = 'driver.profile.change_server';
  static const String profileLogout = 'driver.profile.logout';

  /// 班长专属：查看同部门司机任务。
  static const String profileTeamView = 'driver.profile.team_view';
}
