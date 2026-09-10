import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/api_service.dart';
import '../services/auth_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 我的：账号/角色/当前作业仓信息、修改密码、切换仓库（=退登重选仓）、
/// 服务器设置、退出登录。PDA JWT 无状态，登出只清本地会话。
class ProfilePage extends StatefulWidget {
  const ProfilePage({super.key});
  @override
  State<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends State<ProfilePage> {
  final _auth = AuthService.instance;

  PdaUser? get _u => _auth.current;
  bool get _canSwitch => _auth.can(PdaPerm.switchWarehouse);
  bool get _canChangePwd => _auth.can(PdaPerm.changePwd);

  Future<void> _toLogin() async {
    await _auth.clearSession();
    if (!mounted) return;
    Navigator.pushNamedAndRemoveUntil(context, '/login', (_) => false);
  }

  @override
  Widget build(BuildContext context) {
    final u = _u;
    return Scaffold(
      appBar: AppBar(title: const Text('我的')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(12),
          children: [
            if (u?.mustChangePwd == true) ...[
              PdaAlert.danger('系统要求首次登录修改密码，请先修改密码再作业'),
              const SizedBox(height: 10),
            ],
            Card(
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Row(children: [
                  const CircleAvatar(
                    radius: 26,
                    backgroundColor: PdaTheme.primary,
                    child: Icon(Icons.person, size: 30, color: Colors.white),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(u?.displayName ?? '',
                            style: PdaStyles.title),
                        const SizedBox(height: 2),
                        Text(
                            [
                              '工号 ${u?.username ?? ''}',
                              if ((u?.employeeId ?? '').isNotEmpty)
                                '员工号 ${u!.employeeId}',
                            ].join(' · '),
                            style: PdaStyles.sub),
                        if ((u?.roleName ?? '').isNotEmpty) ...[
                          const SizedBox(height: 2),
                          Text(u!.roleName,
                              style: PdaStyles.sub
                                  .copyWith(color: PdaTheme.primary)),
                        ],
                      ],
                    ),
                  ),
                ]),
              ),
            ),
            const SizedBox(height: 10),
            Card(
              child: ListTile(
                leading: const Icon(Icons.warehouse_outlined,
                    color: PdaTheme.primary),
                title: const Text('当前作业仓'),
                subtitle: Text(u == null
                    ? ''
                    : u.warehouseName +
                        (u.warehouseCode.isNotEmpty
                            ? '（${u.warehouseCode}）'
                            : '')),
                trailing: _canSwitch
                    ? TextButton.icon(
                        icon: const Icon(Icons.swap_horiz, size: 18),
                        label: const Text('切换'),
                        onPressed: _confirmSwitch,
                      )
                    : null,
              ),
            ),
            if (_auth.hasMenu(PdaMenu.exception) ||
                _auth.can(PdaPerm.perfSelf)) ...[
              const SizedBox(height: 10),
              Card(
                child: Column(children: [
                  if (_auth.hasMenu(PdaMenu.exception))
                    ListTile(
                      leading: const Icon(Icons.warning_amber_rounded,
                          color: PdaTheme.warning),
                      title: const Text('异常中心'),
                      subtitle: const Text('查看异常、上报与处理进度'),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => Navigator.pushNamed(context, '/exception'),
                    ),
                  if (_auth.hasMenu(PdaMenu.exception) &&
                      _auth.can(PdaPerm.perfSelf))
                    const Divider(height: 1, indent: 12, endIndent: 12),
                  if (_auth.can(PdaPerm.perfSelf))
                    ListTile(
                      leading: const Icon(Icons.insights,
                          color: Color(0xFF7E57C2)),
                      title: const Text('我的绩效'),
                      subtitle: const Text('个人工作量统计'),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => Navigator.pushNamed(context, '/performance'),
                    ),
                ]),
              ),
            ],
            const SizedBox(height: 10),
            Card(
              child: Column(children: [
                ListTile(
                  leading: const Icon(Icons.lock_outline),
                  title: const Text('修改密码'),
                  enabled: _canChangePwd,
                  trailing: const Icon(Icons.chevron_right),
                  onTap: _canChangePwd ? _changePwdDialog : null,
                ),
                const Divider(height: 1, indent: 12, endIndent: 12),
                ListTile(
                  leading: const Icon(Icons.dns_outlined),
                  title: const Text('服务器设置'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.pushNamed(context, '/settings'),
                ),
                const Divider(height: 1, indent: 12, endIndent: 12),
                ListTile(
                  leading:
                      const Icon(Icons.logout, color: PdaTheme.danger),
                  title: const Text('退出登录',
                      style: TextStyle(color: PdaTheme.danger)),
                  onTap: _confirmLogout,
                ),
              ]),
            ),
            const SizedBox(height: 16),
            Center(
              child: Text(
                '角色：${u == null ? '' : u.roleCodes.join('、')}',
                style: PdaStyles.sub,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _confirmSwitch() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        title: const Text('切换作业仓'),
        content: const Text('切换仓库需要重新登录并选择仓库，确认退出当前账号？'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          ElevatedButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('重新登录')),
        ],
      ),
    );
    if (ok == true) _toLogin();
  }

  Future<void> _confirmLogout() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        title: const Text('退出登录'),
        content: const Text('退出后需重新工号登录，确认？'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          ElevatedButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('退出')),
        ],
      ),
    );
    if (ok == true) _toLogin();
  }

  Future<void> _changePwdDialog() async {
    final oldCtrl = TextEditingController();
    final newCtrl = TextEditingController();
    final confirmCtrl = TextEditingController();
    bool obscure = true;
    String? error;
    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialog) => AlertDialog(
          backgroundColor: PdaTheme.surface,
          title: const Text('修改密码'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: oldCtrl,
                  obscureText: obscure,
                  decoration:
                      const InputDecoration(labelText: '原密码'),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: newCtrl,
                  obscureText: obscure,
                  decoration: const InputDecoration(
                      labelText: '新密码',
                      helperText: '至少 8 位，含字母和数字'),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: confirmCtrl,
                  obscureText: obscure,
                  decoration:
                      const InputDecoration(labelText: '确认新密码'),
                ),
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton.icon(
                    icon: Icon(obscure
                        ? Icons.visibility_outlined
                        : Icons.visibility_off_outlined,
                        size: 18),
                    label: Text(obscure ? '显示密码' : '隐藏密码'),
                    onPressed: () =>
                        setDialog(() => obscure = !obscure),
                  ),
                ),
                if (error != null) ...[
                  Text(error!,
                      style: const TextStyle(
                          fontSize: 13, color: PdaTheme.danger)),
                ],
              ],
            ),
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx),
                child: const Text('取消')),
            ElevatedButton(
              onPressed: () async {
                final oldPwd = oldCtrl.text;
                final newPwd = newCtrl.text;
                if (oldPwd.isEmpty || newPwd.isEmpty) {
                  setDialog(() => error = '请填写原密码和新密码');
                  return;
                }
                if (newPwd != confirmCtrl.text) {
                  setDialog(() => error = '两次输入的新密码不一致');
                  return;
                }
                if (newPwd == oldPwd) {
                  setDialog(() => error = '新密码不能与原密码相同');
                  return;
                }
                try {
                  await _auth.changePassword(oldPwd, newPwd);
                } catch (e) {
                  setDialog(
                      () => error = ApiService.friendlyError(e));
                  return;
                }
                if (!ctx.mounted) return;
                Navigator.pop(ctx);
                if (!mounted) return;
                toast(context, '密码已修改，请重新登录');
                _toLogin();
              },
              child: const Text('确认修改'),
            ),
          ],
        ),
      ),
    );
  }
}
