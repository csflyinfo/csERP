import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../config/theme.dart';
import '../../models/delivery.dart';
import '../../providers/delivery_provider.dart';
import '../../services/launch_service.dart';
import '../../widgets/common.dart';
import '../../widgets/offline_banner.dart';
import 'loading_confirm_page.dart';

/// 调度任务配送点清单页（首页卡片「查看清单」入口）。
///
/// 定位：回答「这趟车要跑哪几个点、按什么顺序跑」。
/// 与装车确认页的分工——本页是行程视图（一行一个配送点，含退货点），
/// 装车页是货物视图（一行一个 SKU，只有发货单）。两者数据源不同接口，
/// 本页走 /loading/stores，装车页走 /loading/items。
///
/// 调序规则（与后端 /loading/sort 一致）：
///   1. 仅未发车（ASSIGNED/ACCEPTED/LOADED）可调序，发车后线路已成事实
///   2. 已完成门店锁定在最前且不可拖动，货已卸下，挪顺序只会让进度显示错乱
///   3. 提交的是门店编码序列而非单据序号，同门店多单不会被拆散到两段
class DispatchStoresPage extends ConsumerStatefulWidget {
  final String dispatchId;
  const DispatchStoresPage({super.key, required this.dispatchId});

  @override
  ConsumerState<DispatchStoresPage> createState() => _DispatchStoresPageState();
}

class _DispatchStoresPageState extends ConsumerState<DispatchStoresPage> {
  /// 拖拽中的门店编码顺序（仅含待配送门店）。
  ///
  /// 不直接改 provider 里的数据：拖动是草稿态，司机可能拖完又反悔。
  /// 只有点「确认顺序」才提交后端，未提交时退出页面等于放弃。
  List<String>? _draft;
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(loadingStoresProvider(widget.dispatchId));
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(
        title: const Text('本次调度任务'),
        actions: const [
          // 地图调序入口先占位：原型里是长按地图节点拖拽，需要地图 SDK 与
          // 经纬度齐备才有意义（当前 base_customer 的经纬度大量为空）。
          IconButton(
            onPressed: null,
            tooltip: '地图调序（待接入）',
            icon: Icon(Icons.map_outlined),
          ),
        ],
      ),
      body: Column(
        children: [
          const OfflineBanner(),
          Expanded(
            child: async.when(
              data: _buildBody,
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Text('加载失败：$e',
                      style: const TextStyle(color: TmsTheme.muted)),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBody(LoadingStores d) {
    if (d.stores.isEmpty) {
      return const Center(
        child: Text('该调度单暂无配送点', style: TextStyle(color: TmsTheme.muted)),
      );
    }

    final doneStores = d.stores.where((s) => s.done).toList();
    final pendingStores = d.stores.where((s) => !s.done).toList();

    // 草稿顺序作用在待配送门店上；后端返回顺序变化时（例如别的端调了序）自动重置
    final codes = pendingStores.map((s) => s.customerCode).toList();
    var draft = _draft;
    if (draft == null ||
        draft.length != codes.length ||
        !draft.every(codes.contains)) {
      draft = codes;
      _draft = null;
    }
    final ordered = [
      ...doneStores,
      ...draft.map((c) => pendingStores.firstWhere((s) => s.customerCode == c)),
    ];
    final dirty = _draft != null && !_sameOrder(_draft!, codes);

    return Column(
      children: [
        _header(d),
        if (d.canSort)
          const Alert.info('长按左侧手柄可拖拽调整配送顺序，调整后需点击下方按钮保存')
        else if (!d.beforeDepart)
          const Alert.warn('已发车，配送顺序不可再调整')
        else if (d.stores.length > 1)
          const Alert.ok('剩余待配送门店不足 2 家，无需调序'),
        Expanded(
          child: RefreshIndicator(
            onRefresh: () async {
              setState(() => _draft = null);
              ref.invalidate(loadingStoresProvider(widget.dispatchId));
            },
            child: ReorderableListView.builder(
              padding: const EdgeInsets.fromLTRB(14, 12, 14, 20),
              buildDefaultDragHandles: false,
              itemCount: ordered.length,
              onReorderItem: (from, to) =>
                  _onReorder(d, doneStores.length, from, to),
              itemBuilder: (_, i) {
                final s = ordered[i];
                return _StoreTile(
                  key: ValueKey(s.customerCode),
                  store: s,
                  index: i,
                  // 序号按当前草稿顺序实时重算，而不是用后端的 orderNo，
                  // 否则拖完不点保存，编号和位置会对不上
                  displayNo: i + 1,
                  draggable: d.canSort && s.sortable,
                );
              },
            ),
          ),
        ),
        _footer(d, dirty, draft.length),
      ],
    );
  }

  bool _sameOrder(List<String> a, List<String> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }

  /// 拖拽落位：把 ReorderableListView 的全局下标换算成待配送段内的下标。
  ///
  /// 已完成门店固定占据列表头部，任何试图拖进这段的操作都夹回待配送段起点，
  /// 免得司机把「已送达」的点拖到后面去。
  ///
  /// 用的是 onReorderItem 而非旧的 onReorder：新回调传进来的 to 已经是
  /// 「移除源项之后」的目标下标，这里不需要再自行做 to-- 补偿。
  void _onReorder(LoadingStores d, int doneCount, int from, int to) {
    final list = List<String>.of(_draft ??
        d.stores.where((s) => !s.done).map((s) => s.customerCode));
    final f = from - doneCount;
    var t = to - doneCount;
    if (f < 0 || f >= list.length) return;
    final code = list.removeAt(f);
    if (t < 0) t = 0;
    if (t > list.length) t = list.length;
    list.insert(t, code);
    setState(() => _draft = list);
  }

  Widget _header(LoadingStores d) {
    return Container(
      color: Colors.white,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(
            child: Text('调度单 ${d.dispatchNo}',
                style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                    color: TmsTheme.ink)),
          ),
          if (d.appended) const MTag.purple('追加'),
          const SizedBox(width: 6),
          MTag.gray(_statusText(d.status)),
        ]),
        const SizedBox(height: 6),
        Text(
          [
            if (d.dispatchDate.isNotEmpty) d.dispatchDate,
            if (d.routeLine.isNotEmpty) d.routeLine,
            if (d.vehiclePlate.isNotEmpty) d.vehiclePlate,
          ].join(' · '),
          style: const TextStyle(fontSize: 12, color: TmsTheme.muted),
        ),
        const SizedBox(height: 8),
        Text(
          '${d.storeCount} 个配送点（待送 ${d.pendingStore}）· ${d.billCount} 单 · ${_qty(d.totalQty)} 件'
          '${d.collectAmount > 0 ? ' · 代收 ¥${_money(d.collectAmount)}' : ''}',
          style: const TextStyle(
              fontSize: 12, fontWeight: FontWeight.w600, color: TmsTheme.ink),
        ),
      ]),
    );
  }

  Widget _footer(LoadingStores d, bool dirty, int pending) {
    // 未发车才给底部按钮：发车后本页退化为只读行程单
    if (!d.beforeDepart) return const SizedBox.shrink();
    return Container(
      color: Colors.white,
      padding: const EdgeInsets.fromLTRB(14, 10, 14, 14),
      child: Row(children: [
        if (dirty) ...[
          Expanded(
            child: TmsButton.outline('恢复原顺序',
                onPressed: _busy ? null : () => setState(() => _draft = null)),
          ),
          const SizedBox(width: 10),
          Expanded(
            flex: 2,
            child: TmsButton.warn(_busy ? '保存中…' : '保存顺序（$pending 个点）',
                onPressed: _busy ? null : () => _submitSort(d)),
          ),
        ] else
          Expanded(
            child: TmsButton.primary(
              d.status == 'LOADED' ? '继续装车 / 发车' : '确认顺序 · 开始装车',
              onPressed: _busy ? null : () => _goLoading(d),
            ),
          ),
      ]),
    );
  }

  Future<void> _submitSort(LoadingStores d) async {
    final codes = _draft;
    if (codes == null || codes.isEmpty) return;
    setState(() => _busy = true);
    try {
      await ref.read(
        loadingSortProvider(
          LoadingSortArgs(dispatchId: widget.dispatchId, customerCodes: codes),
        ).future,
      );
      if (!mounted) return;
      // 提交成功才清草稿：失败时保留司机拖好的顺序，避免白拖一遍
      setState(() => _draft = null);
      ref.invalidate(loadingStoresProvider(widget.dispatchId));
      _toast('配送顺序已保存');
    } catch (e) {
      if (mounted) _toast('保存失败：$e', bad: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _goLoading(LoadingStores d) async {
    // 有未保存的调序时先拦一下，否则司机以为顺序生效了，装车却按老顺序排
    if (_draft != null) {
      _toast('请先保存配送顺序', bad: true);
      return;
    }
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => LoadingConfirmPage(dispatchId: widget.dispatchId),
      ),
    );
    if (!mounted) return;
    ref.invalidate(loadingStoresProvider(widget.dispatchId));
  }

  void _toast(String msg, {bool bad = false}) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg),
      backgroundColor: bad ? TmsTheme.bad : TmsTheme.ok,
    ));
  }
}

/// 配送点卡片：序号 + 门店信息 + 单据列表 + 拖拽手柄。
class _StoreTile extends StatelessWidget {
  final LoadingStore store;
  final int index;
  final int displayNo;
  final bool draggable;

  const _StoreTile({
    super.key,
    required this.store,
    required this.index,
    required this.displayNo,
    required this.draggable,
  });

  @override
  Widget build(BuildContext context) {
    final s = store;
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: s.done ? TmsTheme.rule : TmsTheme.primaryLight),
      ),
      padding: const EdgeInsets.fromLTRB(10, 10, 12, 10),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        _badge(s, displayNo),
        const SizedBox(width: 10),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(
                child: Text(
                  s.customerName.isEmpty ? s.customerCode : s.customerName,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                    color: s.done ? TmsTheme.muted : TmsTheme.ink,
                    decoration: s.done ? TextDecoration.lineThrough : null,
                  ),
                ),
              ),
              if (s.done) const MTag.green('已完成'),
              if (!s.done && s.needCollect) const MTag.orange('COD'),
            ]),
            if (s.customerAddress.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text(s.customerAddress,
                  style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
            ],
            const SizedBox(height: 6),
            Wrap(spacing: 6, runSpacing: 6, children: [
              if (s.receiptCount > 0) MTag.blue('发货 ${s.receiptCount}'),
              if (s.returnCount > 0) MTag.purple('退货 ${s.returnCount}'),
              MTag.gray('${_qty(s.totalQty)} 件'),
              if (s.collectAmount > 0) MTag.orange('¥${_money(s.collectAmount)}'),
            ]),
            const SizedBox(height: 8),
            ...s.bills.map(_billRow),
            if (s.hasPhone) ...[
              const SizedBox(height: 6),
              InkWell(
                onTap: () => LaunchService.instance.dial(s.contactMobile),
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 2),
                  child: Row(children: [
                    const Icon(Icons.phone, size: 14, color: TmsTheme.accent),
                    const SizedBox(width: 4),
                    Text(
                      s.contactName.isEmpty
                          ? s.contactMobile
                          : '${s.contactName} ${s.contactMobile}',
                      style: const TextStyle(
                          fontSize: 12,
                          color: TmsTheme.accent,
                          fontWeight: FontWeight.w600),
                    ),
                  ]),
                ),
              ),
            ],
          ]),
        ),
        if (draggable)
          ReorderableDragStartListener(
            index: index,
            child: const Padding(
              padding: EdgeInsets.only(left: 6, top: 2),
              child: Icon(Icons.drag_handle, color: TmsTheme.muted),
            ),
          ),
      ]),
    );
  }

  Widget _badge(LoadingStore s, int no) {
    final color = s.done ? TmsTheme.ok : TmsTheme.accent;
    return Container(
      width: 26,
      height: 26,
      alignment: Alignment.center,
      decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      child: s.done
          ? const Icon(Icons.check, size: 16, color: Colors.white)
          : Text('$no',
              style: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                  color: Colors.white)),
    );
  }

  Widget _billRow(LoadingStoreBill b) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 3),
      child: Row(children: [
        Icon(
          b.isReturn ? Icons.assignment_return_outlined : Icons.inventory_2_outlined,
          size: 13,
          color: b.isReturn ? TmsTheme.returnPurple : TmsTheme.muted,
        ),
        const SizedBox(width: 5),
        Expanded(
          child: Text(
            b.sourceBillNo,
            style: TextStyle(
              fontSize: 12,
              color: b.done ? TmsTheme.textMuted : TmsTheme.ink,
              decoration: b.done ? TextDecoration.lineThrough : null,
            ),
          ),
        ),
        Text(
          '${_qty(b.qty)} 件${b.skuCount > 0 ? ' / ${b.skuCount} 种' : ''}',
          style: const TextStyle(fontSize: 11, color: TmsTheme.muted),
        ),
      ]),
    );
  }
}

String _statusText(String s) => const {
      'ASSIGNED': '待接单',
      'ACCEPTED': '已接单',
      'LOADED': '已装车',
      'DEPARTED': '已发车',
      'DELIVERING': '配送中',
      'COMPLETED': '已完成',
      'CANCELLED': '已取消',
    }[s] ??
    s;

String _qty(num v) =>
    v == v.roundToDouble() ? v.toInt().toString() : v.toStringAsFixed(2);

String _money(num v) => v.toStringAsFixed(2);
