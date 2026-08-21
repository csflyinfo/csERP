import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../config/theme.dart';
import '../../models/task.dart';
import '../../providers/auth_provider.dart';
import '../../providers/notification_provider.dart';
import '../../providers/task_provider.dart';
import '../../services/connectivity_service.dart';
import '../../services/launch_service.dart';
import '../../services/param_service.dart';
import '../../services/sync_service.dart';
import '../../widgets/common.dart';
import '../../widgets/offline_banner.dart';
import '../delivery/delivering_page.dart';
import '../delivery/dispatch_stores_page.dart';
import '../delivery/exception_list_page.dart';
import '../delivery/exception_report_page.dart';
import '../delivery/loading_confirm_page.dart';
import '../return/driver_return_create_page.dart';
import '../return/warehouse_return_page.dart';
import '../settlement/settlement_page.dart';
import 'history_page.dart';
import 'notification_page.dart';
import 'profile_page.dart';

/// 当前任务工作台 + 底部 Tab（对齐原型 Screen B）。
///
/// 「当前任务」= 所有未办结的调度单（含往日积压），不再限定当天：
/// 未完成任务必须始终有作业入口，否则跨天后就永久卡死。
/// 已办结的行程去「历史」Tab 查看。
class HomePage extends ConsumerStatefulWidget {
  const HomePage({super.key});
  @override
  ConsumerState<HomePage> createState() => _HomePageState();
}

class _HomePageState extends ConsumerState<HomePage> {
  int _tab = 0;

  @override
  void initState() {
    super.initState();
    // 进入首页刷新今日任务。
    // 用 refresh() 而不是 build()：直接调 build() 只是执行一遍方法体，
    // 返回值被丢掉、provider state 不变，等于没刷新。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(todayTasksProvider.notifier).refresh();
    });
  }

  @override
  Widget build(BuildContext context) {
    final driver = ref.watch(authProvider);
    final pages = [
      _TodayTab(driverName: driver?.driverName ?? '司机'),
      const DeliveringPage(),
      const HistoryPage(),
      const ProfilePage(),
    ];
    return Scaffold(
      body: pages[_tab],
      bottomNavigationBar: _TmsBottomBar(
        current: _tab,
        onChanged: (i) => setState(() => _tab = i),
        deliveringBadge: _deliveringBadge(),
      ),
    );
  }

  /// 配送中角标：未处理完的门店数。
  ///
  /// 取门店数而非单据数，与【配送中】页的列表行数保持一致；
  /// 角标数字和点进去看到的行数不一样会让司机以为漏单。
  /// 用 watch 而非 read：read 不建立依赖，门店送完后角标不会消。
  int _deliveringBadge() {
    return ref.watch(deliveringStoresProvider).value?.pendingStore ?? 0;
  }
}

/// 顶栏消息铃铛：未读角标 + 进入消息中心。
///
/// 独立成 ConsumerWidget 而不是写在 _TodayContent 里：
/// 未读数每分钟轮询一次，若与工作台同一个 build 作用域，
/// 每次轮询都会重建整张任务列表，长列表下会有可感知的卡顿。
class _NotifyBell extends ConsumerWidget {
  const _NotifyBell();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final unread = ref.watch(notifyUnreadProvider).value;
    final count = unread?.unreadCount ?? 0;
    final urgent = (unread?.urgentCount ?? 0) > 0;
    return Stack(
      alignment: Alignment.center,
      children: [
        IconButton(
          icon: Icon(
            count > 0 ? Icons.notifications_active : Icons.notifications_none,
            color: Colors.white,
          ),
          tooltip: '消息中心',
          onPressed: () {
            final navigator = Navigator.of(context);
            navigator
                .push(MaterialPageRoute(builder: (_) => const NotificationPage()))
                .then((_) => ref.read(notifyUnreadProvider.notifier).refresh());
          },
        ),
        if (count > 0)
          Positioned(
            right: 6,
            top: 8,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
              constraints: const BoxConstraints(minWidth: 15),
              decoration: BoxDecoration(
                // 有紧急消息时用更深的红，与普通未读区分
                color: urgent ? const Color(0xFFB91C1C) : TmsTheme.bad,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.white, width: 1),
              ),
              child: Text(
                count > 99 ? '99+' : '$count',
                textAlign: TextAlign.center,
                style: const TextStyle(
                    color: Colors.white, fontSize: 9, fontWeight: FontWeight.w700),
              ),
            ),
          ),
      ],
    );
  }
}

/// 底部 Tab 栏（当前 / 配送中 / 历史 / 我的）。
///
/// 【退货回收】不再占独立 Tab：退货是配送过程中的一个动作而非独立工作流，
/// 单独立项会让司机在两个 Tab 间来回找同一家门店。
/// 入口收到「我的」页与配送点详情内，跑店主路径保持单一。
class _TmsBottomBar extends StatelessWidget {
  final int current;
  final ValueChanged<int> onChanged;
  final int deliveringBadge;
  const _TmsBottomBar(
      {required this.current, required this.onChanged, required this.deliveringBadge});

  @override
  Widget build(BuildContext context) {
    final items = [
      _TabItem('当前', '📋', false),
      _TabItem('配送中', '🚚', deliveringBadge > 0),
      _TabItem('历史', '📜', false),
      _TabItem('我的', '👤', false),
    ];
    return Container(
      decoration: const BoxDecoration(color: Colors.white, border: Border(top: BorderSide(color: TmsTheme.rule))),
      padding: const EdgeInsets.only(top: 6, bottom: 10),
      child: Row(
        children: List.generate(items.length, (i) {
          final it = items[i];
          final on = i == current;
          return Expanded(
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => onChanged(i),
              child: Stack(
                alignment: Alignment.center,
                children: [
                  Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(it.icon, style: const TextStyle(fontSize: 18)),
                      const SizedBox(height: 2),
                      Text(it.label, style: TextStyle(fontSize: 11, color: on ? TmsTheme.accent : TmsTheme.muted, fontWeight: on ? FontWeight.w700 : FontWeight.normal)),
                    ],
                  ),
                  if (it.badge)
                    Positioned(top: 0, right: 18, child: Container(
                      padding: const EdgeInsets.all(3),
                      decoration: const BoxDecoration(color: TmsTheme.accent2, shape: BoxShape.circle),
                      child: Text('$deliveringBadge', style: const TextStyle(color: Colors.white, fontSize: 8, fontWeight: FontWeight.w700)),
                    )),
                ],
              ),
            ),
          );
        }),
      ),
    );
  }
}

class _TabItem {
  final String label;
  final String icon;
  final bool badge;
  _TabItem(this.label, this.icon, this.badge);
}

/// 今日工作台内容。
class _TodayTab extends ConsumerWidget {
  final String driverName;
  const _TodayTab({required this.driverName});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tasksAsync = ref.watch(todayTasksProvider);
    return tasksAsync.when(
      data: (tasks) => _TodayContent(driverName: driverName, tasks: tasks),
      loading: () => const Scaffold(body: Center(child: CircularProgressIndicator())),
      error: (e, _) => Scaffold(
        appBar: AppBar(title: const Text('当前任务')),
        body: Center(child: Padding(padding: const EdgeInsets.all(24), child: Text('加载失败：$e', style: const TextStyle(color: TmsTheme.muted)))),
      ),
    );
  }
}

class _TodayContent extends ConsumerWidget {
  final String driverName;
  final TodayTasks tasks;
  const _TodayContent({required this.driverName, required this.tasks});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final details = tasks.details;
    final returns = details.where((d) => d.isReturn && d.status == 'PENDING').toList();
    final done = details.where((d) => d.status == 'DELIVERED').length;
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(
        title: const Text('当前任务'),
        actions: [
          const _NotifyBell(),
          const SizedBox(width: 4),
          Padding(padding: const EdgeInsets.only(right: 16), child: Center(child: Text(driverName, style: const TextStyle(color: Colors.white, fontSize: 13)))),
        ],
      ),
      body: Column(
        children: [
          const OfflineBanner(),
          Expanded(
            child: RefreshIndicator(
              // 两个数据源都要刷：概览与下方列表是同一屏的两半，
              // 只刷一个会出现「列表已更新、概览还是旧数字」的错位。
              onRefresh: () async {
                await Future.wait([
                  ref.read(todayTasksProvider.notifier).refresh(),
                  ref.read(homeOverviewProvider.notifier).refresh(),
                ]);
              },
        child: ListView(
          padding: const EdgeInsets.all(14),
          children: [
            // 顶部概览条：只给数字，点击才下钻到明细。
            // 首页的职责是让司机三秒内知道「还剩多少活、钱收了多少、下一站去哪」，
            // 逐个门店的信息量属于「配送中」列表。
            _OverviewBar(details: details, done: done, returns: returns),
            const SizedBox(height: 8),
            Alert.info('📋 ${_dateLabel()} · ${tasks.dispatches.isNotEmpty ? tasks.dispatches.first.routeLine : ""}${_netText(ref)}'),
            const SizedBox(height: 8),
            // 司机流程总开关（TMS_DRIVER_FLOW_ENABLED，PRD-26 §3.2）关闭时的整屏级提示。
            //
            // 为什么是提示横幅而不是直接把首页换成空白页：司机看到空白页只会
            // 反复重装 APP、打电话找调度。明确告诉他「后台关了流程」，
            // 他才知道该找谁。历史任务与交账入口仍保留可查，只锁写操作。
            if (!ParamService.instance.current.driverFlowEnabled) ...[
              const Alert.danger('⛔ 司机派送流程已被管理员关闭，暂不能接单与作业，如有疑问请联系调度'),
              const SizedBox(height: 8),
            ],
            // 待接单 + 当前作业流程 + 下一站 + 待交账
            const _HomeWorkflow(),
            const SizedBox(height: 4),
            // 这里原本还有「退货回收任务」和「待配送任务」两段逐单列表，已移除。
            //
            // 移除的原因不是嫌页面长，而是这两段与首页的定位冲突：
            //   1. 首页要回答的是「这趟车该干哪一步」——接单、装车、发车、去下一站，
            //      是一条流水线；逐单列表回答的是「有哪些单」，属于清单视图。
            //      两者混在一屏时，司机在几十张单里翻找按钮，反而看不见当前该做什么。
            //   2. 退货单在这里只是「又一张单」，但司机对退货的真实操作入口是
            //      门店详情页与现场退货，首页重复挂一份只会让同一件事有两个入口、
            //      两套状态判断，改一处漏一处。
            //   3. 单据维度的浏览已由「查看清单」（按配送点分组）和「配送中」
            //      （按门店合并）承接，两者的合并口径由后端统一，不会再出现
            //      首页数 8 张单、配送中数 5 个点却对不上的情况。
            //
            // 保留下面的快捷操作，是因为它们都不依赖某张具体单据：
            // 现场退货、返仓交接、异常上报在没有任务上下文时同样要能进。
            // 快捷操作（现场退货 / 返仓交接）
            //
            // 现场退货入口受 TMS_ONSITE_RETURN_ENABLED 控制（PRD-26 §3.2）：
            // 部分企业要求退货必须走后台开单、司机不得现场创建，关掉后这个入口消失。
            // 关掉时不留占位按钮——留一个点了报错的按钮，比没有更让司机困惑。
            Row(children: [
              if (ParamService.instance.current.onsiteReturnEnabled) ...[
                Expanded(child: _QuickAction(
                  icon: '🔄',
                  label: '现场退货',
                  color: TmsTheme.returnPurple,
                  onTap: () {
                    final navigator = Navigator.of(context);
                    navigator.push(MaterialPageRoute(
                      builder: (_) => const DriverReturnCreatePage(),
                    )).then((changed) => _maybeRefresh(ref, changed));
                  },
                )),
                const SizedBox(width: 8),
              ],
              Expanded(child: _QuickAction(
                icon: '🏭',
                label: '返仓交接',
                color: TmsTheme.accent2,
                onTap: () {
                  final navigator = Navigator.of(context);
                  navigator.push(MaterialPageRoute(
                    builder: (_) => const WarehouseReturnPage(),
                  )).then((changed) => _maybeRefresh(ref, changed));
                },
              )),
            ]),
            const SizedBox(height: 8),
            // 异常上报入口放在快捷操作区而非只挂在任务卡上：
            // 出车前检查发现车辆故障、途中被交警拦下时还没有任何门店任务上下文，
            // 若只能从任务卡进入，最需要上报的场景反而进不去。
            Row(children: [
              Expanded(child: _QuickAction(
                icon: '⚠️',
                label: '异常上报',
                color: TmsTheme.bad,
                onTap: () {
                  final navigator = Navigator.of(context);
                  navigator.push(MaterialPageRoute(
                    builder: (_) => const ExceptionReportPage(),
                  ));
                },
              )),
              const SizedBox(width: 8),
              Expanded(child: _QuickAction(
                icon: '📋',
                label: '我的上报',
                color: TmsTheme.muted,
                onTap: () {
                  final navigator = Navigator.of(context);
                  navigator.push(MaterialPageRoute(
                    builder: (_) => const ExceptionListPage(),
                  ));
                },
              )),
            ]),
            const SizedBox(height: 8),
            if (details.isEmpty)
              const Padding(padding: EdgeInsets.all(40), child: Center(child: Text('暂无待办任务', style: TextStyle(color: TmsTheme.muted)))),
          ],
        ),
      ),
          ),
        ],
      ),
    );
  }

  /// 概览条的日期文案。
  ///
  /// 不再固定显示「今天」：当前任务页会带出往日没跑完的积压调度单，
  /// 此时写今天的日期会与卡片里单据的实际日期互相矛盾。
  /// 后端按 dispatch_date 升序返回，取首单日期即最早的待办日期；
  /// 早于今天就显式标出「积压」，提示司机先清旧账。
  String _dateLabel() {
    final today = DateTime.now();
    final raw = tasks.dispatches.isNotEmpty ? tasks.dispatches.first.dispatchDate : '';
    // 后端可能返回 yyyy-MM-dd 或带时分秒，统一截前 10 位再解析
    final d = raw.length >= 10 ? DateTime.tryParse(raw.substring(0, 10)) : null;
    if (d == null) return _fmtDate(today);
    final overdue = DateTime(d.year, d.month, d.day)
        .isBefore(DateTime(today.year, today.month, today.day));
    return overdue ? '${_fmtDate(d)} 积压' : _fmtDate(d);
  }

  String _fmtDate(DateTime d) {
    const week = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    return '${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')} ${week[d.weekday - 1]}';
  }

  /// 网络与待同步状态文案（替代原先硬编码的"网络正常"）。
  String _netText(WidgetRef ref) {
    final online = ref.watch(isOnlineProvider).valueOrNull ?? true;
    final pending = ref.watch(pendingCountProvider).valueOrNull ?? 0;
    if (!online) {
      return pending > 0 ? ' · 离线中 · $pending 条待同步' : ' · 离线中';
    }
    return pending > 0 ? ' · 网络正常 · $pending 条待同步' : ' · 网络正常';
  }

  /// 子页面返回 true 时刷新今日任务。
  void _maybeRefresh(WidgetRef ref, dynamic changed) {
    if (changed == true) {
      ref.invalidate(todayTasksProvider);
    }
  }

  /// 导航、拨号、到达时间格式化三个辅助方法随待配送列表一并下线。
  ///
  /// 导航与拨号都必须先确定「去哪个门店、给谁打电话」，天然依赖单据上下文；
  /// 首页移除逐单列表后已经没有门店可指向，强行保留只会退化成「拿第一张单凑数」，
  /// 把司机导到错误的店。它们的正确归属是门店详情页与配送中列表。
  /// 到达时间只在逐单卡片上展示过，同理不再需要。
}

/// 快捷操作按钮（现场退货 / 返仓交接）。
class _QuickAction extends StatelessWidget {
  final String icon;
  final String label;
  final Color color;
  final VoidCallback onTap;
  const _QuickAction({required this.icon, required this.label, required this.color, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 14),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: color.withValues(alpha: 0.3), width: 1.5),
        ),
        child: Column(children: [
          Text(icon, style: const TextStyle(fontSize: 22)),
          const SizedBox(height: 4),
          Text(label, style: TextStyle(fontSize: 12, color: color, fontWeight: FontWeight.w700)),
        ]),
      ),
    );
  }
}

/// 任务卡上的小操作按钮（改派返仓 / 客户拒收）。
class _MiniAction extends StatelessWidget {
  final String label;
  final IconData icon;
  final Color color;

  /// 传 null 表示按钮已完成使命（如已打卡），保留展示但不可再点。
  final VoidCallback? onTap;
  const _MiniAction({required this.label, required this.icon, required this.color, this.onTap});

  @override
  Widget build(BuildContext context) {
    final disabled = onTap == null;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 10),
        decoration: BoxDecoration(
          color: color.withValues(alpha: disabled ? 0.05 : 0.08),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: color.withValues(alpha: disabled ? 0.18 : 0.3), width: 1.2),
        ),
        child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          Icon(icon, size: 14, color: color),
          const SizedBox(width: 4),
          Text(label, style: TextStyle(fontSize: 11, color: color, fontWeight: FontWeight.w700)),
        ]),
      ),
    );
  }
}

/// 首页顶部数量概览条。
///
/// 数字来源仍是 today-tasks 的明细（前端聚合），而不是 home/overview 的 storeStat：
/// 两者口径不同——storeStat 数「门店」，这里数「配送点单据行」。
/// 概览条下方紧跟的就是按单据行展开的列表，数字必须与列表行数对得上，
/// 否则司机会怀疑有单据没显示出来。门店级口径在「配送中」页呈现。
class _OverviewBar extends StatelessWidget {
  final List<DispatchDetail> details;
  final int done;
  final List<DispatchDetail> returns;
  const _OverviewBar({required this.details, required this.done, required this.returns});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 4),
      decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(12)),
      child: Row(children: [
        _cell('${details.length}', '配送点'),
        _divider(),
        _cell('$done', '已完成', color: TmsTheme.ok),
        _divider(),
        _cell('${details.length - done}', '待配送', color: TmsTheme.accent2),
        _divider(),
        _cell('${returns.length}', '退货', color: TmsTheme.returnPurple),
      ]),
    );
  }

  Widget _cell(String num, String label, {Color? color}) =>
      Expanded(child: Column(children: [
        Text(num, style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800, color: color ?? TmsTheme.ink)),
        Text(label, style: const TextStyle(fontSize: 10, color: TmsTheme.muted)),
      ]));

  Widget _divider() => Container(width: 1, height: 28, color: TmsTheme.rule);
}

/// 首页作业流程区：待接单 → 装车 → 发车，外加下一站与待交账。
///
/// 独立成 ConsumerWidget 而不是并进 _TodayTab：
/// 接单成功后只需要重建这一块，_TodayTab 整屏重建会让司机滚动位置跳回顶部。
class _HomeWorkflow extends ConsumerStatefulWidget {
  const _HomeWorkflow();

  @override
  ConsumerState<_HomeWorkflow> createState() => _HomeWorkflowState();
}

class _HomeWorkflowState extends ConsumerState<_HomeWorkflow> {
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(homeOverviewProvider);
    return async.when(
      // 加载/异常都不占满屏：概览是首页的增强信息，
      // 拿不到时下方任务列表仍然可用，不该因为它把整屏堵住。
      loading: () => const SizedBox(
        height: 60,
        child: Center(child: SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))),
      ),
      error: (e, _) => const Alert.warn('概览加载失败，可下拉重试'),
      data: (o) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        if (o.pendingDispatches.isNotEmpty) ..._dispatchCards(o),
        // 未发车调度单卡片已经把装车/发车入口带上了，此时再出「当前作业」
        // 会出现同一张单两个入口。所以只在全部单都已发车后才显示它，
        // 那时它的职责退化成「在途进度概览」，不再承载动作。
        if (o.currentDispatchId.isNotEmpty && o.pendingDispatches.isEmpty)
          _currentCard(o),
        if (o.nextStore != null) _nextStoreCard(o.nextStore!),
        _settlementCard(o),
      ]),
    );
  }

  /// 未发车调度单卡片：从「待接单」一直陪到「发车」。
  ///
  /// 为什么一张卡片承载三个状态而不是分三个区块：司机关心的是「这趟车现在
  /// 卡在哪一步」，状态是卡片的属性而不是分类维度。分区块的话，接单后卡片
  /// 从「待接单」区消失、在「已接单」区重新出现，视觉上像换了个任务。
  ///
  /// 发车（DEPARTED）后后端不再返回它，卡片自然消失并转入【配送中】页，
  /// 对应需求「只有发车后才从首页消失进入配送中」。
  List<Widget> _dispatchCards(HomeOverview o) => [
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Text('🚚 待发车任务（${o.pendingDispatches.length}）',
              style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w700,
                  color: TmsTheme.accent2)),
        ),
        ...o.pendingDispatches.map(_dispatchCard),
      ];

  Widget _dispatchCard(Dispatch d) {
    final canAccept = d.canAccept;
    // 流程总开关关闭时只锁「接单」这一个写入动作，查看清单仍可点。
    // 后端 accept 接口也有同一道守卫，这里是体验层前移：
    // 让司机在点下去之前就看到原因，而不是点完弹一个错误提示。
    final flowEnabled = ParamService.instance.current.driverFlowEnabled;
    return MCard(
      leftBar: canAccept ? TmsTheme.accent2 : TmsTheme.accent,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(
            child: Text(d.dispatchNo,
                style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                    color: TmsTheme.ink)),
          ),
          if (d.appended) ...[
            const MTag.purple('追加'),
            const SizedBox(width: 6),
          ],
          if (canAccept)
            const MTag.orange('待接单')
          else
            MTag.blue(d.statusText.isEmpty ? d.status : d.statusText),
        ]),
        const SizedBox(height: 4),
        Text(d.subtitle,
            style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
        const SizedBox(height: 2),
        Text(
          '${d.storeCount} 个配送点 · ${d.receiptCount} 发货'
          '${d.returnCount > 0 ? ' / ${d.returnCount} 退货' : ''}'
          ' · ${fmtQty(d.totalQty)} 件',
          style: const TextStyle(fontSize: 12, color: TmsTheme.muted),
        ),
        if (d.collectAmount > 0) ...[
          const SizedBox(height: 2),
          Text('代收货款 ¥${d.collectAmount.toStringAsFixed(2)}',
              style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  color: TmsTheme.accent2)),
        ],
        const SizedBox(height: 8),
        Row(children: [
          // 查看清单在任何状态下都可点：接单前司机要靠它判断这趟活能不能接，
          // 接单后靠它调配送顺序，发车前靠它核对配送点。
          Expanded(
            child: _MiniAction(
              label: '查看清单',
              icon: Icons.list_alt,
              color: TmsTheme.muted,
              onTap: () => _openStores(d.dispatchId),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: canAccept
                ? _MiniAction(
                    label: !flowEnabled ? '流程已关闭' : (_busy ? '处理中…' : '接单'),
                    icon: Icons.assignment_turned_in,
                    color: !flowEnabled ? TmsTheme.muted : TmsTheme.accent2,
                    onTap: (_busy || !flowEnabled) ? null : () => _accept(d.dispatchId),
                  )
                : _MiniAction(
                    // 已装车时不在首页直接发车：发车是不可逆动作，
                    // 必须先进装车页过一遍漏装校验。
                    label: d.status == 'LOADED' ? '核对并发车' : '开始装车',
                    icon: d.status == 'LOADED'
                        ? Icons.local_shipping
                        : Icons.inventory_2,
                    color: TmsTheme.accent,
                    onTap: () => _openLoading(d.dispatchId),
                  ),
          ),
        ]),
      ]),
    );
  }

  Future<void> _openStores(String dispatchId) async {
    await Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => DispatchStoresPage(dispatchId: dispatchId),
    ));
    if (!mounted) return;
    // 清单页里可能完成了装车/发车，无条件刷新而不看返回值：
    // 那一路有多个可返回的层级，靠 result 传递容易漏。
    _refreshAll();
  }

  Future<void> _openLoading(String dispatchId) async {
    final changed = await Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => LoadingConfirmPage(dispatchId: dispatchId),
    ));
    if (!mounted) return;
    if (changed == true) _refreshAll();
  }

  void _refreshAll() {
    ref.invalidate(todayTasksProvider);
    ref.read(homeOverviewProvider.notifier).refresh();
  }

  /// 当前作业调度单：按状态给出唯一的下一步动作。
  ///
  /// 只给一个主按钮而不是把装车/发车都摆出来：状态机决定了同一时刻
  /// 只有一个动作是合法的，多摆一个按钮等于引导司机去点必然报错的操作。
  Widget _currentCard(HomeOverview o) {
    final loaded = o.currentStatus == 'LOADED';
    final canAct = o.currentStatus == 'ACCEPTED' || loaded;
    return MCard(
      leftBar: TmsTheme.accent,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          const Expanded(child: Text('当前作业',
              style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
          MTag.blue(o.currentStatusText.isEmpty ? o.currentStatus : o.currentStatusText),
        ]),
        const SizedBox(height: 4),
        Text('共 ${o.totalStore} 个门店 · 已完成 ${o.doneStore} · 待配送 ${o.pendingStore}',
            style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
        if (canAct) ...[
          const SizedBox(height: 8),
          Row(children: [
            Expanded(child: _MiniAction(
              // 已装车时文案说「继续装车/发车」，装车页里才有发车按钮，
              // 这里不直接发车：发车前应让司机再过一遍装车清单。
              label: loaded ? '核对并发车' : '开始装车',
              icon: loaded ? Icons.local_shipping : Icons.inventory_2,
              color: TmsTheme.accent,
              onTap: () {
                Navigator.of(context).push(MaterialPageRoute(
                  builder: (_) => LoadingConfirmPage(dispatchId: o.currentDispatchId),
                )).then((changed) {
                  if (changed == true) {
                    ref.invalidate(todayTasksProvider);
                    ref.read(homeOverviewProvider.notifier).refresh();
                  }
                });
              },
            )),
          ]),
        ],
      ]),
    );
  }

  /// 下一站门店：发车后司机最需要的单条信息。
  Widget _nextStoreCard(HomeNextStore s) => MCard(
        leftBar: TmsTheme.ok,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(child: Text('📍 下一站 · ${s.customerName}',
                style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
            if (s.billCount > 1) MTag.orange('${s.billCount} 单'),
          ]),
          const SizedBox(height: 4),
          if (s.customerAddress.trim().isNotEmpty)
            Text(s.customerAddress, style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
          if (s.contactName.isNotEmpty || s.hasPhone) ...[
            const SizedBox(height: 2),
            Text([s.contactName, s.contactMobile].where((x) => x.isNotEmpty).join(' · '),
                style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
          ],
          const SizedBox(height: 8),
          Row(children: [
            Expanded(child: _MiniAction(
              label: '导航前往',
              icon: Icons.navigation,
              color: TmsTheme.accent,
              onTap: () => _navigate(s),
            )),
            const SizedBox(width: 8),
            Expanded(child: _MiniAction(
              label: s.hasPhone ? '呼叫门店' : '无电话',
              icon: Icons.phone,
              color: s.hasPhone ? TmsTheme.ok : TmsTheme.muted,
              onTap: s.hasPhone ? () => _call(s.contactMobile) : null,
            )),
          ]),
        ]),
      );

  /// 待交账金额。
  ///
  /// 文案用「待交账」而不是 COD：司机交的是手上的现金，
  /// COD 是结算方式的英文缩写，两者不是一回事，混用会让人以为只交货到付款那部分。
  Widget _settlementCard(HomeOverview o) => MCard(
        leftBar: o.settledToday ? TmsTheme.ok : TmsTheme.accent2,
        onTap: () {
          Navigator.of(context).push(MaterialPageRoute(builder: (_) => const SettlementPage()))
              .then((changed) {
            if (changed == true) {
              ref.invalidate(todayTasksProvider);
              ref.read(homeOverviewProvider.notifier).refresh();
            }
          });
        },
        child: Row(children: [
          Icon(Icons.account_balance_wallet, size: 20,
              color: o.settledToday ? TmsTheme.ok : TmsTheme.accent2),
          const SizedBox(width: 10),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('待交账金额',
                style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            const SizedBox(height: 2),
            Text(
              o.settledToday
                  ? '今日已提交交账，等待财务审核'
                  : '现金实收 ¥${o.cashAmount.toStringAsFixed(2)}'
                      '${o.returnAmount > 0 ? ' · 已冲减退货 ¥${o.returnAmount.toStringAsFixed(2)}' : ''}',
              style: const TextStyle(fontSize: 11, color: TmsTheme.muted),
            ),
          ])),
          if (!o.settledToday)
            Text('¥${o.submitAmount.toStringAsFixed(2)}',
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800, color: TmsTheme.accent2))
          else
            const MTag.green('已交账'),
          const Icon(Icons.chevron_right, size: 18, color: TmsTheme.muted),
        ]),
      );

  Future<void> _accept(String dispatchId) async {
    setState(() => _busy = true);
    final messenger = ScaffoldMessenger.of(context);
    try {
      final res = await ref.read(acceptDispatchProvider.notifier).accept(dispatchId);
      // repeated=true 说明这单早就接过了（弱网重复点击），提示措辞要区分开，
      // 否则司机会以为自己刚才那次没成功。
      final repeated = res['repeated'] == true;
      messenger.showSnackBar(SnackBar(content: Text(repeated ? '该调度单已接单' : '接单成功，可开始装车')));
      ref.invalidate(todayTasksProvider);
      await ref.read(homeOverviewProvider.notifier).refresh();
    } catch (e) {
      messenger.showSnackBar(
          SnackBar(content: Text('接单失败：${e.toString().replaceFirst("Exception: ", "")}')));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _navigate(HomeNextStore s) async {
    final messenger = ScaffoldMessenger.of(context);
    if (!s.hasGeo && s.customerAddress.trim().isEmpty) {
      messenger.showSnackBar(const SnackBar(content: Text('门店未维护坐标与地址，无法导航')));
      return;
    }
    final ok = await LaunchService.instance.navigate(
      longitude: s.longitude,
      latitude: s.latitude,
      address: s.customerAddress,
      name: s.customerName,
    );
    if (!ok) {
      messenger.showSnackBar(const SnackBar(content: Text('未找到可用地图应用')));
    } else if (!s.hasGeo) {
      messenger.showSnackBar(const SnackBar(content: Text('门店未维护坐标，已按地址搜索，请核对位置')));
    }
  }

  Future<void> _call(String mobile) async {
    final messenger = ScaffoldMessenger.of(context);
    final ok = await LaunchService.instance.dial(mobile);
    if (!ok) messenger.showSnackBar(SnackBar(content: Text('拨号失败，请手动联系 $mobile')));
  }
}
