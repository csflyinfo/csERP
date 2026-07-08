/// 全局配置：API 地址、超时、开发期固定验证码。
import 'package:flutter/foundation.dart' show kIsWeb;

class AppConfig {
  // 开发期指向本机后端；Web 下用 localhost，Android 模拟器用 10.0.2.2
  // 真机调试时通过编译参数覆盖：--dart-define=API_BASE=http://192.168.1.100:8080
  static String get apiBase {
    final env = const String.fromEnvironment('API_BASE', defaultValue: '');
    if (env.isNotEmpty) return env;
    return kIsWeb ? 'http://localhost:8080/api' : 'http://10.0.2.2:8080/api';
  }

  static const Duration connectTimeout = Duration(seconds: 10);
  static const Duration receiveTimeout = Duration(seconds: 30);

  // 开发期固定验证码（与后端 TmsAuthService.DEV_VERIFY_CODE 一致）
  static const String devVerifyCode = '888888';

  // token 持久化 key
  static const String tokenKey = 'tms_driver_token';
  static const String driverIdKey = 'tms_driver_id';
  static const String driverNameKey = 'tms_driver_name';
}
