import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../config/theme.dart';
import '../../models/driver_return.dart';
import '../../models/reschedule_reject.dart';
import '../../models/task.dart';
import '../../providers/driver_return_provider.dart';
import '../../providers/reschedule_reject_provider.dart';
import '../../providers/task_provider.dart';
import '../../widgets/common.dart';

/// 返仓交接页面（对齐原型 Screen J，P3-2 扩展为三 Tab）。
///
/// 流程：
///   - Tab 1 司机现场退货：本趟 tms_driver_return 仓库人员逐单清点 → 确认返仓 → 生成退货入库单（库存增加）
///   - Tab 2 改派返仓：tms_reschedule_return 司机返仓确认 → 仓库在 ERP 端验收 → 回调度池
///   - Tab 3 客户拒收：tms_customer_reject 司机返仓确认 → 仓库在 ERP 端收货 → 生成拒收入库单（库存增加）
///
/// 司机端 APP 只负责确认「货物已随车回到仓库」（returned_at），
/// 后续的库存/调度操作在 ERP 端完成。
class WarehouseReturnPage extends ConsumerStatefulWidget {
  const WarehouseReturnPage({super.key});

  @override
  ConsumerState<WarehouseReturnPage> createState() => _WarehouseReturnPageState();
}

class _WarehouseReturnPageState extends ConsumerState<WarehouseReturnPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tab;

  @override
  void initState() {
    super.initState();
    _tab = TabController(length: 3, vsync: this);
  }

  @override
  void dispose() {
    _tab.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(
        title: const Text('返仓交接'),
        bottom: TabBar(
          controller: _tab,
          labelColor: Colors.white,
          unselectedLabelColor: Colors.white70,
          indicatorColor: Colors.white,
          labelStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
          tabs: const [
            Tab(text: '司机现场退货'),
            Tab(text: '改派返仓'),
            Tab(text: '客户拒收'),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tab,
        children: const [
          _DriverReturnTab(),
          _RescheduleReturnTab(),
          _CustomerRejectTab(),
        ],
      ),
    );
  }
}

// ============================================================
// Tab 1：司机现场退货（原 WarehouseReturnPage 逻辑）
// ============================================================
class _DriverReturnTab extends ConsumerStatefulWidget {
  const _DriverReturnTab();
  @override
  ConsumerState<_DriverReturnTab> createState() => _DriverReturnTabState();
}

class _DriverReturnTabState extends ConsumerState<_DriverReturnTab>
    with AutomaticKeepAliveClientMixin {
  final Set<String> _checked = {};
  bool _submitting = false;

  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final async = ref.watch(warehouseReturnListProvider);
    return async.when(
      data: (d) => _buildBody(d),
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text('加载失败：$e', style: const TextStyle(color: TmsTheme.muted)),
        ),
      ),
    );
  }

  Widget _buildBody(Map<String, dynamic> d) {
    final list = d['list'] as List<LoadedReturn>? ?? [];
    final count = d['count'] as int? ?? 0;
    final totalQty = d['totalQty'] as num? ?? 0;

    if (list.isEmpty) {
      return ListView(
        padding: const EdgeInsets.all(40),
        children: const [
          Icon(Icons.check_circle, size: 48, color: TmsTheme.ok),
          SizedBox(height: 12),
          Text('本趟无待返仓退货', textAlign: TextAlign.center, style: TextStyle(fontSize: 14, color: TmsTheme.muted)),
          SizedBox(height: 4),
          Text('司机现场退货后，退货单会出现在这里等待仓库验收', textAlign: TextAlign.center, style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
        ],
      );
    }

    return Column(
      children: [
        Container(
          color: Colors.white,
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
            Text('待返仓退货 $count 单', style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            Text('合计 ${fmtQty(totalQty)} 件', style: const TextStyle(fontSize: 13, color: TmsTheme.accent2, fontWeight: FontWeight.w700)),
            Text('已勾选 ${_checked.length} 单', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
          ]),
        ),
        const Alert.info('📦 仓库人员请逐单清点实物后勾选确认，系统将自动生成退货入库单'),
        Expanded(
          child: RefreshIndicator(
            onRefresh: () async => ref.invalidate(warehouseReturnListProvider),
            child: ListView(
              padding: const EdgeInsets.all(14),
              children: list.map((r) => _ReturnCard(
                ret: r,
                checked: _checked.contains(r.driverReturnId),
                onToggle: () => setState(() {
                  if (_checked.contains(r.driverReturnId)) {
                    _checked.remove(r.driverReturnId);
                  } else {
                    _checked.add(r.driverReturnId);
                  }
                }),
              )).toList(),
            ),
          ),
        ),
        _bottomBar(list),
      ],
    );
  }

  Widget _bottomBar(List<LoadedReturn> list) {
    final allChecked = list.isNotEmpty && list.every((r) => _checked.contains(r.driverReturnId));
    return Container(
      decoration: const BoxDecoration(color: Colors.white, border: Border(top: BorderSide(color: TmsTheme.rule))),
      padding: const EdgeInsets.all(14),
      child: SafeArea(
        top: false,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            GestureDetector(
              onTap: () => setState(() {
                if (allChecked) {
                  _checked.clear();
                } else {
                  _checked.clear();
                  for (final r in list) {
                    _checked.add(r.driverReturnId);
                  }
                }
              }),
              child: Row(children: [
                Icon(allChecked ? Icons.check_box : Icons.check_box_outline_blank, size: 16, color: allChecked ? TmsTheme.accent2 : TmsTheme.muted),
                const SizedBox(width: 4),
                Text(allChecked ? '取消全选' : '全选', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
              ]),
            ),
            const Spacer(),
            Text('已选 ${_checked.length} / ${list.length} 单', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
          ]),
          const SizedBox(height: 10),
          TmsButton.primary(
            _submitting ? '处理中...' : '确认返仓交接（${_checked.length} 单）',
            onPressed: (_submitting || _checked.isEmpty) ? null : _confirm,
          ),
        ]),
      ),
    );
  }

  Future<void> _confirm() async {
    setState(() => _submitting = true);
    try {
      final result = await ref.read(warehouseReturnConfirmProvider(_checked.toList()).future);
      final confirmed = result['confirmed'] as int? ?? 0;
      final inboundNos = result['inboundNos'] as List? ?? [];
      final failed = result['failed'] as List? ?? [];
      String msg = '返仓交接成功 $confirmed 单，生成入库单 ${inboundNos.length} 张';
      if (failed.isNotEmpty) {
        msg += '，失败 ${failed.length} 单';
      }
      _toast(msg);
      _checked.clear();
      ref.invalidate(warehouseReturnListProvider);
      ref.invalidate(returnTaskListProvider);
      ref.invalidate(todayTasksProvider);
    } catch (e) {
      _toast('交接失败：${e.toString().replaceFirst("Exception: ", "")}');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating));
  }
}

/// 待返仓退货卡片。
class _ReturnCard extends StatelessWidget {
  final LoadedReturn ret;
  final bool checked;
  final VoidCallback onToggle;
  const _ReturnCard({required this.ret, required this.checked, required this.onToggle});

  @override
  Widget build(BuildContext context) {
    return MCard(
      leftBar: checked ? TmsTheme.ok : TmsTheme.accent2,
      onTap: onToggle,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(checked ? Icons.check_circle : Icons.radio_button_unchecked, size: 18, color: checked ? TmsTheme.ok : TmsTheme.muted),
          const SizedBox(width: 6),
          Expanded(child: Text(ret.customerName, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
          const MTag.orange('待返仓'),
        ]),
        const SizedBox(height: 4),
        Text('退货单 ${ret.driverReturnNo} · ${ret.returnDate}', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        if (ret.returnReason.isNotEmpty) ...[
          const SizedBox(height: 2),
          Text('退货原因：${ret.returnReason}', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        ],
        const SizedBox(height: 6),
        ...ret.details.map((d) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 2),
          child: Row(children: [
            Expanded(child: Text('${d.goodsName} ${d.spec}', style: const TextStyle(fontSize: 11, color: TmsTheme.ink))),
            Text('${d.qty} ${d.unitName}', style: const TextStyle(fontSize: 11, color: TmsTheme.muted, fontWeight: FontWeight.w600)),
          ]),
        )),
        const SizedBox(height: 4),
        Row(mainAxisAlignment: MainAxisAlignment.end, children: [
          Text('合计 ${fmtQty(ret.qty)} 件', style: const TextStyle(fontSize: 11, color: TmsTheme.accent2, fontWeight: FontWeight.w700)),
        ]),
      ]),
    );
  }
}

// ============================================================
// Tab 2：改派返仓（司机返仓确认 → returned_at）
// ============================================================
class _RescheduleReturnTab extends ConsumerStatefulWidget {
  const _RescheduleReturnTab();
  @override
  ConsumerState<_RescheduleReturnTab> createState() => _RescheduleReturnTabState();
}

class _RescheduleReturnTabState extends ConsumerState<_RescheduleReturnTab>
    with AutomaticKeepAliveClientMixin {
  final Set<String> _checked = {};
  bool _submitting = false;

  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final async = ref.watch(rescheduleReturnListProvider);
    return async.when(
      data: (d) => _buildBody(d),
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text('加载失败：$e', style: const TextStyle(color: TmsTheme.muted)),
        ),
      ),
    );
  }

  Widget _buildBody(Map<String, dynamic> d) {
    final list = d['list'] as List<RescheduleReturn>? ?? [];
    final count = d['count'] as int? ?? 0;
    final totalQty = d['totalQty'] as num? ?? 0;

    if (list.isEmpty) {
      return ListView(
        padding: const EdgeInsets.all(40),
        children: const [
          Icon(Icons.check_circle, size: 48, color: TmsTheme.ok),
          SizedBox(height: 12),
          Text('暂无待返仓的改派返仓单', textAlign: TextAlign.center, style: TextStyle(fontSize: 14, color: TmsTheme.muted)),
          SizedBox(height: 4),
          Text('客户不在 / 地址错误时，司机会在客户现场生成改派返仓单', textAlign: TextAlign.center, style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
        ],
      );
    }

    return Column(
      children: [
        Container(
          color: Colors.white,
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
            Text('待返仓改派 $count 单', style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            Text('合计 ${fmtQty(totalQty)} 件', style: const TextStyle(fontSize: 13, color: TmsTheme.accent2, fontWeight: FontWeight.w700)),
            Text('已勾选 ${_checked.length} 单', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
          ]),
        ),
        const Alert.warn('🔄 司机返仓确认后，仓库需在 ERP 端逐单验收 → 发货单回调度池重新派送'),
        Expanded(
          child: RefreshIndicator(
            onRefresh: () async => ref.invalidate(rescheduleReturnListProvider),
            child: ListView(
              padding: const EdgeInsets.all(14),
              children: list.map((r) => _RescheduleCard(
                ret: r,
                checked: _checked.contains(r.returnId),
                onToggle: () => setState(() {
                  if (_checked.contains(r.returnId)) {
                    _checked.remove(r.returnId);
                  } else {
                    _checked.add(r.returnId);
                  }
                }),
              )).toList(),
            ),
          ),
        ),
        _bottomBar(list),
      ],
    );
  }

  Widget _bottomBar(List<RescheduleReturn> list) {
    final allChecked = list.isNotEmpty && list.every((r) => _checked.contains(r.returnId));
    return Container(
      decoration: const BoxDecoration(color: Colors.white, border: Border(top: BorderSide(color: TmsTheme.rule))),
      padding: const EdgeInsets.all(14),
      child: SafeArea(
        top: false,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            GestureDetector(
              onTap: () => setState(() {
                if (allChecked) {
                  _checked.clear();
                } else {
                  _checked.clear();
                  for (final r in list) {
                    _checked.add(r.returnId);
                  }
                }
              }),
              child: Row(children: [
                Icon(allChecked ? Icons.check_box : Icons.check_box_outline_blank, size: 16, color: allChecked ? TmsTheme.accent2 : TmsTheme.muted),
                const SizedBox(width: 4),
                Text(allChecked ? '取消全选' : '全选', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
              ]),
            ),
            const Spacer(),
            Text('已选 ${_checked.length} / ${list.length} 单', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
          ]),
          const SizedBox(height: 10),
          TmsButton.warn(
            _submitting ? '处理中...' : '司机返仓确认（${_checked.length} 单）',
            onPressed: (_submitting || _checked.isEmpty) ? null : _confirm,
          ),
        ]),
      ),
    );
  }

  Future<void> _confirm() async {
    setState(() => _submitting = true);
    try {
      final result = await ref.read(rescheduleReturnConfirmProvider(_checked.toList()).future);
      final confirmed = result['confirmed'] as int? ?? 0;
      final failed = result['failed'] as List? ?? [];
      String msg = '返仓确认 $confirmed 单';
      if (failed.isNotEmpty) msg += '，失败 ${failed.length} 单';
      _toast(msg);
      _checked.clear();
      ref.invalidate(rescheduleReturnListProvider);
      ref.invalidate(todayTasksProvider);
    } catch (e) {
      _toast('交接失败：${e.toString().replaceFirst("Exception: ", "")}');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating));
  }
}

class _RescheduleCard extends StatelessWidget {
  final RescheduleReturn ret;
  final bool checked;
  final VoidCallback onToggle;
  const _RescheduleCard({required this.ret, required this.checked, required this.onToggle});

  @override
  Widget build(BuildContext context) {
    return MCard(
      leftBar: checked ? TmsTheme.ok : TmsTheme.accent2,
      onTap: onToggle,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(checked ? Icons.check_circle : Icons.radio_button_unchecked, size: 18, color: checked ? TmsTheme.ok : TmsTheme.muted),
          const SizedBox(width: 6),
          Expanded(child: Text(ret.customerName, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
          const MTag.orange('待返仓'),
        ]),
        const SizedBox(height: 4),
        Text('改派返仓单 ${ret.returnNo}', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        const SizedBox(height: 2),
        Text('发货单 ${ret.receiptNo} · ${ret.customerAddress}', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        const SizedBox(height: 2),
        Text('原因：${RescheduleReason.label(ret.reason)}（第 ${ret.rescheduleCount} 次）· 期望改送 ${ret.rescheduleDate}',
            style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        const SizedBox(height: 4),
        Row(mainAxisAlignment: MainAxisAlignment.end, children: [
          Text('合计 ${fmtQty(ret.totalQty)} 件', style: const TextStyle(fontSize: 11, color: TmsTheme.accent2, fontWeight: FontWeight.w700)),
        ]),
      ]),
    );
  }
}

// ============================================================
// Tab 3：客户拒收（司机返仓确认 → returned_at）
// ============================================================
class _CustomerRejectTab extends ConsumerStatefulWidget {
  const _CustomerRejectTab();
  @override
  ConsumerState<_CustomerRejectTab> createState() => _CustomerRejectTabState();
}

class _CustomerRejectTabState extends ConsumerState<_CustomerRejectTab>
    with AutomaticKeepAliveClientMixin {
  final Set<String> _checked = {};
  bool _submitting = false;

  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final async = ref.watch(customerRejectListProvider);
    return async.when(
      data: (d) => _buildBody(d),
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text('加载失败：$e', style: const TextStyle(color: TmsTheme.muted)),
        ),
      ),
    );
  }

  Widget _buildBody(Map<String, dynamic> d) {
    final list = d['list'] as List<CustomerReject>? ?? [];
    final count = d['count'] as int? ?? 0;
    final totalQty = d['totalQty'] as num? ?? 0;
    final totalAmount = d['totalAmount'] as num? ?? 0;

    if (list.isEmpty) {
      return ListView(
        padding: const EdgeInsets.all(40),
        children: const [
          Icon(Icons.check_circle, size: 48, color: TmsTheme.ok),
          SizedBox(height: 12),
          Text('暂无待返仓的客户拒收单', textAlign: TextAlign.center, style: TextStyle(fontSize: 14, color: TmsTheme.muted)),
          SizedBox(height: 4),
          Text('客户拒收时，司机会在客户现场生成拒收单', textAlign: TextAlign.center, style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
        ],
      );
    }

    return Column(
      children: [
        Container(
          color: Colors.white,
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
            Text('待返仓拒收 $count 单', style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            Text('合计 ${fmtQty(totalQty)} 件', style: const TextStyle(fontSize: 13, color: TmsTheme.bad, fontWeight: FontWeight.w700)),
            Text('¥ $totalAmount', style: const TextStyle(fontSize: 13, color: TmsTheme.bad, fontWeight: FontWeight.w700)),
          ]),
        ),
        const Alert.danger('⚠️ 司机返仓确认后，仓库需在 ERP 端逐单收货 → 生成拒收入库单（库存增加）+ 撤销应收'),
        Expanded(
          child: RefreshIndicator(
            onRefresh: () async => ref.invalidate(customerRejectListProvider),
            child: ListView(
              padding: const EdgeInsets.all(14),
              children: list.map((r) => _RejectCard(
                ret: r,
                checked: _checked.contains(r.rejectId),
                onToggle: () => setState(() {
                  if (_checked.contains(r.rejectId)) {
                    _checked.remove(r.rejectId);
                  } else {
                    _checked.add(r.rejectId);
                  }
                }),
              )).toList(),
            ),
          ),
        ),
        _bottomBar(list),
      ],
    );
  }

  Widget _bottomBar(List<CustomerReject> list) {
    final allChecked = list.isNotEmpty && list.every((r) => _checked.contains(r.rejectId));
    return Container(
      decoration: const BoxDecoration(color: Colors.white, border: Border(top: BorderSide(color: TmsTheme.rule))),
      padding: const EdgeInsets.all(14),
      child: SafeArea(
        top: false,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            GestureDetector(
              onTap: () => setState(() {
                if (allChecked) {
                  _checked.clear();
                } else {
                  _checked.clear();
                  for (final r in list) {
                    _checked.add(r.rejectId);
                  }
                }
              }),
              child: Row(children: [
                Icon(allChecked ? Icons.check_box : Icons.check_box_outline_blank, size: 16, color: allChecked ? TmsTheme.bad : TmsTheme.muted),
                const SizedBox(width: 4),
                Text(allChecked ? '取消全选' : '全选', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
              ]),
            ),
            const Spacer(),
            Text('已选 ${_checked.length} / ${list.length} 单', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
          ]),
          const SizedBox(height: 10),
          TmsButton.danger(
            _submitting ? '处理中...' : '司机返仓确认（${_checked.length} 单）',
            onPressed: (_submitting || _checked.isEmpty) ? null : _confirm,
          ),
        ]),
      ),
    );
  }

  Future<void> _confirm() async {
    setState(() => _submitting = true);
    try {
      final result = await ref.read(customerRejectConfirmProvider(_checked.toList()).future);
      final confirmed = result['confirmed'] as int? ?? 0;
      final failed = result['failed'] as List? ?? [];
      String msg = '返仓确认 $confirmed 单';
      if (failed.isNotEmpty) msg += '，失败 ${failed.length} 单';
      _toast(msg);
      _checked.clear();
      ref.invalidate(customerRejectListProvider);
      ref.invalidate(todayTasksProvider);
    } catch (e) {
      _toast('交接失败：${e.toString().replaceFirst("Exception: ", "")}');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating));
  }
}

class _RejectCard extends StatelessWidget {
  final CustomerReject ret;
  final bool checked;
  final VoidCallback onToggle;
  const _RejectCard({required this.ret, required this.checked, required this.onToggle});

  @override
  Widget build(BuildContext context) {
    return MCard(
      leftBar: checked ? TmsTheme.ok : TmsTheme.bad,
      onTap: onToggle,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(checked ? Icons.check_circle : Icons.radio_button_unchecked, size: 18, color: checked ? TmsTheme.ok : TmsTheme.muted),
          const SizedBox(width: 6),
          Expanded(child: Text(ret.customerName, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
          const MTag.red('待返仓'),
        ]),
        const SizedBox(height: 4),
        Text('拒收单 ${ret.rejectNo}', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        const SizedBox(height: 2),
        Text('发货单 ${ret.receiptNo} · ${ret.customerAddress}', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        const SizedBox(height: 2),
        Text('原因：${CustomerRejectReason.label(ret.rejectReason)}', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        const SizedBox(height: 4),
        Row(mainAxisAlignment: MainAxisAlignment.end, children: [
          Text('合计 ${fmtQty(ret.totalQty)} 件 · ¥ ${ret.totalAmount.toStringAsFixed(2)}',
              style: const TextStyle(fontSize: 11, color: TmsTheme.bad, fontWeight: FontWeight.w700)),
        ]),
      ]),
    );
  }
}
