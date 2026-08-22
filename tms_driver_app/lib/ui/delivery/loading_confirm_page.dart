import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../config/theme.dart';
import '../../config/tms_status.dart';
import '../../models/delivery.dart';
import '../../models/task.dart';
import '../../providers/delivery_provider.dart';
import '../../providers/task_provider.dart';
import '../../services/api_service.dart';
import '../../services/location_service.dart';
import '../../services/param_service.dart';
import '../../services/photo_service.dart';
import '../../providers/auth_provider.dart';
import '../../widgets/common.dart';
import '../../widgets/offline_banner.dart';
import '../../widgets/point_bills_sheet.dart';

/// 实装数量变更回调（detailId|goodsCode → 新值）。
typedef OnLoadedChanged = void Function(String key, num value);

/// 装车确认页面（对齐原型 Screen D）。
///
/// 流程：
///   1. 进入页面拉取装车 SKU 明细（按发货单分组，逆配送顺序展示）
///   2. 状态=ASSIGNED 时显示「开始装车」按钮 → 调用 /loading/start → LOADED
///   3. 状态=LOADED 时，按**配送点**点【装车】，或勾选多个/全选后批量【装车】
///   4. 「确认发车」→ /depart，只发已装车的配送点（部分发车）
///
/// 为什么把「核对」改成「装车」：
///   原设计是逐 SKU 录数量再点「核对」，全部核对齐才允许发车。实际场中
///   司机是整点整点搬货的，一个点十几个 SKU 全录一遍不现实，结果是要么
///   乱填、要么卡在发车按钮上。现在核对降级为可选（数量仍可改，用于差异
///   留痕），装车确认上升到配送点粒度：装完一个点点一下，或勾一批一起点。
class LoadingConfirmPage extends ConsumerStatefulWidget {
  final String dispatchId;
  const LoadingConfirmPage({super.key, required this.dispatchId});

  @override
  ConsumerState<LoadingConfirmPage> createState() => _LoadingConfirmPageState();
}

class _LoadingConfirmPageState extends ConsumerState<LoadingConfirmPage> {
  /// 本地编辑的实装数量，按 detailId|goodsCode 索引。
  final Map<String, num> _loadedQties = {};

  /// 已勾选待批量装车的配送点（detailId）。
  final Set<String> _selected = {};
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(loadingItemsProvider(widget.dispatchId));
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(title: const Text('装车确认')),
      body: Column(
        children: [
          const OfflineBanner(),
          Expanded(
            child: async.when(
              data: (d) => _buildBody(d),
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Text('加载失败：$e', style: const TextStyle(color: TmsTheme.muted)),
                ),
              ),
            ),
          ),
        ],
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

    // 状态机：ASSIGNED=待接单（司机还没点「开始装车」）；
    // ACCEPTED=待装车（已接单，正在逐点装）；LOADED=全部装完；DEPARTED/DELIVERING=已上路。
    final isAssigned = d.status == 'ASSIGNED';
    // 接单后（含 ACCEPTED/LOADED/已发车补发）清单可操作；只要还有未发车的配送点，
    // 页面就得保持可操作，否则补装的点无法再发车。
    final isLoaded = d.receipts.isNotEmpty && d.status != 'ASSIGNED';
    final isDeparted = d.receipts.isEmpty && d.status != 'ASSIGNED';
    // 装车阶段（未发车）才能把装不下的配送点退回调度池。
    final canReturnPoint =
        d.status == 'ASSIGNED' || d.status == 'ACCEPTED' || d.status == 'LOADED';

    // 按逆配送顺序展示（后送的先装，seqNo 倒序）
    final sortedReceipts = List<LoadingReceipt>.from(d.receipts)
      ..sort((a, b) => b.seqNo.compareTo(a.seqNo));

    // 勾选集合可能残留已装车/已发车的明细（装车成功后列表会刷新），
    // 每次 build 收敛一次，避免批量装车时把不存在的 detailId 传给后端。
    final pendingIds = sortedReceipts.where((r) => !r.loaded).map((e) => e.detailId).toSet();
    _selected.removeWhere((id) => !pendingIds.contains(id));

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
        // 逆序装车提示 + 全选栏
        if (isLoaded) ...[
          const Alert.warn('📥 请按逆配送顺序装车——后送的货装里侧，先送的货装门口'),
          _selectBar(pendingIds),
        ],
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
                      editable: isLoaded && !r.loaded,
                      selectable: isLoaded && !r.loaded,
                      selected: _selected.contains(r.detailId),
                      compact: true,
                      onSelect: (v) => setState(() {
                        if (v) {
                          _selected.add(r.detailId);
                        } else {
                          _selected.remove(r.detailId);
                        }
                      }),
                      onLoad: (isLoaded && !r.loaded && !_busy)
                          ? () => _confirmLoad(d, [r.detailId], r.customerName)
                          : null,
                      onChanged: (key, v) => setState(() => _loadedQties[key] = v),
                      onScan: (isLoaded && !r.loaded) ? (it) => _scan(d, r, it) : null,
                      onTapBills: () => PointBillsSheet.show(
                        context,
                        dispatchId: d.dispatchId,
                        detailId: r.detailId,
                        customerName: r.customerName,
                      ),
                      onReturn: (isLoaded && canReturnPoint && !r.loaded && !_busy)
                          ? () => _confirmReturnPoint(d, r.detailId, r.customerName)
                          : null,
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
        _bottomBar(d, isAssigned, isLoaded, isDeparted),
      ],
    );
  }

  /// 全选栏：显示装车进度 + 全选/取消全选。
  ///
  /// 只对「未装车」的配送点做全选，已装车的点不再参与勾选，
  /// 避免司机以为要重新勾一遍。
  Widget _selectBar(Set<String> pendingIds) {
    if (pendingIds.isEmpty) return const SizedBox.shrink();
    final allSelected = _selected.length == pendingIds.length;
    return Container(
      color: Colors.white,
      padding: const EdgeInsets.only(left: 6, right: 14),
      child: Row(children: [
        Checkbox(
          value: allSelected,
          visualDensity: VisualDensity.compact,
          onChanged: _busy
              ? null
              : (v) => setState(() {
                    _selected.clear();
                    if (v == true) _selected.addAll(pendingIds);
                  }),
        ),
        Expanded(
          child: Text(
            allSelected ? '取消全选' : '全选（待装 ${pendingIds.length} 个配送点）',
            style: const TextStyle(fontSize: 12, color: TmsTheme.ink),
          ),
        ),
        if (_selected.isNotEmpty)
          Text('已选 ${_selected.length} 个',
              style: const TextStyle(fontSize: 12, color: TmsTheme.accent, fontWeight: FontWeight.w700)),
      ]),
    );
  }

  Widget _statusTag(String status) {
    final text = dispatchStatusText(status);
    switch (status) {
      case 'ASSIGNED':
        return MTag.orange(text);
      case 'ACCEPTED':
        return MTag.blue(text);
      case 'LOADED':
        return MTag.blue(text);
      case 'DEPARTED':
      case 'DELIVERING':
        return MTag.green(text);
      case 'COMPLETED':
        return MTag.gray(text);
      case 'CANCELLED':
        return MTag.red(text);
      default:
        return MTag.gray(text);
    }
  }

  Widget _bottomBar(LoadingDispatch d, bool isAssigned, bool isLoaded, bool isDeparted) {
    return Container(
      decoration: const BoxDecoration(color: Colors.white, border: Border(top: BorderSide(color: TmsTheme.rule))),
      padding: const EdgeInsets.all(14),
      child: SafeArea(
        top: false,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          if (isAssigned)
            TmsButton.primary(_busy ? '处理中...' : '开始装车', onPressed: _busy ? null : () => _action(d, 'start')),
          if (isLoaded) ...[
            // 发车门槛从「全部装完」降为「至少装了一个点」：
            // 支持部分发车后，卡在个别缺货门店上不该拖住整车出发。
            if (d.pendingStoreCount > 0)
              Alert.warn('⚠️ 还有 ${d.pendingStoreCount} 个配送点未装车，发车只会带走已装的 ${d.loadedStoreCount} 个'),
            Row(children: [
              Expanded(child: TmsButton.outline('刷新清单', onPressed: () => ref.invalidate(loadingItemsProvider(widget.dispatchId)))),
              const SizedBox(width: 8),
              Expanded(
                child: TmsButton.primary(
                  _busy
                      ? '处理中...'
                      : _selected.isEmpty
                          ? '全部装车'
                          : '装车（${_selected.length}）',
                  onPressed: (_busy || d.pendingStoreCount == 0)
                      ? null
                      : () => _confirmLoad(d, _selected.toList(), null),
                ),
              ),
            ]),
            const SizedBox(height: 8),
            TmsButton.warn(
              _busy ? '处理中...' : '确认发车，开始配送',
              onPressed: (_busy || !d.anyLoaded) ? null : () => _proceedDepart(d),
            ),
          ],
          if (isDeparted) ...[
            const Alert.ok('✅ 已全部发车，可返回首页查看配送任务'),
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

  /// 确认装车（配送点粒度）。
  ///
  /// [detailIds] 为空表示全部装车——与后端约定一致，省略该字段即全选。
  /// [storeName] 非空时说明是单点装车，toast 里带上门店名让司机确认点对了。
  Future<void> _confirmLoad(LoadingDispatch d, List<String> detailIds, String? storeName) async {
    setState(() => _busy = true);
    try {
      final res = await ref.read(loadingActionProvider(LoadingActionArgs(
        dispatchId: d.dispatchId,
        action: 'confirm',
        detailIds: detailIds,
      )).future);
      _selected.clear();
      ref.invalidate(loadingItemsProvider(d.dispatchId));
      final pending = (res['pendingStoreCount'] as num?)?.toInt() ?? 0;
      final tail = pending > 0 ? '，还剩 $pending 个配送点待装' : '，全部装车完成，可以发车了';
      _toast(storeName != null ? '✅ $storeName 已装车$tail' : '✅ 已确认装车$tail');
    } catch (e) {
      _toast('装车失败：${e.toString().replaceFirst("Exception: ", "")}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _action(LoadingDispatch d, String action, {
    bool force = false,
    num? departMileage,
    String? departPhotoUrl,
  }) async {
    setState(() => _busy = true);
    try {
      final res = await ref.read(loadingActionProvider(
              LoadingActionArgs(
                dispatchId: d.dispatchId,
                action: action,
                force: force,
                departMileage: departMileage,
                departPhotoUrl: departPhotoUrl,
              ))
          .future);

      // 发车完整性校验：后端发现有未装车配送点时返回 needConfirm 而非报错，
      // 此时并未落库。少装可能是合理的（临时缺货、客户改约），所以交给司机决定，
      // 但必须把漏的是哪几个点摆出来——只说「有未装车点」司机没法判断。
      if (action == 'depart' && res['needConfirm'] == true) {
        if (!mounted) return;
        setState(() => _busy = false);
        final ok = await _confirmDepart(res);
        if (ok == true && mounted) {
          await _proceedDepart(d, force: true);
        }
        return;
      }

      ref.invalidate(loadingItemsProvider(d.dispatchId));
      String msg = switch (action) {
        'start' => '已开始装车，装完一个点点一下【装车】',
        'depart' => '已发车，配送开始',
        _ => '操作成功',
      };
      final missed = (res['unloadedCount'] as num?)?.toInt() ?? 0;
      final departed = (res['departCount'] as num?)?.toInt() ?? 0;
      if (action == 'depart' && missed > 0) {
        msg = '已发车 $departed 个配送点，剩 $missed 个未装车，装好后可再次发车';
      }
      _toast(msg);
      if (action == 'depart' && mounted) {
        await _startTracking(d.dispatchId);
        ref.invalidate(todayTasksProvider);
        ref.invalidate(homeOverviewProvider);
        // 发车是门店进入「配送中」的起点，列表必须重取，
        // 否则司机发车后切到【配送中】仍是空的。
        ref.invalidate(deliveringStoresProvider);
        // 部分发车时留在本页继续补装剩余配送点，只有整单发完才退回上一页；
        // 一律 pop 会让司机为了发剩下的点再点进来一次。
        if (mounted && missed == 0) Navigator.pop(context, true);
      }
    } catch (e) {
      _toast('操作失败：${e.toString().replaceFirst("Exception: ", "")}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 未装车配送点确认弹窗。
  ///
  /// 列出具体单号 + 客户 + 数量，让司机能对着车厢核一遍再决定，
  /// 而不是凭一句「有 3 个点没装」猜。
  Future<bool?> _confirmDepart(Map<String, dynamic> res) {
    final list = (res['unloaded'] as List? ?? []).cast<Map<String, dynamic>>();
    return showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('还有配送点未装车', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
        content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(res['message']?.toString() ?? '确认仍要发车？',
              style: const TextStyle(fontSize: 13, color: TmsTheme.ink)),
          const SizedBox(height: 10),
          ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: 220),
            child: SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: list
                    .map((u) => Padding(
                          padding: const EdgeInsets.only(bottom: 6),
                          child: Text(
                            '· ${u['sourceBillNo'] ?? ''}  ${u['customerName'] ?? ''}'
                            '${u['qty'] == null ? '' : '（${u['qty']} 件）'}',
                            style: const TextStyle(fontSize: 12, color: TmsTheme.muted),
                          ),
                        ))
                    .toList(),
              ),
            ),
          ),
          const SizedBox(height: 6),
          const Text('未装车的配送点会留在本页，装好后可再次确认发车。',
              style: TextStyle(fontSize: 11, color: TmsTheme.warning)),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('返回装车')),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('仍要发车', style: TextStyle(color: TmsTheme.danger)),
          ),
        ],
      ),
    );
  }

  /// 发车前置：按系统参数决定是否采集「发车公里数 + 里程照片」，
  /// 采集完（或参数要求为否）再真正调 /depart。
  ///
  /// 未装车确认弹窗（needConfirm）通过后也走这里，force 透传给真正的提交。
  Future<void> _proceedDepart(LoadingDispatch d, {bool force = false}) async {
    num? mileage;
    String? photoUrl;
    if (ParamService.instance.current.departMileageRequired) {
      final got = await showDialog<_DepartMileageResult>(
        context: context,
        barrierDismissible: false,
        builder: (_) => const _DepartMileageDialog(),
      );
      if (got == null) return; // 司机取消，不发车
      mileage = got.mileage;
      photoUrl = got.photoUrl;
    }
    if (!mounted) return;
    await _action(d, 'depart',
        force: force, departMileage: mileage, departPhotoUrl: photoUrl);
  }

  /// 配送点退回调度池：必填原因 → 调 /loading/return-point → 刷新清单。
  ///
  /// 后端按客户编码展开同店全部明细（含取退单）一起退回；若该点是最后一个点，
  /// 调度单会被 CANCELLED，此时直接退回上一页（装车页已无内容可展示）。
  Future<void> _confirmReturnPoint(LoadingDispatch d, String detailId, String storeName) async {
    final controller = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('退回「$storeName」', style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
        content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('该配送点的全部单据会退回调度池重新安排，后台单据将记录「司机退回」。',
              style: TextStyle(fontSize: 12, color: TmsTheme.muted)),
          const SizedBox(height: 10),
          TextField(
            controller: controller,
            maxLines: 3,
            maxLength: 200,
            autofocus: true,
            decoration: const InputDecoration(
              hintText: '请填写退回原因（必填，如：车厢装不下）',
              border: OutlineInputBorder(),
              isDense: true,
            ),
          ),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          TextButton(
            onPressed: () {
              if (controller.text.trim().isEmpty) return;
              Navigator.pop(ctx, true);
            },
            child: const Text('确认退回', style: TextStyle(color: TmsTheme.bad)),
          ),
        ],
      ),
    );
    if (ok != true) return;

    setState(() => _busy = true);
    try {
      final res = await ref.read(returnPointProvider(ReturnPointArgs(
        dispatchId: d.dispatchId,
        detailIds: [detailId],
        reason: controller.text.trim(),
      )).future);
      ref.invalidate(loadingItemsProvider(d.dispatchId));
      ref.invalidate(loadingStoresProvider(d.dispatchId));
      ref.invalidate(todayTasksProvider);
      ref.invalidate(homeOverviewProvider);
      final cancelled = res['cancelled'] == true;
      _toast(cancelled ? '配送点已退回，调度单已无配送点' : '✅ $storeName 已退回调度池');
      if (cancelled && mounted) Navigator.pop(context, true);
    } catch (e) {
      _toast('退回失败：${e.toString().replaceFirst("Exception: ", "")}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 发车成功后启动 GPS 轨迹采集（15s 一次，落本地库后由 SyncService 批量上报）。
  ///
  /// 幂等：LocationService.start 内部已判重，重复点击发车不会启动多个 Timer。
  /// 定位权限被拒时静默降级，不阻断发车主流程。
  Future<void> _startTracking(String dispatchId) async {
    try {
      final driver = ref.read(authProvider);
      if (driver == null) return;
      if (LocationService.instance.isRunning) return;
      await LocationService.instance.start(
        driverId: driver.driverId,
        dispatchId: dispatchId,
      );
    } catch (_) {
      // 定位失败不影响发车结果
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

/// 装车清单中的配送点卡片（含 SKU 行 + 勾选框 + 单点【装车】/【退回】按钮）。
///
/// compact=true 时压缩字号与内边距：装车页一屏要多摆几个配送点，原卡片
/// 标题 13/内边距 12 在 6 寸机上一屏只放得下两张卡，司机要来回滚。
class _ReceiptCard extends StatelessWidget {
  final LoadingReceipt receipt;
  final Map<String, num> loadedQties;
  final bool editable;
  final bool selectable;
  final bool selected;
  final bool compact;
  final ValueChanged<bool> onSelect;
  final VoidCallback? onLoad;
  final VoidCallback? onReturn;
  final VoidCallback? onTapBills;
  final OnLoadedChanged onChanged;
  final void Function(LoadingItem)? onScan;
  const _ReceiptCard({
    required this.receipt,
    required this.loadedQties,
    required this.editable,
    required this.selectable,
    required this.selected,
    required this.onSelect,
    required this.onChanged,
    this.compact = false,
    this.onLoad,
    this.onReturn,
    this.onTapBills,
    this.onScan,
  });

  @override
  Widget build(BuildContext context) {
    final loaded = receipt.items.fold<num>(0, (s, it) => s + (loadedQties['${receipt.detailId}|${it.goodsCode}'] ?? 0));
    final titleSize = compact ? 12.0 : 13.0;
    final subSize = compact ? 10.0 : 11.0;
    return MCard(
      padding: compact ? const EdgeInsets.all(8) : null,
      leftBar: receipt.loaded ? TmsTheme.ok : TmsTheme.accent,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        // 标题行：点击（非勾选区）弹出该配送点的单据+商品清单。
        // 勾选框/按钮各自消费点击，只有标题与单号这一片是「查看单据」热区。
        InkWell(
          onTap: onTapBills,
          borderRadius: BorderRadius.circular(6),
          child: Row(children: [
            if (selectable)
              SizedBox(
                width: 28,
                child: Checkbox(
                  value: selected,
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  onChanged: (v) => onSelect(v == true),
                ),
              ),
            Expanded(
              child: Text('${receipt.seqNo > 0 ? "第${receipt.seqNo}站 " : ""}${receipt.customerName}',
                  style: TextStyle(fontSize: titleSize, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            ),
            if (receipt.loaded) const MTag.green('已装车') else MTag.blue('${fmtQty(receipt.requiredQty)} 件'),
          ]),
        ),
        SizedBox(height: compact ? 1 : 2),
        InkWell(
          onTap: onTapBills,
          borderRadius: BorderRadius.circular(6),
          child: Row(children: [
            Expanded(
              child: Text('${receipt.sourceBillNo} · ${receipt.customerAddress}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: subSize, color: TmsTheme.muted)),
            ),
            if (onTapBills != null)
              Icon(Icons.chevron_right, size: compact ? 14 : 16, color: TmsTheme.muted),
          ]),
        ),
        SizedBox(height: compact ? 4 : 6),
        ...receipt.items.map((it) => _ItemRow(
              item: it,
              loadedQty: loadedQties['${receipt.detailId}|${it.goodsCode}'] ?? it.requiredQty,
              editable: editable,
              onChanged: (v) => onChanged('${receipt.detailId}|${it.goodsCode}', v),
              onScan: onScan == null ? null : () => onScan!(it),
            )),
        SizedBox(height: compact ? 4 : 6),
        Row(children: [
          Expanded(
            child: Text('应装 ${fmtQty(receipt.requiredQty)} 件 · 实装 ${fmtQty(loaded)} 件',
                style: TextStyle(fontSize: subSize, color: TmsTheme.muted)),
          ),
          if (receipt.loaded)
            Text(receipt.loadTime.isEmpty ? '已装车' : '装车 ${receipt.loadTime}',
                style: TextStyle(fontSize: subSize, color: TmsTheme.ok, fontWeight: FontWeight.w700))
          else ...[
            // 装不下时把整个配送点退回调度池；只有未装车且在装车阶段才显示。
            if (onReturn != null) ...[
              TmsButton.outline('退回', size: TmsButtonSize.sm, color: TmsTheme.bad, onPressed: onReturn),
              const SizedBox(width: 6),
            ],
            if (onLoad != null)
              TmsButton.primary('装车', size: TmsButtonSize.sm, onPressed: onLoad),
          ],
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
      padding: const EdgeInsets.symmetric(vertical: 4),
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

/// 发车公里数 + 里程照片采集结果。
class _DepartMileageResult {
  final num mileage;
  final String photoUrl;
  const _DepartMileageResult(this.mileage, this.photoUrl);
}

/// 发车留痕弹窗（V77）：系统参数要求时，确认发车前必须填发车公里数（>0）并拍一张里程表照片。
///
/// 照片在弹窗内即时上传拿到 URL，确认后随 /depart 一起提交；
/// 取消（返回 null）则不发车，回到装车页。
class _DepartMileageDialog extends StatefulWidget {
  const _DepartMileageDialog();

  @override
  State<_DepartMileageDialog> createState() => _DepartMileageDialogState();
}

class _DepartMileageDialogState extends State<_DepartMileageDialog> {
  final TextEditingController _ctrl = TextEditingController();
  String? _photoUrl;
  File? _photoFile;
  bool _uploading = false;
  String? _error;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  Future<void> _takePhoto() async {
    final result = await PhotoService.instance.capture();
    if (result.isFailed) {
      setState(() => _error = result.error);
      return;
    }
    if (!result.isSuccess || result.file == null) return; // 用户取消
    setState(() {
      _uploading = true;
      _error = null;
      _photoFile = File(result.file!.path);
    });
    try {
      final up = await ApiService.instance.uploadImage(_photoFile!, bizType: 'DEPART');
      final url = up['url']?.toString();
      if (url == null || url.isEmpty) throw Exception('上传返回为空');
      setState(() => _photoUrl = url);
    } catch (e) {
      setState(() {
        _photoFile = null;
        _error = '里程照片上传失败：${e.toString().replaceFirst("Exception: ", "")}';
      });
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  void _submit() {
    final m = num.tryParse(_ctrl.text.trim());
    if (m == null || m <= 0) {
      setState(() => _error = '请填写大于 0 的发车公里数');
      return;
    }
    if (_photoUrl == null || _photoUrl!.isEmpty) {
      setState(() => _error = '请拍摄里程表照片');
      return;
    }
    Navigator.pop(context, _DepartMileageResult(m, _photoUrl!));
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('发车留痕', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
      content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        const Text('请填写车辆当前里程表读数，并拍摄一张里程照片，用于本次任务公里数核算。',
            style: TextStyle(fontSize: 12, color: TmsTheme.muted)),
        const SizedBox(height: 12),
        TextField(
          controller: _ctrl,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          autofocus: true,
          decoration: const InputDecoration(
            labelText: '发车公里数',
            hintText: '如 12345',
            suffixText: 'km',
            border: OutlineInputBorder(),
            isDense: true,
          ),
        ),
        const SizedBox(height: 12),
        // 照片区：未拍显示拍照按钮，拍后显示缩略图 + 重拍。
        GestureDetector(
          onTap: _uploading ? null : _takePhoto,
          child: Container(
            height: 140,
            decoration: BoxDecoration(
              color: TmsTheme.primaryLight,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: TmsTheme.rule),
            ),
            child: _uploading
                ? const Center(child: CircularProgressIndicator(strokeWidth: 2))
                : _photoFile != null
                    ? ClipRRect(
                        borderRadius: BorderRadius.circular(8),
                        child: Image.file(_photoFile!, fit: BoxFit.cover, width: double.infinity),
                      )
                    : const Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                        Icon(Icons.add_a_photo_outlined, size: 32, color: TmsTheme.accent),
                        SizedBox(height: 6),
                        Text('点击拍摄里程表照片', style: TextStyle(fontSize: 12, color: TmsTheme.accent)),
                      ]),
          ),
        ),
        if (_photoUrl != null && _photoFile != null)
          const Padding(
            padding: EdgeInsets.only(top: 4),
            child: Row(children: [
              Icon(Icons.check_circle, size: 13, color: TmsTheme.ok),
              SizedBox(width: 4),
              Expanded(child: Text('里程照片已上传，可点击重拍', style: TextStyle(fontSize: 10, color: TmsTheme.ok))),
            ]),
          ),
        if (_error != null) ...[
          const SizedBox(height: 8),
          Text(_error!, style: const TextStyle(fontSize: 12, color: TmsTheme.bad)),
        ],
      ]),
      actions: [
        TextButton(onPressed: _uploading ? null : () => Navigator.pop(context), child: const Text('取消')),
        TextButton(
          onPressed: _uploading ? null : _submit,
          child: const Text('确认发车', style: TextStyle(color: TmsTheme.bad, fontWeight: FontWeight.w700)),
        ),
      ],
    );
  }
}
