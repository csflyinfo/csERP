import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/global_scan_sheet.dart';
import '../services/hardware_scan_key.dart';
import '../widgets/function_card_grid.dart';
import 'task_board_page.dart';

/// 首页（PRD-28 卡片9）：顶部用户/仓库条 + 待办统计 + 按登录菜单树裁剪的入口网格。
///
/// - 渲染哪些卡片完全取决于登录下发的 menus（AuthService.hasMenu），超管全放行；
/// - 待办角标只对已授权菜单发起请求，任何角标接口失败静默处理（不弹错、不阻塞首页）；
/// - "我的/仓库"固定走右上角，不作为业务卡片，保证收货员首页只见
///   收货/退货收货/其他入库/库存查询四张业务卡（PDA-002）。
class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _TileDef {
  final String code;
  final String title;
  final String emoji;
  final String route;
  final Color color;
  final String section;
  const _TileDef(this.code, this.title, this.emoji, this.route, this.color,
      this.section);
}

class _HomePageState extends State<HomePage> {
  final _svc = WmsAppService.instance;
  final _auth = AuthService.instance;
  final Map<String, int> _badges = {};
  bool _loading = true;

  /// 底部导航当前 Tab：0 首页 / 1 任务（P0-4 占位）/ 2 我的。
  /// 扫码 FAB 不切 Tab，直接弹扫码快捷入口（showGlobalScanSheet）。
  int _currentIndex = 0;

  /// 业务卡片目录：code 与后端菜单码逐字一致；顺序即展示顺序。
  static const List<_TileDef> _catalog = [
    // 收货入库
    _TileDef(PdaMenu.receive, '收货', '📥', '/receive', PdaTheme.primary, '收货入库'),
    _TileDef(PdaMenu.receiveReturn, '退货收货', '↩️', '/receive-return',
        Color(0xFF26C6DA), '收货入库'),
    _TileDef(PdaMenu.otherInbound, '其他入库', '📝', '/other-inbound',
        Color(0xFFAB47BC), '收货入库'),
    _TileDef(PdaMenu.putaway, '上架', '🏷️', '/putaway', PdaTheme.info, '收货入库'),
    // 出库作业
    _TileDef(PdaMenu.pick, '拣货', '📦', '/pick', PdaTheme.warning, '出库作业'),
    _TileDef(PdaMenu.check, '复核', '✅', '/check', PdaTheme.primary, '出库作业'),
    _TileDef(PdaMenu.load, '装车', '🚛', '/load', Color(0xFF26C6DA), '出库作业'),
    // 库内作业
    _TileDef(PdaMenu.replenish, '补货', '⚡', '/replenish', PdaTheme.warning, '库内作业'),
    _TileDef(PdaMenu.move, '移库', '🔄', '/move', PdaTheme.info, '库内作业'),
    _TileDef(PdaMenu.stocktake, '盘点', '🔢', '/stocktake', PdaTheme.danger, '库内作业'),
    _TileDef(PdaMenu.damage, '报损', '🧯', '/damage', Color(0xFFEF5350), '库内作业'),
    // 查询与管理
    _TileDef(PdaMenu.stockQuery, '库存查询', '🔎', '/stock-query',
        Color(0xFF66BB6A), '查询与管理'),
    _TileDef(PdaMenu.exception, '异常中心', '⚠️', '/exception',
        Color(0xFFFFB300), '查询与管理'),
    _TileDef(PdaMenu.taskAssign, '派工', '🧭', '/task-assign',
        Color(0xFF42A5F5), '查询与管理'),
    _TileDef(PdaMenu.performance, '绩效', '📈', '/performance',
        Color(0xFF7E57C2), '查询与管理'),
  ];

  static const List<String> _sections = [
    '收货入库',
    '出库作业',
    '库内作业',
    '查询与管理',
  ];

  List<_TileDef> get _visible => _catalog.where((t) {
        if (!_auth.hasMenu(t.code)) return false;
        // 异常中心/绩效是全员共有菜单（查看/上报/个人绩效），但按方案 §7.1
        // "主管首页=任务分派、异常处理、绩效查看"，首页卡片仅对有管理类权限的人
        // （异常处理/转派、全仓绩效）展示；普通作业员从「我的」页进入对应页面。
        // 否则收货员首页会多出两张卡，违反 PDA-002（仅 收货/退货收货/其他入库/库存查询）。
        if (t.code == PdaMenu.exception) {
          return _auth.can(PdaPerm.excHandle) || _auth.can(PdaPerm.excAssign);
        }
        if (t.code == PdaMenu.performance) {
          return _auth.can(PdaPerm.perfTeam);
        }
        return true;
      }).toList();

  @override
  void initState() {
    super.initState();
    HardwareScanKey.instance.attach();
    _refresh();
  }

  @override
  void dispose() {
    HardwareScanKey.instance.detach();
    super.dispose();
  }

  Future<int> _quiet(Future<int> Function() f) async {
    try {
      return await f();
    } catch (_) {
      return 0;
    }
  }

  Future<void> _refresh() async {
    setState(() => _loading = true);
    final tiles = _visible;
    final entries = <Future<MapEntry<String, int>>>[];
    for (final t in tiles) {
      Future<int> Function()? loader;
      switch (t.code) {
        case PdaMenu.receive:
          loader = () => _svc
              .inboundTasks(status: 'PENDING', inboundType: 'PURCHASE')
              .then((v) => v.length);
          break;
        case PdaMenu.receiveReturn:
          loader = () => _svc
              .inboundTasks(status: 'PENDING', inboundType: 'SALES_RETURN')
              .then((v) => v.length);
          break;
        case PdaMenu.otherInbound:
          loader = () => _svc
              .inboundTasks(status: 'PENDING', inboundType: 'OTHER')
              .then((v) => v.length);
          break;
        case PdaMenu.putaway:
          loader = () => _svc.putawayTasks(status: 'PENDING').then((v) => v.length);
          break;
        case PdaMenu.pick:
          loader = () => _svc.pickTasks(scope: 'mine').then((v) => v.length);
          break;
        case PdaMenu.check:
          loader = () => _svc.checkTasks().then((v) => v.length);
          break;
        case PdaMenu.load:
          loader = () => _svc.loadTasks().then((v) => v.length);
          break;
        case PdaMenu.replenish:
          loader =
              () => _svc.replenishTasks(status: 'PENDING').then((v) => v.length);
          break;
        case PdaMenu.move:
          loader = () => _svc
              .moveTasks(status: 'PENDING')
              .then((v) => v.length);
          break;
        case PdaMenu.damage:
          loader =
              () => _svc.damageList(status: 'PENDING').then((v) => v.length);
          break;
        case PdaMenu.exception:
          loader = () =>
              _svc.exceptionList(status: 'OPEN').then((v) => v.length);
          break;
        case PdaMenu.taskAssign:
          loader = () => _svc.assignTasks().then((v) => v.length);
          break;
        default:
          break;
      }
      if (loader != null) {
        final code = t.code;
        entries.add(_quiet(loader).then((n) => MapEntry(code, n)));
      }
    }
    final results = await Future.wait(entries);
    if (!mounted) return;
    setState(() {
      _badges
        ..clear()
        ..addEntries(results);
      _loading = false;
    });
  }

  void _open(_TileDef t) {
    // 入库分组（采购/退货/其他）由路由各自的 ReceivePage(fixedType:...) 承载
    Navigator.pushNamed(context, t.route).then((_) => _refresh());
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('WMS 仓库作业'),
        actions: [
          if (_currentIndex == 0)
            IconButton(
              tooltip: '刷新',
              icon: const Icon(Icons.refresh_rounded),
              onPressed: _refresh,
            ),
          IconButton(
            tooltip: '设置',
            icon: const Icon(Icons.settings_outlined),
            onPressed: () =>
                Navigator.pushNamed(context, '/settings').then((_) {
              if (mounted) setState(() {});
            }),
          ),
        ],
      ),
      body: SafeArea(
        child: IndexedStack(
          index: _currentIndex,
          children: [
            _buildHomeTab(),
            _buildTaskTab(),
            _buildProfileTab(),
          ],
        ),
      ),
      // 中央凸起的扫码按钮：在所有 Tab 都可用，按 homeScan 权限显隐
      floatingActionButton: _auth.can(PdaPerm.homeScan)
          ? FloatingActionButton(
              tooltip: '扫码',
              backgroundColor: PdaTheme.primary,
              foregroundColor: Colors.white,
              elevation: 4,
              highlightElevation: 8,
              onPressed: () => showGlobalScanSheet(context),
              child: const Icon(Icons.qr_code_scanner, size: 28),
            )
          : null,
      floatingActionButtonLocation: FloatingActionButtonLocation.centerDocked,
      // 底部导航：BottomAppBar 留中间 notch 给 FAB；左侧 首页/任务，右侧 我的/设置
      bottomNavigationBar: BottomAppBar(
        shape: const CircularNotchedRectangle(),
        notchMargin: 6,
        color: PdaTheme.surface,
        child: SizedBox(
          height: 56,
          child: Row(
            children: [
              _navTabItem(
                icon: Icons.home_rounded,
                label: '首页',
                index: 0,
                onTap: () => setState(() => _currentIndex = 0),
              ),
              _navTabItem(
                icon: Icons.task_alt_rounded,
                label: '任务',
                index: 1,
                onTap: () => setState(() => _currentIndex = 1),
              ),
              const SizedBox(width: 48), // 中间留空给 FAB notch
              _navTabItem(
                icon: Icons.person_rounded,
                label: '我的',
                index: 2,
                onTap: () => setState(() => _currentIndex = 2),
              ),
              IconButton(
                tooltip: '设置',
                icon: const Icon(Icons.settings_outlined,
                    color: PdaTheme.textSecondary),
                onPressed: () =>
                    Navigator.pushNamed(context, '/settings').then((_) {
                  if (mounted) setState(() {});
                }),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _navTabItem({
    required IconData icon,
    required String label,
    required int index,
    required VoidCallback onTap,
  }) {
    final selected = _currentIndex == index;
    final color = selected ? PdaTheme.primary : PdaTheme.textSecondary;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: SizedBox(
        width: 64,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, color: color, size: 22),
            const SizedBox(height: 2),
            Text(
              label,
              style: TextStyle(
                fontSize: 11,
                color: color,
                fontWeight: FontWeight.w500,
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 首页 Tab：原九宫格 + 待办统计 + 仓库欢迎条。
  Widget _buildHomeTab() {
    final user = _auth.current;
    final name = user?.displayName ?? user?.username ?? '操作员';
    final visible = _visible;
    return RefreshIndicator(
      onRefresh: _refresh,
      color: PdaTheme.primary,
      child: ListView(
        padding: const EdgeInsets.all(PdaSpacing.md),
        children: [
          _welcomeCard(name, user?.warehouseName ?? ''),
          const SizedBox(height: PdaSpacing.md),
          if (_loading)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Center(
                  child:
                      CircularProgressIndicator(color: PdaTheme.primary)),
            ),
          for (final section in _sections) ...[
            if (visible.any((t) => t.section == section)) ...[
              const SizedBox(height: PdaSpacing.lg),
              FunctionCardGrid(
                sectionTitle: section,
                cards: [
                  for (final t in visible.where((t) => t.section == section))
                    FunctionCardData(
                      id: t.code,
                      title: t.title,
                      glyph: t.emoji,
                      color: t.color,
                      badge: _badges[t.code],
                      onTap: () => _open(t),
                    ),
                ],
              ),
            ],
          ],
          if (!_loading && visible.isEmpty)
            const Padding(
              padding: EdgeInsets.only(top: 64),
              child: Center(
                child: Text('当前账号未分配任何 PDA 菜单，请联系管理员',
                    style: PdaStyles.sub),
              ),
            ),
        ],
      ),
    );
  }

  /// 任务 Tab：聚合看板（P0-4）。
  Widget _buildTaskTab() => const TaskBoardPage();

  /// 我的 Tab：精简个人摘要，点击「查看完整资料」进 ProfilePage。
  Widget _buildProfileTab() {
    final user = _auth.current;
    final name = user?.displayName ?? user?.username ?? '操作员';
    return ListView(
      padding: const EdgeInsets.all(PdaSpacing.md),
      children: [
        Container(
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
              width: 56,
              height: 56,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: PdaTheme.primary.withValues(alpha: 0.18),
                borderRadius: BorderRadius.circular(16),
              ),
              child: const Icon(Icons.person_rounded,
                  color: PdaTheme.primary, size: 32),
            ),
            const SizedBox(width: PdaSpacing.md),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(name, style: PdaStyles.title),
                  const SizedBox(height: 4),
                  Text(
                    user?.username ?? '',
                    style: PdaStyles.sub,
                  ),
                  const SizedBox(height: 4),
                  Row(children: [
                    const Icon(Icons.warehouse_outlined,
                        size: 13, color: PdaTheme.textSecondary),
                    const SizedBox(width: 4),
                    Flexible(
                      child: Text(
                        user?.warehouseName ?? '未绑定作业仓库',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: PdaStyles.sub,
                      ),
                    ),
                  ]),
                ],
              ),
            ),
          ]),
        ),
        const SizedBox(height: PdaSpacing.lg),
        if (_auth.hasMenu(PdaMenu.profile))
          ElevatedButton.icon(
            icon: const Icon(Icons.badge_outlined, size: 18),
            label: const Text('查看完整资料'),
            onPressed: () => Navigator.pushNamed(context, '/profile').then((_) {
              if (mounted) setState(() {});
            }),
          ),
        const SizedBox(height: PdaSpacing.sm),
        OutlinedButton.icon(
          icon: const Icon(Icons.settings_outlined, size: 18),
          label: const Text('系统设置'),
          onPressed: () => Navigator.pushNamed(context, '/settings').then((_) {
            if (mounted) setState(() {});
          }),
        ),
      ],
    );
  }

  Widget _welcomeCard(String name, String warehouse) {
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
          width: 44,
          height: 44,
          decoration: BoxDecoration(
            color: PdaTheme.primary.withValues(alpha: 0.18),
            borderRadius: BorderRadius.circular(12),
          ),
          child: const Icon(Icons.qr_code_scanner,
              color: PdaTheme.primary, size: 26),
        ),
        const SizedBox(width: PdaSpacing.md),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('你好，$name', style: PdaStyles.title),
              const SizedBox(height: 4),
              Row(children: [
                const Icon(Icons.warehouse_outlined,
                    size: 13, color: PdaTheme.textSecondary),
                const SizedBox(width: 4),
                Flexible(
                  child: Text(
                    warehouse.isEmpty ? '未绑定作业仓库' : '当前仓库：$warehouse',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: PdaStyles.sub,
                  ),
                ),
              ]),
            ],
          ),
        ),
      ]),
    );
  }
}
