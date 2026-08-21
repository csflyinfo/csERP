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

  /// 从本地存储恢复登录态（APP 启动时调用）。
  Future<bool> restore() async {
    final prefs = await SharedPreferences.getInstance();
    final token = prefs.getString(AppConfig.tokenKey) ?? '';
    if (token.isEmpty) return false;
    final driver = Driver(
      token: token,
      driverId: prefs.getString(AppConfig.driverIdKey) ?? '',
      driverCode: '',
      driverName: prefs.getString(AppConfig.driverNameKey) ?? '',
      mobile: '',
      roleCode: 'DRIVER',
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

  /// 司机登录（开发期验证码固定 888888）。
  Future<Driver> login(String mobile, String verifyCode) async {
    final data = await ApiService.instance.post('/tms/app/login', body: {
      'mobile': mobile,
      'verifyCode': verifyCode,
    }) as Map<String, dynamic>;
    final driver = Driver.fromJson(data);
    _current = driver;
    ApiService.instance.setToken(driver.token);
    // 登录响应已带参数快照（PRD-26 §5.5），直接落地，无需再请求一次
    await ParamService.instance.applyFromLogin(data['params']);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(AppConfig.tokenKey, driver.token);
    await prefs.setString(AppConfig.driverIdKey, driver.driverId);
    await prefs.setString(AppConfig.driverNameKey, driver.driverName);
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
  }
}
