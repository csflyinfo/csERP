import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/api_service.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 异常中心：当前仓异常单列表、上报、主管分派/处理。
/// exception.report 全员；exception.assign/handle 仅主管（后端强制 403）。
class ExceptionPage extends StatefulWidget {
  const ExceptionPage({super.key});
  @override
  State<ExceptionPage> createState() => _ExceptionPageState();
}

class _ExceptionPageState extends State<ExceptionPage> {
  final _svc = WmsAppService.instance;
  final _auth = AuthService.instance;
  final _keywordCtrl = TextEditingController();
  static const _filters = <String, String>{
    '': '全部',
    'OPEN': '待处理',
    'HANDLING': '处理中',
    'RESOLVED': '已处理',
  };
  static const _types = <String, String>{
    'SHORT': '缺货',
    'DIFF': '差异',
    'DAMAGE': '破损',
    'OTHER': '其他',
  };
  String _status = 'OPEN';
  List<dynamic> _rows = const [];
  bool _loading = true;

  bool get _canReport => _auth.can(PdaPerm.excReport);
  bool get _canHandle => _auth.can(PdaPerm.excHandle);
  bool get _canAssign => _auth.can(PdaPerm.excAssign);

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      _rows = await _svc.exceptionList(
        status: _status,
        keyword: _keywordCtrl.text.trim(),
      );
    } catch (e) {
      if (mounted) toast(context, ApiService.friendlyError(e), error: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('异常中心'),
        actions: [
          IconButton(icon: const Icon(Icons.refresh), onPressed: _load),
        ],
      ),
      floatingActionButton: _canReport
          ? FloatingActionButton.extended(
              onPressed: _showReportSheet,
              icon: const Icon(Icons.report_problem_outlined),
              label: const Text('上报异常'),
            )
          : null,
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
              child: Row(children: [
                Expanded(
                  child: TextField(
                    controller: _keywordCtrl,
                    textInputAction: TextInputAction.search,
                    onSubmitted: (_) => _load(),
                    decoration: const InputDecoration(
                      hintText: '异常号 / 订单 / 商品 / 描述',
                      prefixIcon: Icon(Icons.search),
                      isDense: true,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                ElevatedButton(
                    onPressed: _loading ? null : _load,
                    child: const Text('查询')),
              ]),
            ),
            SizedBox(
              height: 46,
              child: ListView(
                scrollDirection: Axis.horizontal,
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                children: [
                  for (final e in _filters.entries)
                    Padding(
                      padding: const EdgeInsets.only(right: 8),
                      child: ChoiceChip(
                        label: Text(e.value),
                        selected: _status == e.key,
                        selectedColor:
                            PdaTheme.primary.withValues(alpha: 0.25),
                        onSelected: (_) {
                          _status = e.key;
                          _load();
                        },
                      ),
                    ),
                ],
              ),
            ),
            Expanded(child: _buildList()),
          ],
        ),
      ),
    );
  }

  Widget _buildList() {
    if (_loading) {
      return const Center(
          child: CircularProgressIndicator(color: PdaTheme.primary));
    }
    if (_rows.isEmpty) {
      return const Center(child: Text('暂无异常单', style: PdaStyles.sub));
    }
    return RefreshIndicator(
      onRefresh: _load,
      color: PdaTheme.primary,
      child: ListView.builder(
        padding: const EdgeInsets.fromLTRB(12, 4, 12, 80),
        itemCount: _rows.length,
        itemBuilder: (_, i) =>
            _card(Map<String, dynamic>.from(_rows[i] as Map)),
      ),
    );
  }

  Widget _card(Map<String, dynamic> m) {
    final status = pickStr(m, ['status']);
    final type = pickStr(m, ['exceptionType', 'exception_type']);
    final id = pickStr(m, ['exceptionId', 'exception_id']);
    final active = status == 'OPEN' || status == 'HANDLING';
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Expanded(
                child: Text(pickStr(m, ['exceptionNo', 'exception_no']),
                    style: PdaStyles.title),
              ),
              _typeChip(type),
              const SizedBox(width: 6),
              _statusChip(status),
            ]),
            const SizedBox(height: 4),
            Text(pickStr(m, ['description']), style: PdaStyles.sub),
            const SizedBox(height: 2),
            Text(
                [
                  if (pickStr(m, ['sourceOrderNo', 'source_order_no']).isNotEmpty)
                    '订单 ${pickStr(m, ['sourceOrderNo', 'source_order_no'])}',
                  if (pickStr(m, ['goodsCode', 'goods_code']).isNotEmpty)
                    pickStr(m, ['goodsCode', 'goods_code']),
                  if (m['qty'] != null) '数量 ${pickNum(m, ['qty'])}',
                ].join(' · '),
                style: PdaStyles.sub),
            const SizedBox(height: 2),
            Text(
                [
                  '上报 ${pickStr(m, ['reporter'])}',
                  if (pickStr(m, ['assignee']).isNotEmpty)
                    '处理人 ${pickStr(m, ['assignee'])}',
                ].join(' · '),
                style: PdaStyles.sub),
            if (pickStr(m, ['resolution']).isNotEmpty) ...[
              const SizedBox(height: 2),
              Text('处理结果：${pickStr(m, ['resolution'])}',
                  style: PdaStyles.sub
                      .copyWith(color: PdaTheme.primary)),
            ],
            if (active && (_canAssign || _canHandle)) ...[
              const SizedBox(height: 8),
              Wrap(spacing: 8, runSpacing: 8, children: [
                if (_canAssign)
                  OutlinedButton.icon(
                    icon: const Icon(Icons.person_add_alt, size: 18),
                    label: const Text('分派'),
                    onPressed: () => _assignDialog(id),
                  ),
                if (_canHandle)
                  ElevatedButton.icon(
                    icon: const Icon(Icons.check_circle_outline, size: 18),
                    label: const Text('处理完结'),
                    onPressed: () => _handleDialog(id),
                  ),
              ]),
            ],
          ],
        ),
      ),
    );
  }

  Widget _typeChip(String t) {
    final color = switch (t) {
      'SHORT' => PdaTheme.danger,
      'DIFF' => PdaTheme.warning,
      'DAMAGE' => PdaTheme.info,
      _ => PdaTheme.textSecondary,
    };
    return _chip(_types[t] ?? t, color);
  }

  Widget _statusChip(String s) {
    final (label, color) = switch (s) {
      'OPEN' => ('待处理', PdaTheme.danger),
      'HANDLING' => ('处理中', PdaTheme.warning),
      'RESOLVED' => ('已处理', PdaTheme.primary),
      _ => (s, PdaTheme.textSecondary),
    };
    return _chip(label, color);
  }

  Widget _chip(String label, Color color) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.15),
          borderRadius: BorderRadius.circular(6),
        ),
        child: Text(label,
            style: TextStyle(
                fontSize: 11,
                color: color,
                fontWeight: FontWeight.w600)),
      );

  Future<void> _assignDialog(String id) async {
    final ctrl = TextEditingController();
    String? error;
    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialog) => AlertDialog(
          backgroundColor: PdaTheme.surface,
          title: const Text('分派异常'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: ctrl,
                autofocus: true,
                decoration: const InputDecoration(
                    labelText: '处理人工号 *',
                    hintText: '须启用且绑定当前作业仓'),
              ),
              if (error != null) ...[
                const SizedBox(height: 8),
                Text(error!,
                    style: const TextStyle(
                        fontSize: 13, color: PdaTheme.danger)),
              ],
            ],
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx),
                child: const Text('取消')),
            ElevatedButton(
              onPressed: () async {
                if (ctrl.text.trim().isEmpty) {
                  setDialog(() => error = '请输入处理人工号');
                  return;
                }
                Navigator.pop(ctx);
                final r = await runWithBusy(
                  context,
                  () => _svc.exceptionAssign(
                      exceptionId: id, assignee: ctrl.text.trim()),
                  successMsg: '已分派',
                );
                if (r != null) _load();
              },
              child: const Text('分派'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _handleDialog(String id) async {
    final ctrl = TextEditingController();
    String? error;
    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialog) => AlertDialog(
          backgroundColor: PdaTheme.surface,
          title: const Text('处理完结'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: ctrl,
                autofocus: true,
                maxLines: 3,
                decoration:
                    const InputDecoration(labelText: '处理结果 *'),
              ),
              if (error != null) ...[
                const SizedBox(height: 8),
                Text(error!,
                    style: const TextStyle(
                        fontSize: 13, color: PdaTheme.danger)),
              ],
            ],
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx),
                child: const Text('取消')),
            ElevatedButton(
              onPressed: () async {
                if (ctrl.text.trim().isEmpty) {
                  setDialog(() => error = '请填写处理结果');
                  return;
                }
                Navigator.pop(ctx);
                final r = await runWithBusy(
                  context,
                  () => _svc.exceptionHandle(
                      exceptionId: id, resolution: ctrl.text.trim()),
                  successMsg: '异常已处理',
                );
                if (r != null) _load();
              },
              child: const Text('确认完结'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _showReportSheet() async {
    String type = 'OTHER';
    String sourceType = 'OUTBOUND';
    String priority = 'MEDIUM';
    final descCtrl = TextEditingController();
    final qtyCtrl = TextEditingController();
    final goodsCtrl = TextEditingController();
    final waveCtrl = TextEditingController();
    final billCtrl = TextEditingController();
    final binCtrl = TextEditingController();
    String? error;
    await showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheet) => Padding(
          padding: EdgeInsets.only(
            left: 16,
            right: 16,
            top: 16,
            bottom: MediaQuery.of(ctx).viewInsets.bottom + 16,
          ),
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Text('上报异常', style: PdaStyles.title),
                const SizedBox(height: 10),
                Wrap(spacing: 8, children: [
                  for (final e in _types.entries)
                    ChoiceChip(
                      label: Text(e.value),
                      selected: type == e.key,
                      selectedColor:
                          PdaTheme.primary.withValues(alpha: 0.25),
                      onSelected: (_) => setSheet(() => type = e.key),
                    ),
                ]),
                const SizedBox(height: 10),
                TextField(
                  controller: descCtrl,
                  maxLines: 2,
                  decoration:
                      const InputDecoration(labelText: '异常描述 *'),
                ),
                const SizedBox(height: 8),
                Row(children: [
                  Expanded(
                    child: TextField(
                      controller: goodsCtrl,
                      decoration:
                          const InputDecoration(labelText: '商品编码（可空）'),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: TextField(
                      controller: qtyCtrl,
                      keyboardType:
                          const TextInputType.numberWithOptions(decimal: true),
                      decoration:
                          const InputDecoration(labelText: '数量（可空）'),
                    ),
                  ),
                ]),
                const SizedBox(height: 8),
                Row(children: [
                  SegmentedButton<String>(
                    segments: const [
                      ButtonSegment(value: 'OUTBOUND', label: Text('出库')),
                      ButtonSegment(value: 'INBOUND', label: Text('入库')),
                    ],
                    selected: {sourceType},
                    style: ButtonStyle(
                      visualDensity: VisualDensity.compact,
                    ),
                    onSelectionChanged: (s) =>
                        setSheet(() => sourceType = s.first),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: DropdownButtonFormField<String>(
                      initialValue: priority,
                      decoration:
                          const InputDecoration(labelText: '紧急度'),
                      items: const [
                        DropdownMenuItem(value: 'LOW', child: Text('低')),
                        DropdownMenuItem(value: 'MEDIUM', child: Text('中')),
                        DropdownMenuItem(value: 'HIGH', child: Text('高')),
                        DropdownMenuItem(value: 'URGENT', child: Text('紧急')),
                      ],
                      onChanged: (v) =>
                          setSheet(() => priority = v ?? 'MEDIUM'),
                    ),
                  ),
                ]),
                const SizedBox(height: 8),
                TextField(
                  controller: sourceType == 'INBOUND' ? billCtrl : waveCtrl,
                  decoration: InputDecoration(
                    labelText: sourceType == 'INBOUND'
                        ? '入库任务号 *'
                        : '波次 ID（可空）',
                  ),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: binCtrl,
                  decoration:
                      const InputDecoration(labelText: '库位（可空）'),
                ),
                if (error != null) ...[
                  const SizedBox(height: 8),
                  Text(error!,
                      style: const TextStyle(
                          fontSize: 13, color: PdaTheme.danger)),
                ],
                const SizedBox(height: 14),
                ElevatedButton.icon(
                  icon: const Icon(Icons.send),
                  label: const Text('提交上报'),
                  onPressed: () async {
                    if (descCtrl.text.trim().isEmpty) {
                      setSheet(() => error = '请填写异常描述');
                      return;
                    }
                    if (sourceType == 'INBOUND' &&
                        billCtrl.text.trim().isEmpty) {
                      setSheet(() => error = '入库异常必须关联入库任务号');
                      return;
                    }
                    final qty = num.tryParse(qtyCtrl.text.trim());
                    Navigator.pop(ctx);
                    final r = await runWithBusy(
                      context,
                      () => _svc.exceptionReport(
                        exceptionType: type,
                        description: descCtrl.text.trim(),
                        goodsCode: goodsCtrl.text.trim(),
                        qty: qty,
                        sourceType: sourceType,
                        waveId: waveCtrl.text.trim(),
                        sourceBill: billCtrl.text.trim(),
                        binCode: binCtrl.text.trim(),
                        priority: priority,
                      ),
                      successMsg: '异常已上报',
                    );
                    if (r != null) {
                      _status = 'OPEN';
                      _load();
                    }
                  },
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
