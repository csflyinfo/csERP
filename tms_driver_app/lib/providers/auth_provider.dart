import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/auth_service.dart';
import '../services/location_service.dart';
import '../services/push_service.dart';
import '../models/driver.dart';

/// 鉴权状态：未登录时为 null。
final authProvider = StateNotifierProvider<AuthNotifier, Driver?>((ref) {
  return AuthNotifier();
});

class AuthNotifier extends StateNotifier<Driver?> {
  AuthNotifier() : super(null);

  Future<bool> restore() async {
    final ok = await AuthService.instance.restore();
    state = AuthService.instance.current;
    return ok;
  }

  Future<void> login(String mobile, String verifyCode) async {
    state = await AuthService.instance.login(mobile, verifyCode);
    // 登录后补报设备 CID：CID 可能在登录前就已下发，
    // 当时因拿不到 driverId 而暂缓上报，此处补齐绑定。
    await PushService.instance.onLogin();
  }

  Future<void> logout() async {
    // 退出登录必须停止 GPS 采集，否则后台 Timer 继续耗电且上报无归属
    LocationService.instance.stop();
    // 解绑设备必须放在清 token 之前：AuthService.logout 会立即
    // clearToken，之后再发请求缺少 Authorization 头会被判 401，
    // 后端也就取不到 driverId，导致设备永远解绑不掉。
    await PushService.instance.onLogout();
    await AuthService.instance.logout();
    state = null;
  }
}
