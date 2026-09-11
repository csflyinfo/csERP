/// 司机模型。
///
/// PRD-28 卡片10 起，登录响应额外下发 funcs（功能码集合，用于按钮/入口裁剪）、
/// user.roleCodes（角色码：TMS_DRIVER/TMS_LOADER/TMS_LEADER）与 superAdmin。
/// 旧字段（driverId/driverCode/...）保留，兼容持久化与既有页面。
class Driver {
  final String token;
  final String driverId;
  final String driverCode;
  final String driverName;
  final String mobile;
  final String roleCode;

  /// 服务端下发的功能码集合（如 driver.sign.normal）。
  final List<String> funcs;

  /// 全部司机端角色码（一个账号可兼多角色，如司机兼装车员）。
  final List<String> roleCodes;

  /// 超管直通（服务端短路，APP 同样不裁剪）。
  final bool superAdmin;

  /// 本次登录是否拿到了服务端权限集。
  ///
  /// false 只出现在「老版本升级后用本地持久化 token 恢复」这一种场景：
  /// 本地没有 funcs，此时不能把按钮全藏起来（等于逼所有司机重新登录），
  /// hasPerm 一律放行、由服务端兜底；下次正常登录后即为 true。
  final bool permsLoaded;

  Driver({
    required this.token,
    required this.driverId,
    required this.driverCode,
    required this.driverName,
    required this.mobile,
    required this.roleCode,
    List<String>? funcs,
    List<String>? roleCodes,
    this.superAdmin = false,
    this.permsLoaded = true,
  })  : funcs = funcs ?? const [],
        roleCodes = roleCodes ?? const [];

  factory Driver.fromJson(Map<String, dynamic> j) {
    final user = j['user'] is Map<String, dynamic>
        ? j['user'] as Map<String, dynamic>
        : null;
    final legacyRole = j['roleCode']?.toString() ?? 'DRIVER';
    final roles = _stringList(user?['roleCodes']);
    return Driver(
      token: j['token']?.toString() ?? '',
      driverId: j['driverId']?.toString() ??
          (user?['employeeId']?.toString() ?? ''),
      driverCode: j['driverCode']?.toString() ??
          (user?['username']?.toString() ?? ''),
      driverName: j['driverName']?.toString() ??
          (user?['displayName']?.toString() ?? ''),
      mobile: j['mobile']?.toString() ?? '',
      roleCode: legacyRole,
      funcs: _stringList(j['funcs']),
      roleCodes: roles.isNotEmpty
          ? roles
          : (legacyRole == 'DRIVER' ? const [] : [legacyRole]),
      superAdmin: j['superAdmin'] == true,
      permsLoaded: true,
    );
  }

  /// 是否拥有某功能码。超管直通；老版本本地恢复（权限集未落库）时放行；
  /// 否则在服务端下发集合里精确匹配。
  ///
  /// 注意：这里只决定按钮/入口「显不显示」，不决定「能不能操作成功」——
  /// 服务端 RequirePermInterceptor 才是安全边界，漏裁一个按钮不会越权。
  bool hasPerm(String code) =>
      superAdmin || !permsLoaded || funcs.contains(code);

  /// 是否拥有某角色码（TMS_DRIVER/TMS_LOADER/TMS_LEADER）。
  bool hasRole(String role) => roleCodes.contains(role);

  static List<String> _stringList(dynamic v) {
    if (v is List) return v.map((e) => e.toString()).toList(growable: false);
    return const [];
  }
}
