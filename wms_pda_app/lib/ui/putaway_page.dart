import 'package:flutter/material.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 入库类型 → 分组 key。PDA 卡片分 全部 / 采购收货 / 销售退货 / 其他。
const Map<String, String> _inboundTypeLabels = {
  'PURCHASE': '采购入库',
  'SALES_RETURN': '销售退货',
  'REJECT': '拒收入库',
  'TRANSFER': '调拨入库',
  'OTHER': '其他入库',
};

const List<Map<String, String>> _groupTabs = [
  {'key': '', 'label': '全部'},
  {'key': 'PURCHASE', 'label': '采购收货'},
  {'key': 'SALES_RETURN', 'label': '销售退货'},
  {'key': '__OTHER__', 'label': '其他'},
];

bool _matchesGroup(String inboundType, String groupKey) {
  if (groupKey.isEmpty) return true;
  if (groupKey == '__OTHER__') {
    return inboundType != 'PURCHASE' && inboundType != 'SALES_RETURN';
  }
  return inboundType == groupKey;
}

/// 上架作业：按 全部/采购收货/销售退货/其他 分组展示；
/// 卡片显示条码、规格、业务类型、收货生产日期、批次号、业务单号、容器编号、收货码、推荐库位；
/// 顶部搜索框支持扫码/输入；支持批量勾选一键上推荐库位；卡片右下"查看库存"弹窗。
class PutawayPage extends StatefulWidget {
  const PutawayPage({super.key});
  @override
  State<PutawayPage> createState() => _PutawayPageState();
}

class _PutawayPageState extends State<PutawayPage> {
  final _svc = WmsAppService.instance;
  final _searchCtrl = TextEditingController();
  List<dynamic> _all = const [];
  String _group = '';
  bool _loading = true;
  bool _selectMode = false;
  final Set<String> _selected = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      // 空 status 拿全部，客户端保留 PENDING/PUTTING
      final all = await _svc.putawayTasks(status: '');
      _all = all.where((t) {
        final s = pickStr(Map<String, dynamic>.from(t as Map), ['status']);
        return s == 'PENDING' || s == 'PUTTING';
      }).toList();
    } catch (e) {
      if (mounted) toast(context, '$e', error: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  List<Map<String, dynamic>> _filtered() {
    final kw = _searchCtrl.text.trim().toLowerCase();
    return _all
        .map((e) => Map<String, dynamic>.from(e as Map))
        .where((m) {
          final t = pickStr(m, ['inboundType', 'inbound_type']);
          if (!_matchesGroup(t, _group)) return false;
          if (kw.isEmpty) return true;
          final hay = [
            pickStr(m, ['goodsName', 'goods_name']),
            pickStr(m, ['goodsCode', 'goods_code']),
            pickStr(m, ['barcode']),
            pickStr(m, ['putawayNo', 'putaway_no']),
            pickStr(m, ['inboundNo', 'inbound_no']),
            pickStr(m, ['sourceOrderNo', 'source_order_no']),
            pickStr(m, ['containerCode', 'container_code']),
            pickStr(m, ['batchNo', 'batch_no']),
            pickStr(m, ['recommendBin', 'recommend_bin']),
          ].join(' ').toLowerCase();
          return hay.contains(kw);
        })
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    final items = _filtered();
    return Scaffold(
      appBar: AppBar(
        title: const Text('上架作业'),
        actions: [
          TextButton(
            onPressed: () {
              setState(() {
                _selectMode = !_selectMode;
                _selected.clear();
              });
            },
            child: Text(_selectMode ? '取消' : '批量上架',
                style: const TextStyle(
                    color: PdaTheme.primary,
                    fontSize: 15,
                    fontWeight: FontWeight.w600)),
          ),
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(96),
          child: Column(children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
              child: _searchBar(),
            ),
            _groupTabBar(items),
          ]),
        ),
      ),
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _load,
          color: PdaTheme.primary,
          child: _buildBody(items),
        ),
      ),
      bottomNavigationBar: _selectMode && _selected.isNotEmpty
          ? _batchBar(items)
          : null,
    );
  }

  Widget _searchBar() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10),
      decoration: BoxDecoration(
        color: PdaTheme.surface,
        borderRadius: BorderRadius.circular(PdaSpacing.radius),
        border: Border.all(color: PdaTheme.border),
      ),
      child: Row(children: [
        const Icon(Icons.search, color: PdaTheme.textSecondary, size: 20),
        const SizedBox(width: 6),
        Expanded(
          child: TextField(
            controller: _searchCtrl,
            style: const TextStyle(fontSize: 14, color: PdaTheme.textPrimary),
            decoration: const InputDecoration(
              isDense: true,
              border: InputBorder.none,
              hintText: '扫描商品条码、货位码或单号',
              hintStyle:
                  TextStyle(fontSize: 13, color: PdaTheme.textSecondary),
            ),
            onChanged: (_) => setState(() {}),
            onSubmitted: _findAndOpen,
          ),
        ),
        if (_searchCtrl.text.isNotEmpty)
          GestureDetector(
            onTap: () {
              _searchCtrl.clear();
              setState(() {});
            },
            child: const Icon(Icons.close,
                size: 16, color: PdaTheme.textSecondary),
          ),
      ]),
    );
  }

  Widget _groupTabBar(List<Map<String, dynamic>> items) {
    int countFor(String key) =>
        items.where((m) => _matchesGroup(
            pickStr(m, ['inboundType', 'inbound_type']), key)).length;
    return Row(
      children: _groupTabs.map((t) {
        final selected = _group == t['key'];
        final n = countFor(t['key']!);
        return Expanded(
          child: InkWell(
            onTap: () => setState(() => _group = t['key']!),
            child: Container(
              padding: const EdgeInsets.symmetric(vertical: 10),
              decoration: BoxDecoration(
                border: Border(
                  bottom: BorderSide(
                    color: selected ? PdaTheme.primary : Colors.transparent,
                    width: 2,
                  ),
                ),
              ),
              child: Text(
                '${t['label']}${n > 0 ? ' $n' : ''}',
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 14,
                  color: selected ? PdaTheme.primary : PdaTheme.textSecondary,
                  fontWeight: selected ? FontWeight.w600 : FontWeight.normal,
                ),
              ),
            ),
          ),
        );
      }).toList(),
    );
  }

  Widget _buildBody(List<Map<String, dynamic>> items) {
    if (_loading) {
      return ListView(children: const [
        SizedBox(
            height: 240,
            child: Center(
                child: CircularProgressIndicator(color: PdaTheme.primary)))
      ]);
    }
    if (items.isEmpty) {
      return ListView(children: const [
        SizedBox(
            height: 240,
            child:
                Center(child: Text('暂无待上架任务', style: PdaStyles.sub)))
      ]);
    }
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
      itemCount: items.length,
      itemBuilder: (_, i) => _card(items[i]),
    );
  }

  Widget _card(Map<String, dynamic> m) {
    final pid = pickStr(m, ['putawayId', 'putaway_id']);
    final goodsName = pickStr(m, ['goodsName', 'goods_name'], '未命名商品');
    final barcode = pickStr(m, ['barcode']);
    final spec = pickStr(m, ['spec']);
    final unit = pickStr(m, ['unitName', 'unit_name'], '');
    final qty = pickNum(m, ['qty']);
    final inboundType = pickStr(m, ['inboundType', 'inbound_type'], 'OTHER');
    final typeLabel = _inboundTypeLabels[inboundType] ?? inboundType;
    final productionDate = _fmtDate(pickStr(m, ['productionDate', 'production_date']));
    final batchNo = pickStr(m, ['batchNo', 'batch_no']);
    final sourceOrderNo = pickStr(m, ['sourceOrderNo', 'source_order_no']);
    final container = pickStr(m, ['containerCode', 'container_code']);
    final putawayNo = pickStr(m, ['putawayNo', 'putaway_no']);
    final recBin = pickStr(m, ['recommendBin', 'recommend_bin']);
    final recZone = pickStr(m, ['recommendZoneName', 'recommend_zone_name']);
    final recProp = pickStr(m, ['recommendStorageProp', 'recommend_storage_prop']);
    final status = pickStr(m, ['status']);
    final claimed = pickStr(m, ['assignee']).isNotEmpty || status == 'PUTTING';
    final checked = _selected.contains(pid);

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: PdaTheme.surface,
        borderRadius: BorderRadius.circular(PdaSpacing.radius),
        border: Border.all(
          color: checked ? PdaTheme.primary : PdaTheme.border,
          width: checked ? 1.5 : 1,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 标题行
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 12, 12, 6),
            child: Row(children: [
              if (_selectMode)
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: SizedBox(
                    width: 22, height: 22,
                    child: Checkbox(
                      value: checked,
                      activeColor: PdaTheme.primary,
                      onChanged: recBin.isEmpty
                          ? null
                          : (v) => setState(() {
                                if (v == true) {
                                  _selected.add(pid);
                                } else {
                                  _selected.remove(pid);
                                }
                              }),
                    ),
                  ),
                ),
              Expanded(
                child: Text(goodsName,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 17,
                        fontWeight: FontWeight.w600,
                        color: PdaTheme.textPrimary)),
              ),
              if (recProp.isNotEmpty)
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: _propColor(recProp).withValues(alpha: 0.18),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: Text(recProp,
                      style: TextStyle(
                          fontSize: 11, color: _propColor(recProp))),
                ),
            ]),
          ),
          // 条码
          if (barcode.isNotEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Row(children: [
                const Text('条码：', style: PdaStyles.label),
                Expanded(
                  child: Text(barcode,
                      style: const TextStyle(
                          fontSize: 15,
                          color: PdaTheme.textPrimary,
                          fontWeight: FontWeight.w600)),
                ),
              ]),
            ),
          const SizedBox(height: 6),
          // 上架数量 + 规格
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Row(children: [
              const Text('上架数量：', style: PdaStyles.label),
              Text('$qty',
                  style: const TextStyle(
                      fontSize: 22,
                      fontWeight: FontWeight.bold,
                      color: PdaTheme.danger)),
              Text(' $unit',
                  style: const TextStyle(
                      fontSize: 14, color: PdaTheme.textPrimary)),
              const Spacer(),
              if (spec.isNotEmpty) ...[
                const Text('规格：', style: PdaStyles.label),
                Text(spec,
                    style: const TextStyle(
                        fontSize: 14, color: PdaTheme.textPrimary)),
              ],
            ]),
          ),
          const SizedBox(height: 8),
          // 三列：业务类型 / 生产日期 / 批次号
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Row(children: [
              Expanded(
                  child: _kv('业务类型', typeLabel,
                      color: _typeColor(inboundType))),
              Expanded(
                  child: _kv('生产日期', productionDate.isEmpty ? '—' : productionDate)),
              Expanded(
                  child: _kv('批次号', batchNo.isEmpty ? '—' : batchNo)),
            ]),
          ),
          const SizedBox(height: 6),
          // 单号 + 容器 + 收货码
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Row(children: [
              const Text('单号：', style: PdaStyles.label),
              Expanded(
                child: Text(sourceOrderNo.isEmpty ? '—' : sourceOrderNo,
                    style: const TextStyle(
                        fontSize: 14, color: PdaTheme.textPrimary)),
              ),
              if (container.isNotEmpty) ...[
                const Icon(Icons.inventory_2_outlined,
                    size: 14, color: PdaTheme.info),
                const SizedBox(width: 3),
                Text(container,
                    style:
                        const TextStyle(fontSize: 13, color: PdaTheme.info)),
                const SizedBox(width: 10),
              ],
            ]),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 2, 12, 8),
            child: Row(children: [
              const Text('收货码：', style: PdaStyles.label),
              Text(putawayNo.isEmpty ? '—' : putawayNo,
                  style: const TextStyle(
                      fontSize: 13, color: PdaTheme.textPrimary)),
            ]),
          ),
          const Divider(height: 1, color: PdaTheme.border),
          // 底部：推荐库位 + 查看库存 / 确认上架
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 10),
            child: Row(children: [
              const Icon(Icons.location_on_outlined,
                  size: 18, color: PdaTheme.textSecondary),
              const SizedBox(width: 4),
              const Text('推荐货位：', style: PdaStyles.label),
              const SizedBox(width: 4),
              Expanded(
                child: Text(
                  recBin.isEmpty
                      ? '未推荐，请扫码上架'
                      : '$recBin${recZone.isEmpty ? '' : ' · $recZone'}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: recBin.isEmpty
                        ? PdaTheme.textSecondary
                        : PdaTheme.primary,
                  ),
                ),
              ),
              if (!_selectMode) ...[
                TextButton.icon(
                  onPressed: () => _showStock(m),
                  icon: const Icon(Icons.visibility_outlined, size: 18),
                  label: const Text('查看库存'),
                  style: TextButton.styleFrom(
                    foregroundColor: PdaTheme.textSecondary,
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                  ),
                ),
                const SizedBox(width: 4),
                claimed
                    ? ElevatedButton.icon(
                        style: ElevatedButton.styleFrom(
                          minimumSize: const Size(96, 40),
                          padding: const EdgeInsets.symmetric(horizontal: 12),
                        ),
                        icon: const Icon(Icons.check, size: 18),
                        label: const Text('确认上架'),
                        onPressed: () => _confirmDialog(pid, recBin),
                      )
                    : OutlinedButton.icon(
                        style: OutlinedButton.styleFrom(
                          minimumSize: const Size(96, 40),
                          padding: const EdgeInsets.symmetric(horizontal: 12),
                        ),
                        icon: const Icon(Icons.play_arrow, size: 18),
                        label: const Text('领取'),
                        onPressed: () async {
                          await runWithBusy(
                            context,
                            () => _svc.putawayClaim(pid),
                            successMsg: '已领取',
                          );
                          _load();
                        },
                      ),
              ],
            ]),
          ),
        ],
      ),
    );
  }

  Widget _kv(String label, String value, {Color? color}) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: PdaStyles.label),
        const SizedBox(height: 2),
        Text(value,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
                fontSize: 14,
                color: color ?? PdaTheme.textPrimary,
                fontWeight: FontWeight.w600)),
      ],
    );
  }

  Widget _batchBar(List<Map<String, dynamic>> items) {
    final canBatch =
        _selected.every((id) => _recBinOf(id, items).isNotEmpty);
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: const BoxDecoration(
        color: PdaTheme.surface,
        border: Border(top: BorderSide(color: PdaTheme.border)),
      ),
      child: Row(children: [
        TextButton(
          onPressed: () {
            final all = items
                .where((m) =>
                    pickStr(m, ['recommendBin', 'recommend_bin']).isNotEmpty)
                .map((m) => pickStr(m, ['putawayId', 'putaway_id']))
                .toSet();
            setState(() {
              if (_selected.containsAll(all)) {
                _selected.clear();
              } else {
                _selected
                  ..clear()
                  ..addAll(all);
              }
            });
          },
          child: Text(
            _selected.length == items.where((m) => pickStr(m,['recommendBin','recommend_bin']).isNotEmpty).length
                ? '取消全选'
                : '全选（有推荐位）',
          ),
        ),
        const Spacer(),
        Text('已选 ${_selected.length} 项',
            style: PdaStyles.label),
        const SizedBox(width: 10),
        ElevatedButton.icon(
          style: ElevatedButton.styleFrom(
            minimumSize: const Size(140, 44),
          ),
          icon: const Icon(Icons.check_circle_outline, size: 20),
          label: const Text('批量上架'),
          onPressed: canBatch ? _doBatch : null,
        ),
      ]),
    );
  }

  String _recBinOf(String pid, List<Map<String, dynamic>> items) {
    for (final m in items) {
      if (pickStr(m, ['putawayId', 'putaway_id']) == pid) {
        return pickStr(m, ['recommendBin', 'recommend_bin']);
      }
    }
    return '';
  }

  Future<void> _doBatch() async {
    final ids = _selected.toList();
    if (ids.isEmpty) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        title: const Text('批量上架'),
        content: Text(
            '将把选中的 ${ids.length} 个任务一次性上到各自的推荐库位。\n无推荐库位的任务请单独扫码上架。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          ElevatedButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('确认')),
        ],
      ),
    );
    if (ok != true) return;
    if (!mounted) return;
    final r = await runWithBusy(
      context,
      () => _svc.putawayBatchConfirm(ids),
      busyMsg: '正在批量上架...',
    );
    if (r == null) return;
    final done = pickNum(r, ['doneCount']);
    final skipped = (r['skipped'] as List?) ?? const [];
    if (!mounted) return;
    if (skipped.isNotEmpty) {
      String msg = '成功 $done 项，跳过 ${skipped.length} 项：\n';
      msg += skipped
          .take(5)
          .map((e) =>
              '· ${(e as Map)['putawayId']}：${e['reason']}')
          .join('\n');
      if (skipped.length > 5) msg += '\n...';
      showDialog(
        context: context,
        builder: (ctx) => AlertDialog(
          backgroundColor: PdaTheme.surface,
          title: const Text('批量上架结果'),
          content: SingleChildScrollView(child: Text(msg)),
          actions: [
            ElevatedButton(
                onPressed: () => Navigator.pop(ctx),
                child: const Text('知道了'))
          ],
        ),
      );
    } else {
      toast(context, '✅ 批量上架成功 $done 项');
    }
    setState(() {
      _selectMode = false;
      _selected.clear();
    });
    _load();
  }

  Future<void> _findAndOpen(String keyword) async {
    final hit = _all.firstWhere(
      (t) {
        final m = Map<String, dynamic>.from(t as Map);
        return pickStr(m, ['putawayNo', 'putaway_no']).contains(keyword) ||
            pickStr(m, ['inboundNo', 'inbound_no']).contains(keyword) ||
            pickStr(m, ['sourceOrderNo', 'source_order_no'])
                .contains(keyword) ||
            pickStr(m, ['barcode']).contains(keyword) ||
            pickStr(m, ['goodsCode', 'goods_code']).contains(keyword);
      },
      orElse: () => null,
    );
    if (hit == null) {
      if (mounted) toast(context, '未在待上架列表找到：$keyword', error: true);
      return;
    }
    final m = Map<String, dynamic>.from(hit as Map);
    final pid = pickStr(m, ['putawayId', 'putaway_id']);
    final claimed = pickStr(m, ['assignee']).isNotEmpty;
    if (!claimed) {
      try {
        await _svc.putawayClaim(pid);
      } catch (e) {
        if (mounted) toast(context, '$e', error: true);
        return;
      }
    }
    if (mounted) {
      _confirmDialog(pid, pickStr(m, ['recommendBin', 'recommend_bin']));
    }
  }

  Future<void> _confirmDialog(String pid, String recommendBin) async {
    final ctrl = TextEditingController(text: recommendBin);
    await showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        title: const Text('确认上架'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (recommendBin.isNotEmpty)
              PdaAlert.info('推荐库位：$recommendBin，扫其他库位表示实际入位不同。')
            else
              PdaAlert.warning('该任务无推荐库位，请扫目标库位条码'),
            const SizedBox(height: 12),
            TextField(
              controller: ctrl,
              autofocus: true,
              decoration: const InputDecoration(
                  labelText: '目标库位（空=使用推荐）',
                  prefixIcon: Icon(Icons.qr_code_scanner)),
              onSubmitted: (v) async {
                Navigator.pop(ctx);
                await runWithBusy(
                  context,
                  () => _svc.putawayConfirm(pid, actualBin: ctrl.text.trim()),
                  successMsg: '上架成功，库存已更新',
                );
                _load();
              },
            ),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('取消')),
          ElevatedButton(
            onPressed: () async {
              Navigator.pop(ctx);
              await runWithBusy(
                context,
                () => _svc.putawayConfirm(pid, actualBin: ctrl.text.trim()),
                successMsg: '上架成功，库存已更新',
              );
              _load();
            },
            child: const Text('确认上架'),
          ),
        ],
      ),
    );
  }

  Future<void> _showStock(Map<String, dynamic> m) async {
    final goodsCode = pickStr(m, ['goodsCode', 'goods_code']);
    final goodsName = pickStr(m, ['goodsName', 'goods_name'], '未命名商品');
    final warehouse = pickStr(m, ['warehouse']);
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => const Center(
        child: SizedBox(
          width: 40, height: 40,
          child: CircularProgressIndicator(color: PdaTheme.primary),
        ),
      ),
    );
    List<dynamic> rows = const [];
    String? err;
    try {
      rows = await _svc.putawayBinStock(goodsCode, warehouse: warehouse);
    } catch (e) {
      err = '$e';
    }
    if (!mounted) return;
    Navigator.of(context, rootNavigator: true).pop();
    await showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(goodsName,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: PdaStyles.title),
            const SizedBox(height: 2),
            Text('当前库存分布', style: PdaStyles.sub),
          ],
        ),
        content: SizedBox(
          width: double.maxFinite,
          child: err != null
              ? Text('查询失败：$err',
                  style: const TextStyle(color: PdaTheme.danger))
              : rows.isEmpty
                  ? const Padding(
                      padding: EdgeInsets.all(20),
                      child: Center(
                          child: Text('暂无可售库存', style: PdaStyles.sub)),
                    )
                  : ListView.separated(
                      shrinkWrap: true,
                      itemCount: rows.length,
                      separatorBuilder: (_, __) => const Divider(
                          height: 1, color: PdaTheme.border),
                      itemBuilder: (_, i) {
                        final s = Map<String, dynamic>.from(rows[i] as Map);
                        final bin = pickStr(s, ['binCode', 'bin_code']);
                        final zone = pickStr(s, ['zoneName', 'zone_name']);
                        final prod = _fmtDate(
                            pickStr(s, ['productionDate', 'production_date']));
                        final batch = pickStr(s, ['batchNo', 'batch_no']);
                        final qty = pickNum(s, ['qty']);
                        final container =
                            pickStr(s, ['containerCode', 'container_code']);
                        return Padding(
                          padding: const EdgeInsets.symmetric(vertical: 8),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Row(children: [
                                const Icon(Icons.warehouse_outlined,
                                    size: 16, color: PdaTheme.primary),
                                const SizedBox(width: 4),
                                Text(bin,
                                    style: const TextStyle(
                                        fontSize: 15,
                                        fontWeight: FontWeight.w600,
                                        color: PdaTheme.primary)),
                                if (zone.isNotEmpty) ...[
                                  const SizedBox(width: 6),
                                  Text('· $zone', style: PdaStyles.sub),
                                ],
                                const Spacer(),
                                Text('$qty',
                                    style: const TextStyle(
                                        fontSize: 18,
                                        fontWeight: FontWeight.bold,
                                        color: PdaTheme.textPrimary)),
                              ]),
                              const SizedBox(height: 3),
                              Wrap(spacing: 12, children: [
                                if (prod.isNotEmpty)
                                  Text('生产日期：$prod',
                                      style: PdaStyles.sub),
                                if (batch.isNotEmpty)
                                  Text('批次：$batch', style: PdaStyles.sub),
                                if (container.isNotEmpty)
                                  Text('容器：$container', style: PdaStyles.sub),
                              ]),
                            ],
                          ),
                        );
                      },
                    ),
        ),
        actions: [
          ElevatedButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('关闭')),
        ],
      ),
    );
  }

  String _fmtDate(String raw) {
    if (raw.length >= 10) return raw.substring(0, 10);
    return raw;
  }

  Color _propColor(String p) {
    switch (p) {
      case '冷藏':
        return PdaTheme.info;
      case '冷冻':
        return const Color(0xFF64B5F6);
      case '恒温':
        return PdaTheme.warning;
      case '避光':
        return const Color(0xFFAB47BC);
      default:
        return PdaTheme.primary;
    }
  }

  Color _typeColor(String t) => switch (t) {
        'PURCHASE' => PdaTheme.primary,
        'SALES_RETURN' => PdaTheme.warning,
        'REJECT' => PdaTheme.danger,
        'TRANSFER' => PdaTheme.info,
        _ => PdaTheme.textSecondary,
      };
}
