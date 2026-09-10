import 'package:flutter/material.dart';
import '../config/app_config.dart';
import '../services/api_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});
  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  late final TextEditingController _c =
      TextEditingController(text: AppConfig.hasOverride ? AppConfig.apiBase : '');

  @override
  Widget build(BuildContext context) {
    return PdaScaffold(
      title: '服务器地址',
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          PdaAlert.info('当前生效：\n${AppConfig.apiBase}'),
          const SizedBox(height: 16),
          const Text('自定义地址（留空恢复默认）', style: PdaStyles.title),
          const SizedBox(height: 6),
          TextField(
            controller: _c,
            decoration: const InputDecoration(
              hintText: '例如 192.168.1.10 或 192.168.1.10:8080',
              prefixIcon: Icon(Icons.link),
            ),
            keyboardType: TextInputType.url,
          ),
          const SizedBox(height: 8),
          const Text(
            '提示：雷电/MuMu 模拟器请填宿主机局域网 IP，不能用 10.0.2.2。',
            style: PdaStyles.sub,
          ),
          const SizedBox(height: 20),
          ElevatedButton.icon(
            icon: const Icon(Icons.save_outlined),
            label: const Text('保存并重启请求通道'),
            onPressed: () async {
              final v = _c.text.trim();
              final normalized = v.isEmpty ? '' : AppConfig.normalize(v);
              await AppConfig.saveOverride(normalized);
              ApiService.instance.applyBaseUrl();
              if (!mounted) return;
              toast(this.context, '已保存：${AppConfig.apiBase}');
              Navigator.pop(this.context);
            },
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            icon: const Icon(Icons.refresh),
            label: const Text('恢复默认'),
            onPressed: () async {
              await AppConfig.saveOverride('');
              ApiService.instance.applyBaseUrl();
              _c.clear();
              if (!mounted) return;
              setState(() {});
              toast(this.context, '已恢复默认：${AppConfig.defaultApiBase}');
            },
          ),
        ],
      ),
    );
  }
}
