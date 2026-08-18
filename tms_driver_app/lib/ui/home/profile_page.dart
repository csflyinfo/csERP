import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../config/theme.dart';
import '../../models/task.dart';
import '../../providers/auth_provider.dart';
import '../../providers/task_provider.dart';
import '../../services/launch_service.dart';
import '../../services/sync_service.dart';
import '../login/login_page.dart';
import 'collect_records_page.dart';
import 'history_page.dart';
import 'sync_center_page.dart';

/// 我的页（司机信息 + 退出登录）。
class ProfilePage extends ConsumerWidget {
  const ProfilePage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final driver = ref.watch(authProvider);
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(title: const Text('我的')),
      body: ListView(
        padding: const EdgeInsets.all(14),
        children: [
          Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(12)),
            child: Row(children: [
              const CircleAvatar(radius: 28, backgroundColor: TmsTheme.accentLight, child: Text('🧑‍✈️', style: TextStyle(fontSize: 28))),
              const SizedBox(width: 14),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(driver?.driverName ?? '司机', style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
                const SizedBox(height: 2),
                Text('工号：${driver?.driverCode.isEmpty == true ? "—" : driver?.driverCode}', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                Text('手机：${driver?.mobile.isEmpty == true ? "—" : driver?.mobile}', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
              ])),
            ]),
          ),
          const SizedBox(height: 12),
          const _StatsCard(),
          const SizedBox(height: 12),
          Container(
            decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(12)),
            child: Column(children: [
              _row(Icons.history, '配送历史', () {
                Navigator.push(context, MaterialPageRoute(builder: (_) => const HistoryPage()));
              }),
              const Divider(height: 1, indent: 56),
              // 收款记录独立入口：上面统计卡的「累计收款」只是个总数，
              // 跟调度对不上账时司机需要逐笔流水才能定位差额在哪家门店。
              _row(Icons.receipt_long, '收款记录', () {
                Navigator.push(context, MaterialPageRoute(builder: (_) => const CollectRecordsPage()));
              }),
              const Divider(height: 1, indent: 56),
              // 同步中心入口：离线队列里躺的是「司机已经干完、公司还没收到」的活。
              // 顶部横幅只在有异常时才出现，收工核对时司机需要一个稳定入口
              // 主动确认「我今天的单子都传上去了没有」。
              const _SyncCenterRow(),
              const Divider(height: 1, indent: 56),
              _row(Icons.support_agent, '联系调度员', () => _callDispatcher(context, ref)),
              const Divider(height: 1, indent: 56),
              _row(Icons.description, '版本说明', () => _showVersion(context)),
            ]),
          ),
          const SizedBox(height: 24),
          ElevatedButton.icon(
            onPressed: () async {
              await ref.read(authProvider.notifier).logout();
              if (context.mounted) {
                Navigator.pushAndRemoveUntil(context, MaterialPageRoute(builder: (_) => const LoginPage()), (_) => false);
              }
            },
            icon: const Icon(Icons.logout, size: 18),
            label: const Text('退出登录'),
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.white,
              foregroundColor: TmsTheme.bad,
              elevation: 0,
              padding: const EdgeInsets.symmetric(vertical: 13),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
          ),
        ],
      ),
    );
  }

  Widget _row(IconData icon, String label, VoidCallback onTap) => ListTile(
        leading: Icon(icon, color: TmsTheme.accent, size: 22),
        title: Text(label, style: const TextStyle(fontSize: 14, color: TmsTheme.ink)),
        trailing: const Icon(Icons.chevron_right, color: TmsTheme.muted, size: 20),
        onTap: onTap,
      );

  void _showVersion(BuildContext context) {
    showDialog(context: context, builder: (_) => AlertDialog(
      title: const Text('版本说明'),
      content: const Text('TMS 司机配送 V1.2.0\n退货调度闭环 · 退货回收签收'),
      actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('知道了'))],
    ));
  }

  /// 拨打调度中心电话（号码来自 TMS_DISPATCHER_PHONE 参数）。
  ///
  /// 号码未配置时明确提示管理员去配参数，而不是静默无反应——
  /// 后者会让司机以为 APP 坏了。
  Future<void> _callDispatcher(BuildContext context, WidgetRef ref) async {
    final messenger = ScaffoldMessenger.of(context);
    final contact = await ref.read(dispatcherContactProvider.future);
    if (contact.phone.trim().isEmpty) {
      messenger.showSnackBar(const SnackBar(
        content: Text('尚未配置调度中心电话，请联系管理员在「参数配置」中设置 TMS_DISPATCHER_PHONE'),
      ));
      return;
    }
    final ok = await LaunchService.instance.dial(contact.phone);
    if (!ok) {
      messenger.showSnackBar(SnackBar(content: Text('拨号失败，${contact.name}：${contact.phone}')));
    }
  }
}

/// 同步中心入口行（带待同步/失败角标）。
///
/// 单独抽成组件而不是复用 _row：这一行需要 watch 队列计数，
/// 若写在 ProfilePage 里会让整页跟着队列变化重建（含绩效卡）。
///
/// 角标区分两种状态：失败优先用红色，因为它意味着「不介入就永久丢单」；
/// 只有待同步时用橙色，那只是「等联网」，不该用同一种警示强度去惊动司机。
class _SyncCenterRow extends ConsumerWidget {
  const _SyncCenterRow();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pending = ref.watch(pendingCountProvider).valueOrNull ?? 0;
    final failed = ref.watch(failedCountProvider).valueOrNull ?? 0;
    final total = pending + failed;
    return ListTile(
      leading: const Icon(Icons.sync, color: TmsTheme.accent, size: 22),
      title: const Text('同步中心', style: TextStyle(fontSize: 14, color: TmsTheme.ink)),
      subtitle: Text(
        total == 0 ? '全部已同步' : '待同步 $pending 条${failed > 0 ? "，失败 $failed 条" : ""}',
        style: TextStyle(fontSize: 11, color: failed > 0 ? TmsTheme.bad : TmsTheme.muted),
      ),
      trailing: Row(mainAxisSize: MainAxisSize.min, children: [
        if (total > 0)
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
            decoration: BoxDecoration(
              color: failed > 0 ? TmsTheme.bad : TmsTheme.accent2,
              borderRadius: BorderRadius.circular(9),
            ),
            child: Text('$total',
                style: const TextStyle(
                    fontSize: 11, fontWeight: FontWeight.w700, color: Colors.white)),
          ),
        const SizedBox(width: 6),
        const Icon(Icons.chevron_right, color: TmsTheme.muted, size: 20),
      ]),
      onTap: () => Navigator.push(
        context,
        MaterialPageRoute(builder: (_) => const SyncCenterPage()),
      ),
    );
  }
}

/// 绩效统计周期选项。
///
/// days=0 走后端「不加日期条件」分支，其余按 dispatch_date/trip_date >= 今天-N 过滤。
class _StatsPeriod {
  final int days;
  final String label;
  const _StatsPeriod(this.days, this.label);
}

const List<_StatsPeriod> _statsPeriods = [
  _StatsPeriod(7, '近7天'),
  _StatsPeriod(30, '近30天'),
  _StatsPeriod(90, '近90天'),
  _StatsPeriod(0, '累计'),
];

/// 绩效统计卡片。
///
/// 数据全部由后端按真实单据聚合，没有兜底假数据；
/// 原型里的「客户评分」因系统内无评价表暂不展示，避免出现编造数字。
///
/// 默认落在近 30 天而非累计：累计口径随工龄单调增长，老司机看到的数字只会
/// 越来越大，反而失去「这段时间我干得怎么样」的反馈价值；且与历史任务查询
/// 的默认窗口保持一致。累计仍是一次点击可达。
class _StatsCard extends ConsumerStatefulWidget {
  const _StatsCard();
  @override
  ConsumerState<_StatsCard> createState() => _StatsCardState();
}

class _StatsCardState extends ConsumerState<_StatsCard> {
  int _days = 30;

  @override
  Widget build(BuildContext context) {
    final stats = ref.watch(driverStatsProvider(_days));
    final periodLabel = _statsPeriods.firstWhere((p) => p.days == _days).label;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(12)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text('我的绩效（$periodLabel）',
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
          InkWell(
            // 只失效当前周期：driverStatsProvider 是 family，四个周期各有独立缓存，
            // 全量失效会让其余三个周期在下次点开时白等一次请求
            onTap: () => ref.invalidate(driverStatsProvider(_days)),
            child: const Padding(
              padding: EdgeInsets.all(4),
              child: Icon(Icons.refresh, size: 18, color: TmsTheme.muted),
            ),
          ),
        ]),
        const SizedBox(height: 10),
        Row(
          children: [
            for (final p in _statsPeriods) ...[
              _PeriodChip(
                label: p.label,
                selected: p.days == _days,
                onTap: _days == p.days ? null : () => setState(() => _days = p.days),
              ),
              if (p != _statsPeriods.last) const SizedBox(width: 6),
            ],
          ],
        ),
        const SizedBox(height: 12),
        stats.when(
          // 固定高度：切周期时命中的是另一个 provider 实例（必然无缓存值），
          // 若让 spinner 自然占位，卡片会先塌陷再撑开，观感像闪屏
          loading: () => const SizedBox(
            height: 96,
            child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
          ),
          error: (e, _) => Padding(
            padding: const EdgeInsets.symmetric(vertical: 10),
            child: Text('统计加载失败：${e.toString().replaceFirst("Exception: ", "")}',
                style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
          ),
          data: (s) => Column(children: [
            Row(children: [
              _cell('出车次数', '${s.tripCount}'),
              _cell('配送门店', '${s.signedStore}'),
              _cell('签收件数', s.signedQty.toStringAsFixed(0)),
            ]),
            const SizedBox(height: 14),
            Row(children: [
              _cell('签收完成率', DriverStats.pct(s.signRate), color: TmsTheme.ok),
              _cell('打卡正常率', DriverStats.pct(s.gpsNormalRate), color: TmsTheme.accent),
              _cell('收款金额', '¥${s.collectAmount.toStringAsFixed(0)}', color: TmsTheme.accent2),
            ]),
            if (s.rejectStore > 0) ...[
              const SizedBox(height: 10),
              Align(
                alignment: Alignment.centerLeft,
                child: Text('其中拒收 ${s.rejectStore} 家',
                    style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
              ),
            ],
            if (_days > 0) ...[
              const SizedBox(height: 8),
              const Align(
                alignment: Alignment.centerLeft,
                // 明确口径：跨天签收按调度日期归属，否则司机会拿手工记账去对不上
                child: Text('统计窗口按调度日期计算', style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
              ),
            ],
          ]),
        ),
      ]),
    );
  }

  Widget _cell(String label, String value, {Color color = TmsTheme.ink}) => Expanded(
        child: Column(children: [
          Text(value, style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: color)),
          const SizedBox(height: 3),
          Text(label, style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        ]),
      );
}

/// 周期切换标签。onTap 传 null 表示已选中（同时也就屏蔽了重复点击）。
class _PeriodChip extends StatelessWidget {
  final String label;
  final bool selected;
  final VoidCallback? onTap;
  const _PeriodChip({required this.label, required this.selected, this.onTap});

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
        decoration: BoxDecoration(
          color: selected ? TmsTheme.accent : TmsTheme.bg,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: selected ? TmsTheme.accent : TmsTheme.rule),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: 12,
            fontWeight: selected ? FontWeight.w700 : FontWeight.w400,
            color: selected ? Colors.white : TmsTheme.muted,
          ),
        ),
      ),
    );
  }
}
