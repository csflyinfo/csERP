import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';

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
    _refresh();
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
    final user = _auth.current;
    final name = user?.displayName ?? user?.username ?? '操作员';
    final visible = _visible;
    return Scaffold(
      appBar: AppBar(
        title: const Text('WMS 仓库作业'),
        actions: [
          IconButton(
            tooltip: '刷新',
            icon: const Icon(Icons.refresh_rounded),
            onPressed: _refresh,
          ),
          if (_auth.hasMenu(PdaMenu.profile))
            IconButton(
              tooltip: '我的',
              icon: const Icon(Icons.person_outline),
              onPressed: () => Navigator.pushNamed(context, '/profile')
                  .then((_) {
                if (mounted) setState(() {});
                _refresh();
              }),
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
                  _sectionTitle(section),
                  const SizedBox(height: PdaSpacing.sm),
                  _grid([
                    for (final t in visible.where((t) => t.section == section))
                      _tile(t, _badges[t.code], () => _open(t)),
                  ]),
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
        ),
      ),
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

  /// 单个九宫格入口卡片，badge 右上角红点。
  Widget _tile(_TileDef def, int? badge, VoidCallback onTap) {
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
                      color: def.color.withValues(alpha: 0.16),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Text(def.emoji,
                        style: const TextStyle(fontSize: 26, height: 1.1)),
                  ),
                  const SizedBox(height: 8),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                    child: Text(
                      def.title,
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
                  padding:
                      const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
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
}
