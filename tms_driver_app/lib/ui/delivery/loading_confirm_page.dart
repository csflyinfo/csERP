import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../config/theme.dart';
import '../../models/delivery.dart';
import '../../providers/delivery_provider.dart';
import '../../providers/task_provider.dart';
import '../../widgets/common.dart';

/// 实装数量变更回调（detailId|goodsCode → 新值）。
typedef OnLoadedChanged = void Function(String key, num value);

/// 装车确认页面（对齐原型 Screen D）。
///
/// 流程：
///   1. 进入页面拉取装车 SKU 明细（按发货单分组，逆配送顺序展示）
///   2. 状态=ASSIGNED 时显示「开始装车」按钮 → 调用 /loading/start → LOADED
///   3. 状态=LOADED 时，逐商品录入实装数量 → /loading/scan 写装车核对
///   4. 全部核对一致后，「确认装车完毕」→ /loading/confirm
///   5. 装车完毕后，「确认发车」→ /depart → DEPARTED，返回首页
class LoadingConfirmPage extends ConsumerStatefulWidget {
  final String dispatchId;
  const LoadingConfirmPage({super.key, required this.dispatchId});

  @override
  ConsumerState<LoadingConfirmPage> createState() => _LoadingConfirmPageState();
}

class _LoadingConfirmPageState extends ConsumerState<LoadingConfirmPage> {
  /// 本地编辑的实装数量，按 detailId|goodsCode 索引。
  final Map<String, num> _loadedQties = {};
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(loadingItemsProvider(widget.dispatchId));
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(title: const Text('装车确认')),
      body: async.when(
        data: (d) => _buildBody(d),
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Text('加载失败：$e', style: const TextStyle(color: TmsTheme.muted)),
          ),
        ),
      ),
    );
  }

  Widget _buildBody(LoadingDispatch d) {
    // 初始化实装数量默认=应装
    for (final r in d.receipts) {
      for (final it in r.items) {
        final key = '${r.detailId}|${it.goodsCode}';
        _loadedQties.putIfAbsent(key, () => it.loadedQty > 0 ? it.loadedQty : it.requiredQty);
      }
    }
    final totalLoaded = _loadedQties.values.fold<num>(0, (s, v) => s + v);
    final allChecked = d.receipts.isNotEmpty &&
        d.receipts.every((r) =>
            r.items.every((it) => (_loadedQties['${r.detailId}|${it.goodsCode}'] ?? 0) >= it.requiredQty));

    // 状态机
    final isAssigned = d.status == 'ASSIGNED';
    final isLoaded = d.status == 'LOADED';
    final isDeparted = d.status == 'DEPARTED';

    // 按逆配送顺序展示（后送的先装，seqNo 倒序）
    final sortedReceipts = List<LoadingReceipt>.from(d.receipts)
      ..sort((a, b) => b.seqNo.compareTo(a.seqNo));

    return Column(
      children: [
        // 顶部信息条
        Container(
          color: Colors.white,
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              Expanded(child: Text('调度单 ${d.dispatchNo}', style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
              _statusTag(d.status),
            ]),
            const SizedBox(height: 4),
            Text('${d.routeLine} · ${d.vehiclePlate} · ${d.storeCount} 个配送点', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
          ]),
        ),
        // 逆序装车提示
        if (isLoaded)
          const Alert.warn('📥 请按逆配送顺序装车——后送的货装里侧，先送的货装门口'),
        // 装车清单
        Expanded(
          child: RefreshIndicator(
            onRefresh: () async => ref.invalidate(loadingItemsProvider(widget.dispatchId)),
            child: ListView(
              padding: const EdgeInsets.all(14),
              children: [
                ...sortedReceipts.map((r) => _ReceiptCard(
                      receipt: r,
                      loadedQties: _loadedQties,
                      editable: isLoaded,
                      onChanged: (key, v) => setState(() => _loadedQties[key] = v),
                      onScan: isLoaded ? (it) => _scan(d, r, it) : null,
                    )),
                if (sortedReceipts.isEmpty)
                  const Padding(padding: EdgeInsets.all(40), child: Center(child: Text('无装车清单', style: TextStyle(color: TmsTheme.muted)))),
                const SizedBox(height: 8),
                // 合计
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(8)),
                  child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                    Text('合计：应装 ${d.totalRequired} 件', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                    Text('实装 $totalLoaded 件', style: const TextStyle(fontSize: 12, color: TmsTheme.ok, fontWeight: FontWeight.w700)),
                    Text('差异 ${totalLoaded - d.totalRequired} 件', style: TextStyle(fontSize: 12, color: totalLoaded < d.totalRequired ? TmsTheme.bad : TmsTheme.muted, fontWeight: FontWeight.w700)),
                  ]),
                ),
              ],
            ),
          ),
        ),
        // 底部操作区
        _bottomBar(d, isAssigned, isLoaded, isDeparted, allChecked),
      ],
    );
  }

  Widget _statusTag(String status) {
    switch (status) {
      case 'ASSIGNED':
        return const MTag.orange('待装车');
      case 'LOADED':
        return const MTag.blue('装车中');
      case 'DEPARTED':
        return const MTag.green('已发车');
      default:
        return MTag.gray(status);
    }
  }

  Widget _bottomBar(LoadingDispatch d, bool isAssigned, bool isLoaded, bool isDeparted, bool allChecked) {
    return Container(
      decoration: const BoxDecoration(color: Colors.white, border: Border(top: BorderSide(color: TmsTheme.rule))),
      padding: const EdgeInsets.all(14),
      child: SafeArea(
        top: false,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          if (isAssigned)
            TmsButton.primary(_busy ? '处理中...' : '开始装车', onPressed: _busy ? null : () => _action(d, 'start')),
          if (isLoaded) ...[
            if (!allChecked)
              const Alert.warn('⚠️ 尚有商品未核对完成，无法确认装车完毕'),
            Row(children: [
              Expanded(child: TmsButton.outline('刷新清单', onPressed: () => ref.invalidate(loadingItemsProvider(widget.dispatchId)))),
              const SizedBox(width: 8),
              Expanded(child: TmsButton.primary(_busy ? '处理中...' : '确认装车完毕', onPressed: (_busy || !allChecked) ? null : () => _action(d, 'confirm'))),
            ]),
            const SizedBox(height: 8),
            TmsButton.warn(_busy ? '处理中...' : '确认发车，开始配送', onPressed: (_busy || !allChecked) ? null : () => _action(d, 'depart')),
          ],
          if (isDeparted) ...[
            const Alert.ok('✅ 已发车，可返回首页查看配送任务'),
            const SizedBox(height: 8),
            TmsButton.outline('返回首页', color: TmsTheme.muted, onPressed: () {
              ref.invalidate(todayTasksProvider);
              Navigator.pop(context, true);
            }),
          ],
        ]),
      ),
    );
  }

  Future<void> _action(LoadingDispatch d, String action) async {
    setState(() => _busy = true);
    try {
      await ref.read(loadingActionProvider(LoadingActionArgs(dispatchId: d.dispatchId, action: action)).future);
      ref.invalidate(loadingItemsProvider(d.dispatchId));
      String msg = switch (action) {
        'start' => '已开始装车，请逐商品核对实装数量',
        'confirm' => '装车确认完成',
        'depart' => '已发车，配送开始',
        _ => '操作成功',
      };
      _toast(msg);
      if (action == 'depart' && mounted) {
        ref.invalidate(todayTasksProvider);
        Navigator.pop(context, true);
      }
    } catch (e) {
      _toast('操作失败：${e.toString().replaceFirst("Exception: ", "")}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _scan(LoadingDispatch d, LoadingReceipt r, LoadingItem it) async {
    final key = '${r.detailId}|${it.goodsCode}';
    final loaded = _loadedQties[key] ?? it.requiredQty;
    setState(() => _busy = true);
    try {
      await ref.read(loadingScanProvider(LoadingScanArgs(
        dispatchId: d.dispatchId,
        detailId: r.detailId,
        sourceBillNo: r.sourceBillNo,
        goodsCode: it.goodsCode,
        goodsName: it.goodsName,
        requiredQty: it.requiredQty,
        loadedQty: loaded,
      )).future);
      _toast('✅ ${it.goodsName} 已核对（实装 $loaded）');
    } catch (e) {
      _toast('核对失败：${e.toString().replaceFirst("Exception: ", "")}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating));
  }
}

/// 装车清单中的发货单卡片（含 SKU 行）。
class _ReceiptCard extends StatelessWidget {
  final LoadingReceipt receipt;
  final Map<String, num> loadedQties;
  final bool editable;
  final OnLoadedChanged onChanged;
  final void Function(LoadingItem)? onScan;
  const _ReceiptCard({required this.receipt, required this.loadedQties, required this.editable, required this.onChanged, this.onScan});

  @override
  Widget build(BuildContext context) {
    final loaded = receipt.items.fold<num>(0, (s, it) => s + (loadedQties['${receipt.detailId}|${it.goodsCode}'] ?? 0));
    final checked = loaded >= receipt.requiredQty;
    return MCard(
      leftBar: checked ? TmsTheme.ok : TmsTheme.accent,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
          Expanded(child: Text('① ${receipt.customerName}', style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
          if (checked) const MTag.green('已核对') else MTag.blue('${receipt.requiredQty} 件'),
        ]),
        const SizedBox(height: 2),
        Text('${receipt.sourceBillNo} · ${receipt.customerAddress}', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        const SizedBox(height: 6),
        ...receipt.items.map((it) => _ItemRow(
              item: it,
              loadedQty: loadedQties['${receipt.detailId}|${it.goodsCode}'] ?? it.requiredQty,
              editable: editable,
              onChanged: (v) => onChanged('${receipt.detailId}|${it.goodsCode}', v),
              onScan: onScan == null ? null : () => onScan!(it),
            )),
        const SizedBox(height: 4),
        Row(mainAxisAlignment: MainAxisAlignment.end, children: [
          Text('应装 ${receipt.requiredQty} 件 · 实装 $loaded 件', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        ]),
      ]),
    );
  }
}

/// 装车 SKU 行。
class _ItemRow extends StatelessWidget {
  final LoadingItem item;
  final num loadedQty;
  final bool editable;
  final ValueChanged<num> onChanged;
  final VoidCallback? onScan;
  const _ItemRow({required this.item, required this.loadedQty, required this.editable, required this.onChanged, this.onScan});

  @override
  Widget build(BuildContext context) {
    final diff = loadedQty - item.requiredQty;
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 6),
      decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Color(0xFFF0F1F4)))),
      child: Row(children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(item.goodsName, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: TmsTheme.ink)),
            if (item.unitName.isNotEmpty)
              Text(item.unitName, style: const TextStyle(fontSize: 10, color: TmsTheme.muted)),
            const SizedBox(height: 2),
            Text('应装 ${item.requiredQty}', style: const TextStyle(fontSize: 10, color: TmsTheme.muted)),
          ]),
        ),
        SizedBox(
          width: 80,
          child: TextField(
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            textAlign: TextAlign.center,
            enabled: editable,
            controller: TextEditingController(text: loadedQty.toString()),
            decoration: InputDecoration(
              isDense: true,
              contentPadding: const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
              enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
              disabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1)),
            ),
            onChanged: (v) {
              final n = num.tryParse(v) ?? 0;
              onChanged(n < 0 ? 0 : n);
            },
          ),
        ),
        const SizedBox(width: 6),
        SizedBox(
          width: 40,
          child: Text(
            diff == 0 ? '一致' : '${diff > 0 ? "+" : ""}$diff',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: diff == 0 ? TmsTheme.ok : (diff < 0 ? TmsTheme.bad : TmsTheme.accent2)),
          ),
        ),
        if (editable && onScan != null) ...[
          const SizedBox(width: 4),
          GestureDetector(
            onTap: onScan,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
              decoration: BoxDecoration(color: TmsTheme.primaryLight, borderRadius: BorderRadius.circular(6)),
              child: const Text('核对', style: TextStyle(fontSize: 10, color: TmsTheme.accent, fontWeight: FontWeight.w700)),
            ),
          ),
        ],
      ]),
    );
  }
}
