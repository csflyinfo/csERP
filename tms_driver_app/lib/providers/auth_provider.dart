import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/auth_service.dart';
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
  }

  Future<void> logout() async {
    await AuthService.instance.logout();
    state = null;
  }
}
