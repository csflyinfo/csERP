import 'dart:async';
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

  /// 各类型最近完成数据：待办为空时展示，给操作员"刚做完什么"的反馈。
  final Map<String, List<dynamic>> _recentDone = {};

  /// 当前选中的 Tab；默认 'all'（全部）。
  String _activeTab = 'all';

  /// 自动轮询定时器：进入页面每 30s 静默刷新（不显示 loading、不打断操作）。
  Timer? _pollTimer;

  /// 轮询间隔（方案 V1.1：30 秒）。
  static const _pollInterval = Duration(seconds: 30);

  @override
  void initState() {
    super.initState();
    _load();
    _pollTimer = Timer.periodic(_pollInterval, (_) => _load(silent: true));
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    super.dispose();
  }

  /// 拉取全部可见类型待办。
  /// - silent=false（首屏/手动下拉）：显示 loading；
  /// - silent=true（定时轮询）：后台静默刷新，不改变 loading、不弹错，
  ///   避免操作员正处理任务时界面被打断。
  Future<void> _load({bool silent = false}) async {
    if (!silent) setState(() => _loading = true);
    final pending = <Future<_BucketEntry>>[];
    final done = <Future<_BucketEntry>>[];
    for (final cat in _catalog) {
      if (!cat.visible) continue;
      pending.add(_quiet(cat.loader).then((v) => _BucketEntry(cat.code, v)));
      final dl = cat.doneLoader;
      if (dl != null) {
        done.add(_quiet(dl).then((v) => _BucketEntry(cat.code, v)));
      }
    }
    final pendingResults = await Future.wait(pending);
    final doneResults = await Future.wait(done);
    if (!mounted) return;
    setState(() {
      _buckets
        ..clear()
        ..addEntries([for (final e in pendingResults) MapEntry(e.code, e.list)]);
      _recentDone
        ..clear()
        ..addEntries([for (final e in doneResults) MapEntry(e.code, e.list)]);
      if (!silent) _loading = false;
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
      doneLoader: () => _svc.inboundTasks(status: 'DONE'),
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
      doneLoader: () => _svc.putawayTasks(status: 'DONE'),
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
      doneLoader: () => _svc.pickTasks(scope: 'mine'),
      extractNo: (m) => pickStr(m, ['taskNo', 'task_no', 'pickNo']),
      extractSub: (m) => pickStr(m, ['waveNo', 'wave_no', 'zone']),
      priorityOf: (m) {
        // 拣货列表含已完成(PICKED)行，完成行在空态展示时不参与加急判定，固定 low
        if (pickStr(m, ['status']).toUpperCase() == 'PICKED') return Priority.low;
        return Priority.resolve(m);
      },
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
      doneLoader: () => _svc.replenishTasks(status: 'DONE'),
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
      doneLoader: () => _svc.moveTasks(status: 'DONE'),
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
      doneLoader: () => _svc.stocktakeList(status: 'APPROVED'),
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
      doneLoader: () => _svc.damageList(status: 'APPROVED'),
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

  /// 当前 Tab 下所有任务（带 category 关联），按优先级 加急→常规→一般 排序。
  List<_TaskRow> get _activeRows {
    final rows = <_TaskRow>[];
    for (final cat in _activeCats) {
      final list = _buckets[cat.code] ?? const [];
      for (final raw in list) {
        rows.add(_TaskRow(cat, Map<String, dynamic>.from(raw as Map)));
      }
    }
    rows.sort((a, b) => a.priority.index.compareTo(b.priority.index));
    return rows;
  }

  /// 当前 Tab 下的最近完成记录（空态展示），每个类型最多取 2 条，最多展示 6 条。
  List<_TaskRow> get _recentRows {
    final rows = <_TaskRow>[];
    for (final cat in _activeCats) {
      final list = _recentDone[cat.code] ?? const [];
      final shown = list.take(2).toList();
      for (final raw in shown) {
        rows.add(_TaskRow(cat, Map<String, dynamic>.from(raw as Map)));
        if (rows.length >= 6) return rows;
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
            _EmptyHint(recentRows: _recentRows)
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

  /// 已完成任务加载器：用于空态"最近完成"；为空表示该类型不展示完成记录。
  final Future<List<dynamic>> Function()? doneLoader;

  final String Function(Map<String, dynamic>) extractNo;
  final String Function(Map<String, dynamic>) extractSub;

  /// 优先级判定：默认走 [Priority.resolve]，特殊类型可覆盖。
  final Priority Function(Map<String, dynamic>) priorityOf;

  const _CategoryDef({
    required this.code,
    required this.label,
    required this.icon,
    required this.color,
    required this.route,
    required this.visible,
    required this.loader,
    this.doneLoader,
    required this.extractNo,
    required this.extractSub,
    Priority Function(Map<String, dynamic>)? priorityOf,
  }) : priorityOf = priorityOf ?? Priority.resolve;
}

class _TaskRow {
  final _CategoryDef cat;
  final Map<String, dynamic> data;
  const _TaskRow(this.cat, this.data);

  /// 本行优先级（由类型目录的提取器决定）。
  Priority get priority => cat.priorityOf(data);
}

/// 任务优先级（方案 V1.1 优化项二）。
///
/// 当前数据层各任务表普遍没有可靠的"截止时间"字段，因此优先级
/// 主要由 加急标志(expedited) 与状态推导：
/// - urgent：显式加急（expedited=Y）或未领取但已下放需立即处理的波次；
/// - normal：已分配/进行中的常规任务；
/// - low：无加急、无紧迫状态的待办。
/// 后续表结构补充截止时间字段后，只需在各 priorityOf 中加规则，UI 无需改动。
enum Priority {
  urgent('加急', PdaTheme.danger),
  normal('常规', PdaTheme.warning),
  low('一般', PdaTheme.primary);

  final String label;
  final Color color;
  const Priority(this.label, this.color);

  /// 从任务数据推导优先级。
  /// - expedited/urgent 为真 → urgent；
  /// - 否则按 assignee/status：已有人认领或进行中 → normal；
  /// - 都不是 → low。
  static Priority resolve(Map<String, dynamic> m, {bool? forceUrgent}) {
    if (forceUrgent == true) return Priority.urgent;
    if (_flag(m, ['expedited', 'urgent', 'isUrgent'])) return Priority.urgent;
    final assignee = pickStr(m, ['assignee', 'assigneeName']);
    final status = pickStr(m, ['status']).toUpperCase();
    final inProgress = status == 'CLAIMED' ||
        status == 'PICKING' ||
        status == 'PUTTING' ||
        status == 'CHECKING';
    if (assignee.isNotEmpty || inProgress) return Priority.normal;
    return Priority.low;
  }

  /// 读取布尔/字符型标志（Y/1/true 视为真）。
  static bool _flag(Map<String, dynamic> m, List<String> keys) {
    for (final k in keys) {
      final v = m[k];
      if (v == null) continue;
      if (v is bool) return v;
      final s = v.toString().trim().toUpperCase();
      if (s == 'Y' || s == '1' || s == 'TRUE') return true;
    }
    return false;
  }
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
  final List<_TaskRow> recentRows;
  const _EmptyHint({this.recentRows = const []});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 32),
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
          Text('下拉刷新获取最新数据 · 每 30 秒自动更新', style: PdaStyles.sub),
          if (recentRows.isNotEmpty) ...[
            const SizedBox(height: PdaSpacing.lg),
            const Row(children: [
              Icon(Icons.history, size: 15, color: PdaTheme.textSecondary),
              SizedBox(width: 6),
              Text('最近完成',
                  style: TextStyle(
                      fontSize: 13, color: PdaTheme.textSecondary)),
            ]),
            const SizedBox(height: PdaSpacing.sm),
            ...recentRows.map((r) => _RecentDoneRow(row: r)),
          ],
        ],
      ),
    );
  }
}

/// 最近完成记录行：灰显 + 对勾图标，与待办卡片视觉区分。
class _RecentDoneRow extends StatelessWidget {
  final _TaskRow row;
  const _RecentDoneRow({required this.row});

  @override
  Widget build(BuildContext context) {
    final no = row.cat.extractNo(row.data);
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Container(
        padding: const EdgeInsets.symmetric(
            horizontal: PdaSpacing.md, vertical: PdaSpacing.sm),
        decoration: BoxDecoration(
          color: PdaTheme.surface,
          borderRadius: BorderRadius.circular(PdaSpacing.radius),
          border: Border.all(color: PdaTheme.border),
        ),
        child: Row(children: [
          const Icon(Icons.check_circle_rounded,
              size: 16, color: PdaTheme.primary),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              no.isEmpty ? '已完成任务' : '${row.cat.label} · $no',
              style: const TextStyle(
                  fontSize: 12, color: PdaTheme.textSecondary),
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ]),
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
    final pr = row.priority;
    return Padding(
      padding: const EdgeInsets.only(bottom: PdaSpacing.sm),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(PdaSpacing.radius),
        child: Container(
          decoration: BoxDecoration(
            color: PdaTheme.surface,
            borderRadius: BorderRadius.circular(PdaSpacing.radius),
            border: Border.all(color: PdaTheme.border),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _leftBar(pr.color),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.all(PdaSpacing.md),
                  child: Row(
                    children: [
                      _typeIcon(),
                      const SizedBox(width: PdaSpacing.md),
                      Expanded(child: _text(no, sub, pr)),
                      const Icon(Icons.chevron_right_rounded,
                          color: PdaTheme.textSecondary, size: 22),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 左侧优先级色条。
  Widget _leftBar(Color color) {
    return Container(
      width: 4,
      decoration: BoxDecoration(
        color: color,
        borderRadius: const BorderRadius.only(
          topLeft: Radius.circular(PdaSpacing.radius),
          bottomLeft: Radius.circular(PdaSpacing.radius),
        ),
      ),
    );
  }

  /// 类型图标。
  Widget _typeIcon() {
    return Container(
      width: 40,
      height: 40,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: row.cat.color.withValues(alpha: 0.16),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Icon(row.cat.icon, color: row.cat.color, size: 22),
    );
  }

  /// 右侧文本：类型标签 + 单号（第一行），优先级点 + 摘要（第二行）。
  Widget _text(String no, String sub, Priority pr) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Row(
          children: [
            Container(
              padding:
                  const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
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
          ],
        ),
        const SizedBox(height: 4),
        Row(
          children: [
            Icon(Icons.circle, size: 7, color: pr.color),
            const SizedBox(width: 4),
            Text(pr.label,
                style: TextStyle(
                    fontSize: 10,
                    color: pr.color,
                    fontWeight: FontWeight.w600)),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                sub.isEmpty ? '点击进入处理' : sub,
                style: PdaStyles.sub,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ),
      ],
    );
  }
}
