import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import '../config/app_config.dart';
import 'api_service.dart';

/// 当前登录用户（仓库作业员）。
class PdaUser {
  final String userId;
  final String username;
  final String displayName;
  final String roleCode;
  final String roleName;
  const PdaUser({
    required this.userId,
    required this.username,
    required this.displayName,
    required this.roleCode,
    required this.roleName,
  });

  factory PdaUser.fromJson(Map<String, dynamic> j) => PdaUser(
        userId: j['userId']?.toString() ?? '',
        username: j['username']?.toString() ?? '',
        displayName: j['displayName']?.toString() ?? j['username']?.toString() ?? '',
        roleCode: j['roleCode']?.toString() ?? '',
        roleName: j['roleName']?.toString() ?? '',
      );

  Map<String, dynamic> toJson() => {
        'userId': userId,
        'username': username,
        'displayName': displayName,
        'roleCode': roleCode,
        'roleName': roleName,
      };
}

class AuthService {
  AuthService._();
  static final AuthService instance = AuthService._();

  PdaUser? _current;
  PdaUser? get current => _current;
  bool get isLoggedIn => _current != null;

  Future<bool> restore() async {
    final prefs = await SharedPreferences.getInstance();
    final token = prefs.getString(AppConfig.tokenKey) ?? '';
    if (token.isEmpty) return false;
    final userRaw = prefs.getString(AppConfig.userKey);
    if (userRaw == null || userRaw.isEmpty) return false;
    try {
      _current = PdaUser.fromJson(jsonDecode(userRaw) as Map<String, dynamic>);
      ApiService.instance.setToken(token);
      return true;
    } catch (_) {
      return false;
    }
  }

  Future<PdaUser> login(String username, String password) async {
    final data = await ApiService.instance.post('/auth/login', body: {
      'username': username,
      'password': password,
    }) as Map<String, dynamic>;
    final token = data['token']?.toString() ?? '';
    final user = PdaUser.fromJson(
      (data['user'] as Map<String, dynamic>?) ?? const {},
    );
    _current = user;
    ApiService.instance.setToken(token);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(AppConfig.tokenKey, token);
    await prefs.setString(AppConfig.userKey, jsonEncode(user.toJson()));
    return user;
  }

  Future<void> logout() async {
    _current = null;
    ApiService.instance.clearToken();
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(AppConfig.tokenKey);
    await prefs.remove(AppConfig.userKey);
    try {
      await ApiService.instance.post('/auth/logout');
    } catch (_) {
      // 本地登出即使后端不通也要执行
    }
  }
}
