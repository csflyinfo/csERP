import 'package:flutter/material.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 首页：顶部用户条 + 待办统计 + 九宫格业务入口。
/// 九宫格按 3 列方形卡片排布，图标+文字垂直居中，badge 右上角红点，
/// 视觉节奏与 PDA 手持端常规风格一致（富杰/科箭 PDA 端参考）。
class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  int _pendingReceive = 0;
  int _pendingPutaway = 0;
  int _pendingPick = 0;
  int _helpPick = 0;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    setState(() => _loading = true);
    try {
      final results = await Future.wait([
        WmsAppService.instance.inboundTasks(status: 'PENDING'),
        WmsAppService.instance.putawayTasks(status: 'PENDING'),
        WmsAppService.instance.pickTasks(scope: 'mine'),
        WmsAppService.instance.pickTasks(scope: 'help'),
      ]);
      if (!mounted) return;
      setState(() {
        _pendingReceive = results[0].length;
        _pendingPutaway = results[1].length;
        _pendingPick = results[2].length;
        _helpPick = results[3].length;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _loading = false);
      if (context.mounted) toast(context, '加载待办失败：$e', error: true);
    }
  }

  void _open(String route) {
    Navigator.pushNamed(context, route).then((_) => _refresh());
  }

  @override
  Widget build(BuildContext context) {
    final user = AuthService.instance.current;
    final name = user?.displayName ?? user?.username ?? '操作员';
    return Scaffold(
      appBar: AppBar(
        title: const Text('WMS 仓库作业'),
        actions: [
          IconButton(
            tooltip: '刷新',
            icon: const Icon(Icons.refresh_rounded),
            onPressed: _refresh,
          ),
          IconButton(
            tooltip: '服务器地址',
            icon: const Icon(Icons.dns_outlined),
            onPressed: () => Navigator.pushNamed(context, '/settings'),
          ),
          PopupMenuButton<String>(
            onSelected: (v) async {
              if (v == 'logout') {
                await AuthService.instance.logout();
                if (context.mounted) {
                  Navigator.pushNamedAndRemoveUntil(context, '/login', (_) => false);
                }
              }
            },
            itemBuilder: (_) => [
              PopupMenuItem(
                enabled: false,
                child: Text('👤 $name',
                    style: const TextStyle(color: PdaTheme.textSecondary)),
              ),
              const PopupMenuItem(value: 'logout', child: Text('退出登录')),
            ],
          ),
        ],
      ),
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _refresh,
          color: PdaTheme.primary,
          child: ListView(
            padding: const EdgeInsets.all(PdaSpacing.md),
            children: [
              _welcomeCard(name),
              const SizedBox(height: PdaSpacing.md),
              if (_loading)
                const Padding(
                  padding: EdgeInsets.all(24),
                  child: Center(
                      child: CircularProgressIndicator(color: PdaTheme.primary)),
                )
              else
                Row(children: [
                  _stat('待收货', _pendingReceive, PdaTheme.primary, Icons.inbox_rounded),
                  const SizedBox(width: PdaSpacing.sm),
                  _stat('待拣货', _pendingPick + _helpPick, PdaTheme.warning, Icons.shopping_bag_rounded),
                  const SizedBox(width: PdaSpacing.sm),
                  _stat('待上架', _pendingPutaway, PdaTheme.info, Icons.shelves),
                ]),
              const SizedBox(height: PdaSpacing.lg),
              _sectionTitle('收货 / 上架'),
              const SizedBox(height: PdaSpacing.sm),
              _grid([
                _tile('📥', '收货', _pendingReceive, PdaTheme.primary,
                    () => _open('/receive')),
                _tile('🏷️', '上架', _pendingPutaway, PdaTheme.info,
                    () => _open('/putaway')),
                _tile('🚫', '拒收', null, PdaTheme.danger,
                    () => _open('/reject')),
              ]),
              const SizedBox(height: PdaSpacing.lg),
              _sectionTitle('出库作业'),
              const SizedBox(height: PdaSpacing.sm),
              _grid([
                _tile('📦', '拣货', _pendingPick + _helpPick, PdaTheme.warning,
                    () => _open('/pick')),
                _tile('✅', '复核', null, PdaTheme.primary,
                    () => _open('/check')),
                _tile('🚛', '装车', null, const Color(0xFF26C6DA),
                    () => _open('/load')),
              ]),
              const SizedBox(height: PdaSpacing.lg),
              _sectionTitle('库内作业'),
              const SizedBox(height: PdaSpacing.sm),
              _grid([
                _tile('🔄', '移库', null, PdaTheme.info,
                    () => _open('/move')),
                _tile('⚡', '补货', null, PdaTheme.warning,
                    () => _open('/replenish')),
                _tile('🔢', '盘点', null, PdaTheme.danger,
                    () => _open('/stocktake')),
              ]),
              const SizedBox(height: PdaSpacing.lg),
              _sectionTitle('更多'),
              const SizedBox(height: PdaSpacing.sm),
              _grid([
                _tile('🔧', '组合拆分', null, const Color(0xFFAB47BC),
                    () => _open('/assembly')),
                _tile('📅', '生产日期', null, PdaTheme.warning,
                    () => _open('/proddate')),
              ]),
            ],
          ),
        ),
      ),
    );
  }

  Widget _welcomeCard(String name) {
    return Container(
      padding: const EdgeInsets.all(PdaSpacing.lg),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF1F2A44), PdaTheme.surface],
        ),
        borderRadius: BorderRadius.circular(PdaSpacing.radiusLg),
        border: Border.all(color: PdaTheme.border),
      ),
      child: Row(children: [
        Container(
          width: 44, height: 44,
          decoration: BoxDecoration(
            color: PdaTheme.primary.withValues(alpha: 0.18),
            borderRadius: BorderRadius.circular(12),
          ),
          child: const Icon(Icons.qr_code_scanner, color: PdaTheme.primary, size: 26),
        ),
        const SizedBox(width: PdaSpacing.md),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('你好，$name', style: PdaStyles.title),
              const SizedBox(height: 2),
              const Text('选择作业类型开始，或下拉刷新待办', style: PdaStyles.sub),
            ],
          ),
        ),
      ]),
    );
  }

  Widget _sectionTitle(String text) {
    return Padding(
      padding: const EdgeInsets.only(left: 2),
      child: Row(children: [
        Container(width: 3, height: 14, color: PdaTheme.primary),
        const SizedBox(width: 8),
        Text(text,
            style: const TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w600,
                color: PdaTheme.textPrimary)),
      ]),
    );
  }

  /// 三列等宽网格，不滚动；按 0.96 的宽高比给出"近方形"卡片。
  Widget _grid(List<Widget> children) {
    return GridView.count(
      crossAxisCount: 3,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      mainAxisSpacing: PdaSpacing.sm,
      crossAxisSpacing: PdaSpacing.sm,
      childAspectRatio: 0.96,
      children: children,
    );
  }

  /// 单个九宫格入口卡片：圆角 14，图标底色 + 主色描边微动效，badge 右上红点。
  Widget _tile(String emoji, String title, int? badge, Color color, VoidCallback onTap) {
    final hasBadge = badge != null && badge > 0;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(PdaSpacing.radius),
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Container(
              decoration: BoxDecoration(
                color: PdaTheme.surface,
                borderRadius: BorderRadius.circular(PdaSpacing.radius),
                border: Border.all(color: PdaTheme.border),
              ),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Container(
                    width: 48,
                    height: 48,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: color.withValues(alpha: 0.16),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Text(emoji, style: const TextStyle(fontSize: 26, height: 1.1)),
                  ),
                  const SizedBox(height: 8),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                    child: Text(
                      title,
                      textAlign: TextAlign.center,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontSize: 13,
                          color: PdaTheme.textPrimary,
                          fontWeight: FontWeight.w600),
                    ),
                  ),
                ],
              ),
            ),
            if (hasBadge)
              Positioned(
                top: -4,
                right: -2,
                child: Container(
                  constraints: const BoxConstraints(minWidth: 22),
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                  decoration: BoxDecoration(
                    color: PdaTheme.danger,
                    borderRadius: BorderRadius.circular(11),
                    border: Border.all(color: PdaTheme.bg, width: 2),
                  ),
                  child: Text('$badge',
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                          color: Colors.white,
                          fontSize: 11,
                          fontWeight: FontWeight.bold,
                          height: 1.1)),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _stat(String label, int value, Color color, IconData icon) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 10),
        decoration: BoxDecoration(
          color: PdaTheme.surface,
          borderRadius: BorderRadius.circular(PdaSpacing.radius),
          border: Border.all(color: PdaTheme.border),
        ),
        child: Column(
          children: [
            Icon(icon, color: color, size: 22),
            const SizedBox(height: 6),
            Text('$value',
                style: TextStyle(
                    fontSize: 22, fontWeight: FontWeight.bold, color: color)),
            const SizedBox(height: 2),
            Text(label, style: PdaStyles.sub),
          ],
        ),
      ),
    );
  }
}
