import 'package:flutter/material.dart';
import '../config/app_config.dart';
import '../config/glove_mode.dart';
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
  void initState() {
    super.initState();
    GloveMode.instance.addListener(_onGloveChange);
  }

  @override
  void dispose() {
    GloveMode.instance.removeListener(_onGloveChange);
    super.dispose();
  }

  void _onGloveChange() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final glove = GloveMode.instance;
    return PdaScaffold(
      title: '设置',
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // 手套模式（防误触）
          Container(
            padding: const EdgeInsets.symmetric(
                horizontal: 12, vertical: 6),
            decoration: BoxDecoration(
              color: PdaTheme.surface,
              borderRadius: BorderRadius.circular(PdaSpacing.radius),
              border: Border.all(color: PdaTheme.border),
            ),
            child: SwitchListTile(
              contentPadding: EdgeInsets.zero,
              activeThumbColor: PdaTheme.primary,
              title: const Text('手套模式', style: PdaStyles.title),
              subtitle: Text(
                '开启后增大按钮热区与字号，减少戴手套误触',
                style: PdaStyles.sub,
              ),
              value: glove.on,
              onChanged: (v) => GloveMode.instance.setOn(v),
            ),
          ),
          const SizedBox(height: 16),
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
