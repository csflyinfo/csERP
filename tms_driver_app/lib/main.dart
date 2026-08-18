import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'config/theme.dart';
import 'models/notification.dart';
import 'providers/auth_provider.dart';
import 'providers/notification_provider.dart';
import 'services/api_service.dart';
import 'services/connectivity_service.dart';
import 'services/local_db_service.dart';
import 'services/push_service.dart';
import 'services/sync_service.dart';
import 'ui/home/home_page.dart';
import 'ui/home/notification_page.dart';
import 'ui/login/login_page.dart';

/// TMS 司机配送 APP 入口（V1.3 离线能力）。
void main() {
  runApp(const ProviderScope(child: TmsDriverApp()));
}

class TmsDriverApp extends ConsumerStatefulWidget {
  const TmsDriverApp({super.key});
  @override
  ConsumerState<TmsDriverApp> createState() => _TmsDriverAppState();
}

class _TmsDriverAppState extends ConsumerState<TmsDriverApp> {
  bool _bootstrapping = true;
  bool _loggedIn = false;

  /// 通知点击发生在 UI 树之外（甚至 APP 未启动时），拿不到任何 BuildContext，
  /// 而本工程没有集中路由表，只能靠全局 navigatorKey 做跳转。
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();

  @override
  void initState() {
    super.initState();
    _bootstrap();
  }

  Future<void> _bootstrap() async {
    // 401 时跳登录
    ApiService.instance.setUnauthorizedHandler(() {
      if (!_loggedIn) return;
      ref.read(authProvider.notifier).logout();
    });

    // Web 模式下 sqflite/path_provider 不可用，跳过离线初始化
    if (!kIsWeb) {
      try {
        await LocalDbService.instance.init();
        await ConnectivityService.instance.init();
        await SyncService.instance.init();
      } catch (e) {
        // 离线能力初始化失败不影响在线功能
        debugPrint('离线能力初始化失败: $e');
      }
    } else {
      await ConnectivityService.instance.init();
    }

    final ok = await ref.read(authProvider.notifier).restore();

    // 推送初始化放在 restore 之后：CID 回调里要判断登录态才决定是否上报，
    // 先恢复登录态可让老用户在冷启动后立刻完成 CID 绑定，少等一轮。
    _wirePush();
    await PushService.instance.init();
    if (ok) await PushService.instance.registerToken();

    if (mounted) {
      setState(() {
        _loggedIn = ok;
        _bootstrapping = false;
      });
    }
  }

  /// 注入推送回调，把「设备层事件」翻译成「应用层动作」。
  void _wirePush() {
    PushService.instance.onNotificationTap = _openNotificationCenter;
    // 推送到达即刷新未读角标：站内轮询最长 600 秒才刷一次，
    // 不主动刷会出现「通知栏已有新消息但首页角标仍是旧数字」的割裂感。
    PushService.instance.onMessageArrived = () {
      ref.read(notifyUnreadProvider.notifier).refresh();
    };
  }

  /// 点击通知跳消息中心。
  ///
  /// 暂统一落到消息中心而非按 linkType 直达业务详情：各详情页入参不一致
  /// （部分需要先查列表拿到完整对象），从消息中心二次点击可复用已有跳转逻辑，
  /// 避免在此处重复一套参数拼装。
  void _openNotificationCenter(PushPayload payload) {
    final navigator = _navigatorKey.currentState;
    if (navigator == null) return;
    // 未登录时点通知只能停在登录页，跳消息中心会因无 token 立即 401 弹回
    if (!_loggedIn) return;
    navigator.push(
      MaterialPageRoute(builder: (_) => const NotificationPage()),
    );
  }

  @override
  Widget build(BuildContext context) {
    // 监听登录态变化。
    // 设备绑定/解绑不在此处触发：解绑需要在 token 被清除前发出，
    // 而本回调运行于 state 置空之后，故该逻辑收敛在 AuthNotifier 内。
    ref.listen(authProvider, (_, next) {
      if (mounted) setState(() => _loggedIn = next != null);
    });

    return MaterialApp(
      title: 'TMS 司机配送',
      debugShowCheckedModeBanner: false,
      theme: TmsTheme.light,
      navigatorKey: _navigatorKey,
      home: _bootstrapping
          ? const Scaffold(body: Center(child: CircularProgressIndicator()))
          : (_loggedIn ? const HomePage() : const LoginPage()),
    );
  }
}
