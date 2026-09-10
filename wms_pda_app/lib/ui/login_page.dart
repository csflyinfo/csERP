import 'package:flutter/material.dart';
import '../services/auth_service.dart';
import '../services/api_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// PDA 登录页（PRD-28 卡片9）。
///
/// 两步选仓：
/// 1. 工号 + 密码提交；单仓账号直接签发 token；
/// 2. 多仓账号后端返回 needWarehouse + 仓库列表，本页切到选仓视图，
///    选定后带 warehouseId 再提交一次。
class LoginPage extends StatefulWidget {
  const LoginPage({super.key});
  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final _user = TextEditingController();
  final _pass = TextEditingController();
  bool _busy = false;

  // 第二步选仓态
  List<WarehouseOption> _warehouses = const [];
  String? _pendingUser;
  String? _pendingPass;

  @override
  void dispose() {
    _user.dispose();
    _pass.dispose();
    super.dispose();
  }

  void _gotoHome() {
    Navigator.of(context).pushNamedAndRemoveUntil('/home', (_) => false);
  }

  Future<void> _submit({String? warehouseId}) async {
    final username = warehouseId == null ? _user.text.trim() : _pendingUser!;
    final password = warehouseId == null ? _pass.text : _pendingPass!;
    setState(() => _busy = true);
    try {
      final out = await AuthService.instance
          .login(username, password, warehouseId: warehouseId);
      if (!mounted) return;
      if (out.needWarehouse) {
        setState(() {
          _warehouses = out.warehouses;
          _pendingUser = username;
          _pendingPass = password;
        });
        if (out.warehouses.isEmpty) {
          toast(context, '该账号未绑定作业仓库，请联系管理员', error: true);
        }
        return;
      }
      _gotoHome();
    } catch (e) {
      if (mounted) toast(context, ApiService.friendlyError(e), error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _backToForm() {
    setState(() {
      _warehouses = const [];
      _pendingUser = null;
      _pendingPass = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final choosing = _warehouses.isNotEmpty;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(28),
            child: choosing ? _buildWarehouseStep() : _buildCredentialStep(),
          ),
        ),
      ),
    );
  }

  Widget _brand() => const Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text('🏗️', style: TextStyle(fontSize: 56)),
          SizedBox(height: 12),
          Text('WMS 仓库作业系统',
              style: TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.bold,
                  color: PdaTheme.textPrimary)),
          SizedBox(height: 4),
          Text('PDA 手持终端 · 扫码作业', style: PdaStyles.sub),
        ],
      );

  Widget _buildCredentialStep() => Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          _brand(),
          const SizedBox(height: 32),
          TextField(
            controller: _user,
            decoration: const InputDecoration(
              labelText: '工号 / 账号',
              prefixIcon: Icon(Icons.badge_outlined),
            ),
            textInputAction: TextInputAction.next,
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _pass,
            obscureText: true,
            decoration: const InputDecoration(
              labelText: '密码',
              prefixIcon: Icon(Icons.lock_outline),
            ),
            onSubmitted: (_) => _busy ? null : _submit(),
          ),
          const SizedBox(height: 20),
          SizedBox(
            width: double.infinity,
            child: ElevatedButton(
              onPressed: _busy ? null : () => _submit(),
              child: _busy
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: Colors.white),
                    )
                  : const Text('登 录'),
            ),
          ),
          const SizedBox(height: 16),
          TextButton.icon(
            onPressed: () => Navigator.pushNamed(context, '/settings'),
            icon: const Icon(Icons.dns_outlined,
                size: 16, color: PdaTheme.textSecondary),
            label: const Text('服务器地址', style: PdaStyles.sub),
          ),
        ],
      );

  Widget _buildWarehouseStep() => Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _brand(),
          const SizedBox(height: 24),
          const Text('选择作业仓库',
              textAlign: TextAlign.center, style: PdaStyles.title),
          const SizedBox(height: 4),
          Text('账号 $_pendingUser 绑定了多个仓库，请选择本次登录的作业仓库',
              textAlign: TextAlign.center, style: PdaStyles.sub),
          const SizedBox(height: 16),
          ..._warehouses.map((w) => Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: _BusyAwareWarehouseTile(
                  option: w,
                  enabled: !_busy,
                  onTap: () => _submit(warehouseId: w.id),
                ),
              )),
          const SizedBox(height: 8),
          TextButton.icon(
            onPressed: _busy ? null : _backToForm,
            icon: const Icon(Icons.arrow_back, size: 18),
            label: const Text('返回'),
          ),
        ],
      );
}

/// 选仓按钮：请求中显示转圈，避免重复点选发两次登录。
class _BusyAwareWarehouseTile extends StatelessWidget {
  final WarehouseOption option;
  final bool enabled;
  final VoidCallback onTap;
  const _BusyAwareWarehouseTile(
      {required this.option, required this.enabled, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return OutlinedButton.icon(
      onPressed: enabled ? onTap : null,
      icon: const Icon(Icons.warehouse_outlined),
      label: Align(
        alignment: Alignment.centerLeft,
        child: Text(option.label, overflow: TextOverflow.ellipsis),
      ),
    );
  }
}
