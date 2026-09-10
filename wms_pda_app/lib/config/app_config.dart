import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:shared_preferences/shared_preferences.dart';

/// 全局配置：API 地址、超时、token 持久化 key。
///
/// 雷电/MuMu 等 VirtualBox 系模拟器网段是 172.16.x.x，没有 10.0.2.2 别名，
/// 因此默认用宿主机局域网 IP。换网络后在"我的→服务器地址"里改，不必重新出包。
class AppConfig {
  AppConfig._();

  /// 开发期宿主机 IP。打包前 `ipconfig` 确认；或用 --dart-define 覆盖。
  static const String devHost = String.fromEnvironment(
    'DEV_HOST',
    defaultValue: '192.168.0.237',
  );

  static const String apiBaseKey = 'wms_pda_api_base';
  static const String tokenKey = 'wms_pda_token';

  /// 旧版本用户缓存 key（登录成功后清理）。
  static const String userKey = 'wms_pda_user';

  /// PRD-28：完整登录快照（user/menus/funcs/fields/params/superAdmin）。
  static const String sessionKey = 'wms_pda_session';

  static String _override = '';

  static String get apiBase {
    if (_override.isNotEmpty) return _override;
    const env = String.fromEnvironment('API_BASE', defaultValue: '');
    if (env.isNotEmpty) return env;
    return kIsWeb ? 'http://localhost:8080/api' : 'http://$devHost:8080/api';
  }

  static String get defaultApiBase {
    const env = String.fromEnvironment('API_BASE', defaultValue: '');
    if (env.isNotEmpty) return env;
    return kIsWeb ? 'http://localhost:8080/api' : 'http://$devHost:8080/api';
  }

  static bool get hasOverride => _override.isNotEmpty;

  static Future<void> loadOverride() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      _override = prefs.getString(apiBaseKey) ?? '';
    } catch (_) {
      _override = '';
    }
  }

  static Future<void> saveOverride(String value) async {
    final normalized = normalize(value);
    _override = normalized;
    final prefs = await SharedPreferences.getInstance();
    if (normalized.isEmpty) {
      await prefs.remove(apiBaseKey);
    } else {
      await prefs.setString(apiBaseKey, normalized);
    }
  }

  static String normalize(String raw) {
    var v = raw.trim();
    if (v.isEmpty) return '';
    if (!v.startsWith('http://') && !v.startsWith('https://')) {
      v = 'http://$v';
    }
    if (!v.contains('/api')) {
      v = v.endsWith('/') ? '${v}api' : '$v/api';
    }
    return v;
  }

  static const Duration connectTimeout = Duration(seconds: 10);
  static const Duration receiveTimeout = Duration(seconds: 30);
}
