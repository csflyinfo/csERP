import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/notification.dart';
import '../services/api_service.dart';
import '../services/connectivity_service.dart';

/// 消息列表查询参数。
///
/// family 参数类必须实现 ==/hashCode，否则每次 build 都会生成新实例导致重复请求。
class NotifyListArgs {
  final bool onlyUnread;
  final String notifyType;

  const NotifyListArgs({this.onlyUnread = false, this.notifyType = ''});

  Map<String, dynamic> toJson() => {
        'onlyUnread': onlyUnread,
        if (notifyType.isNotEmpty) 'notifyType': notifyType,
        'limit': 100,
      };

  @override
  bool operator ==(Object other) =>
      other is NotifyListArgs &&
      other.onlyUnread == onlyUnread &&
      other.notifyType == notifyType;

  @override
  int get hashCode => Object.hash(onlyUnread, notifyType);
}

/// 消息列表。
///
/// 刻意不做离线缓存：消息的价值在于时效（新任务、异常回执），
/// 缓存出来的旧消息会让司机误以为没有新动态，反而有害。
final notificationListProvider =
    FutureProvider.family<NotifyList, NotifyListArgs>((ref, args) async {
  if (!ConnectivityService.instance.isOnline) {
    throw Exception('当前处于离线状态，消息需联网查看');
  }
  final data = await ApiService.instance
      .post('/tms/app/notification/list', body: args.toJson());
  return NotifyList.fromJson(data as Map<String, dynamic>);
});

/// 未读消息数（角标 + 前台轮询）。
///
/// 用 AsyncNotifier 而非 FutureProvider：需要对外暴露 refresh 与 startPolling，
/// 且轮询间隔由服务端下发（pollSeconds），拿到首个响应后才能确定定时器周期。
final notifyUnreadProvider =
    AsyncNotifierProvider<NotifyUnreadNotifier, NotifyUnread>(
        NotifyUnreadNotifier.new);

class NotifyUnreadNotifier extends AsyncNotifier<NotifyUnread> {
  Timer? _timer;
  int _lastUnread = 0;

  @override
  Future<NotifyUnread> build() async {
    // provider 被销毁时必须停表，否则页面退出后定时器仍在打接口
    ref.onDispose(() {
      _timer?.cancel();
      _timer = null;
    });
    final r = await _load();
    _schedule(r.pollSeconds);
    return r;
  }

  /// 拉取失败一律返回零值而不抛错：角标只是辅助信息，
  /// 让 AppBar 因为一次网络抖动就显示错误态不划算。
  Future<NotifyUnread> _load() async {
    if (!ConnectivityService.instance.isOnline) {
      return NotifyUnread(unreadCount: _lastUnread);
    }
    try {
      final data = await ApiService.instance.post('/tms/app/notification/unread-count');
      final r = NotifyUnread.fromJson(data as Map<String, dynamic>);
      _lastUnread = r.unreadCount;
      return r;
    } catch (_) {
      return NotifyUnread(unreadCount: _lastUnread);
    }
  }

  void _schedule(int seconds) {
    _timer?.cancel();
    // 兜底夹取：参数误配成 1 秒会打满后端并耗尽司机流量
    final period = seconds < 30 ? 30 : (seconds > 600 ? 600 : seconds);
    _timer = Timer.periodic(Duration(seconds: period), (_) => refresh());
  }

  /// 静默刷新：不置 loading 态，避免轮询时角标反复闪烁。
  Future<void> refresh() async {
    final r = await _load();
    state = AsyncValue.data(r);
    if (_timer == null) _schedule(r.pollSeconds);
  }

  /// 本地立即扣减，用于点开消息后即时反馈，无需等下一轮轮询。
  void decrease([int n = 1]) {
    final cur = state.value;
    if (cur == null) return;
    final left = cur.unreadCount - n;
    _lastUnread = left < 0 ? 0 : left;
    state = AsyncValue.data(NotifyUnread(
      unreadCount: _lastUnread,
      urgentCount: cur.urgentCount,
      pollSeconds: cur.pollSeconds,
    ));
  }

  void clear() {
    final cur = state.value;
    _lastUnread = 0;
    state = AsyncValue.data(NotifyUnread(
      unreadCount: 0,
      urgentCount: 0,
      pollSeconds: cur?.pollSeconds ?? 60,
    ));
  }
}

/// 标记单条已读。
Future<void> markNotifyRead(String notifyId) async {
  await ApiService.instance
      .post('/tms/app/notification/read', body: {'notifyId': notifyId});
}

/// 全部已读。
Future<int> markAllNotifyRead() async {
  final data = await ApiService.instance.post('/tms/app/notification/read-all');
  final m = data as Map<String, dynamic>?;
  return (m?['updated'] as num?)?.toInt() ?? 0;
}
