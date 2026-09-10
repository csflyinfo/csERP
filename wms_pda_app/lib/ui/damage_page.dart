import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/api_service.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 报损中心：当前仓报损单列表 + PDA 登记 + 主管审批。
/// damage.add 登记（照片受参数 WMS_DAMAGE_NEED_PHOTO 控制）、damage.audit 审批。
class DamagePage extends StatefulWidget {
  const DamagePage({super.key});
  @override
  State<DamagePage> createState() => _DamagePageState();
}

class _DamagePageState extends State<DamagePage> {
  final _svc = WmsAppService.instance;
  final _auth = AuthService.instance;
  static const _filters = <String, String>{
    '': '全部',
    'PENDING': '待审批',
    'APPROVED': '已批准',
    'REJECTED': '已驳回',
  };
  String _status = '';
  List<dynamic> _rows = const [];
  bool _loading = true;

  bool get _canAdd => _auth.can(PdaPerm.damageAdd);
  bool get _canAudit => _auth.can(PdaPerm.damageAudit);
  bool get _needPhoto => _auth.param('WMS_DAMAGE_NEED_PHOTO', '1') == '1';

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      _rows = await _svc.damageList(status: _status);
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
        title: const Text('报损管理'),
        actions: [
          IconButton(icon: const Icon(Icons.refresh), onPressed: _load),
        ],
      ),
      floatingActionButton: _canAdd
          ? FloatingActionButton.extended(
              onPressed: _showAddSheet,
              icon: const Icon(Icons.add),
              label: const Text('报损登记'),
            )
          : null,
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SizedBox(
              height: 48,
              child: ListView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
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
      return const Center(child: Text('暂无报损单', style: PdaStyles.sub));
    }
    return RefreshIndicator(
      onRefresh: _load,
      color: PdaTheme.primary,
      child: ListView.builder(
        padding: const EdgeInsets.fromLTRB(12, 4, 12, 80),
        itemCount: _rows.length,
        itemBuilder: (_, i) => _card(Map<String, dynamic>.from(_rows[i] as Map)),
      ),
    );
  }

  Widget _card(Map<String, dynamic> m) {
    final status = pickStr(m, ['status']);
    final id = pickStr(m, ['damageId', 'damage_id']);
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Expanded(
                child: Text(pickStr(m, ['damageNo', 'damage_no']),
                    style: PdaStyles.title),
              ),
              _statusChip(status),
            ]),
            const SizedBox(height: 4),
            Text(
                '${pickStr(m, ['goodsName', 'goods_name'])} · ${pickStr(m, ['goodsCode', 'goods_code'])}',
                style: PdaStyles.sub),
            const SizedBox(height: 2),
            Text(
                [
                  '数量 ${pickNum(m, ['qty'])}',
                  if (pickStr(m, ['batchNo', 'batch_no']).isNotEmpty)
                    '批次 ${pickStr(m, ['batchNo', 'batch_no'])}',
                  if (pickStr(m, ['binCode', 'bin_code']).isNotEmpty)
                    '库位 ${pickStr(m, ['binCode', 'bin_code'])}',
                ].join(' · '),
                style: PdaStyles.sub),
            if (pickStr(m, ['reason']).isNotEmpty) ...[
              const SizedBox(height: 2),
              Text('原因：${pickStr(m, ['reason'])}', style: PdaStyles.sub),
            ],
            const SizedBox(height: 2),
            Text('登记人：${pickStr(m, ['operator'])}',
                style: PdaStyles.sub),
            if (status == 'PENDING' && _canAudit) ...[
              const SizedBox(height: 8),
              Row(children: [
                OutlinedButton.icon(
                  icon: const Icon(Icons.close,
                      size: 18, color: PdaTheme.danger),
                  label: const Text('驳回',
                      style: TextStyle(color: PdaTheme.danger)),
                  onPressed: () => _audit(id, false),
                ),
                const SizedBox(width: 10),
                ElevatedButton.icon(
                  icon: const Icon(Icons.check, size: 18),
                  label: const Text('批准'),
                  onPressed: () => _audit(id, true),
                ),
              ]),
            ],
          ],
        ),
      ),
    );
  }

  Widget _statusChip(String s) {
    final (label, color) = switch (s) {
      'PENDING' => ('待审批', PdaTheme.warning),
      'APPROVED' => ('已批准', PdaTheme.primary),
      'REJECTED' => ('已驳回', PdaTheme.danger),
      _ => (s, PdaTheme.textSecondary),
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

  Future<void> _audit(String id, bool approved) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        title: Text(approved ? '批准报损' : '驳回报损'),
        content: Text(approved
            ? '批准后将从实物库存扣减对应数量，确认？'
            : '确认驳回该报损单？'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          ElevatedButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: Text(approved ? '批准' : '驳回')),
        ],
      ),
    );
    if (ok != true) return;
    if (!mounted) return;
    final r = await runWithBusy(
      context,
      () => _svc.damageAudit(damageId: id, approved: approved),
      successMsg: approved ? '已批准' : '已驳回',
    );
    if (r != null) _load();
  }

  Future<void> _showAddSheet() async {
    final codeCtrl = TextEditingController();
    final nameCtrl = TextEditingController();
    final batchCtrl = TextEditingController();
    final binCtrl = TextEditingController();
    final qtyCtrl = TextEditingController(text: '1');
    final reasonCtrl = TextEditingController();
    final imageCtrl = TextEditingController();
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
                const Text('报损登记', style: PdaStyles.title),
                const SizedBox(height: 12),
                TextField(
                  controller: codeCtrl,
                  decoration: const InputDecoration(
                      labelText: '商品编码 *', prefixIcon: Icon(Icons.qr_code)),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: nameCtrl,
                  decoration: const InputDecoration(labelText: '商品名称'),
                ),
                const SizedBox(height: 8),
                Row(children: [
                  Expanded(
                    child: TextField(
                      controller: batchCtrl,
                      decoration:
                          const InputDecoration(labelText: '批次（可空）'),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: TextField(
                      controller: binCtrl,
                      decoration:
                          const InputDecoration(labelText: '库位（可空）'),
                    ),
                  ),
                ]),
                const SizedBox(height: 8),
                TextField(
                  controller: qtyCtrl,
                  keyboardType:
                      const TextInputType.numberWithOptions(decimal: true),
                  decoration: const InputDecoration(labelText: '报损数量 *'),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: reasonCtrl,
                  maxLines: 2,
                  decoration: const InputDecoration(
                      labelText: '报损原因 *', hintText: '例如：破损、过期'),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: imageCtrl,
                  decoration: InputDecoration(
                    labelText: _needPhoto ? '照片 URL *' : '照片 URL（可空）',
                    prefixIcon: const Icon(Icons.image_outlined),
                  ),
                ),
                if (error != null) ...[
                  const SizedBox(height: 8),
                  Text(error!,
                      style: const TextStyle(
                          fontSize: 13, color: PdaTheme.danger)),
                ],
                const SizedBox(height: 14),
                ElevatedButton.icon(
                  icon: const Icon(Icons.check),
                  label: const Text('提交报损'),
                  onPressed: () async {
                    final qty = num.tryParse(qtyCtrl.text.trim());
                    if (codeCtrl.text.trim().isEmpty ||
                        reasonCtrl.text.trim().isEmpty) {
                      setSheet(() => error = '请填写商品编码和报损原因');
                      return;
                    }
                    if (qty == null || qty <= 0) {
                      setSheet(() => error = '报损数量必须大于 0');
                      return;
                    }
                    if (_needPhoto && imageCtrl.text.trim().isEmpty) {
                      setSheet(() => error = '当前仓库参数要求报损必须上传照片');
                      return;
                    }
                    Navigator.pop(ctx);
                    final r = await runWithBusy(
                      context,
                      () => _svc.damageAdd(
                        goodsCode: codeCtrl.text.trim(),
                        goodsName: nameCtrl.text.trim(),
                        batchNo: batchCtrl.text.trim(),
                        binCode: binCtrl.text.trim(),
                        qty: qty,
                        reason: reasonCtrl.text.trim(),
                        imageUrl: imageCtrl.text.trim(),
                      ),
                      successMsg: '报损单已提交',
                    );
                    if (r != null) _load();
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
