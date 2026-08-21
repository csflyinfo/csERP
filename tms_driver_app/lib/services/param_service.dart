import 'package:shared_preferences/shared_preferences.dart';
import '../config/app_config.dart';
import '../models/app_params.dart';
import 'api_service.dart';

/// APP 运行参数服务（PRD-26 §5.5）。
///
/// 设计要点：
///  1. **同步读取**。各业务页面在 build 与提交校验里都要读参数，若做成异步，
///     每个页面都得加一次 FutureBuilder / loading 态，改动面会失控。
///     因此参数常驻内存，`current` 永远立即可用，最坏情况返回默认值。
///  2. **登录时下发、按需刷新**。参数随登录响应一次性带回；已登录的司机
///     靠 `refresh()`（冷启动恢复、下拉刷新任务列表）拉最新值。
///  3. **绝不因参数失败阻塞业务**。刷新失败保留上一次的值，
///     参数只该影响校验松紧，不能让司机连任务都看不了。
class ParamService {
  ParamService._();
  static final ParamService instance = ParamService._();

  AppParams _current = AppParams.defaults;

  /// 当前生效参数，任何时刻都可同步读取。
  AppParams get current => _current;

  /// 从本地缓存恢复（APP 启动时、拉接口之前调用）。
  ///
  /// 先用缓存把界面渲染出来，再异步 refresh 覆盖，
  /// 避免弱网下首屏按默认值渲染、几秒后按钮突然变化的跳变。
  Future<void> restore() async {
    final prefs = await SharedPreferences.getInstance();
    _current = AppParams.decode(prefs.getString(AppConfig.appParamsKey));
  }

  /// 用登录响应里的 params 字段直接落地，省一次网络往返。
  Future<void> applyFromLogin(Object? raw) async {
    if (raw is! Map) return;
    await _save(AppParams.fromJson(Map<String, dynamic>.from(raw)));
  }

  /// 主动拉取最新参数。
  ///
  /// 返回是否刷新成功；失败时保留原值，调用方无需处理异常。
  Future<bool> refresh() async {
    try {
      final data = await ApiService.instance.post('/tms/app/params');
      if (data is Map) {
        await _save(AppParams.fromJson(Map<String, dynamic>.from(data)));
        return true;
      }
    } catch (_) {
      // 忽略：网络异常/未登录时沿用现有参数
    }
    return false;
  }

  Future<void> _save(AppParams params) async {
    _current = params;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(AppConfig.appParamsKey, params.encode());
  }
}
