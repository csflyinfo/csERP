import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../config/theme.dart';
import '../../models/task.dart';
import '../../providers/task_provider.dart';
import '../../services/launch_service.dart';
import '../../services/local_db_service.dart';
import '../../widgets/common.dart';
import '../return/driver_return_create_page.dart';
import '../return/reschedule_return_page.dart';
import '../return/return_sign_page.dart';
import '../store/store_location_page.dart';
import 'arrive_page.dart';
import 'delivery_sign_page.dart';
import 'store_settle_page.dart';

/// 配送点详情：某门店在某张调度单下的全部单据。
///
/// 从「配送中」列表点门店卡片进入，是司机在店内停留时的主操作屏。
/// 与签收页的分工：本页只负责「这家店有哪些单、该走哪个动作」，
/// 具体的数量/签名/收款录入仍在各签收页完成，避免本页变成巨型表单。
///
/// 顶部动作区放门店级动作（改派返仓 / 定位修改 / 现场退货），
/// 下方单据列表放单据级动作（点行进签收）。这样区分是因为
/// 「这家店今天送不了」和「这张单怎么签」是两类决策，混在一起司机容易误触。
class StoreDetailPage extends ConsumerWidget {
  /// 限定只看某张调度单下的单据；配送中入口不传（跨单合并，见 StoreBillsArgs）。
  final String? dispatchId;
  final String customerCode;

  /// 列表页已知的门店名，仅用于加载期间的标题占位，避免首帧标题空白。
  final String customerName;

  const StoreDetailPage({
    super.key,
    this.dispatchId,
    required this.customerCode,
    this.customerName = '',
  });

  StoreBillsArgs get _args =>
      StoreBillsArgs(dispatchId: dispatchId, customerCode: customerCode);

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(storeBillsProvider(_args));
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(
        title: Text(async.valueOrNull?.customerName.isNotEmpty == true
            ? async.value!.customerName
            : (customerName.isEmpty ? '配送点详情' : customerName)),
        actions: [
          IconButton(
            tooltip: '刷新',
            onPressed: () => ref.read(storeBillsProvider(_args).notifier).refresh(),
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () => ref.read(storeBillsProvider(_args).notifier).refresh(),
        child: async.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => ListView(
            padding: const EdgeInsets.all(12),
            children: const [Alert.warn('单据加载失败，可下拉重试')],
          ),
          data: (d) => d.bills.isEmpty
              ? ListView(
                  padding: const EdgeInsets.all(12),
                  children: const [Alert.info('该配送点暂无单据。')],
                )
              : ListView(
                  padding: const EdgeInsets.fromLTRB(12, 12, 12, 24),
                  children: [
                    _storeCard(context, d),
                    const SizedBox(height: 8),
                    // 打卡提示压成一行（提示条 + 小按钮）：原来是「整条警示 + 整宽橙色大按钮」，
                    // 加上下方的门店操作区要吃掉大半屏，司机得滚动才看得到单据。
                    if (!d.hasArrived) ...[
                      _arriveHint(context, ref, d),
                      const SizedBox(height: 8),
                    ],
                    // 单据列表前置：司机进店后 90% 的操作是「点单去签收」，
                    // 低频的门店级动作（改派/定位/退货）折叠到列表下方。
                    Padding(
                      padding: const EdgeInsets.only(left: 4, bottom: 6),
                      child: Text('单据列表（${d.bills.length}）',
                          style: const TextStyle(
                              fontSize: 13,
                              fontWeight: FontWeight.w700,
                              color: TmsTheme.ink)),
                    ),
                    ...d.bills.map((b) => Padding(
                          padding: const EdgeInsets.only(bottom: 8),
                          child: _billCard(context, ref, b),
                        )),
                    const SizedBox(height: 2),
                    _actionCard(context, ref, d),
                  ],
                ),
        ),
      ),
      // 结算入口做成底部常驻栏而不是列表里的一张卡：
      // 签完最后一单回到本页时，结算是唯一的下一步动作，不该还要滚动去找。
      bottomNavigationBar: _settleBar(context, ref),
    );
  }

  /// 底部结算栏。仅当本店存在「已签收待结算」的本地草稿时出现。
  ///
  /// 待结算张数来自本地库而非接口：签收阶段刻意不回传后台（见 delivery_sign_page），
  /// 所以只有本地 sign_drafts 知道司机到底签了几张。
  Widget? _settleBar(BuildContext context, WidgetRef ref) {
    return FutureBuilder<List<Map<String, dynamic>>>(
      future: LocalDbService.instance.getSignDrafts(customerCode),
      builder: (ctx, snap) {
        final n = snap.data?.length ?? 0;
        if (n == 0) return const SizedBox.shrink();
        return Container(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
          decoration: const BoxDecoration(
            color: Colors.white,
            border: Border(top: BorderSide(color: TmsTheme.rule)),
          ),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text('$n 张单据已签收待结算',
                        style: const TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w700,
                            color: TmsTheme.ink)),
                    const Text('结算后才会回传后台',
                        style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
                  ],
                ),
              ),
              SizedBox(
                width: 140,
                child: TmsButton.warn('💰 去结算',
                    onPressed: () => _openSettle(context, ref)),
              ),
            ],
          ),
        );
      },
    );
  }

  Future<void> _openSettle(BuildContext context, WidgetRef ref) async {
    final ok = await Navigator.push<bool>(
      context,
      MaterialPageRoute(
        builder: (_) => StoreSettlePage(
          dispatchId: dispatchId,
          customerCode: customerCode,
          customerName: customerName,
        ),
      ),
    );
    if (ok == true && context.mounted) {
      // 结算成功后草稿已删、后台单据状态已变，两边都要刷新，
      // 否则底部结算栏和单据状态会停在结算前的旧值。
      ref.read(storeBillsProvider(_args).notifier).refresh();
    }
  }

  // ==========================================================================
  // 门店信息
  // ==========================================================================

  Widget _storeCard(BuildContext context, StoreBills d) {
    return MCard(
      leftBar: d.pendingCount == 0 ? TmsTheme.ok : TmsTheme.accent,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Text(
                  d.customerName.isEmpty ? '(未命名门店)' : d.customerName,
                  style: const TextStyle(
                      fontSize: 16, fontWeight: FontWeight.w700, color: TmsTheme.ink),
                ),
              ),
              if (d.returnCount > 0) ...[
                const SizedBox(width: 4),
                const MTag.purple('退'),
              ],
              if (d.pendingCount == 0) ...[
                const SizedBox(width: 4),
                const MTag.green('已完成'),
              ] else if (d.hasArrived) ...[
                const SizedBox(width: 4),
                const MTag.blue('已到店'),
              ],
            ],
          ),
          const SizedBox(height: 6),
          if (d.customerAddress.isNotEmpty)
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('📍 ', style: TextStyle(fontSize: 12)),
                Expanded(
                  child: Text(d.customerAddress,
                      style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                ),
              ],
            ),
          const SizedBox(height: 4),
          Text(
            [
              if (d.contactName.isNotEmpty) '👤 ${d.contactName}',
              if (d.hasPhone) d.contactMobile,
              if (d.dispatchNo.isNotEmpty) d.dispatchNo,
            ].join('  ·  '),
            style: const TextStyle(fontSize: 12, color: TmsTheme.muted),
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              _cell('单数', '${d.billCount}', TmsTheme.ink),
              _vline(),
              _cell('待处理', '${d.pendingCount}',
                  d.pendingCount == 0 ? TmsTheme.ok : TmsTheme.accent2),
              _vline(),
              _cell('件数', d.qtyText, TmsTheme.ink),
              _vline(),
              _cell('金额', '¥${d.totalAmount.toStringAsFixed(2)}', TmsTheme.accent2),
            ],
          ),
          const Divider(height: 16),
          Row(
            children: [
              Expanded(
                  child: _btn('🧭 导航', TmsTheme.accent, () => _navigate(context, d))),
              const SizedBox(width: 8),
              Expanded(
                child: _btn('📞 呼叫', TmsTheme.ok,
                    d.hasPhone ? () => _call(context, d) : null),
              ),
            ],
          ),
        ],
      ),
    );
  }

  // ==========================================================================
  // 到店打卡提示（单行紧凑版）
  // ==========================================================================

  /// 打卡提示条：提示文字与入口按钮同一行。
  ///
  /// 打卡本身不强制（配置项控制），所以这里只做「软提醒 + 快捷入口」，
  /// 不值得占用一整块屏幕高度去挤压真正要看的单据列表。
  Widget _arriveHint(BuildContext context, WidgetRef ref, StoreBills d) {
    return Container(
      padding: const EdgeInsets.fromLTRB(10, 6, 6, 6),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF7E6),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: const Color(0xFFFFE0A3)),
      ),
      child: Row(
        children: [
          const Expanded(
            child: Text('尚未到店打卡，建议先打卡',
                style: TextStyle(fontSize: 12, color: Color(0xFF8A5B00))),
          ),
          SizedBox(
            height: 30,
            child: TextButton(
              onPressed: () => _arrive(context, ref, d),
              style: TextButton.styleFrom(
                foregroundColor: TmsTheme.warning,
                padding: const EdgeInsets.symmetric(horizontal: 10),
                minimumSize: Size.zero,
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              child: const Text('📍 去打卡',
                  style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700)),
            ),
          ),
        ],
      ),
    );
  }

  // ==========================================================================
  // 门店级动作
  // ==========================================================================

  /// 门店级动作区（默认折叠）。
  ///
  /// 改派返仓 / 定位修改 / 现场退货都是低频异常处理，之前三个整宽按钮
  /// 常驻首屏，把「单据列表」挤到了屏幕外。这里改成 ExpansionTile 折叠，
  /// 收起状态只占一行标题高度，展开后动作与原来完全一致。
  Widget _actionCard(BuildContext context, WidgetRef ref, StoreBills d) {
    final reschedulable = d.reschedulable;
    return MCard(
      padding: EdgeInsets.zero,
      child: Theme(
        // ExpansionTile 默认会画上下分隔线，与 MCard 的圆角边框叠在一起显脏
        data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
        child: ExpansionTile(
          tilePadding: const EdgeInsets.symmetric(horizontal: 12),
          childrenPadding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
          title: const Text('门店操作',
              style: TextStyle(
                  fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
          subtitle: const Text('改派返仓 · 定位修改 · 现场退货',
              style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
          children: [
            // 改派返仓置顶：这是「整店送不了」的出口，司机进店发现关门时第一时间要找到它。
            // 全部待签收发货单一次性改派，避免同店多单逐张重复填原因和拍照。
            SizedBox(
              width: double.infinity,
              child: TmsButton.purple(
                reschedulable.length > 1
                    ? '🔄 改派返仓（全部 ${reschedulable.length} 张）'
                    : '🔄 改派返仓',
                onPressed: reschedulable.isEmpty
                    ? null
                    : () => _reschedule(context, ref, d, reschedulable),
              ),
            ),
            if (reschedulable.isEmpty) ...[
              const SizedBox(height: 4),
              const Align(
                alignment: Alignment.centerLeft,
                child: Text('无待签收发货单，无需改派',
                    style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
              ),
            ],
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: TmsButton.outline('📌 定位修改',
                      onPressed: () => _fixLocation(context, ref, d)),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TmsButton.outline('📦 现场退货',
                      color: TmsTheme.accent2,
                      onPressed: () => _createReturn(context, ref, d)),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  // ==========================================================================
  // 单据行
  // ==========================================================================

  Widget _billCard(BuildContext context, WidgetRef ref, DispatchDetail b) {
    final pending = b.status == 'PENDING';
    return MCard(
      leftBar: b.isReturn ? TmsTheme.returnPurple : (pending ? TmsTheme.accent : TmsTheme.ok),
      // 已处理的单不再允许进签收页：重复签收会再写一条签收流水，
      // 造成交账金额和库存双重计数。
      onTap: pending ? () => _openSign(context, ref, b) : null,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              b.isReturn ? const MTag.purple('取退') : const MTag.blue('发货'),
              const SizedBox(width: 6),
              Expanded(
                child: Text(b.sourceBillNo,
                    style: const TextStyle(
                        fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              ),
              _statusTag(b.status),
            ],
          ),
          const SizedBox(height: 6),
          Row(
            children: [
              Text('${b.qtyText} 件',
                  style: const TextStyle(fontSize: 13, color: TmsTheme.ink)),
              if (b.skuCount > 0) ...[
                const SizedBox(width: 8),
                Text('${b.skuCount} 个品种',
                    style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
              ],
              const Spacer(),
              // 取退单恒为 0，不显示金额，避免司机误以为要向客户收钱
              if (!b.isReturn && b.receivableAmount > 0)
                Text('¥${b.receivableAmount.toStringAsFixed(2)}',
                    style: const TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w700,
                        color: TmsTheme.accent2)),
            ],
          ),
          if (b.settlementText.isNotEmpty || b.needCollect) ...[
            const SizedBox(height: 4),
            Row(
              children: [
                if (b.settlementText.isNotEmpty)
                  Text(b.settlementText,
                      style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                if (b.needCollect) ...[
                  const SizedBox(width: 6),
                  const MTag.orange('需收款'),
                ],
              ],
            ),
          ],
          if (pending) ...[
            const SizedBox(height: 6),
            Text(b.isReturn ? '点击进入退货签收 ›' : '点击进入配送签收 ›',
                style: const TextStyle(fontSize: 12, color: TmsTheme.accent)),
          ],
        ],
      ),
    );
  }

  Widget _statusTag(String status) {
    switch (status) {
      case 'DELIVERED':
        return const MTag.green('已签收');
      case 'PARTIAL':
        return const MTag.orange('部分签收');
      case 'REJECTED':
        return const MTag.red('已拒收');
      default:
        return const MTag.gray('待签收');
    }
  }

  // ==========================================================================
  // 动作实现
  // ==========================================================================

  Future<void> _navigate(BuildContext context, StoreBills d) async {
    if (!d.hasGeo && d.customerAddress.isEmpty) {
      _toast(context, '该门店未维护坐标和地址，无法导航');
      return;
    }
    final ok = await LaunchService.instance.navigate(
      longitude: d.longitude,
      latitude: d.latitude,
      address: d.customerAddress,
      name: d.customerName,
    );
    if (!context.mounted) return;
    if (!ok) {
      _toast(context, '未找到可用地图应用');
    } else if (!d.hasGeo) {
      _toast(context, '已按地址搜索，请核对位置');
    }
  }

  Future<void> _call(BuildContext context, StoreBills d) async {
    final ok = await LaunchService.instance.dial(d.contactMobile);
    if (!context.mounted || ok) return;
    _toast(context, '拨号失败：${d.contactMobile}');
  }

  Future<void> _arrive(BuildContext context, WidgetRef ref, StoreBills d) async {
    if (d.arriveDetailId.isEmpty) {
      _toast(context, '该门店暂无待处理单据');
      return;
    }
    final done = await Navigator.push<bool>(
      context,
      MaterialPageRoute(
        builder: (_) => ArrivePage(
          dispatchId: d.dispatchIdOf(d.arriveDetailId),
          detailId: d.arriveDetailId,
          customerName: d.customerName,
          customerAddress: d.customerAddress,
          storeLongitude: d.longitude,
          storeLatitude: d.latitude,
        ),
      ),
    );
    if (done != true) return;
    await _refreshAll(ref);
  }

  /// 改派返仓：本店全部待签收发货单共用一组原因/照片，一次性提交。
  Future<void> _reschedule(BuildContext context, WidgetRef ref, StoreBills d,
      List<DispatchDetail> targets) async {
    final first = targets.first;
    final done = await Navigator.push<bool>(
      context,
      MaterialPageRoute(
        builder: (_) => RescheduleReturnPage(
          dispatchId: d.dispatchIdOf(first.detailId),
          detailId: first.detailId,
          receiptNo: first.sourceBillNo,
          customerName: d.customerName,
          customerAddress: d.customerAddress,
          totalQty: first.qty,
          siblings: targets
              .skip(1)
              .map((t) => RescheduleSibling(
                  detailId: t.detailId, receiptNo: t.sourceBillNo, totalQty: t.qty))
              .toList(),
        ),
      ),
    );
    if (done != true) return;
    await _refreshAll(ref);
  }

  /// 定位修改：传门店现有坐标，让纠偏页能展示「原定位 vs 新定位」对比。
  Future<void> _fixLocation(BuildContext context, WidgetRef ref, StoreBills d) async {
    final done = await Navigator.push<bool>(
      context,
      MaterialPageRoute(
        builder: (_) => StoreLocationPage(
          customerCode: d.customerCode,
          customerName: d.customerName,
          dispatchId: d.primaryDispatchId,
          oldLat: d.latitude,
          oldLng: d.longitude,
        ),
      ),
    );
    if (done != true) return;
    // 定位改的是客户档案，会影响列表页的导航坐标，一并刷新
    await _refreshAll(ref);
  }

  Future<void> _createReturn(BuildContext context, WidgetRef ref, StoreBills d) async {
    final done = await Navigator.push<bool>(
      context,
      MaterialPageRoute(
        builder: (_) => DriverReturnCreatePage(
          customerCode: d.customerCode,
          customerName: d.customerName,
          dispatchId: d.primaryDispatchId,
        ),
      ),
    );
    if (done != true) return;
    await _refreshAll(ref);
  }

  /// 进签收页：发货单走配送签收（拒收由签收页按拒收数量自动生成拒收单），
  /// 取退任务走退货签收（以退货单号 applyNo 为主键）。
  Future<void> _openSign(BuildContext context, WidgetRef ref, DispatchDetail b) async {
    final done = await Navigator.push<bool>(
      context,
      MaterialPageRoute(
        builder: (_) => b.isReturn
            ? ReturnSignPage(applyNo: b.sourceBillNo)
            : DeliverySignPage(detailId: b.detailId),
      ),
    );
    if (done != true) return;
    await _refreshAll(ref);
  }

  /// 单据状态变化会同时影响本页、配送中列表和首页汇总，三处一起刷新，
  /// 否则返回列表时会看到旧的待处理单数。
  Future<void> _refreshAll(WidgetRef ref) async {
    await ref.read(storeBillsProvider(_args).notifier).refresh();
    await ref.read(deliveringStoresProvider.notifier).refresh();
    ref.invalidate(todayTasksProvider);
    ref.read(homeOverviewProvider.notifier).refresh();
  }

  // ==========================================================================
  // 小组件
  // ==========================================================================

  Widget _cell(String label, String value, Color color) => Expanded(
        child: Column(
          children: [
            Text(value,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style:
                    TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: color)),
            const SizedBox(height: 2),
            Text(label, style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
          ],
        ),
      );

  Widget _vline() => Container(width: 1, height: 24, color: TmsTheme.rule);

  Widget _btn(String label, Color color, VoidCallback? onTap) {
    final c = onTap != null ? color : TmsTheme.muted;
    return SizedBox(
      height: 34,
      child: OutlinedButton(
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

  void _toast(BuildContext context, String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }
}
