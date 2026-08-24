import 'package:flutter/material.dart';
import 'config/app_config.dart';
import 'services/api_service.dart';
import 'services/auth_service.dart';
import 'theme/pda_theme.dart';
import 'ui/login_page.dart';
import 'ui/home_page.dart';
import 'ui/receive_page.dart';
import 'ui/putaway_page.dart';
import 'ui/pick_page.dart';
import 'ui/check_page.dart';
import 'ui/stocktake_page.dart';
import 'ui/move_page.dart';
import 'ui/replenish_page.dart';
import 'ui/load_page.dart';
import 'ui/placeholder_page.dart';
import 'ui/settings_page.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await AppConfig.loadOverride();
  ApiService.instance.applyBaseUrl();
  await AuthService.instance.restore();
  runApp(const WmsPdaApp());
}

class WmsPdaApp extends StatelessWidget {
  const WmsPdaApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'WMS 仓库作业',
      debugShowCheckedModeBanner: false,
      theme: PdaTheme.dark,
      initialRoute: AuthService.instance.isLoggedIn ? '/home' : '/login',
      routes: {
        '/login': (_) => const LoginPage(),
        '/home': (_) => const HomePage(),
        '/receive': (_) => const ReceivePage(),
        '/putaway': (_) => const PutawayPage(),
        '/pick': (_) => const PickPage(),
        '/check': (_) => const CheckPage(),
        '/stocktake': (_) => const StocktakePage(),
        '/move': (_) => const MovePage(),
        '/replenish': (_) => const ReplenishPage(),
        '/load': (_) => const LoadPage(),
        '/assembly': (_) => const PlaceholderPage(
              title: '组合 / 拆分',
              reason: '组装拆卸单目前需在 PC 端建单并完成；PDA 端后续版本支持逐件扫商品出/入库。',
            ),
        '/proddate': (_) => const PlaceholderPage(
              title: '生产日期修改',
              reason: '生产日期/批次修改在收货、拣货改批次、JSRK 编辑流程中处理；独立的库存批次修改请在 PC 端操作。',
            ),
        '/reject': (_) => const PlaceholderPage(
              title: '拒收入库',
              reason: '销售拒收入库单由发货签收自动生成，在 PC 端编辑/审核（JSRK）。PDA 端后续版本支持扫码清点。',
            ),
        '/settings': (_) => const SettingsPage(),
      },
    );
  }
}
