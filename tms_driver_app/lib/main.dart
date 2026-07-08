import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'config/theme.dart';
import 'providers/auth_provider.dart';
import 'services/api_service.dart';
import 'services/connectivity_service.dart';
import 'services/local_db_service.dart';
import 'services/sync_service.dart';
import 'ui/home/home_page.dart';
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
    if (mounted) {
      setState(() {
        _loggedIn = ok;
        _bootstrapping = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    // 监听登录态变化
    ref.listen(authProvider, (_, next) {
      if (mounted) setState(() => _loggedIn = next != null);
    });

    return MaterialApp(
      title: 'TMS 司机配送',
      debugShowCheckedModeBanner: false,
      theme: TmsTheme.light,
      home: _bootstrapping
          ? const Scaffold(body: Center(child: CircularProgressIndicator()))
          : (_loggedIn ? const HomePage() : const LoginPage()),
    );
  }
}
