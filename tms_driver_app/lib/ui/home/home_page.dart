import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../config/theme.dart';
import '../../models/task.dart';
import '../../providers/auth_provider.dart';
import '../../providers/task_provider.dart';
import '../../widgets/common.dart';
import '../../widgets/offline_banner.dart';
import '../delivery/delivery_sign_page.dart';
import '../delivery/loading_confirm_page.dart';
import '../return/customer_reject_page.dart';
import '../return/driver_return_create_page.dart';
import '../return/reschedule_return_page.dart';
import '../return/return_list_page.dart';
import '../return/warehouse_return_page.dart';
import '../settlement/settlement_page.dart';
import 'history_page.dart';
import 'profile_page.dart';

/// 今日工作台 + 底部 Tab（对齐原型 Screen B）。
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
    // 进入首页刷新今日任务
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(todayTasksProvider.notifier).build();
    });
  }

  @override
  Widget build(BuildContext context) {
    final driver = ref.watch(authProvider);
    final pages = [
      _TodayTab(driverName: driver?.driverName ?? '司机'),
      const HistoryPage(),
      const ReturnListPage(),
      const ProfilePage(),
    ];
    return Scaffold(
      body: pages[_tab],
      bottomNavigationBar: _TmsBottomBar(
        current: _tab,
        onChanged: (i) => setState(() => _tab = i),
        returnBadge: _returnBadge(),
      ),
    );
  }

  int _returnBadge() {
    final tasks = ref.read(todayTasksProvider);
    final val = tasks.value;
    if (val == null) return 0;
    return val.details.where((d) => d.isReturn && d.status == 'PENDING').length;
  }
}

/// 底部 Tab 栏（今日 / 历史 / 退货回收 / 我的）。
class _TmsBottomBar extends StatelessWidget {
  final int current;
  final ValueChanged<int> onChanged;
  final int returnBadge;
  const _TmsBottomBar({required this.current, required this.onChanged, required this.returnBadge});

  @override
  Widget build(BuildContext context) {
    final items = [
      _TabItem('今日', '📋', false),
      _TabItem('历史', '📜', false),
      _TabItem('退货回收', '♻️', returnBadge > 0),
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
                      child: Text('$returnBadge', style: const TextStyle(color: Colors.white, fontSize: 8, fontWeight: FontWeight.w700)),
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
        appBar: AppBar(title: const Text('今日工作台')),
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
    final receipts = details.where((d) => !d.isReturn).toList();
    final returns = details.where((d) => d.isReturn && d.status == 'PENDING').toList();
    final done = details.where((d) => d.status == 'DELIVERED').length;
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(
        title: const Text('今日工作台'),
        actions: [
          const Icon(Icons.notifications_none, color: Colors.white),
          const SizedBox(width: 12),
          Padding(padding: const EdgeInsets.only(right: 16), child: Center(child: Text(driverName, style: const TextStyle(color: Colors.white, fontSize: 13)))),
        ],
      ),
      body: Column(
        children: [
          const OfflineBanner(),
          Expanded(
            child: RefreshIndicator(
              onRefresh: () => ref.read(todayTasksProvider.notifier).build(),
        child: ListView(
          padding: const EdgeInsets.all(14),
          children: [
            // 顶部概览条
            Container(
              padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 4),
              decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(12)),
              child: Row(
                children: [
                  _statCell('${details.length}', '配送点'),
                  _divider(),
                  _statCell('$done', '已完成', color: TmsTheme.ok),
                  _divider(),
                  _statCell('${details.length - done}', '待配送', color: TmsTheme.accent2),
                  _divider(),
                  _statCell('${returns.length}', '退货', color: TmsTheme.returnPurple),
                ],
              ),
            ),
            const SizedBox(height: 8),
            Alert.info('📋 ${_todayStr()} · ${tasks.dispatches.isNotEmpty ? tasks.dispatches.first.routeLine : ""} · 网络正常'),
            const SizedBox(height: 8),
            // 退货回收任务（V1.2 重点）
            if (returns.isNotEmpty) ...[
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 4),
                child: Text('🔄 退货回收任务', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.returnPurple)),
              ),
              ...returns.map((d) => MCard(
                    leftBar: TmsTheme.returnPurple,
                    onTap: () => Navigator.push(context, MaterialPageRoute(
                      builder: (_) => ReturnListPage(initialApplyNo: d.sourceBillNo),
                    )),
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                        Expanded(child: Text(d.customerName, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
                        const MTag.purple('待回收'),
                      ]),
                      const SizedBox(height: 4),
                      Text('${d.sourceBillNo} · ${d.customerAddress}', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                      const SizedBox(height: 2),
                      Text('退货 ${d.qty} 件 · 调度单 ${d.dispatchNo}', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                    ]),
                  )),
            ],
            // 快捷操作（现场退货 / 返仓交接）
            const SizedBox(height: 4),
            Row(children: [
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
            // 交账结算（一天配送结束后操作）
            MCard(
              leftBar: TmsTheme.accent,
              onTap: () {
                final navigator = Navigator.of(context);
                navigator.push(MaterialPageRoute(
                  builder: (_) => const SettlementPage(),
                )).then((changed) => _maybeRefresh(ref, changed));
              },
              child: Row(children: [
                const Icon(Icons.account_balance_wallet, size: 20, color: TmsTheme.accent),
                const SizedBox(width: 10),
                const Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('交账结算', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
                  Text('汇总应收/实收 · 拍照签名 · 提交审核', style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
                ])),
                const Icon(Icons.chevron_right, size: 18, color: TmsTheme.muted),
              ]),
            ),
            // 发货配送任务
            if (receipts.isNotEmpty) ...[
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 4),
                child: Text('📦 今日配送任务', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              ),
              ...receipts.map((d) {
                final dispatch = tasks.dispatches.firstWhere(
                  (x) => x.dispatchId == d.dispatchId,
                  orElse: () => Dispatch(dispatchId: d.dispatchId, dispatchNo: d.dispatchNo, status: ''),
                );
                final dStatus = dispatch.status;
                final canLoad = dStatus == 'ASSIGNED' || dStatus == 'LOADED';
                final canSign = (dStatus == 'DEPARTED' || dStatus == 'DELIVERING') && d.status == 'PENDING';
                final canTap = canLoad || canSign;
                return MCard(
                  leftBar: d.status == 'DELIVERED' ? TmsTheme.ok : (canSign ? TmsTheme.accent2 : TmsTheme.accent),
                  onTap: canTap
                      ? () {
                          final navigator = Navigator.of(context);
                          if (canLoad) {
                            navigator.push(MaterialPageRoute(
                              builder: (_) => LoadingConfirmPage(dispatchId: d.dispatchId),
                            )).then((changed) => _maybeRefresh(ref, changed));
                          } else {
                            navigator.push(MaterialPageRoute(
                              builder: (_) => DeliverySignPage(detailId: d.detailId),
                            )).then((changed) => _maybeRefresh(ref, changed));
                          }
                        }
                      : null,
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                      Expanded(child: Text('${d.seqNo}. ${d.customerName}', style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
                      if (d.status == 'DELIVERED')
                        const MTag.green('已签收')
                      else if (d.status == 'REJECTED')
                        const MTag.red('已拒收')
                      else if (d.status == 'RESCHEDULED')
                        const MTag.orange('改派返仓')
                      else if (canSign)
                        const MTag.orange('待签收')
                      else if (canLoad)
                        const MTag.blue('待装车')
                      else
                        MTag.gray(d.status.isEmpty ? '待配送' : d.status),
                    ]),
                    const SizedBox(height: 4),
                    Text('${d.sourceBillNo} · ${d.customerAddress}', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                    const SizedBox(height: 2),
                    Text('${d.qty} 件 · ${d.billTypeText} · ${d.dispatchNo}', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                    if (canTap) ...[
                      const SizedBox(height: 4),
                      Text(canLoad ? '👉 点击进入装车确认' : '👉 点击进入签收',
                          style: const TextStyle(fontSize: 10, color: TmsTheme.accent, fontWeight: FontWeight.w600)),
                    ],
                    // 已到达客户处时可触发改派返仓 / 客户拒收（异常分支）
                    if (canSign) ...[
                      const SizedBox(height: 8),
                      Row(children: [
                        Expanded(child: _MiniAction(
                          label: '改派返仓',
                          icon: Icons.refresh,
                          color: TmsTheme.accent2,
                          onTap: () {
                            final navigator = Navigator.of(context);
                            navigator.push(MaterialPageRoute(
                              builder: (_) => RescheduleReturnPage(
                                dispatchId: d.dispatchId,
                                detailId: d.detailId,
                                receiptNo: d.sourceBillNo,
                                customerName: d.customerName,
                                customerAddress: d.customerAddress,
                                totalQty: d.qty,
                              ),
                            )).then((changed) => _maybeRefresh(ref, changed));
                          },
                        )),
                        const SizedBox(width: 8),
                        Expanded(child: _MiniAction(
                          label: '客户拒收',
                          icon: Icons.block,
                          color: TmsTheme.bad,
                          onTap: () {
                            final navigator = Navigator.of(context);
                            navigator.push(MaterialPageRoute(
                              builder: (_) => CustomerRejectPage(
                                dispatchId: d.dispatchId,
                                detailId: d.detailId,
                                receiptNo: d.sourceBillNo,
                                customerName: d.customerName,
                                customerAddress: d.customerAddress,
                                totalQty: d.qty,
                              ),
                            )).then((changed) => _maybeRefresh(ref, changed));
                          },
                        )),
                      ]),
                    ],
                  ]),
                );
              }),
            ],
            if (details.isEmpty)
              const Padding(padding: EdgeInsets.all(40), child: Center(child: Text('今日暂无任务', style: TextStyle(color: TmsTheme.muted)))),
          ],
        ),
      ),
          ),
        ],
      ),
    );
  }

  Widget _statCell(String num, String label, {Color? color}) =>
      Expanded(child: Column(children: [
        Text(num, style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800, color: color ?? TmsTheme.ink)),
        Text(label, style: const TextStyle(fontSize: 10, color: TmsTheme.muted)),
      ]));
  Widget _divider() => Container(width: 1, height: 28, color: TmsTheme.rule);

  String _todayStr() {
    final d = DateTime.now();
    const week = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    return '${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')} ${week[d.weekday - 1]}';
  }

  /// 子页面返回 true 时刷新今日任务。
  void _maybeRefresh(WidgetRef ref, dynamic changed) {
    if (changed == true) {
      ref.invalidate(todayTasksProvider);
    }
  }
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
  final VoidCallback onTap;
  const _MiniAction({required this.label, required this.icon, required this.color, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 10),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: color.withValues(alpha: 0.3), width: 1.2),
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
