import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/api_service.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 复核出库（PRD-28 卡片9）：
/// 待复核波次来自 /wms/app/check/tasks（当前仓 PICKED/CHECKING/SUSPENDED），
/// 每行附按单聚合的 orders——复核员不需要 pick.view 即可逐单通过/登记差异。
///
/// 按钮裁剪：check.confirm 复核通过、check.exception 差异登记、
/// check.scan 扫码集齐核对、check.pack 打包封箱；后端同口径强制。
class CheckPage extends StatefulWidget {
  const CheckPage({super.key});
  @override
  State<CheckPage> createState() => _CheckPageState();
}

class _CheckPageState extends State<CheckPage> {
  final _svc = WmsAppService.instance;
  final _auth = AuthService.instance;
  List<dynamic> _waves = const [];
  bool _loading = true;
  final Set<String> _expanded = {};

  bool get _canConfirm => _auth.can(PdaPerm.checkConfirm);
  bool get _canException => _auth.can(PdaPerm.checkException);
  bool get _canScan => _auth.can(PdaPerm.checkScan);
  bool get _canPack => _auth.can(PdaPerm.checkPack);

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      _waves = await _svc.checkTasks();
    } catch (e) {
      if (mounted) toast(context, _friendly(e), error: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  String _friendly(Object e) => ApiService.friendlyError(e);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('复核出库'),
        actions: [
          IconButton(
              icon: const Icon(Icons.refresh), onPressed: _load),
        ],
      ),
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _load,
          color: PdaTheme.primary,
          child: _buildBody(),
        ),
      ),
    );
  }

  Widget _buildBody() {
    if (_loading) {
      return ListView(children: const [
        SizedBox(
            height: 200,
            child: Center(
                child: CircularProgressIndicator(color: PdaTheme.primary)))
      ]);
    }
    if (_waves.isEmpty) {
      return ListView(children: const [
        SizedBox(
            height: 200,
            child:
                Center(child: Text('暂无待复核波次', style: PdaStyles.sub)))
      ]);
    }
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
      itemCount: _waves.length,
      itemBuilder: (_, i) => _waveCard(Map<String, dynamic>.from(_waves[i] as Map)),
    );
  }

  Widget _waveCard(Map<String, dynamic> w) {
    final waveId = w['waveId'].toString();
    final waveNo = pickStr(w, ['waveNo', 'wave_no']);
    final status = pickStr(w, ['status']);
    final orderCount = pickNum(w, ['orderCount', 'order_count']);
    final required = pickNum(w, ['requiredQty', 'required_qty']);
    final picked = pickNum(w, ['pickedQty', 'picked_qty']);
    final orders = (w['orders'] as List?) ?? const [];
    final open = _expanded.contains(waveId);
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          InkWell(
            borderRadius: BorderRadius.circular(12),
            onTap: () => setState(() {
              open ? _expanded.remove(waveId) : _expanded.add(waveId);
            }),
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(children: [
                    Expanded(
                      child: Text(waveNo.isEmpty ? '波次 $waveId' : waveNo,
                          style: PdaStyles.title),
                    ),
                    _statusChip(status),
                    const SizedBox(width: 6),
                    Icon(open ? Icons.expand_less : Icons.expand_more,
                        color: PdaTheme.textSecondary),
                  ]),
                  const SizedBox(height: 6),
                  Row(children: [
                    Text('订单 $orderCount', style: PdaStyles.sub),
                    const SizedBox(width: 14),
                    Text('应拣 $required', style: PdaStyles.sub),
                    const SizedBox(width: 14),
                    Text('实拣 $picked',
                        style: PdaStyles.sub.copyWith(color: PdaTheme.primary)),
                  ]),
                  if (pickStr(w, ['driver']).isNotEmpty ||
                      pickStr(w, ['routeLine', 'route_line']).isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(
                        [
                          pickStr(w, ['routeLine', 'route_line']),
                          pickStr(w, ['driver']),
                        ].where((s) => s.isNotEmpty).join(' · '),
                        style: PdaStyles.sub),
                  ],
                ],
              ),
            ),
          ),
          if (open) ...[
            const Divider(height: 1),
            if (_canScan)
              Padding(
                padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: OutlinedButton.icon(
                    icon: const Icon(Icons.qr_code_scanner, size: 18),
                    label: const Text('扫码集齐核对'),
                    onPressed: () => _scanAck(waveId),
                  ),
                ),
              ),
            for (final raw in orders)
              _orderCard(waveId, Map<String, dynamic>.from(raw as Map)),
          ],
        ],
      ),
    );
  }

  Widget _orderCard(String waveId, Map<String, dynamic> o) {
    final orderNo = pickStr(o, ['sourceOrderNo', 'source_order_no'], '');
    final customer = pickStr(o, ['customerName', 'customer_name']);
    final required = pickNum(o, ['requiredQty', 'required_qty']);
    final picked = pickNum(o, ['pickedQty', 'picked_qty']);
    final short = required - picked;
    return Container(
      margin: const EdgeInsets.fromLTRB(12, 8, 12, 8),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: PdaTheme.surface2,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: PdaTheme.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(orderNo, style: PdaStyles.title),
          if (customer.isNotEmpty) ...[
            const SizedBox(height: 2),
            Text(customer, style: PdaStyles.sub),
          ],
          const SizedBox(height: 6),
          Row(children: [
            Text('应拣 $required', style: PdaStyles.sub),
            const SizedBox(width: 12),
            Text('实拣 $picked',
                style: PdaStyles.sub.copyWith(
                    color: short > 0 ? PdaTheme.danger : PdaTheme.primary)),
            if (short > 0) ...[
              const SizedBox(width: 12),
              Text('少 $short',
                  style: PdaStyles.sub.copyWith(color: PdaTheme.danger)),
            ],
          ]),
          const SizedBox(height: 8),
          Wrap(spacing: 8, runSpacing: 8, children: [
            if (_canException)
              OutlinedButton.icon(
                icon: const Icon(Icons.error_outline,
                    size: 18, color: PdaTheme.danger),
                label: const Text('差异登记',
                    style: TextStyle(color: PdaTheme.danger)),
                onPressed: () => _failDialog(waveId, orderNo, short),
              ),
            if (_canPack)
              OutlinedButton.icon(
                icon: const Icon(Icons.inventory_2_outlined, size: 18),
                label: const Text('打包封箱'),
                onPressed: () => _packAck(waveId, orderNo),
              ),
            if (_canConfirm)
              ElevatedButton.icon(
                icon: const Icon(Icons.check, size: 18),
                label: const Text('复核通过'),
                onPressed: () => _passDialog(waveId, orderNo, short),
              ),
          ]),
        ],
      ),
    );
  }

  Widget _statusChip(String status) {
    final (label, color) = switch (status) {
      'PICKED' => ('待复核', PdaTheme.warning),
      'CHECKING' => ('复核中', PdaTheme.info),
      'SUSPENDED' => ('已挂起', PdaTheme.danger),
      _ => (status, PdaTheme.textSecondary),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(label,
          style: TextStyle(
              fontSize: 11, color: color, fontWeight: FontWeight.w600)),
    );
  }

  /// 扫码集齐核对为非变更 ack（后端载荷 check.scan）。
  Future<void> _scanAck(String waveId) async {
    final r = await runWithBusy(
      context,
      () => _svc.checkPass(waveId: waveId, orderNo: '', action: 'scan'),
      successMsg: '扫码核对通过',
    );
    if (r != null) _load();
  }

  Future<void> _packAck(String waveId, String orderNo) async {
    await runWithBusy(
      context,
      () =>
          _svc.checkPass(waveId: waveId, orderNo: orderNo, action: 'pack'),
      successMsg: '已封箱',
    );
  }

  /// 有少发数量时弹窗让复核员确认少发数（默认按差值），可直接通过。
  Future<void> _passDialog(
      String waveId, String orderNo, num short) async {
    if (short <= 0) {
      await _submitPass(waveId, orderNo, 0, '');
      return;
    }
    final qtyCtrl = TextEditingController(text: '$short');
    final reasonCtrl = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        title: const Text('复核通过（存在少发）'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('订单 $orderNo 实拣比应拣少 $short，确认通过并登记少发？',
                style: PdaStyles.sub),
            const SizedBox(height: 10),
            TextField(
              controller: qtyCtrl,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: '少发数量'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: reasonCtrl,
              maxLines: 2,
              decoration: const InputDecoration(labelText: '备注（可空）'),
            ),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('确认通过'),
          ),
        ],
      ),
    );
    if (ok == true) {
      await _submitPass(
        waveId,
        orderNo,
        num.tryParse(qtyCtrl.text.trim()) ?? short,
        reasonCtrl.text.trim(),
      );
    }
  }

  Future<void> _submitPass(
      String waveId, String orderNo, num shortQty, String reason) async {
    final r = await runWithBusy(
      context,
      () => _svc.checkPass(
        waveId: waveId,
        orderNo: orderNo,
        shortQty: shortQty,
        reason: reason,
      ),
      successMsg: '复核通过',
    );
    if (r != null) _load();
  }

  /// 差异登记：原因 PDA 必填（后端强制），可选少发数量。
  Future<void> _failDialog(
      String waveId, String orderNo, num short) async {
    final qtyCtrl = TextEditingController(text: short > 0 ? '$short' : '');
    final reasonCtrl = TextEditingController();
    String? error;
    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialog) => AlertDialog(
          backgroundColor: PdaTheme.surface,
          title: const Text('差异登记'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('订单 $orderNo', style: PdaStyles.sub),
              const SizedBox(height: 10),
              TextField(
                controller: qtyCtrl,
                keyboardType: TextInputType.number,
                decoration:
                    const InputDecoration(labelText: '少发数量（可空）'),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: reasonCtrl,
                maxLines: 2,
                decoration: const InputDecoration(
                    labelText: '差异原因 *', hintText: '例如：破损、缺货'),
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
                if (reasonCtrl.text.trim().isEmpty) {
                  setDialog(() => error = '请填写差异原因');
                  return;
                }
                Navigator.pop(ctx);
                final r = await runWithBusy(
                  context,
                  () => _svc.checkFail(
                    waveId: waveId,
                    orderNo: orderNo,
                    shortQty: num.tryParse(qtyCtrl.text.trim()) ?? 0,
                    reason: reasonCtrl.text.trim(),
                  ),
                  successMsg: '差异已登记，已开异常单',
                );
                if (r != null) _load();
              },
              child: const Text('提交差异'),
            ),
          ],
        ),
      ),
    );
  }
}
