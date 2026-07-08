import 'package:shared_preferences/shared_preferences.dart';
import '../config/app_config.dart';
import '../models/driver.dart';
import 'api_service.dart';

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
