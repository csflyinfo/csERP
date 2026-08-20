import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../config/theme.dart';
import '../../models/task.dart';
import '../../providers/task_provider.dart';
import '../../services/launch_service.dart';
import '../../widgets/common.dart';
import 'arrive_page.dart';
import 'store_detail_page.dart';

/// 配送中门店列表（底部 Tab 第二位）。
///
/// 按 seq_no 顺序展示当前所有未完成调度单下的配送点，一行一店。
/// 与首页的区别：首页只给汇总数字，这里给可执行的跑店清单
/// （导航 / 呼叫 / 到达），是司机在路上停留时间最长的一屏。
class DeliveringPage extends ConsumerWidget {
  const DeliveringPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(deliveringStoresProvider);
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(
        title: const Text('配送中'),
        actions: [
          IconButton(
            tooltip: '刷新',
            onPressed: () => ref.read(deliveringStoresProvider.notifier).refresh(),
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () => ref.read(deliveringStoresProvider.notifier).refresh(),
        child: async.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => ListView(
            padding: const EdgeInsets.all(12),
            children: const [Alert.warn('配送点加载失败，可下拉重试')],
          ),
          data: (d) => d.stores.isEmpty
              ? ListView(
                  padding: const EdgeInsets.all(12),
                  children: const [
                    Alert.info('暂无配送中的配送点。接单并发车后，配送点会按顺序出现在这里。'),
                  ],
                )
              : ListView.builder(
                  padding: const EdgeInsets.fromLTRB(12, 12, 12, 24),
                  itemCount: d.stores.length + 1,
                  itemBuilder: (c, i) {
                    if (i == 0) return _summary(d);
                    return _StoreCard(store: d.stores[i - 1]);
                  },
                ),
        ),
      ),
    );
  }

  Widget _summary(DeliveringStores d) {
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.symmetric(vertical: 12),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: TmsTheme.rule),
      ),
      child: Row(
        children: [
          _cell('待送点', '${d.pendingStore}', TmsTheme.accent2),
          _vline(),
          _cell('待送单', '${d.billCount}', TmsTheme.ink),
          _vline(),
          // 已完成门店不再出现在列表里（需求：已完成的不显示在配送中），
          // 所以这里用「已完成/总数」的比值代替原来的独立计数格，
          // 让司机仍能看出今天的整体进度，而不是只看到剩余量。
          _cell('已完成', '${d.doneStore}/${d.storeCount}', TmsTheme.ok),
          _vline(),
          _cell('代收', '¥${d.totalAmount.toStringAsFixed(0)}', TmsTheme.accent2),
        ],
      ),
    );
  }

  Widget _cell(String label, String value, Color color) => Expanded(
        child: Column(
          children: [
            Text(value,
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: color)),
            const SizedBox(height: 2),
            Text(label, style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
          ],
        ),
      );

  Widget _vline() => Container(width: 1, height: 26, color: TmsTheme.rule);
}

/// 单个配送点卡片：名称/地址/联系人/金额/单数 + 导航、呼叫、到达。
class _StoreCard extends ConsumerWidget {
  final DeliveringStore store;
  const _StoreCard({required this.store});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = store;
    return MCard(
      leftBar: s.done ? TmsTheme.ok : TmsTheme.accent,
      // 整卡可点进详情：司机在店里要看的是「这家店有哪几张单」，
      // 卡片本身只给汇总，单据级动作都在详情页
      onTap: () => _openDetail(context, ref),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 序号用后端重编号后的 orderNo：跨调度单按门店合并后，各单内部的
              // seq_no 会重号（都从 10 起排），直接展示会出现两个「1」并排。
              Container(
                width: 22,
                height: 22,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: s.done ? TmsTheme.ok : TmsTheme.accent,
                  borderRadius: BorderRadius.circular(11),
                ),
                child: Text('${s.orderNo > 0 ? s.orderNo : s.seqNo}',
                    style: const TextStyle(
                        fontSize: 11, color: Colors.white, fontWeight: FontWeight.w700)),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  s.customerName.isEmpty ? '(未命名门店)' : s.customerName,
                  style: const TextStyle(
                      fontSize: 15, fontWeight: FontWeight.w700, color: TmsTheme.ink),
                ),
              ),
              // 退货角标：有退货单的门店要带空箱/回收物出车，漏看会白跑一趟
              if (s.hasReturn) ...[
                const SizedBox(width: 4),
                const MTag.purple('退'),
              ],
              if (s.needCollect) ...[
                const SizedBox(width: 4),
                const MTag.orange('收款'),
              ],
              if (s.done) ...[
                const SizedBox(width: 4),
                const MTag.green('已完成'),
              ] else if (s.hasArrived) ...[
                const SizedBox(width: 4),
                const MTag.blue('已到店'),
              ],
            ],
          ),
          const SizedBox(height: 6),
          if (s.customerAddress.isNotEmpty)
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('📍 ', style: TextStyle(fontSize: 12)),
                Expanded(
                  child: Text(s.customerAddress,
                      style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                ),
              ],
            ),
          const SizedBox(height: 4),
          Text(
            [
              if (s.contactName.isNotEmpty) '👤 ${s.contactName}',
              if (s.hasPhone) s.contactMobile,
              if (s.settlementText.isNotEmpty) s.settlementText,
            ].join('  ·  '),
            style: const TextStyle(fontSize: 12, color: TmsTheme.muted),
          ),
          const SizedBox(height: 6),
          Row(
            children: [
              Text('¥${s.totalAmount.toStringAsFixed(2)}',
                  style: const TextStyle(
                      fontSize: 15, fontWeight: FontWeight.w700, color: TmsTheme.accent2)),
              const SizedBox(width: 10),
              Text('${s.billCount} 单 · ${s.qtyText} 件',
                  style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
              if (!s.done && s.pendingCount != s.billCount) ...[
                const SizedBox(width: 6),
                Text('待处理 ${s.pendingCount}',
                    style: const TextStyle(fontSize: 12, color: TmsTheme.warning)),
              ],
              const Spacer(),
              const Text('查看单据 ›',
                  style: TextStyle(fontSize: 12, color: TmsTheme.accent)),
            ],
          ),
          const Divider(height: 16),
          Row(
            children: [
              Expanded(child: _btn('🧭 导航', TmsTheme.accent, () => _navigate(context))),
              const SizedBox(width: 8),
              Expanded(
                child: _btn('📞 呼叫', TmsTheme.ok,
                    s.hasPhone ? () => _call(context) : null),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _btn(
                  s.done ? '✅ 已完成' : '📍 到达',
                  TmsTheme.accent2,
                  // 已完成门店不再允许到达打卡：单据都处理完了还打卡，
                  // 只会产生一条无对应业务动作的时间记录。
                  s.done ? null : () => _arrive(context, ref),
                  filled: true,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _btn(String label, Color color, VoidCallback? onTap, {bool filled = false}) {
    final enabled = onTap != null;
    final c = enabled ? color : TmsTheme.muted;
    return SizedBox(
      height: 34,
      child: filled
          ? ElevatedButton(
              onPressed: onTap,
              style: ElevatedButton.styleFrom(
                backgroundColor: c,
                foregroundColor: Colors.white,
                padding: EdgeInsets.zero,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
              ),
              child: Text(label, style: const TextStyle(fontSize: 12)),
            )
          : OutlinedButton(
              onPressed: onTap,
              style: OutlinedButton.styleFrom(
                foregroundColor: c,
                side: BorderSide(color: c),
                padding: EdgeInsets.zero,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
              ),
              child: Text(label, style: const TextStyle(fontSize: 12)),
            ),
    );
  }

  Future<void> _navigate(BuildContext context) async {
    final s = store;
    if (!s.hasGeo && s.customerAddress.isEmpty) {
      _toast(context, '该门店未维护坐标和地址，无法导航');
      return;
    }
    final ok = await LaunchService.instance.navigate(
      longitude: s.longitude,
      latitude: s.latitude,
      address: s.customerAddress,
      name: s.customerName,
    );
    if (!context.mounted) return;
    if (!ok) {
      _toast(context, '未找到可用地图应用');
    } else if (!s.hasGeo) {
      // 只有地址没坐标时是按文字搜索，可能落在同名地点上，必须让司机核对
      _toast(context, '已按地址搜索，请核对位置');
    }
  }

  Future<void> _call(BuildContext context) async {
    final ok = await LaunchService.instance.dial(store.contactMobile);
    if (!context.mounted || ok) return;
    _toast(context, '拨号失败：${store.contactMobile}');
  }

  Future<void> _arrive(BuildContext context, WidgetRef ref) async {
    final s = store;
    if (s.arriveDetailId.isEmpty) {
      _toast(context, '该门店暂无待处理单据');
      return;
    }
    final done = await Navigator.push<bool>(
      context,
      MaterialPageRoute(
        builder: (_) => ArrivePage(
          dispatchId: s.dispatchId,
          detailId: s.arriveDetailId,
          customerName: s.customerName,
          customerAddress: s.customerAddress,
          storeLongitude: s.longitude,
          storeLatitude: s.latitude,
        ),
      ),
    );
    if (done != true) return;
    // 到达会改变门店状态与首页「下一站」，两处一起刷新，避免数字打架
    await ref.read(deliveringStoresProvider.notifier).refresh();
    ref.invalidate(todayTasksProvider);
    ref.read(homeOverviewProvider.notifier).refresh();
    // 打卡成功后直接进详情页：司机打卡的目的就是接着处理这家店的单据，
    // 让他退回列表再点一次卡片是多余的一步
    if (!context.mounted) return;
    await _openDetail(context, ref);
  }

  Future<void> _openDetail(BuildContext context, WidgetRef ref) async {
    final s = store;
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => StoreDetailPage(
          // 不传 dispatchId：本行门店可能同时挂着原单和追加单的货，
          // 传了就只能看到其中一张单的单据，司机会漏送。
          customerCode: s.customerCode,
          customerName: s.customerName,
        ),
      ),
    );
    // 详情页里的签收/改派都会改变本店单数，返回时无条件刷新列表：
    // 详情页内部动作多，靠返回值判断是否变更容易漏
    await ref.read(deliveringStoresProvider.notifier).refresh();
  }

  void _toast(BuildContext context, String msg) {
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }
}
