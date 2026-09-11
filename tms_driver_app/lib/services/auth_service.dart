import 'package:shared_preferences/shared_preferences.dart';
import '../config/app_config.dart';
import '../models/driver.dart';
import 'api_service.dart';
import 'param_service.dart';

/// 鉴权服务：司机登录、token 持久化、当前司机信息。
class AuthService {
  AuthService._();
  static final AuthService instance = AuthService._();

  Driver? _current;
  Driver? get current => _current;

  /// 当前登录司机是否拥有某功能码（供 UI 裁剪按钮/入口）。
  /// 未登录或老版本本地恢复（无权限快照）时放行，拦截由服务端负责。
  static bool hasPerm(String code) {
    final d = instance._current;
    return d == null || d.hasPerm(code);
  }

  /// 当前登录司机是否拥有某角色码（TMS_DRIVER/TMS_LOADER/TMS_LEADER）。
  static bool hasRole(String role) =>
      instance._current?.hasRole(role) ?? false;

  /// 从本地存储恢复登录态（APP 启动时调用）。
  Future<bool> restore() async {
    final prefs = await SharedPreferences.getInstance();
    final token = prefs.getString(AppConfig.tokenKey) ?? '';
    if (token.isEmpty) return false;
    final funcs = prefs.getStringList(AppConfig.driverFuncsKey);
    final roles = prefs.getStringList(AppConfig.driverRolesKey);
    final driver = Driver(
      token: token,
      driverId: prefs.getString(AppConfig.driverIdKey) ?? '',
      driverCode: '',
      driverName: prefs.getString(AppConfig.driverNameKey) ?? '',
      mobile: '',
      roleCode: 'DRIVER',
      // 老版本升级：本地没有权限快照，funcs=null → permsLoaded=false，
      // hasPerm 全部放行，等下次登录拿到真实集合后再裁剪。
      funcs: funcs,
      roleCodes: roles ?? const [],
      superAdmin: prefs.getBool(AppConfig.driverSuperAdminKey) ?? false,
      permsLoaded: funcs != null,
    );
    _current = driver;
    ApiService.instance.setToken(token);
    // 参数先用本地缓存立即生效，再异步拉最新值。
    // 不 await refresh：登录态恢复在启动路径上，等参数接口会拖慢首屏；
    // 拿到新值后各页面下次读 current 自然生效。
    await ParamService.instance.restore();
    ParamService.instance.refresh();
    return true;
  }

  /// 短信验证码登录（主路径，开发期验证码固定 888888）。
  Future<Driver> login(String mobile, String verifyCode) {
    return _login({
      'mobile': mobile,
      'verifyCode': verifyCode,
    });
  }

  /// 工号 + 密码登录（备选路径：装车员等管理员预建账号）。
  Future<Driver> loginByPassword(String employeeCode, String password) {
    return _login({
      'employeeCode': employeeCode,
      'password': password,
    });
  }

  Future<Driver> _login(Map<String, dynamic> body) async {
    final data = await ApiService.instance.post('/tms/app/login', body: body)
        as Map<String, dynamic>;
    final driver = Driver.fromJson(data);
    _current = driver;
    ApiService.instance.setToken(driver.token);
    // 登录响应已带参数快照（PRD-26 §5.5），直接落地，无需再请求一次
    await ParamService.instance.applyFromLogin(data['params']);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(AppConfig.tokenKey, driver.token);
    await prefs.setString(AppConfig.driverIdKey, driver.driverId);
    await prefs.setString(AppConfig.driverNameKey, driver.driverName);
    await prefs.setStringList(AppConfig.driverFuncsKey, driver.funcs);
    await prefs.setStringList(AppConfig.driverRolesKey, driver.roleCodes);
    await prefs.setBool(AppConfig.driverSuperAdminKey, driver.superAdmin);
    return driver;
  }

  /// 退出登录。
  Future<void> logout() async {
    _current = null;
    ApiService.instance.clearToken();
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(AppConfig.tokenKey);
    await prefs.remove(AppConfig.driverIdKey);
    await prefs.remove(AppConfig.driverNameKey);
    await prefs.remove(AppConfig.driverFuncsKey);
    await prefs.remove(AppConfig.driverRolesKey);
    await prefs.remove(AppConfig.driverSuperAdminKey);
  }
}
