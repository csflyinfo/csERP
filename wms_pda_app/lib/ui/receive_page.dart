import 'package:flutter/material.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 入库类型标签：与后端 WmsInboundService 约定一致。
const Map<String, String> _inboundTypeLabels = {
  'PURCHASE': '采购收货',
  'SALES_RETURN': '销售退货',
  'REJECT': '拒收入库',
  'TRANSFER': '调拨入库',
  'OTHER': '其他入库',
};

const List<Map<String, String>> _typeTabs = [
  {'key': '', 'label': '全部'},
  {'key': 'PURCHASE', 'label': '采购'},
  {'key': 'SALES_RETURN', 'label': '退货'},
  {'key': 'TRANSFER', 'label': '调拨'},
  {'key': 'REJECT', 'label': '拒收'},
  {'key': 'OTHER', 'label': '其他'},
];

Color _typeColor(String t) => switch (t) {
      'PURCHASE' => PdaTheme.primary,
      'SALES_RETURN' => PdaTheme.warning,
      'REJECT' => PdaTheme.danger,
      'TRANSFER' => PdaTheme.info,
      _ => PdaTheme.textSecondary,
    };

/// 收货任务列表：类型 TAB + 搜索框（供应商/单据号/商品名/条码）。
class ReceivePage extends StatefulWidget {
  const ReceivePage({super.key});
  @override
  State<ReceivePage> createState() => _ReceivePageState();
}

class _ReceivePageState extends State<ReceivePage> {
  final _svc = WmsAppService.instance;
  final _searchCtrl = TextEditingController();
  String _type = '';
  List<dynamic> _tasks = const [];
  bool _loading = true;

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
      _tasks = await _svc.inboundTasks(
        status: 'PENDING',
        inboundType: _type,
        keyword: _searchCtrl.text.trim(),
      );
    } catch (e) {
      if (mounted) toast(context, '$e', error: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    // 不用 PdaScaffold：它会把 body 包进 ListView，与内部 Expanded 冲突导致列表空白。
    return Scaffold(
      appBar: AppBar(title: const Text('收货作业')),
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 6),
              child: _searchBar(),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: _typeTabBar(),
            ),
            const SizedBox(height: 8),
            Expanded(
              child: RefreshIndicator(
                onRefresh: _load,
                color: PdaTheme.primary,
                child: _buildList(),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _searchBar() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        color: PdaTheme.surface,
        borderRadius: BorderRadius.circular(PdaSpacing.radius),
        border: Border.all(color: PdaTheme.border),
      ),
      child: Row(children: [
        const Icon(Icons.search, color: PdaTheme.textSecondary, size: 22),
        const SizedBox(width: 8),
        Expanded(
          child: TextField(
            controller: _searchCtrl,
            textInputAction: TextInputAction.search,
            style: const TextStyle(fontSize: 15, color: PdaTheme.textPrimary),
            decoration: const InputDecoration(
              isDense: true,
              border: InputBorder.none,
              hintText: '供应商 / 单据号 / 商品名 / 条码',
              hintStyle: TextStyle(fontSize: 14, color: PdaTheme.textSecondary),
            ),
            onSubmitted: (_) => _load(),
          ),
        ),
        if (_searchCtrl.text.isNotEmpty)
          IconButton(
            visualDensity: VisualDensity.compact,
            icon: const Icon(Icons.close, size: 18, color: PdaTheme.textSecondary),
            onPressed: () {
              _searchCtrl.clear();
              _load();
            },
          ),
        ElevatedButton(
          style: ElevatedButton.styleFrom(
            minimumSize: const Size(64, 40),
            padding: const EdgeInsets.symmetric(horizontal: 12),
          ),
          onPressed: _load,
          child: const Text('查询'),
        ),
      ]),
    );
  }

  Widget _typeTabBar() {
    return SizedBox(
      height: 34,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: _typeTabs.length,
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemBuilder: (_, i) {
          final t = _typeTabs[i];
          final selected = _type == t['key'];
          return ChoiceChip(
            label: Text(t['label']!, style: const TextStyle(fontSize: 13)),
            selected: selected,
            selectedColor: PdaTheme.primary,
            backgroundColor: PdaTheme.surface,
            labelStyle: TextStyle(
              color: selected ? Colors.white : PdaTheme.textPrimary,
              fontWeight: selected ? FontWeight.w600 : FontWeight.normal,
            ),
            side: BorderSide(color: selected ? PdaTheme.primary : PdaTheme.border),
            shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(17)),
            onSelected: (_) {
              setState(() => _type = t['key']!);
              _load();
            },
          );
        },
      ),
    );
  }

  Widget _buildList() {
    if (_loading) {
      // 包一层 ListView 让 RefreshIndicator 有滚动祖先
      return ListView(
        children: const [
          SizedBox(
              height: 200,
              child: Center(
                  child: CircularProgressIndicator(color: PdaTheme.primary)))
        ],
      );
    }
    if (_tasks.isEmpty) {
      return ListView(
        children: const [
          SizedBox(
              height: 200,
              child:
                  Center(child: Text('暂无待收货任务', style: PdaStyles.sub)))
        ],
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(12, 4, 12, 12),
      itemCount: _tasks.length,
      itemBuilder: (_, i) {
        final m = Map<String, dynamic>.from(_tasks[i] as Map);
        final type = pickStr(m, ['inboundType', 'inbound_type'], 'OTHER');
        final typeLabel = _inboundTypeLabels[type] ?? type;
        final partner = pickStr(m, ['supplierName', 'supplier_name']).isNotEmpty
            ? pickStr(m, ['supplierName', 'supplier_name'])
            : pickStr(m, ['customerName', 'customer_name']);
        final skuTotal = pickNum(m, ['skuTotal', 'sku_total']);
        final skuReceived = pickNum(m, ['skuReceived', 'sku_received']);
        final color = _typeColor(type);
        return Card(
          margin: const EdgeInsets.only(bottom: 8),
          child: InkWell(
            borderRadius: BorderRadius.circular(12),
            onTap: () async {
              await Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (_) => ReceiveDetailPage(
                    taskId: m['taskId'].toString(),
                    taskNo: pickStr(m, ['taskNo', 'task_no']),
                  ),
                ),
              );
              _load();
            },
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(children: [
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                      decoration: BoxDecoration(
                        color: color.withValues(alpha: 0.16),
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Text(typeLabel,
                          style: TextStyle(
                              fontSize: 12,
                              color: color,
                              fontWeight: FontWeight.w600)),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(pickStr(m, ['taskNo', 'task_no']),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: PdaStyles.title),
                    ),
                    const Icon(Icons.chevron_right,
                        color: PdaTheme.textSecondary, size: 20),
                  ]),
                  const SizedBox(height: 6),
                  Text(partner.isEmpty ? '—' : partner,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 13, color: PdaTheme.textPrimary)),
                  const SizedBox(height: 2),
                  Text('来源：${pickStr(m, ['sourceOrderNo', 'source_order_no'], '—')}',
                      style: PdaStyles.sub),
                  const SizedBox(height: 6),
                  Row(children: [
                    _miniStat('总SKU', '$skuTotal', PdaTheme.textPrimary),
                    const SizedBox(width: 12),
                    _miniStat('已收', '$skuReceived', PdaTheme.primary),
                    const SizedBox(width: 12),
                    _miniStat('待收', '${skuTotal - skuReceived}', PdaTheme.warning),
                  ]),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _miniStat(String label, String value, Color color) {
    return Row(children: [
      Text(value,
          style: TextStyle(
              fontSize: 15, fontWeight: FontWeight.bold, color: color)),
      const SizedBox(width: 3),
      Text(label, style: PdaStyles.sub),
    ]);
  }
}

// ============================================================
// 收货详情：SKU 统计 + 仓库紧凑卡 + 商品搜索 + 待收/已收 TAB
// ============================================================

class ReceiveDetailPage extends StatefulWidget {
  final String taskId;
  final String taskNo;
  const ReceiveDetailPage({super.key, required this.taskId, required this.taskNo});

  @override
  State<ReceiveDetailPage> createState() => _ReceiveDetailPageState();
}

class _ReceiveDetailPageState extends State<ReceiveDetailPage>
    with SingleTickerProviderStateMixin {
  final _svc = WmsAppService.instance;
  final _goodsSearchCtrl = TextEditingController();
  late final TabController _tab = TabController(length: 2, vsync: this);
  Map<String, dynamic>? _detail;
  String _currentContainer = '';
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _tab.dispose();
    _goodsSearchCtrl.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      _detail = await _svc.inboundDetail(widget.taskId);
      // 从明细里挑一个已绑定的容器作为当前容器（详情页顶部展示）
      final lines = (_detail!['details'] as List?) ?? const [];
      String c = '';
      for (final raw in lines) {
        final l = Map<String, dynamic>.from(raw as Map);
        final v = pickStr(l, ['containerCode', 'container_code']);
        if (v.isNotEmpty) { c = v; break; }
      }
      _currentContainer = c;
    } catch (e) {
      if (mounted) toast(context, '$e', error: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  List<Map<String, dynamic>> _filteredLines(bool received) {
    final all = ((_detail?['details'] as List?) ?? const [])
        .map((e) => Map<String, dynamic>.from(e as Map))
        .toList();
    final kw = _goodsSearchCtrl.text.trim().toLowerCase();
    bool match(Map<String, dynamic> l) {
      final got = pickNum(l, ['receivedQty', 'received_qty']);
      final isReceived = got > 0;
      if (received != isReceived) return false;
      if (kw.isEmpty) return true;
      final hay = [
        pickStr(l, ['goodsName', 'goods_name']),
        pickStr(l, ['goodsCode', 'goods_code']),
        pickStr(l, ['barcode']),
        pickStr(l, ['spec']),
      ].join(' ').toLowerCase();
      return hay.contains(kw);
    }
    return all.where(match).toList();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.taskNo.isEmpty ? '收货详情' : widget.taskNo),
        actions: [
          IconButton(
            icon: const Icon(Icons.inventory_2_outlined),
            tooltip: '容器收货',
            onPressed: _showContainerSheet,
          ),
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _load,
          ),
        ],
        bottom: TabBar(
          controller: _tab,
          indicatorColor: PdaTheme.primary,
          labelColor: PdaTheme.primary,
          unselectedLabelColor: PdaTheme.textSecondary,
          tabs: const [Tab(text: '待收'), Tab(text: '已收')],
        ),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator(color: PdaTheme.primary))
          : SafeArea(
              child: Column(children: [
                _header(),
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 10, 12, 6),
                  child: _goodsSearchBar(),
                ),
                Expanded(
                  child: TabBarView(
                    controller: _tab,
                    children: [
                      _lineList(_filteredLines(false), received: false),
                      _lineList(_filteredLines(true), received: true),
                    ],
                  ),
                ),
              ]),
            ),
      bottomNavigationBar: _detail == null
          ? null
          : Container(
              padding: const EdgeInsets.all(12),
              decoration: const BoxDecoration(
                color: PdaTheme.surface,
                border: Border(top: BorderSide(color: PdaTheme.border)),
              ),
              child: Row(children: [
                Expanded(
                  child: OutlinedButton.icon(
                    icon: const Icon(Icons.qr_code_2, size: 20),
                    label: Text(_currentContainer.isEmpty
                        ? '绑定容器'
                        : '容器：$_currentContainer'),
                    onPressed: _showContainerSheet,
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  flex: 2,
                  child: ElevatedButton.icon(
                    icon: const Icon(Icons.check_circle_outline),
                    label: const Text('完成收货'),
                    onPressed: _finish,
                  ),
                ),
              ]),
            ),
    );
  }

  Widget _header() {
    final m = _detail!;
    final type = pickStr(m, ['inboundType', 'inbound_type'], 'OTHER');
    final partner = pickStr(m, ['supplierName', 'supplier_name']).isNotEmpty
        ? pickStr(m, ['supplierName', 'supplier_name'])
        : pickStr(m, ['customerName', 'customer_name']);
    final skuTotal = pickNum(m, ['skuTotal', 'sku_total']);
    final skuReceived = pickNum(m, ['skuReceived', 'sku_received']);
    final skuPending = pickNum(m, ['skuPending', 'sku_pending']);
    final color = _typeColor(type);
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: const BoxDecoration(
        color: PdaTheme.surface,
        border: Border(bottom: BorderSide(color: PdaTheme.border)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.16),
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(_inboundTypeLabels[type] ?? type,
                  style: TextStyle(
                      fontSize: 12,
                      color: color,
                      fontWeight: FontWeight.w600)),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(partner.isEmpty ? '—' : partner,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                      fontSize: 14,
                      color: PdaTheme.textPrimary,
                      fontWeight: FontWeight.w600)),
            ),
          ]),
          const SizedBox(height: 8),
          // SKU 维度统计：已收 / 总 / 待收（按需求不显示单据待收货数量）
          Row(children: [
            _skuBox('已收 SKU', '$skuReceived', PdaTheme.primary),
            const SizedBox(width: 8),
            _skuBox('总 SKU', '$skuTotal', PdaTheme.textPrimary),
            const SizedBox(width: 8),
            _skuBox('待收 SKU', '$skuPending', PdaTheme.warning),
            const Spacer(),
            // 仓库紧凑展示
            const Icon(Icons.warehouse_outlined,
                size: 16, color: PdaTheme.textSecondary),
            const SizedBox(width: 4),
            Text(pickStr(m, ['warehouse'], '总仓'),
                style: PdaStyles.sub),
          ]),
        ],
      ),
    );
  }

  Widget _skuBox(String label, String value, Color color) {
    return Row(children: [
      Text(value,
          style: TextStyle(
              fontSize: 20, fontWeight: FontWeight.bold, color: color)),
      const SizedBox(width: 4),
      Text(label, style: PdaStyles.sub),
    ]);
  }

  Widget _goodsSearchBar() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10),
      decoration: BoxDecoration(
        color: PdaTheme.surface2,
        borderRadius: BorderRadius.circular(PdaSpacing.radius),
        border: Border.all(color: PdaTheme.border),
      ),
      child: Row(children: [
        const Icon(Icons.qr_code_scanner, color: PdaTheme.primary, size: 20),
        const SizedBox(width: 6),
        Expanded(
          child: TextField(
            controller: _goodsSearchCtrl,
            style: const TextStyle(fontSize: 14, color: PdaTheme.textPrimary),
            decoration: const InputDecoration(
              isDense: true,
              border: InputBorder.none,
              hintText: '扫商品条码 / 输入名称 / 编码',
              hintStyle: TextStyle(fontSize: 13, color: PdaTheme.textSecondary),
            ),
            onChanged: (_) => setState(() {}),
            onSubmitted: (_) => setState(() {}),
          ),
        ),
        if (_goodsSearchCtrl.text.isNotEmpty)
          GestureDetector(
            onTap: () {
              _goodsSearchCtrl.clear();
              setState(() {});
            },
            child: const Icon(Icons.close,
                size: 16, color: PdaTheme.textSecondary),
          ),
      ]),
    );
  }

  Widget _lineList(List<Map<String, dynamic>> lines, {required bool received}) {
    if (lines.isEmpty) {
      return Center(
        child: Text(received ? '暂无已收商品' : '暂无待收商品',
            style: PdaStyles.sub),
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(12, 4, 12, 12),
      itemCount: lines.length,
      itemBuilder: (_, i) => _lineCard(lines[i], received: received),
    );
  }

  Widget _lineCard(Map<String, dynamic> l, {required bool received}) {
    final name = pickStr(l, ['goodsName', 'goods_name'], '未命名商品');
    final code = pickStr(l, ['goodsCode', 'goods_code']);
    final spec = pickStr(l, ['spec']);
    final barcode = pickStr(l, ['barcode']);
    final storage = pickStr(l, ['storageProperty', 'storage_property'], '常温');
    final expected = pickNum(l, ['expectedQty', 'expected_qty']);
    final got = pickNum(l, ['receivedQty', 'received_qty']);
    final unit = pickStr(l, ['unitName', 'unit_name'], '');
    final shelfLife = pickNum(l, ['shelfLifeDays', 'shelf_life_days']);
    final putawayDone = pickNum(l, ['putawayDone', 'putaway_done']) > 0;
    final container = pickStr(l, ['containerCode', 'container_code']);
    final remark = pickStr(l, ['goodsRemark', 'goods_remark']);

    return InkWell(
      borderRadius: BorderRadius.circular(PdaSpacing.radius),
      onTap: received
          ? null
          : () async {
              await Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (_) => ReceiveLinePage(
                    detail: l,
                    taskId: widget.taskId,
                    defaultContainer: _currentContainer,
                  ),
                ),
              );
              _load();
            },
      child: Container(
        margin: const EdgeInsets.only(bottom: 8),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: PdaTheme.surface,
          borderRadius: BorderRadius.circular(PdaSpacing.radius),
          border: Border.all(color: PdaTheme.border),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Expanded(
                child: Text(name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 15,
                        color: PdaTheme.textPrimary,
                        fontWeight: FontWeight.w600)),
              ),
              // 应收数量醒目大字
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text('$expected',
                      style: const TextStyle(
                          fontSize: 24,
                          height: 1.0,
                          fontWeight: FontWeight.bold,
                          color: PdaTheme.primary)),
                  Text('应收 $unit',
                      style: const TextStyle(
                          fontSize: 11, color: PdaTheme.textSecondary)),
                ],
              ),
            ]),
            const SizedBox(height: 6),
            _infoRow('编码', code),
            if (spec.isNotEmpty) _infoRow('规格', spec),
            if (barcode.isNotEmpty) _infoRow('条码', barcode),
            _infoRow('存储', storage,
                valueColor: storage == '常温'
                    ? PdaTheme.info
                    : PdaTheme.warning),
            if (shelfLife > 0)
              _infoRow('保质期', '$shelfLife 天',
                  valueColor: PdaTheme.warning),
            if (container.isNotEmpty) _infoRow('容器', container),
            if (remark.isNotEmpty) _infoRow('备注', remark),
            if (received) ...[
              const SizedBox(height: 6),
              Row(children: [
                _putawayBadge(putawayDone),
                const Spacer(),
                Text('已收 $got $unit',
                    style: TextStyle(
                        fontSize: 13,
                        color: putawayDone ? PdaTheme.primary : PdaTheme.warning,
                        fontWeight: FontWeight.w600)),
              ]),
            ],
          ],
        ),
      ),
    );
  }

  Widget _infoRow(String label, String value, {Color? valueColor}) {
    return Padding(
      padding: const EdgeInsets.only(top: 3),
      child: Row(children: [
        SizedBox(
          width: 48,
          child: Text(label,
              style: const TextStyle(
                  fontSize: 12, color: PdaTheme.textSecondary)),
        ),
        Expanded(
          child: Text(value.isEmpty ? '—' : value,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                  fontSize: 13,
                  color: valueColor ?? PdaTheme.textPrimary)),
        ),
      ]),
    );
  }

  Widget _putawayBadge(bool done) {
    final color = done ? PdaTheme.primary : PdaTheme.warning;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(done ? Icons.check_circle : Icons.hourglass_top,
            size: 13, color: color),
        const SizedBox(width: 4),
        Text(done ? '已上架' : '待上架',
            style: TextStyle(
                fontSize: 12, color: color, fontWeight: FontWeight.w600)),
      ]),
    );
  }

  Future<void> _finish() async {
    final pending = _filteredLines(false);
    if (pending.isNotEmpty) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          backgroundColor: PdaTheme.surface,
          title: const Text('仍有未收商品'),
          content: Text('还有 ${pending.length} 个 SKU 待收，确认完成收货？'),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx, false),
                child: const Text('继续收货')),
            ElevatedButton(
                onPressed: () => Navigator.pop(ctx, true),
                child: const Text('确认完成')),
          ],
        ),
      );
      if (ok != true) return;
    }
    if (!mounted) return;
    await runWithBusy(
      context,
      () => _svc.inboundFinish(widget.taskId),
      successMsg: '收货完成，已生成上架任务',
    );
    if (mounted) Navigator.pop(context);
  }

  // ---------- 容器收货 ----------
  Future<void> _showContainerSheet() async {
    final codeCtrl = TextEditingController(text: _currentContainer);
    Map<String, dynamic>? info;
    String? error;
    bool loading = false;

    Future<void> lookup(void Function(void Function()) setSheet) async {
      final code = codeCtrl.text.trim();
      if (code.isEmpty) {
        setSheet(() => error = '请扫描或输入容器条码');
        return;
      }
      setSheet(() {
        loading = true;
        error = null;
      });
      try {
        final r = await _svc.containerLookup(code);
        setSheet(() {
          info = r;
          _currentContainer = code;
          loading = false;
        });
      } catch (e) {
        setSheet(() {
          error = '$e';
          loading = false;
        });
      }
    }

    await showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: PdaTheme.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (ctx) {
        return StatefulBuilder(
          builder: (ctx, setSheet) {
            return Padding(
              padding: EdgeInsets.only(
                left: 16, right: 16, top: 16,
                bottom: MediaQuery.of(ctx).viewInsets.bottom + 16,
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text('容器收货',
                      style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                          color: PdaTheme.textPrimary)),
                  const SizedBox(height: 4),
                  const Text('扫描周转箱/托盘条码，收货时商品将装入该容器',
                      style: PdaStyles.sub),
                  const SizedBox(height: 12),
                  Row(children: [
                    Expanded(
                      child: TextField(
                        controller: codeCtrl,
                        textInputAction: TextInputAction.done,
                        style: const TextStyle(
                            fontSize: 15, color: PdaTheme.textPrimary),
                        decoration: const InputDecoration(
                          hintText: '扫描/输入容器条码',
                          prefixIcon: Icon(Icons.qr_code_2, size: 20),
                        ),
                        onSubmitted: (_) => lookup(setSheet),
                      ),
                    ),
                    const SizedBox(width: 8),
                    ElevatedButton(
                      style: ElevatedButton.styleFrom(
                          minimumSize: const Size(72, 48)),
                      onPressed: loading ? null : () => lookup(setSheet),
                      child: const Text('确认'),
                    ),
                  ]),
                  const SizedBox(height: 8),
                  Align(
                    alignment: Alignment.centerRight,
                    child: TextButton.icon(
                      icon: const Icon(Icons.add_circle_outline, size: 18),
                      label: const Text('新容器建档'),
                      onPressed: () async {
                        Navigator.pop(ctx);
                        await _showCreateContainer();
                      },
                    ),
                  ),
                  if (loading)
                    const Padding(
                      padding: EdgeInsets.all(16),
                      child: Center(
                          child: CircularProgressIndicator(
                              color: PdaTheme.primary)),
                    ),
                  if (error != null)
                    Container(
                      margin: const EdgeInsets.only(top: 8),
                      padding: const EdgeInsets.all(10),
                      decoration: BoxDecoration(
                        color: PdaTheme.danger.withValues(alpha: 0.1),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(error!,
                          style: const TextStyle(
                              fontSize: 13, color: PdaTheme.danger)),
                    ),
                  if (info != null) _containerInfo(info!),
                  const SizedBox(height: 8),
                  Row(children: [
                    Expanded(
                      child: OutlinedButton(
                        onPressed: () {
                          setState(() => _currentContainer = '');
                          Navigator.pop(ctx);
                        },
                        child: const Text('清除绑定'),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      flex: 2,
                      child: ElevatedButton(
                        onPressed: () {
                          setState(() {});
                          Navigator.pop(ctx);
                          toast(context, '容器已绑定：$_currentContainer');
                        },
                        child: const Text('使用该容器收货'),
                      ),
                    ),
                  ]),
                ],
              ),
            );
          },
        );
      },
    );
  }

  Widget _containerInfo(Map<String, dynamic> c) {
    final code = pickStr(c, ['containerCode', 'container_code']);
    final type = pickStr(c, ['containerType', 'container_type'], 'TOTE');
    final status = pickStr(c, ['status'], 'EMPTY');
    final items = (c['items'] as List?) ?? const [];
    return Container(
      margin: const EdgeInsets.only(top: 10),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: PdaTheme.surface2,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: PdaTheme.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            const Icon(Icons.inventory_2, color: PdaTheme.primary, size: 20),
            const SizedBox(width: 6),
            Expanded(
                child: Text(code,
                    style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        color: PdaTheme.textPrimary))),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                color: status == 'IN_USE'
                    ? PdaTheme.warning.withValues(alpha: 0.15)
                    : PdaTheme.primary.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(status,
                  style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                      color: status == 'IN_USE'
                          ? PdaTheme.warning
                          : PdaTheme.primary)),
            ),
          ]),
          const SizedBox(height: 4),
          Text('类型：$type', style: PdaStyles.sub),
          if (items.isNotEmpty) ...[
            const SizedBox(height: 8),
            const Text('容器内已装：', style: PdaStyles.label),
            const SizedBox(height: 4),
            ...items.map((raw) {
              final it = Map<String, dynamic>.from(raw as Map);
              return Padding(
                padding: const EdgeInsets.only(bottom: 2),
                child: Text(
                    '· ${pickStr(it, ['goodsName', 'goods_name'])} × ${pickStr(it, ['qty'])}',
                    style: const TextStyle(
                        fontSize: 13, color: PdaTheme.textPrimary)),
              );
            }),
          ],
        ],
      ),
    );
  }

  Future<void> _showCreateContainer() async {
    final codeCtrl = TextEditingController();
    String type = 'TOTE';
    final types = ['TOTE', 'PALLET', 'CAGE'];
    await showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: PdaTheme.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (ctx) {
        return StatefulBuilder(
          builder: (ctx, setSheet) => Padding(
            padding: EdgeInsets.only(
              left: 16, right: 16, top: 16,
              bottom: MediaQuery.of(ctx).viewInsets.bottom + 16,
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Text('新容器建档',
                    style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                        color: PdaTheme.textPrimary)),
                const SizedBox(height: 12),
                TextField(
                  controller: codeCtrl,
                  style: const TextStyle(
                      fontSize: 15, color: PdaTheme.textPrimary),
                  decoration: const InputDecoration(
                    labelText: '容器条码（留空自动生成）',
                  ),
                ),
                const SizedBox(height: 10),
                DropdownButtonFormField<String>(
                  initialValue: type,
                  dropdownColor: PdaTheme.surface2,
                  decoration: const InputDecoration(labelText: '容器类型'),
                  items: types
                      .map((t) =>
                          DropdownMenuItem(value: t, child: Text(_containerTypeName(t))))
                      .toList(),
                  onChanged: (v) => setSheet(() => type = v ?? 'TOTE'),
                ),
                const SizedBox(height: 16),
                ElevatedButton.icon(
                  icon: const Icon(Icons.save_outlined),
                  label: const Text('建档并绑定'),
                  onPressed: () async {
                    try {
                      final r = await _svc.containerCreate(
                        containerCode: codeCtrl.text.trim(),
                        containerType: type,
                        warehouse: pickStr(_detail!, ['warehouse'], '总仓'),
                      );
                      if (!ctx.mounted) return;
                      Navigator.pop(ctx);
                      final code = pickStr(r, ['containerCode', 'container_code']);
                      setState(() => _currentContainer = code);
                      if (mounted) toast(context, '容器 $code 建档成功');
                    } catch (e) {
                      if (ctx.mounted) toast(ctx, '$e', error: true);
                    }
                  },
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  String _containerTypeName(String t) => switch (t) {
        'TOTE' => '周转箱',
        'PALLET' => '托盘',
        'CAGE' => '笼车',
        _ => t,
      };
}

// ============================================================
// 扫码/点击收货页：数量 + 生产日期（大日期控件）+ 批次号 + 到期日 + 备注 + 容器
// ============================================================

class ReceiveLinePage extends StatefulWidget {
  final Map<String, dynamic> detail;
  final String taskId;
  final String defaultContainer;
  const ReceiveLinePage({
    super.key,
    required this.detail,
    required this.taskId,
    this.defaultContainer = '',
  });

  @override
  State<ReceiveLinePage> createState() => _ReceiveLinePageState();
}

class _ReceiveLinePageState extends State<ReceiveLinePage> {
  final _svc = WmsAppService.instance;
  late final TextEditingController _qtyCtrl;
  final _batchCtrl = TextEditingController();
  final _remarkCtrl = TextEditingController();
  final _containerCtrl = TextEditingController();

  DateTime? _productionDate;
  DateTime? _expiryDate;
  bool _batchManuallyEdited = false;
  String? _dateError;
  bool _submitting = false;

  int get _shelfLifeDays =>
      pickNum(widget.detail, ['shelfLifeDays', 'shelf_life_days']).toInt();

  @override
  void initState() {
    super.initState();
    final expected = pickNum(widget.detail, ['expectedQty', 'expected_qty']);
    final got = pickNum(widget.detail, ['receivedQty', 'received_qty']);
    final remaining = (expected - got);
    _qtyCtrl = TextEditingController(
        text: (remaining > 0 ? remaining : expected).toString());
    final existingBatch = pickStr(widget.detail, ['batchNo', 'batch_no']);
    if (existingBatch.isNotEmpty) {
      _batchCtrl.text = existingBatch;
      _batchManuallyEdited = true; // 已有批次号视为非自动
    }
    final existingProd = pickStr(widget.detail, ['productionDate', 'production_date']);
    if (existingProd.length >= 10) {
      _productionDate = DateTime.tryParse(existingProd.substring(0, 10));
      _recalcExpiry();
    }
    final remark = pickStr(widget.detail, ['goodsRemark', 'goods_remark']);
    if (remark.isNotEmpty) _remarkCtrl.text = remark;
    _containerCtrl.text = widget.defaultContainer;
  }

  @override
  void dispose() {
    _qtyCtrl.dispose();
    _batchCtrl.dispose();
    _remarkCtrl.dispose();
    _containerCtrl.dispose();
    super.dispose();
  }

  void _recalcExpiry() {
    if (_productionDate != null && _shelfLifeDays > 0) {
      _expiryDate = _productionDate!.add(Duration(days: _shelfLifeDays));
    } else {
      _expiryDate = null;
    }
  }

  /// 校验生产日期：不能晚于今天；有保质期时不能已过期。
  String? _validateDate(DateTime d) {
    final today = DateTime.now();
    final today0 = DateTime(today.year, today.month, today.day);
    if (d.isAfter(today0)) {
      return '生产日期不能晚于今天';
    }
    if (_shelfLifeDays > 0) {
      final expiry = d.add(Duration(days: _shelfLifeDays));
      if (expiry.isBefore(today0)) {
        return '你录入的生产日期已过期，请核对重新录入';
      }
    }
    return null;
  }

  Future<void> _pickProductionDate() async {
    final today = DateTime.now();
    final initial = _productionDate ?? today;
    final picked = await showDatePicker(
      context: context,
      initialDate: initial.isAfter(today) ? today : initial,
      firstDate: DateTime(2000),
      lastDate: today,
      helpText: '选择生产日期',
      fieldLabelText: '生产日期',
      builder: (ctx, child) {
        // 放大日期控件，便于手持端操作
        return Theme(
          data: Theme.of(ctx).copyWith(
            dialogTheme: DialogThemeData(
              shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(16)),
            ),
            textTheme: Theme.of(ctx).textTheme.copyWith(
                  bodyLarge: const TextStyle(fontSize: 16),
                  titleLarge: const TextStyle(
                      fontSize: 18, fontWeight: FontWeight.w600),
                ),
          ),
          child: MediaQuery(
            data: MediaQuery.of(ctx)
                .copyWith(textScaler: const TextScaler.linear(1.15)),
            child: child!,
          ),
        );
      },
    );
    if (picked == null) return;
    final err = _validateDate(picked);
    setState(() {
      _dateError = err;
      if (err == null) {
        _productionDate = picked;
        _recalcExpiry();
        // 自动生成批次号；用户手动改过则不同步
        if (!_batchManuallyEdited) {
          _batchCtrl.text =
              '${picked.year.toString().padLeft(4, '0')}${picked.month.toString().padLeft(2, '0')}${picked.day.toString().padLeft(2, '0')}';
        }
      }
    });
  }

  Future<void> _submit() async {
    final qty = num.tryParse(_qtyCtrl.text.trim()) ?? 0;
    if (qty <= 0) {
      toast(context, '请输入大于 0 的收货数量', error: true);
      return;
    }
    if (_shelfLifeDays > 0 && _productionDate == null) {
      setState(() => _dateError = '该商品有保质期要求，必须录入生产日期');
      return;
    }
    if (_dateError != null) {
      toast(context, _dateError!, error: true);
      return;
    }
    setState(() => _submitting = true);
    try {
      await _svc.inboundReceive(
        detailId: widget.detail['detailId'].toString(),
        qty: qty,
        batchNo: _batchCtrl.text.trim(),
        productionDate: _productionDate == null
            ? ''
            : _fmtDate(_productionDate!),
        expiryDate:
            _expiryDate == null ? '' : _fmtDate(_expiryDate!),
        containerCode: _containerCtrl.text.trim(),
        goodsRemark: _remarkCtrl.text.trim(),
      );
      if (mounted) {
        toast(context, '✅ 收货成功');
        Navigator.pop(context);
      }
    } catch (e) {
      if (mounted) toast(context, '$e', error: true);
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  String _fmtDate(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  @override
  Widget build(BuildContext context) {
    final name = pickStr(widget.detail, ['goodsName', 'goods_name'], '未命名商品');
    final code = pickStr(widget.detail, ['goodsCode', 'goods_code']);
    final spec = pickStr(widget.detail, ['spec']);
    final barcode = pickStr(widget.detail, ['barcode']);
    final storage = pickStr(widget.detail, ['storageProperty', 'storage_property'], '常温');
    final expected = pickNum(widget.detail, ['expectedQty', 'expected_qty']);
    final got = pickNum(widget.detail, ['receivedQty', 'received_qty']);
    final unit = pickStr(widget.detail, ['unitName', 'unit_name'], '');

    return Scaffold(
      appBar: AppBar(title: const Text('扫码收货')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 商品信息卡
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: PdaTheme.surface,
                  borderRadius: BorderRadius.circular(PdaSpacing.radius),
                  border: Border.all(color: PdaTheme.border),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(name,
                        style: const TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w600,
                            color: PdaTheme.textPrimary)),
                    const SizedBox(height: 4),
                    Text('编码：$code', style: PdaStyles.sub),
                    if (spec.isNotEmpty)
                      Text('规格：$spec', style: PdaStyles.sub),
                    if (barcode.isNotEmpty)
                      Text('条码：$barcode', style: PdaStyles.sub),
                    Text('存储：$storage', style: PdaStyles.sub),
                    const SizedBox(height: 6),
                    Row(children: [
                      Text('应收 ', style: PdaStyles.sub),
                      Text('$expected',
                          style: PdaStyles.numHuge
                              .copyWith(color: PdaTheme.primary)),
                      Text(' $unit', style: PdaStyles.sub),
                      const SizedBox(width: 16),
                      Text('已收 $got $unit', style: PdaStyles.sub),
                    ]),
                  ],
                ),
              ),
              const SizedBox(height: 12),

              // 数量
              const Text('本次实收数量', style: PdaStyles.label),
              const SizedBox(height: 6),
              Row(children: [
                _qtyStepper(Icons.remove, () {
                  final v = num.tryParse(_qtyCtrl.text) ?? 0;
                  if (v > 1) _qtyCtrl.text = '${v - 1}';
                }),
                Expanded(
                  child: TextField(
                    controller: _qtyCtrl,
                    textAlign: TextAlign.center,
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    style: const TextStyle(
                        fontSize: 28,
                        fontWeight: FontWeight.bold,
                        color: PdaTheme.textPrimary),
                    decoration: const InputDecoration(
                      contentPadding: EdgeInsets.symmetric(vertical: 10),
                    ),
                  ),
                ),
                _qtyStepper(Icons.add, () {
                  final v = num.tryParse(_qtyCtrl.text) ?? 0;
                  _qtyCtrl.text = '${v + 1}';
                }),
              ]),
              const SizedBox(height: 14),

              // 生产日期（大按钮 + 日期控件）
              const Text('生产日期', style: PdaStyles.label),
              const SizedBox(height: 6),
              _bigDateButton(),
              if (_dateError != null) ...[
                const SizedBox(height: 6),
                Text(_dateError!,
                    style: const TextStyle(
                        fontSize: 13, color: PdaTheme.danger)),
              ],
              if (_shelfLifeDays > 0) ...[
                const SizedBox(height: 6),
                Row(children: [
                  const Icon(Icons.timer_outlined,
                      size: 14, color: PdaTheme.warning),
                  const SizedBox(width: 4),
                  Text('保质期 $_shelfLifeDays 天（必须录入生产日期）',
                      style: const TextStyle(
                          fontSize: 12, color: PdaTheme.warning)),
                ]),
              ],
              if (_expiryDate != null) ...[
                const SizedBox(height: 6),
                Row(children: [
                  const Icon(Icons.event_available,
                      size: 14, color: PdaTheme.info),
                  const SizedBox(width: 4),
                  Text('到期日：${_fmtDate(_expiryDate!)}（自动换算）',
                      style:
                          const TextStyle(fontSize: 13, color: PdaTheme.info)),
                ]),
              ],
              const SizedBox(height: 14),

              // 批次号
              const Text('批次号（生产日期自动生成，可手动修改）',
                  style: PdaStyles.label),
              const SizedBox(height: 6),
              TextField(
                controller: _batchCtrl,
                style: const TextStyle(
                    fontSize: 16, color: PdaTheme.textPrimary),
                decoration: const InputDecoration(hintText: 'YYYYMMDD'),
                onChanged: (_) => _batchManuallyEdited = true,
              ),
              const SizedBox(height: 14),

              // 容器
              const Text('容器（周转箱/托盘，可空）', style: PdaStyles.label),
              const SizedBox(height: 6),
              Row(children: [
                Expanded(
                  child: TextField(
                    controller: _containerCtrl,
                    style: const TextStyle(
                        fontSize: 15, color: PdaTheme.textPrimary),
                    decoration: const InputDecoration(
                      hintText: '扫描容器条码',
                      prefixIcon: Icon(Icons.inventory_2_outlined, size: 20),
                    ),
                  ),
                ),
              ]),
              const SizedBox(height: 14),

              // 备注
              const Text('收货备注（可空）', style: PdaStyles.label),
              const SizedBox(height: 6),
              TextField(
                controller: _remarkCtrl,
                maxLines: 2,
                style: const TextStyle(
                    fontSize: 14, color: PdaTheme.textPrimary),
                decoration: const InputDecoration(
                  hintText: '例如：外箱轻微破损、数量已与司机核对',
                ),
              ),
              const SizedBox(height: 20),
            ],
          ),
        ),
      ),
      bottomNavigationBar: Container(
        padding: const EdgeInsets.all(12),
        decoration: const BoxDecoration(
          color: PdaTheme.surface,
          border: Border(top: BorderSide(color: PdaTheme.border)),
        ),
        child: Row(children: [
          Expanded(
            child: OutlinedButton(
              onPressed:
                  _submitting ? null : () => Navigator.pop(context),
              child: const Text('取消'),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            flex: 2,
            child: ElevatedButton.icon(
              icon: _submitting
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: Colors.white))
                  : const Icon(Icons.check),
              label: const Text('确认收货'),
              onPressed: _submitting ? null : _submit,
            ),
          ),
        ]),
      ),
    );
  }

  Widget _qtyStepper(IconData icon, VoidCallback onTap) {
    return Material(
      color: PdaTheme.surface2,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(10),
        child: Container(
          width: 48,
          height: 56,
          alignment: Alignment.center,
          child: Icon(icon, color: PdaTheme.primary, size: 26),
        ),
      ),
    );
  }

  /// 大号生产日期按钮：未选显示提示，已选显示日期大字。
  Widget _bigDateButton() {
    final selected = _productionDate != null;
    final hasError = _dateError != null;
    return InkWell(
      borderRadius: BorderRadius.circular(PdaSpacing.radius),
      onTap: _pickProductionDate,
      child: Container(
        height: 64,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        decoration: BoxDecoration(
          color: PdaTheme.surface2,
          borderRadius: BorderRadius.circular(PdaSpacing.radius),
          border: Border.all(
            color: hasError
                ? PdaTheme.danger
                : (selected ? PdaTheme.primary : PdaTheme.border),
            width: selected ? 1.5 : 1,
          ),
        ),
        child: Row(children: [
          Icon(Icons.calendar_today_rounded,
              size: 28,
              color: hasError
                  ? PdaTheme.danger
                  : (selected ? PdaTheme.primary : PdaTheme.textSecondary)),
          const SizedBox(width: 14),
          Expanded(
            child: selected
                ? Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      const Text('生产日期',
                          style: TextStyle(
                              fontSize: 11, color: PdaTheme.textSecondary)),
                      Text(_fmtDate(_productionDate!),
                          style: const TextStyle(
                              fontSize: 24,
                              fontWeight: FontWeight.bold,
                              color: PdaTheme.textPrimary,
                              height: 1.1)),
                    ],
                  )
                : Text(
                    _shelfLifeDays > 0
                        ? '请选择生产日期（必填）'
                        : '请选择生产日期（选填）',
                    style: const TextStyle(
                        fontSize: 16, color: PdaTheme.textSecondary)),
          ),
          const Icon(Icons.edit_calendar_outlined,
              size: 22, color: PdaTheme.textSecondary),
        ]),
      ),
    );
  }
}
