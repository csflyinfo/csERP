import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../config/app_config.dart';
import '../../config/theme.dart';
import '../../providers/auth_provider.dart';
import '../../services/api_service.dart';
import '../common/api_base_dialog.dart';

/// 司机登录页（对齐原型 screen-login）。
class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key});

  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends ConsumerState<LoginPage> {
  final _mobileCtrl = TextEditingController(text: '');
  final _codeCtrl = TextEditingController(text: AppConfig.devVerifyCode);
  bool _loading = false;
  bool _obscure = true;

  @override
  void dispose() {
    _mobileCtrl.dispose();
    _codeCtrl.dispose();
    super.dispose();
  }

  Future<void> _login() async {
    final mobile = _mobileCtrl.text.trim();
    if (mobile.isEmpty) {
      _toast('请输入手机号');
      return;
    }
    setState(() => _loading = true);
    try {
      await ref.read(authProvider.notifier).login(mobile, _codeCtrl.text.trim());
    } catch (e) {
      // 连接层错误（超时/拒绝/无网）通常是地址或网络问题，直接引导去改服务器地址，
      // 比把 DioException 原文糊在屏幕上更有用——司机看到 "Connection refused, errno=111"
      // 既不理解也无从下手，而长按 Logo 这个隐藏入口他们根本不知道。
      final isConnError = e is DioException &&
          (e.type == DioExceptionType.connectionError ||
              e.type == DioExceptionType.connectionTimeout ||
              e.type == DioExceptionType.receiveTimeout ||
              e.error.toString().contains('Connection refused'));
      if (isConnError && mounted) {
        _showConnErrorSnack(e);
      } else {
        _toast('登录失败：${e.toString().replaceFirst("Exception: ", "")}');
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _showConnErrorSnack(Object e) {
    final current = ApiService.instance.baseUrl;
    final msg = e is DioException && e.error != null ? e.error.toString() : e.toString();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 6),
        content: Text('无法连接服务器：$msg\n当前地址：$current\n请长按上方 🚚 图标检查地址',
            style: const TextStyle(fontSize: 12, height: 1.4)),
        action: SnackBarAction(label: '去设置', onPressed: _showApiBase),
      ),
    );
  }

  Future<void> _showApiBase() async {
    final changed = await ApiBaseDialog.show(context);
    if (!changed || !mounted) return;
    // 登录页本身无登录态可清，改完地址给个明确回执即可
    _toast('服务器地址已更新');
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            // 顶部蓝色区 + 底部白色登录卡（对齐原型 Screen A）
            Expanded(
              child: Column(
                children: [
                  // 蓝色头部
                  Expanded(
                    flex: 4,
                    child: Container(
                      width: double.infinity,
                      decoration: const BoxDecoration(
                        gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [TmsTheme.accent, TmsTheme.accent]),
                      ),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          // 长按整个品牌区（图标+标题）都能打开服务器地址配置。
                          // 触发区做宽一点：用户的直觉是按 Logo，而不是按标题文字；
                          // 换网络后唯一能自救的入口不能小到只有一行字才命中。
                          GestureDetector(
                            onLongPress: _showApiBase,
                            behavior: HitTestBehavior.opaque,
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Container(
                                  width: 64, height: 64,
                                  decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.18), borderRadius: BorderRadius.circular(20)),
                                  child: const Center(child: Text('🚚', style: TextStyle(fontSize: 32))),
                                ),
                                const SizedBox(height: 10),
                                const Text('智速达',
                                    style: TextStyle(color: Colors.white, fontSize: 20, fontWeight: FontWeight.bold)),
                                const SizedBox(height: 4),
                                const Text('智速达司机配送', style: TextStyle(color: Colors.white70, fontSize: 12)),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  // 白色登录卡（圆角向上）
                  Expanded(
                    flex: 5,
                    child: Container(
                      width: double.infinity,
                      decoration: const BoxDecoration(
                        color: Colors.white,
                        borderRadius: BorderRadius.only(topLeft: Radius.circular(20), topRight: Radius.circular(20)),
                      ),
                      padding: const EdgeInsets.fromLTRB(20, 24, 20, 32),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          _inputField('手机号 / 工号', _mobileCtrl, placeholder: '请输入手机号或司机工号'),
                          const SizedBox(height: 14),
                          _inputField('验证码', _codeCtrl, placeholder: '请输入验证码（开发期固定 888888）', obscure: _obscure,
                            suffix: IconButton(
                              visualDensity: VisualDensity.compact,
                              icon: Icon(_obscure ? Icons.visibility_off : Icons.visibility, size: 20, color: TmsTheme.muted),
                              onPressed: () => setState(() => _obscure = !_obscure),
                            )),
                          const Spacer(),
                          ElevatedButton(
                            onPressed: _loading ? null : _login,
                            style: ElevatedButton.styleFrom(
                              backgroundColor: TmsTheme.accent,
                              foregroundColor: Colors.white,
                              padding: const EdgeInsets.symmetric(vertical: 14),
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                            ),
                            child: _loading
                                ? const Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)),
                                      SizedBox(width: 8),
                                      Text('登录中…', style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
                                    ],
                                  )
                                : const Text('登 录', style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
                          ),
                          const SizedBox(height: 10),
                          const Text('首次登录需联系管理员开通账号', textAlign: TextAlign.center,
                              style: TextStyle(fontSize: 12, color: TmsTheme.muted)),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _inputField(String label, TextEditingController ctrl, {String placeholder = '', bool obscure = false, Widget? suffix}) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(fontSize: 12, color: TmsTheme.muted, fontWeight: FontWeight.w600)),
        const SizedBox(height: 4),
        TextField(
          controller: ctrl,
          obscureText: obscure,
          decoration: InputDecoration(
            hintText: placeholder,
            filled: true,
            fillColor: Colors.white,
            contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
            suffixIcon: suffix,
            border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
            enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
            focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.accent, width: 1.5)),
          ),
        ),
      ],
    );
  }
}
