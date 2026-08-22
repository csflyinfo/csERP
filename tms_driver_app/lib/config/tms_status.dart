/// 调度单状态中文文案（司机端视角）。
///
/// 集中一处，消除 home_page / dispatch_stores_page / loading_confirm_page
/// 各自硬编码 map 导致的漂移（例如 ASSIGNED 在一处「待接单」、另一处「已分配」）。
///
/// 注意这是**司机端**口径：ACCEPTED 显示「待装车」（接单后的下一步动作），
/// 后台调度端 TmsDispatchController 仍可显示「已接单」，两端各取所需。
String dispatchStatusText(String? code) {
  switch (code) {
    case 'DRAFT':
      return '草稿';
    case 'ASSIGNED':
      return '待接单';
    case 'ACCEPTED':
      return '待装车';
    case 'LOADED':
      return '已装车';
    case 'DEPARTED':
      return '已发车';
    case 'DELIVERING':
      return '配送中';
    case 'COMPLETED':
      return '已完成';
    case 'CANCELLED':
      return '已取消';
    default:
      return code ?? '';
  }
}
