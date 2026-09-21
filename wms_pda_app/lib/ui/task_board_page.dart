import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 任务聚合看板（P0-4）：底部导航「任务」Tab 实现。
///
/// 设计：
/// - 顶部汇总 Chip（横向滚动）：按登录权限裁剪可见类型，显示各类型待办数；
/// - Tab 切换：全部 / 各业务类型；
/// - 列表：当前 Tab 下的待办任务卡片，点击跳转对应业务页；
/// - 所有接口并行请求，任何失败静默处理（不弹错、不阻塞看板）。
class TaskBoardPage extends StatefulWidget {
  const TaskBoardPage({super.key});
  @override
  State<TaskBoardPage> createState() => _TaskBoardPageState();
}

class _TaskBoardPageState extends State<TaskBoardPage> {
  final _svc = WmsAppService.instance;
  final _auth = AuthService.instance;

  bool _loading = true;

  /// 各类型待办数据：key = 类型 code（见下面的 _catalog）。
  final Map<String, List<dynamic>> _buckets = {};

  /// 当前选中的 Tab；默认 'all'（全部）。
  String _activeTab = 'all';

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    final entries = <Future<_BucketEntry>>[];
    for (final cat in _catalog) {
      if (!cat.visible) continue;
      entries.add(_quiet(cat.loader).then((v) => _BucketEntry(cat.code, v)));
    }
    final results = await Future.wait(entries);
    if (!mounted) return;
    setState(() {
      _buckets
        ..clear()
        ..addEntries([for (final e in results) MapEntry(e.code, e.list)]);
      _loading = false;
    });
  }

  Future<List<dynamic>> _quiet(Future<List<dynamic>> Function() f) async {
    try {
      return await f();
    } catch (_) {
      return const [];
    }
  }

  // ==================== 类型目录 ====================

  late final List<_CategoryDef> _catalog = [
    _CategoryDef(
      code: 'receive',
      label: '收货',
      icon: Icons.download_rounded,
      color: PdaTheme.primary,
      route: '/receive',
      visible: _auth.can(PdaPerm.receiveView),
      loader: () => _svc.inboundTasks(status: 'PENDING'),
      extractNo: (m) => pickStr(m, ['taskNo', 'task_no', 'inboundNo', 'inbound_no']),
      extractSub: (m) => pickStr(m, ['supplierName', 'supplier', 'warehouse']),
    ),
    _CategoryDef(
      code: 'putaway',
      label: '上架',
      icon: Icons.label_rounded,
      color: PdaTheme.info,
      route: '/putaway',
      visible: _auth.can(PdaPerm.putawayView),
      loader: () => _svc.putawayTasks(status: 'PENDING'),
      extractNo: (m) => pickStr(m, ['putawayNo', 'putaway_no', 'taskNo']),
      extractSub: (m) => pickStr(m, ['containerCode', 'container_code', 'goodsCode']),
    ),
    _CategoryDef(
      code: 'pick',
      label: '拣货',
      icon: Icons.outbox_rounded,
      color: PdaTheme.warning,
      route: '/pick',
      visible: _auth.can(PdaPerm.pickView),
      loader: () => _svc.pickTasks(scope: 'mine'),
      extractNo: (m) => pickStr(m, ['taskNo', 'task_no', 'pickNo']),
      extractSub: (m) => pickStr(m, ['waveNo', 'wave_no', 'zone']),
    ),
    _CategoryDef(
      code: 'check',
      label: '复核',
      icon: Icons.fact_check_rounded,
      color: PdaTheme.primary,
      route: '/check',
      visible: _auth.can(PdaPerm.checkView),
      loader: () => _svc.checkTasks(),
      extractNo: (m) => pickStr(m, ['waveNo', 'wave_no']),
      extractSub: (m) => '${pickNum(m, ['orderCount', 'order_count'])} 单',
    ),
    _CategoryDef(
      code: 'load',
      label: '装车',
      icon: Icons.local_shipping_rounded,
      color: Color(0xFF26C6DA),
      route: '/load',
      visible: _auth.can(PdaPerm.loadView),
      loader: () => _svc.loadTasks(),
      extractNo: (m) => pickStr(m, ['waveNo', 'wave_no']),
      extractSub: (m) => '${pickNum(m, ['orderCount', 'order_count'])} 单',
    ),
    _CategoryDef(
      code: 'replenish',
      label: '补货',
      icon: Icons.bolt_rounded,
      color: PdaTheme.warning,
      route: '/replenish',
      visible: _auth.can(PdaPerm.replenishView),
      loader: () => _svc.replenishTasks(status: 'PENDING'),
      extractNo: (m) => pickStr(m, ['taskNo', 'task_no', 'replenishNo']),
      extractSub: (m) => pickStr(m, ['toBin', 'to_bin', 'goodsCode']),
    ),
    _CategoryDef(
      code: 'move',
      label: '移库',
      icon: Icons.swap_horiz_rounded,
      color: PdaTheme.info,
      route: '/move',
      visible: _auth.can(PdaPerm.moveView),
      loader: () => _svc.moveTasks(status: 'PENDING'),
      extractNo: (m) => pickStr(m, ['taskNo', 'task_no', 'moveNo']),
      extractSub: (m) =>
          '${pickStr(m, ['fromBin', 'from_bin'])} → ${pickStr(m, ['toBin', 'to_bin'])}',
    ),
    _CategoryDef(
      code: 'stocktake',
      label: '盘点',
      icon: Icons.fact_check_outlined,
      color: PdaTheme.danger,
      route: '/stocktake',
      visible: _auth.can(PdaPerm.takeView),
      loader: () => _svc.stocktakeList(status: 'COUNTING'),
      extractNo: (m) => pickStr(m, ['taskNo', 'task_no']),
      extractSub: (m) => pickStr(m, ['warehouse', 'scopeText', 'scope_text']),
    ),
    _CategoryDef(
      code: 'damage',
      label: '报损',
      icon: Icons.report_problem_rounded,
      color: Color(0xFFEF5350),
      route: '/damage',
      visible: _auth.can(PdaPerm.damageView),
      loader: () => _svc.damageList(status: 'PENDING'),
      extractNo: (m) => pickStr(m, ['damageNo', 'damage_no', 'id']),
      extractSub: (m) => pickStr(m, ['goodsCode', 'goods_code']),
    ),
    _CategoryDef(
      code: 'exception',
      label: '异常',
      icon: Icons.warning_amber_rounded,
      color: Color(0xFFFFB300),
      route: '/exception',
      visible: _auth.can(PdaPerm.excView),
      loader: () => _svc.exceptionList(status: 'OPEN'),
      extractNo: (m) => pickStr(m, ['exceptionNo', 'exception_no', 'id']),
      extractSub: (m) => pickStr(m, ['exceptionType', 'exception_type', 'sourceType']),
    ),
  ];

  List<_CategoryDef> get _visibleCats =>
      _catalog.where((c) => c.visible).toList();

  /// 当前 Tab 对应的类型集合；'all' = 全量。
  List<_CategoryDef> get _activeCats {
    if (_activeTab == 'all') return _visibleCats;
    return _visibleCats.where((c) => c.code == _activeTab).toList();
  }

  /// 当前 Tab 下所有任务（带 category 关联）。
  List<_TaskRow> get _activeRows {
    final rows = <_TaskRow>[];
    for (final cat in _activeCats) {
      final list = _buckets[cat.code] ?? const [];
      for (final raw in list) {
        rows.add(_TaskRow(cat, Map<String, dynamic>.from(raw as Map)));
      }
    }
    return rows;
  }

  // ==================== Build ====================

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      onRefresh: _load,
      color: PdaTheme.primary,
      child: ListView(
        padding: const EdgeInsets.all(PdaSpacing.md),
        children: [
          _SummaryBar(_visibleCats, _buckets, onCatTap: (code) {
            setState(() => _activeTab = code);
          }),
          const SizedBox(height: PdaSpacing.lg),
          _TabChips(
            visibleCats: _visibleCats,
            activeTab: _activeTab,
            buckets: _buckets,
            onChanged: (v) => setState(() => _activeTab = v),
          ),
          const SizedBox(height: PdaSpacing.md),
          if (_loading)
            const Padding(
              padding: EdgeInsets.all(40),
              child: Center(
                  child: CircularProgressIndicator(color: PdaTheme.primary)),
            )
          else if (_activeRows.isEmpty)
            _EmptyHint()
          else
            ..._activeRows.map((r) => _TaskCard(
                  row: r,
                  onTap: () => Navigator.pushNamed(context, r.cat.route),
                )),
        ],
      ),
    );
  }
}

// ==================== 数据类型 ====================

class _BucketEntry {
  final String code;
  final List<dynamic> list;
  const _BucketEntry(this.code, this.list);
}

class _CategoryDef {
  final String code;
  final String label;
  final IconData icon;
  final Color color;
  final String route;
  final bool visible;
  final Future<List<dynamic>> Function() loader;
  final String Function(Map<String, dynamic>) extractNo;
  final String Function(Map<String, dynamic>) extractSub;
  const _CategoryDef({
    required this.code,
    required this.label,
    required this.icon,
    required this.color,
    required this.route,
    required this.visible,
    required this.loader,
    required this.extractNo,
    required this.extractSub,
  });
}

class _TaskRow {
  final _CategoryDef cat;
  final Map<String, dynamic> data;
  const _TaskRow(this.cat, this.data);
}

// ==================== 顶部汇总条 ====================

class _SummaryBar extends StatelessWidget {
  final List<_CategoryDef> cats;
  final Map<String, List<dynamic>> buckets;
  final void Function(String code) onCatTap;
  const _SummaryBar(this.cats, this.buckets, {required this.onCatTap});

  int _count(_CategoryDef c) => (buckets[c.code] ?? const []).length;

  @override
  Widget build(BuildContext context) {
    final total = cats.fold<int>(0, (s, c) => s + _count(c));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          Container(
            width: 36,
            height: 36,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: PdaTheme.primary.withValues(alpha: 0.14),
              borderRadius: BorderRadius.circular(10),
            ),
            child: const Icon(Icons.task_alt_rounded,
                color: PdaTheme.primary, size: 22),
          ),
          const SizedBox(width: PdaSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('待办汇总', style: PdaStyles.title),
                const SizedBox(height: 2),
                Text(
                  total == 0 ? '当前没有待办任务' : '合计 $total 项待办',
                  style: PdaStyles.sub,
                ),
              ],
            ),
          ),
        ]),
        const SizedBox(height: PdaSpacing.sm),
        if (cats.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 24),
            child: Text('当前账号没有任何作业权限', style: PdaStyles.sub),
          )
        else
          SizedBox(
            height: 72,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: cats.length,
              separatorBuilder: (_, __) => const SizedBox(width: PdaSpacing.sm),
              itemBuilder: (_, i) {
                final c = cats[i];
                final n = _count(c);
                return _SummaryChip(
                  def: c,
                  count: n,
                  onTap: () => onCatTap(c.code),
                );
              },
            ),
          ),
      ],
    );
  }
}

class _SummaryChip extends StatelessWidget {
  final _CategoryDef def;
  final int count;
  final VoidCallback onTap;
  const _SummaryChip({
    required this.def,
    required this.count,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final dim = count == 0;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(PdaSpacing.radius),
        child: Container(
          width: 84,
          padding: const EdgeInsets.symmetric(
              horizontal: PdaSpacing.sm, vertical: PdaSpacing.sm),
          decoration: BoxDecoration(
            color: PdaTheme.surface,
            borderRadius: BorderRadius.circular(PdaSpacing.radius),
            border: Border.all(color: PdaTheme.border),
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(def.icon,
                  size: 20, color: dim ? PdaTheme.textSecondary : def.color),
              const SizedBox(height: 4),
              Text(
                '$count',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                  color: dim ? PdaTheme.textSecondary : PdaTheme.textPrimary,
                  height: 1.1,
                ),
              ),
              const SizedBox(height: 2),
              Text(def.label,
                  style: const TextStyle(fontSize: 10, color: PdaTheme.textSecondary)),
            ],
          ),
        ),
      ),
    );
  }
}

// ==================== Tab Chips ====================

class _TabChips extends StatelessWidget {
  final List<_CategoryDef> visibleCats;
  final String activeTab;
  final Map<String, List<dynamic>> buckets;
  final void Function(String) onChanged;
  const _TabChips({
    required this.visibleCats,
    required this.activeTab,
    required this.buckets,
    required this.onChanged,
  });

  int _count(_CategoryDef c) => (buckets[c.code] ?? const []).length;

  @override
  Widget build(BuildContext context) {
    final tabs = <_TabDef>[
      _TabDef('all', '全部', visibleCats.fold<int>(0, (s, c) => s + _count(c))),
      for (final c in visibleCats) _TabDef(c.code, c.label, _count(c)),
    ];
    return SizedBox(
      height: 38,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: tabs.length,
        separatorBuilder: (_, __) => const SizedBox(width: 6),
        itemBuilder: (_, i) {
          final t = tabs[i];
          final selected = t.code == activeTab;
          return ChoiceChip(
            label: Text(t.count > 0 ? '${t.label} · ${t.count}' : t.label),
            selected: selected,
            onSelected: (_) => onChanged(t.code),
          );
        },
      ),
    );
  }
}

class _TabDef {
  final String code;
  final String label;
  final int count;
  const _TabDef(this.code, this.label, this.count);
}

// ==================== 空态 ====================

class _EmptyHint extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 64),
      child: Column(
        children: [
          Container(
            width: 64,
            height: 64,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: PdaTheme.primary.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(16),
            ),
            child: const Icon(Icons.check_circle_outline_rounded,
                size: 32, color: PdaTheme.primary),
          ),
          const SizedBox(height: PdaSpacing.md),
          const Text('当前类型暂无待办', style: PdaStyles.title),
          const SizedBox(height: 4),
          Text('下拉刷新获取最新数据', style: PdaStyles.sub),
        ],
      ),
    );
  }
}

// ==================== 任务卡片 ====================

class _TaskCard extends StatelessWidget {
  final _TaskRow row;
  final VoidCallback onTap;
  const _TaskCard({required this.row, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final no = row.cat.extractNo(row.data);
    final sub = row.cat.extractSub(row.data);
    return Padding(
      padding: const EdgeInsets.only(bottom: PdaSpacing.sm),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(PdaSpacing.radius),
          child: Container(
            padding: const EdgeInsets.all(PdaSpacing.md),
            decoration: BoxDecoration(
              color: PdaTheme.surface,
              borderRadius: BorderRadius.circular(PdaSpacing.radius),
              border: Border.all(color: PdaTheme.border),
            ),
            child: Row(children: [
              Container(
                width: 40,
                height: 40,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: row.cat.color.withValues(alpha: 0.16),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(row.cat.icon, color: row.cat.color, size: 22),
              ),
              const SizedBox(width: PdaSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(children: [
                      Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 6, vertical: 1),
                        decoration: BoxDecoration(
                          color: row.cat.color.withValues(alpha: 0.16),
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Text(row.cat.label,
                            style: TextStyle(
                                fontSize: 10,
                                color: row.cat.color,
                                fontWeight: FontWeight.w600)),
                      ),
                      const SizedBox(width: 6),
                      Expanded(
                        child: Text(
                          no.isEmpty ? '未知任务' : no,
                          style: PdaStyles.title,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ]),
                    const SizedBox(height: 4),
                    Text(sub.isEmpty ? '点击进入处理' : sub, style: PdaStyles.sub),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right_rounded,
                  color: PdaTheme.textSecondary, size: 22),
            ]),
          ),
        ),
      ),
    );
  }
}
