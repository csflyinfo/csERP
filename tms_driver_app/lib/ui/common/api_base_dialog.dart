import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import '../../config/app_config.dart';
import '../../config/theme.dart';
import '../../services/api_service.dart';

/// 服务器地址配置弹窗（隐藏功能）。
///
/// 放在 common 下而不是某个页面内部，是因为它必须同时被登录页和「我的」页复用：
/// 换网络后的典型症状恰恰是「登录不上」，此时用户根本进不到「我的」页，
/// 只在登录后的页面提供入口等于在最需要它的场景下失效。
///
/// pop 返回 true 表示地址已变更，调用方需要据此重置登录态。
class ApiBaseDialog extends StatefulWidget {
  const ApiBaseDialog({super.key});

  /// 弹出配置框；返回 true 表示地址被改动过。
  static Future<bool> show(BuildContext context) async {
    final changed = await showDialog<bool>(
      context: context,
      builder: (_) => const ApiBaseDialog(),
    );
    return changed == true;
  }

  @override
  State<ApiBaseDialog> createState() => _ApiBaseDialogState();
}

class _ApiBaseDialogState extends State<ApiBaseDialog> {
  late final TextEditingController _ctrl =
      TextEditingController(text: ApiService.instance.baseUrl);
  bool _testing = false;
  String _result = '';
  bool _ok = false;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  /// 探测目标地址是否为可用的后端。
  ///
  /// 借用登录接口做探针（故意传空手机号）：它在后端安全白名单内、不需要 token，
  /// 且无论校验是否通过，只要能拿到 HTTP 响应就说明「地址通、服务活着」。
  /// 所以这里把业务错误也算连通成功，只有连接层异常（超时/拒绝）才算失败。
  ///
  /// 用临时 Dio 而不是 ApiService.instance.dio：探测不应污染全局 baseUrl，
  /// 否则测完没保存就退出，后续请求会打向一个未确认的地址。
  Future<void> _test() async {
    final target = AppConfig.normalizeApiBase(_ctrl.text);
    if (target.isEmpty) {
      setState(() {
        _ok = false;
        _result = '请先填写地址';
      });
      return;
    }
    setState(() {
      _testing = true;
      _result = '';
    });
    final dio = Dio(BaseOptions(
      baseUrl: target,
      // 探测用短超时：默认 10s 会让人以为界面卡死，5s 足以区分通与不通
      connectTimeout: const Duration(seconds: 5),
      receiveTimeout: const Duration(seconds: 5),
      headers: {'Content-Type': 'application/json'},
    ));
    String message;
    bool ok;
    try {
      await dio.post('/tms/app/login', data: {'mobile': '', 'verifyCode': ''});
      ok = true;
      message = '连接正常：$target';
    } on DioException catch (e) {
      if (e.response != null) {
        ok = true;
        message = '连接正常：$target';
      } else {
        ok = false;
        message = '连接失败（${e.type.name}），请检查地址与网络';
      }
    } catch (e) {
      ok = false;
      message = '连接失败：$e';
    }
    dio.close();
    if (!mounted) return;
    setState(() {
      _testing = false;
      _ok = ok;
      _result = message;
    });
  }

  Future<void> _save() async {
    final normalized = AppConfig.normalizeApiBase(_ctrl.text);
    // 与默认地址一致时按「恢复默认」存：避免把默认值固化进本地存储，
    // 否则以后改了内置默认或编译参数，这台设备仍会被旧值钉住。
    await AppConfig.saveApiBaseOverride(
        normalized == AppConfig.defaultApiBase ? '' : normalized);
    ApiService.instance.applyBaseUrl();
    if (mounted) Navigator.pop(context, true);
  }

  Future<void> _reset() async {
    await AppConfig.saveApiBaseOverride('');
    ApiService.instance.applyBaseUrl();
    if (mounted) Navigator.pop(context, true);
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('服务器地址', style: TextStyle(fontSize: 16)),
      content: Column(mainAxisSize: MainAxisSize.min, children: [
        TextField(
          controller: _ctrl,
          keyboardType: TextInputType.url,
          autocorrect: false,
          style: const TextStyle(fontSize: 13),
          decoration: const InputDecoration(
            hintText: '192.168.1.5',
            helperText: '可只填 IP，自动补 http:// 、端口 8080 与 /api',
            helperMaxLines: 2,
            isDense: true,
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 12),
        Align(
          alignment: Alignment.centerLeft,
          child: Text('默认地址：${AppConfig.defaultApiBase}',
              style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        ),
        if (_result.isNotEmpty) ...[
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerLeft,
            child: Text(_result,
                style: TextStyle(
                    fontSize: 12, color: _ok ? TmsTheme.ok : TmsTheme.bad)),
          ),
        ],
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
            onPressed: _testing ? null : _test,
            icon: _testing
                ? const SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.network_check, size: 16),
            label: Text(_testing ? '测试中…' : '测试连接',
                style: const TextStyle(fontSize: 13)),
            style: TextButton.styleFrom(
                padding: EdgeInsets.zero, visualDensity: VisualDensity.compact),
          ),
        ),
      ]),
      actions: [
        TextButton(onPressed: _reset, child: const Text('恢复默认')),
        TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消')),
        TextButton(onPressed: _save, child: const Text('保存')),
      ],
    );
  }
}
