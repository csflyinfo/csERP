import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import '../config/app_config.dart';
import 'api_service.dart';

/// 可选作业仓库（两步登录第二步）。
class WarehouseOption {
  final String id;
  final String code;
  final String name;
  const WarehouseOption({required this.id, this.code = '', this.name = ''});

  String get label => name.isNotEmpty
      ? (code.isNotEmpty ? '$name（$code）' : name)
      : (code.isNotEmpty ? code : id);

  factory WarehouseOption.fromJson(Map<String, dynamic> j) => WarehouseOption(
        id: j['warehouseId']?.toString() ?? '',
        code: j['warehouseCode']?.toString() ?? '',
        name: j['warehouseName']?.toString() ?? '',
      );
}

/// 当前登录用户（仓库作业员）。字段与 POST /wms/app/login 响应的 user 对齐。
class PdaUser {
  final String userId;
  final String username;
  final String displayName;
  final String employeeId;
  final List<String> roleCodes;
  final String primaryRoleCode;
  final String roleName;
  final String warehouseId;
  final String warehouseCode;
  final String warehouseName;
  final bool mustChangePwd;
  const PdaUser({
    required this.userId,
    required this.username,
    required this.displayName,
    required this.employeeId,
    required this.roleCodes,
    required this.primaryRoleCode,
    required this.roleName,
    required this.warehouseId,
    required this.warehouseCode,
    required this.warehouseName,
    required this.mustChangePwd,
  });

  /// 兼容旧页面：单一主角色编码。
  String get roleCode => primaryRoleCode;

  factory PdaUser.fromJson(Map<String, dynamic> j) {
    final roles = (j['roles'] as List?) ?? const [];
    String roleName = '';
    for (final r in roles) {
      if (r is Map) {
        final n = r['roleName']?.toString() ?? r['role_name']?.toString() ?? '';
        if (n.isNotEmpty) {
          roleName = n;
          break;
        }
      }
    }
    return PdaUser(
      userId: j['userId']?.toString() ?? '',
      username: j['username']?.toString() ?? '',
      displayName:
          j['displayName']?.toString() ?? j['username']?.toString() ?? '',
      employeeId: j['employeeId']?.toString() ?? '',
      roleCodes: ((j['roleCodes'] as List?) ?? const [])
          .map((e) => e.toString())
          .toList(),
      primaryRoleCode: j['primaryRoleCode']?.toString() ??
          j['roleCode']?.toString() ??
          '',
      roleName: roleName,
      warehouseId: j['warehouseId']?.toString() ?? '',
      warehouseCode: j['warehouseCode']?.toString() ?? '',
      warehouseName: j['warehouseName']?.toString() ?? '',
      mustChangePwd: j['mustChangePwd'] == true,
    );
  }

  Map<String, dynamic> toJson() => {
        'userId': userId,
        'username': username,
        'displayName': displayName,
        'employeeId': employeeId,
        'roleCodes': roleCodes,
        'primaryRoleCode': primaryRoleCode,
        'roleName': roleName,
        'warehouseId': warehouseId,
        'warehouseCode': warehouseCode,
        'warehouseName': warehouseName,
        'mustChangePwd': mustChangePwd,
      };
}

/// 登录结果：多仓账号首步返回 needWarehouse=true + 仓库列表；
/// 单仓账号或第二步选仓成功返回 user。
class LoginOutcome {
  final bool needWarehouse;
  final List<WarehouseOption> warehouses;
  final PdaUser? user;
  const LoginOutcome.need(this.warehouses)
      : needWarehouse = true,
        user = null;
  const LoginOutcome.done(this.user)
      : needWarehouse = false,
        warehouses = const [];
}

class AuthService {
  AuthService._();
  static final AuthService instance = AuthService._();

  PdaUser? _current;
  PdaUser? get current => _current;
  bool get isLoggedIn => _current != null;

  bool _superAdmin = false;
  final Set<String> _funcs = {};
  final Set<String> _fields = {};
  final Set<String> _menuCodes = {};
  final Map<String, dynamic> _params = {};

  bool get isSuperAdmin => _superAdmin;

  /// 功能点裁剪：超管服务端已短路，前端同步放行。
  bool can(String funcCode) => _superAdmin || _funcs.contains(funcCode);

  /// 菜单/任务卡是否可见。
  bool hasMenu(String menuCode) => _superAdmin || _menuCodes.contains(menuCode);

  /// 敏感字段（如 VIEW_COST）。
  bool hasField(String fieldCode) =>
      _superAdmin || _fields.contains(fieldCode);

  /// 登录下发的 PDA 参数快照（键缺失返回 dft）。
  String param(String key, [String dft = '']) {
    final v = _params[key];
    if (v == null) return dft;
    final s = v.toString();
    return s.isEmpty ? dft : s;
  }

  Future<bool> restore() async {
    final prefs = await SharedPreferences.getInstance();
    final token = prefs.getString(AppConfig.tokenKey) ?? '';
    if (token.isEmpty) return false;
    final raw = prefs.getString(AppConfig.sessionKey);
    if (raw == null || raw.isEmpty) return false;
    try {
      final session = jsonDecode(raw) as Map<String, dynamic>;
      _current =
          PdaUser.fromJson((session['user'] as Map?)?.cast() ?? const {});
      _superAdmin = session['superAdmin'] == true;
      _funcs
        ..clear()
        ..addAll(((session['funcs'] as List?) ?? const [])
            .map((e) => e.toString()));
      _fields
        ..clear()
        ..addAll(((session['fields'] as List?) ?? const [])
            .map((e) => e.toString()));
      _menuCodes
        ..clear()
        ..addAll(_flattenMenus((session['menus'] as List?) ?? const []));
      _params
        ..clear()
        ..addAll((session['params'] as Map?)?.cast() ?? const {});
      ApiService.instance.setToken(token);
      return true;
    } catch (_) {
      return false;
    }
  }

  /// 两步登录。warehouseId 为空时：单仓自动签发，多仓返回仓库列表。
  Future<LoginOutcome> login(String username, String password,
      {String? warehouseId}) async {
    final data = await ApiService.instance.post('/wms/app/login', body: {
      'username': username,
      'password': password,
      if (warehouseId != null && warehouseId.isNotEmpty)
        'warehouseId': warehouseId,
    }) as Map<String, dynamic>;

    if (data['needWarehouse'] == true) {
      final list = ((data['warehouses'] as List?) ?? const [])
          .map((e) => WarehouseOption.fromJson(
              Map<String, dynamic>.from(e as Map)))
          .toList();
      return LoginOutcome.need(list);
    }

    final token = data['token']?.toString() ?? '';
    if (token.isEmpty) {
      throw StateError('登录响应缺少 token');
    }
    final user = PdaUser.fromJson(
        (data['user'] as Map?)?.cast() ?? const {});
    _current = user;
    _superAdmin = data['superAdmin'] == true;
    _funcs
      ..clear()
      ..addAll(((data['funcs'] as List?) ?? const [])
          .map((e) => e.toString()));
    _fields
      ..clear()
      ..addAll(((data['fields'] as List?) ?? const [])
          .map((e) => e.toString()));
    final menus = (data['menus'] as List?) ?? const [];
    _menuCodes
      ..clear()
      ..addAll(_flattenMenus(menus));
    _params
      ..clear()
      ..addAll((data['params'] as Map?)?.cast() ?? const {});
    ApiService.instance.setToken(token);

    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(AppConfig.tokenKey, token);
    await prefs.setString(
        AppConfig.sessionKey,
        jsonEncode({
          'user': user.toJson(),
          'menus': menus,
          'funcs': _funcs.toList(),
          'fields': _fields.toList(),
          'superAdmin': _superAdmin,
          'params': _params,
        }));
    // 清理旧版本残留
    await prefs.remove(AppConfig.userKey);
    return LoginOutcome.done(user);
  }

  /// 401 回调使用：只清本地态，不打登出端点。
  Future<void> clearSession() async {
    _current = null;
    _superAdmin = false;
    _funcs.clear();
    _fields.clear();
    _menuCodes.clear();
    _params.clear();
    ApiService.instance.clearToken();
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(AppConfig.tokenKey);
    await prefs.remove(AppConfig.sessionKey);
  }

  /// PDA 无状态 JWT，登出只清本地（后端无专用登出端点）。
  Future<void> logout() => clearSession();

  /// 修改密码复用 ERP 端点（接受任意已认证 token）；载荷 wms_pda.profile.change_pwd。
  Future<void> changePassword(String oldPassword, String newPassword) async {
    await ApiService.instance.post('/auth/change-password', body: {
      'oldPassword': oldPassword,
      'newPassword': newPassword,
    });
  }

  /// 递归拍平菜单树取 code 集合。
  static Set<String> _flattenMenus(List<dynamic> nodes) {
    final out = <String>{};
    void walk(dynamic n) {
      if (n is! Map) return;
      final m = Map<String, dynamic>.from(n);
      final code = m['code']?.toString() ?? '';
      if (code.isNotEmpty) out.add(code);
      for (final c in (m['children'] as List?) ?? const []) {
        walk(c);
      }
    }

    for (final n in nodes) {
      walk(n);
    }
    return out;
  }
}
