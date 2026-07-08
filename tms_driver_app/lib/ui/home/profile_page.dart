import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../config/theme.dart';
import '../../providers/auth_provider.dart';
import '../login/login_page.dart';

/// 我的页（司机信息 + 退出登录）。
class ProfilePage extends ConsumerWidget {
  const ProfilePage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final driver = ref.watch(authProvider);
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(title: const Text('我的')),
      body: ListView(
        padding: const EdgeInsets.all(14),
        children: [
          Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(12)),
            child: Row(children: [
              const CircleAvatar(radius: 28, backgroundColor: TmsTheme.accentLight, child: Text('🧑‍✈️', style: TextStyle(fontSize: 28))),
              const SizedBox(width: 14),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(driver?.driverName ?? '司机', style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
                const SizedBox(height: 2),
                Text('工号：${driver?.driverCode.isEmpty == true ? "—" : driver?.driverCode}', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                Text('手机：${driver?.mobile.isEmpty == true ? "—" : driver?.mobile}', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
              ])),
            ]),
          ),
          const SizedBox(height: 12),
          Container(
            decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(12)),
            child: Column(children: [
              _row(Icons.history, '配送历史', () {}),
              const Divider(height: 1, indent: 56),
              _row(Icons.support_agent, '联系调度员', () {}),
              const Divider(height: 1, indent: 56),
              _row(Icons.description, '版本说明', () => _showVersion(context)),
            ]),
          ),
          const SizedBox(height: 24),
          ElevatedButton.icon(
            onPressed: () async {
              await ref.read(authProvider.notifier).logout();
              if (context.mounted) {
                Navigator.pushAndRemoveUntil(context, MaterialPageRoute(builder: (_) => const LoginPage()), (_) => false);
              }
            },
            icon: const Icon(Icons.logout, size: 18),
            label: const Text('退出登录'),
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.white,
              foregroundColor: TmsTheme.bad,
              elevation: 0,
              padding: const EdgeInsets.symmetric(vertical: 13),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
          ),
        ],
      ),
    );
  }

  Widget _row(IconData icon, String label, VoidCallback onTap) => ListTile(
        leading: Icon(icon, color: TmsTheme.accent, size: 22),
        title: Text(label, style: const TextStyle(fontSize: 14, color: TmsTheme.ink)),
        trailing: const Icon(Icons.chevron_right, color: TmsTheme.muted, size: 20),
        onTap: onTap,
      );

  void _showVersion(BuildContext context) {
    showDialog(context: context, builder: (_) => AlertDialog(
      title: const Text('版本说明'),
      content: const Text('TMS 司机配送 V1.2.0\n退货调度闭环 · 退货回收签收'),
      actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('知道了'))],
    ));
  }
}
