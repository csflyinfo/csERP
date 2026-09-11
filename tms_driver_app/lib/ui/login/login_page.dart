import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show kDebugMode;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../config/app_config.dart';
import '../../config/theme.dart';
import '../../providers/auth_provider.dart';
import '../../services/api_service.dart';
import '../common/api_base_dialog.dart';

/// 司机登录页（对齐原型 screen-login；PRD-28 卡片10 起支持短信/工号两种方式）。
class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key});

  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

/// 登录方式：sms=手机号+短信验证码（主路径）；password=工号+密码（装车员等预建账号）。
enum _LoginMode { sms, password }

class _LoginPageState extends ConsumerState<LoginPage> {
  final _mobileCtrl = TextEditingController(text: '');
  final _codeCtrl = TextEditingController(text: kDebugMode ? AppConfig.devVerifyCode : '');
  final _pwdCtrl = TextEditingController(text: '');

  _LoginMode _mode = _LoginMode.sms;
  bool _loading = false;
  bool _obscure = true;
  bool _sendingCode = false;

  /// 重发倒计时剩余秒数；>0 时「获取验证码」按钮禁用。
  int _countdown = 0;
  Timer? _timer;

  @override
  void dispose() {
    _timer?.cancel();
    _mobileCtrl.dispose();
    _codeCtrl.dispose();
    _pwdCtrl.dispose();
    super.dispose();
  }

  /// 把后端/网络异常归一化成可直接展示的中文文案。
  ///
  /// 业务拦截器 reject 的 DioException 中文原因在 e.message 里，
  /// 直接 toString 会带 "DioException [bad response]:" 前缀，司机看不懂。
  String _errText(Object e) {
    if (e is DioException) {
      final msg = e.message?.trim();
      if (msg != null && msg.isNotEmpty && msg != '请求失败') return msg;
    }
    return e.toString().replaceFirst('Exception: ', '');
  }

  /// 获取短信验证码：POST /auth/sms/send（匿名端点）。
  ///
  /// 频控（60 秒重发、日 10 次、5 次失败锁 15 分钟）全部在服务端，
  /// 前端只做 60 秒倒计时与后端文案透传——频控规则以后端为准，不在前端复制。
  Future<void> _sendCode() async {
    final mobile = _mobileCtrl.text.trim();
    if (mobile.length != 11) {
      _toast('请输入 11 位手机号');
      return;
    }
    setState(() => _sendingCode = true);
    try {
      final data = await ApiService.instance.post('/auth/sms/send', body: {
        'mobile': mobile,
        'bizType': 'LOGIN',
      }) as Map<String, dynamic>?;
      final secs = (data?['expireSeconds'] ?? 300);
      _toast('验证码已发送，$secs 秒内有效');
      _startCountdown();
    } catch (e) {
      final isConnError = e is DioException &&
          (e.type == DioExceptionType.connectionError ||
              e.type == DioExceptionType.connectionTimeout ||
              e.type == DioExceptionType.receiveTimeout ||
              e.error.toString().contains('Connection refused'));
      if (isConnError && mounted) {
        _showConnErrorSnack(e);
      } else {
        // 典型文案：「验证码错误次数过多，请 15 分钟后再试」「今日发送次数已达上限」
        _toast(_errText(e));
      }
    } finally {
      if (mounted) setState(() => _sendingCode = false);
    }
  }

  void _startCountdown() {
    _timer?.cancel();
    setState(() => _countdown = 60);
    _timer = Timer.periodic(const Duration(seconds: 1), (t) {
      if (!mounted) {
        t.cancel();
        return;
      }
      setState(() => _countdown -= 1);
      if (_countdown <= 0) t.cancel();
    });
  }

  Future<void> _login() async {
    setState(() => _loading = true);
    try {
      if (_mode == _LoginMode.sms) {
        final mobile = _mobileCtrl.text.trim();
        final code = _codeCtrl.text.trim();
        if (mobile.isEmpty) {
          _toast('请输入手机号');
          return;
        }
        if (code.isEmpty) {
          _toast('请输入验证码');
          return;
        }
        await ref.read(authProvider.notifier).login(mobile, code);
      } else {
        final employeeCode = _mobileCtrl.text.trim();
        final pwd = _pwdCtrl.text;
        if (employeeCode.isEmpty) {
          _toast('请输入工号');
          return;
        }
        if (pwd.isEmpty) {
          _toast('请输入密码');
          return;
        }
        await ref.read(authProvider.notifier).loginByPassword(employeeCode, pwd);
      }
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
        _toast('登录失败：${_errText(e)}');
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
                          _modeSwitch(),
                          const SizedBox(height: 16),
                          _inputField(
                            _mode == _LoginMode.sms ? '手机号' : '工号',
                            _mobileCtrl,
                            placeholder: _mode == _LoginMode.sms
                                ? '请输入手机号'
                                : '请输入司机工号',
                            keyboardType: _mode == _LoginMode.sms
                                ? TextInputType.phone
                                : TextInputType.text,
                          ),
                          const SizedBox(height: 14),
                          if (_mode == _LoginMode.sms)
                            _inputField('验证码', _codeCtrl,
                                placeholder: '请输入 6 位验证码',
                                obscure: _obscure,
                                keyboardType: TextInputType.number,
                                suffix: _codeSuffix())
                          else
                            _inputField('密码', _pwdCtrl,
                                placeholder: '请输入密码',
                                obscure: _obscure,
                                suffix: IconButton(
                                  visualDensity: VisualDensity.compact,
                                  icon: Icon(_obscure ? Icons.visibility_off : Icons.visibility, size: 20, color: TmsTheme.muted),
                                  onPressed: () => setState(() => _obscure = !_obscure),
                                )),
                          // 开发/测试环境固定验证码提示；release 不显示，避免被当成真实后门。
                          if (_mode == _LoginMode.sms && kDebugMode) ...[
                            const SizedBox(height: 6),
                            const Text('开发环境固定验证码：${AppConfig.devVerifyCode}',
                                style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
                          ],
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
                          const Text('首次登录将自动开通账号；装车员账号请由管理员预先开通', textAlign: TextAlign.center,
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

  /// 短信登录 / 工号登录 切换。
  Widget _modeSwitch() {
    return Container(
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: TmsTheme.bg,
        borderRadius: BorderRadius.circular(9),
      ),
      child: Row(children: [
        _modeTab('短信登录', _LoginMode.sms),
        _modeTab('工号登录', _LoginMode.password),
      ]),
    );
  }

  Widget _modeTab(String label, _LoginMode m) {
    final selected = _mode == m;
    return Expanded(
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => setState(() => _mode = m),
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 8),
          decoration: BoxDecoration(
            color: selected ? Colors.white : Colors.transparent,
            borderRadius: BorderRadius.circular(7),
            boxShadow: selected
                ? [BoxShadow(color: Colors.black.withValues(alpha: 0.06), blurRadius: 4)]
                : null,
          ),
          alignment: Alignment.center,
          child: Text(label,
              style: TextStyle(
                fontSize: 13,
                fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                color: selected ? TmsTheme.accent : TmsTheme.muted,
              )),
        ),
      ),
    );
  }

  /// 验证码输入框尾部：密码眼 + 获取验证码/倒计时。
  Widget _codeSuffix() {
    return Row(mainAxisSize: MainAxisSize.min, children: [
      IconButton(
        visualDensity: VisualDensity.compact,
        icon: Icon(_obscure ? Icons.visibility_off : Icons.visibility, size: 20, color: TmsTheme.muted),
        onPressed: () => setState(() => _obscure = !_obscure),
      ),
      GestureDetector(
        onTap: (_sendingCode || _countdown > 0) ? null : _sendCode,
        child: Padding(
          padding: const EdgeInsets.only(right: 10),
          child: Text(
            _sendingCode
                ? '发送中'
                : (_countdown > 0 ? '${_countdown}s 后重发' : '获取验证码'),
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w700,
              color: (_sendingCode || _countdown > 0) ? TmsTheme.muted : TmsTheme.accent,
            ),
          ),
        ),
      ),
    ]);
  }

  Widget _inputField(String label, TextEditingController ctrl,
      {String placeholder = '',
      bool obscure = false,
      Widget? suffix,
      TextInputType? keyboardType}) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(fontSize: 12, color: TmsTheme.muted, fontWeight: FontWeight.w600)),
        const SizedBox(height: 4),
        TextField(
          controller: ctrl,
          obscureText: obscure,
          keyboardType: keyboardType,
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
