import 'dart:async';

/// 提交去重器（方案 V1.1 优化项三：防重复提交）。
///
/// 规则：
/// - 同一指纹（key）在 [window] 时间窗内的重复提交被直接拒绝；
/// - 已在执行中的提交（inFlight）再次触发也拒绝。
///
/// 与 [ConfirmButton] 的按钮置灰互补：按钮置灰挡住"同一个按钮"，
/// 本去重器能挡住"换个入口/快速多点"造成的重复请求。
class SubmitGuard {
  SubmitGuard._();
  static final SubmitGuard instance = SubmitGuard._();

  /// 最近提交时间：key → 提交时刻。
  final Map<String, DateTime> _last = {};

  /// 正在执行的提交集合。
  final Set<String> _inFlight = {};

  /// 默认去重窗口（方案：3 秒）。
  static const defaultWindow = Duration(seconds: 3);

  /// 尝试进入提交。
  ///
  /// - [key]：提交指纹（建议 接口路径 + 关键业务参数 拼接）；
  /// - 若在窗口内重复或正在执行，返回 false 表示应放弃本次提交；
  /// - 返回 true 表示放行，调用方最终必须调用 [complete] 释放。
  bool tryBegin(String key, {Duration window = defaultWindow}) {
    final now = DateTime.now();
    _last.removeWhere((_, t) => now.difference(t) > window);
    if (_inFlight.contains(key)) return false;
    final last = _last[key];
    if (last != null && now.difference(last) < window) return false;
    _last[key] = now;
    _inFlight.add(key);
    return true;
  }

  /// 提交结束（成功或失败都要调用），释放 inFlight 标记。
  void complete(String key) {
    _inFlight.remove(key);
  }

  /// 便捷包装：自动去重 + 结束释放。被去重拦截时 [action] 不会执行。
  /// 返回 action 的结果；若被拦截返回 null。
  Future<T?> run<T>(String key, Future<T> Function() action,
      {Duration window = defaultWindow}) async {
    if (!tryBegin(key, window: window)) return null;
    try {
      return await action();
    } finally {
      complete(key);
    }
  }
}
