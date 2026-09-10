import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/api_service.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 移库作业：列出当前仓移库单 → 确认完成；有 move.add 可在 PDA 直接建单。
/// 按钮裁剪：move.add 新建（FAB）、move.confirm 确认完成；后端同口径强制。
class MovePage extends StatefulWidget {
  const MovePage({super.key});
  @override
  State<MovePage> createState() => _MovePageState();
}

class _MovePageState extends State<MovePage> {
  final _svc = WmsAppService.instance;
  final _auth = AuthService.instance;
  final _search = TextEditingController();
  List<dynamic> _tasks = const [];
  bool _loading = true;

  bool get _canAdd => _auth.can(PdaPerm.moveAdd);
  bool get _canConfirm => _auth.can(PdaPerm.moveConfirm);

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      _tasks = await _svc.moveTasks(keyword: _search.text.trim());
    } catch (e) {
      if (mounted) toast(context, ApiService.friendlyError(e), error: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return PdaScaffold(
      title: '移库作业',
      onRefresh: _load,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ScanZone(
            title: '扫描任务号 / 商品',
            hint: '搜索过滤',
            controller: _search,
            icon: Icons.search,
            buttonLabel: '搜索',
            onSubmit: (_) => _load(),
          ),
          const SizedBox(height: 12),
          if (_loading)
            const Padding(
              padding: EdgeInsets.all(40),
              child: Center(
                  child: CircularProgressIndicator(color: PdaTheme.primary)),
            )
          else if (_tasks.isEmpty)
            const Padding(
              padding: EdgeInsets.all(40),
              child: Center(child: Text('暂无移库任务', style: PdaStyles.sub)),
            )
          else
            ..._tasks.map((t) {
              final m = Map<String, dynamic>.from(t as Map);
              final status = pickStr(m, ['status']);
              final fromBin = pickStr(m, ['fromBin', 'from_bin']);
              final toBin = pickStr(m, ['toBin', 'to_bin']);
              final qty = pickNum(m, ['qty']);
              final batch = pickStr(m, ['batchNo', 'batch_no']);
              final active = status != 'DONE' && status != 'CANCELLED';
              return Card(
                margin: const EdgeInsets.only(bottom: 8),
                child: Padding(
                  padding: const EdgeInsets.all(10),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(children: [
                        Expanded(
                            child: Text(pickStr(m, ['taskNo', 'task_no']),
                                style: PdaStyles.title)),
                        Text(_statusText(status),
                            style: TextStyle(
                                fontSize: 11, color: _statusColor(status))),
                      ]),
                      const SizedBox(height: 4),
                      Text(
                          '${pickStr(m, ['goodsName', 'goods_name'])} · $qty${batch.isNotEmpty ? ' · 批次：$batch' : ''}',
                          style: PdaStyles.sub),
                      const SizedBox(height: 6),
                      Row(children: [
                        const Icon(Icons.upload_rounded,
                            size: 16, color: PdaTheme.warning),
                        const SizedBox(width: 4),
                        Text(fromBin, style: PdaStyles.sub),
                        const Padding(
                          padding: EdgeInsets.symmetric(horizontal: 8),
                          child: Icon(Icons.arrow_forward,
                              size: 14, color: PdaTheme.textSecondary),
                        ),
                        const Icon(Icons.download_rounded,
                            size: 16, color: PdaTheme.primary),
                        const SizedBox(width: 4),
                        Text(toBin, style: PdaStyles.sub),
                      ]),
                      if (active) ...[
                        const SizedBox(height: 8),
                        if (_canConfirm)
                          ElevatedButton.icon(
                            icon: const Icon(Icons.check, size: 18),
                            label: const Text('确认移库完成'),
                            onPressed: () async {
                              final r = await runWithBusy(
                                context,
                                () =>
                                    _svc.moveComplete(m['taskId'].toString()),
                                successMsg: '移库完成',
                              );
                              if (r != null) _load();
                            },
                          )
                        else
                          const Text('无移库完成权限', style: PdaStyles.sub),
                      ],
                    ],
                  ),
                ),
              );
            }),
        ],
      ),
      floatingActionButton: _canAdd
          ? FloatingActionButton.extended(
              onPressed: _showAddSheet,
              icon: const Icon(Icons.add),
              label: const Text('新建移库'),
            )
          : null,
    );
  }

  /// PDA 建移库单：源/目标库位、商品、数量必填；仓库强制登录仓（后端处理）。
  Future<void> _showAddSheet() async {
    final fromCtrl = TextEditingController();
    final toCtrl = TextEditingController();
    final codeCtrl = TextEditingController();
    final nameCtrl = TextEditingController();
    final batchCtrl = TextEditingController();
    final qtyCtrl = TextEditingController(text: '1');
    final remarkCtrl = TextEditingController();
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
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('新建移库单', style: PdaStyles.title),
              const SizedBox(height: 12),
              TextField(
                controller: fromCtrl,
                decoration: const InputDecoration(
                    labelText: '源库位 *', prefixIcon: Icon(Icons.upload)),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: toCtrl,
                decoration: const InputDecoration(
                    labelText: '目标库位 *', prefixIcon: Icon(Icons.download)),
              ),
              const SizedBox(height: 8),
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
                    decoration: const InputDecoration(labelText: '批次（可空）'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: TextField(
                    controller: qtyCtrl,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(labelText: '数量 *'),
                  ),
                ),
              ]),
              const SizedBox(height: 8),
              TextField(
                controller: remarkCtrl,
                decoration: const InputDecoration(labelText: '备注（可空）'),
              ),
              if (error != null) ...[
                const SizedBox(height: 8),
                Text(error!,
                    style:
                        const TextStyle(fontSize: 13, color: PdaTheme.danger)),
              ],
              const SizedBox(height: 14),
              ElevatedButton.icon(
                icon: const Icon(Icons.check),
                label: const Text('提交'),
                onPressed: () async {
                  final qty = num.tryParse(qtyCtrl.text.trim());
                  if (fromCtrl.text.trim().isEmpty ||
                      toCtrl.text.trim().isEmpty ||
                      codeCtrl.text.trim().isEmpty) {
                    setSheet(() => error = '请填写源库位、目标库位和商品编码');
                    return;
                  }
                  if (qty == null || qty <= 0) {
                    setSheet(() => error = '数量必须大于 0');
                    return;
                  }
                  Navigator.pop(ctx);
                  final r = await runWithBusy(
                    context,
                    () => _svc.moveAdd(
                      fromBin: fromCtrl.text.trim(),
                      toBin: toCtrl.text.trim(),
                      goodsCode: codeCtrl.text.trim(),
                      goodsName: nameCtrl.text.trim(),
                      batchNo: batchCtrl.text.trim(),
                      qty: qty,
                      remark: remarkCtrl.text.trim(),
                    ),
                    successMsg: '移库单已创建',
                  );
                  if (r != null) _load();
                },
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _statusText(String s) => switch (s) {
        'PENDING' => '待执行',
        'DOING' => '执行中',
        'DONE' => '已完成',
        'CANCELLED' => '已取消',
        _ => s,
      };

  Color _statusColor(String s) => switch (s) {
        'DONE' => PdaTheme.primary,
        'CANCELLED' => PdaTheme.danger,
        'DOING' => PdaTheme.warning,
        _ => PdaTheme.textSecondary,
      };
}
