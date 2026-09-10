import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 盘点作业：扫描/输入盘点任务号 → 逐行录实盘 → 整单提交 → 主管审核。
/// 按钮裁剪：stocktake.scan 扫码进入、stocktake.input 录数/复盘、
/// stocktake.submit 提交、stocktake.audit 审核；后端同口径强制。
class StocktakePage extends StatefulWidget {
  const StocktakePage({super.key});
  @override
  State<StocktakePage> createState() => _StocktakePageState();
}

class _StocktakePageState extends State<StocktakePage> {
  final _svc = WmsAppService.instance;
  final _auth = AuthService.instance;
  final _taskCtrl = TextEditingController();
  List<dynamic> _bins = const [];
  String? _taskId;

  bool get _canScan => _auth.can(PdaPerm.takeScan);
  bool get _canInput => _auth.can(PdaPerm.takeInput);
  bool get _canSubmit => _auth.can(PdaPerm.takeSubmit);
  bool get _canAudit => _auth.can(PdaPerm.takeAudit);

  int get _counted =>
      _bins.where((b) => (Map<String, dynamic>.from(b as Map))['realQty'] != null).length;
  int get _diffCount => _bins.where((b) {
        final m = Map<String, dynamic>.from(b as Map);
        final d = m['diffQty'];
        return d is num && d != 0;
      }).length;

  Future<void> _load() async {
    final v = _taskCtrl.text.trim();
    if (v.isEmpty) return;
    setState(() => _taskId = v);
    final r = await runWithBusy(
      context,
      // 有扫码权限才以 scan 动作进入（后端对动作做独立鉴权）
      () => _svc.stocktakeBins(v, scan: _canScan),
    );
    if (r != null) {
      setState(() => _bins = r);
    }
  }

  @override
  Widget build(BuildContext context) {
    final bottoms = <Widget>[
      if (_taskId != null && _canSubmit)
        ElevatedButton.icon(
          icon: const Icon(Icons.upload_file, size: 18),
          label: const Text('提交盘点'),
          onPressed: _submit,
        ),
      if (_taskId != null && _canAudit)
        ElevatedButton.icon(
          icon: const Icon(Icons.verified, size: 18),
          label: const Text('审核'),
          onPressed: _audit,
        ),
    ];
    return PdaScaffold(
      title: '盘点作业',
      onRefresh: _taskId == null ? null : _load,
      bottomButtons: bottoms.isEmpty ? null : bottoms,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ScanZone(
            title: '扫描盘点任务号',
            hint: '请输入 PC 端创建的盘点任务号',
            controller: _taskCtrl,
            icon: Icons.numbers,
            buttonLabel: '加载',
            onSubmit: (_) => _load(),
          ),
          const SizedBox(height: 12),
          if (_taskId == null)
            const Padding(
              padding: EdgeInsets.all(40),
              child: Center(
                child: Text('请先加载盘点任务', style: PdaStyles.sub),
              ),
            )
          else ...[
            PdaAlert.info(
                '盘点任务：$_taskId · 已盘 $_counted/${_bins.length} · 差异 $_diffCount'),
            if (!_canInput) ...[
              const SizedBox(height: 8),
              PdaAlert.warning('当前账号只有查看权限，不能录入实盘'),
            ],
            const SizedBox(height: 8),
            ..._bins.map((raw) {
              final l = Map<String, dynamic>.from(raw as Map);
              final sys = pickNum(l, ['bookQty', 'book_qty']);
              final realRaw = l['realQty'];
              final num? real = realRaw is num ? realRaw : null;
              final counted = real != null;
              final diff = counted ? real - sys : 0;
              return ProductRow(
                name: pickStr(l, ['binCode', 'bin_code'], '未知库位'),
                code:
                    '${pickStr(l, ['goodsCode', 'goods_code'])} · 系统 $sys',
                qtyLabel: counted ? '$real' : '--',
                qtyUnit: counted ? (diff == 0 ? '一致' : '差异 $diff') : '待盘',
                qtyColor: !counted
                    ? PdaTheme.textSecondary
                    : diff == 0
                        ? PdaTheme.primary
                        : (diff < 0 ? PdaTheme.danger : PdaTheme.warning),
                statusIcon: !counted
                    ? Icons.radio_button_unchecked
                    : (diff == 0 ? Icons.check_circle : Icons.warning),
                statusColor: diff == 0
                    ? PdaTheme.primary
                    : (diff < 0 ? PdaTheme.danger : PdaTheme.warning),
                onTap:
                    !_canInput ? null : () => _countDialog(l, recount: false),
                trailing: counted && _canInput
                    ? PopupMenuButton<String>(
                        icon: const Icon(Icons.more_vert, size: 20),
                        onSelected: (v) {
                          if (v == 'recount') {
                            _countDialog(l, recount: true);
                          }
                        },
                        itemBuilder: (_) => const [
                          PopupMenuItem(
                              value: 'recount', child: Text('复盘本行')),
                        ],
                      )
                    : null,
              );
            }),
          ],
        ],
      ),
    );
  }

  Future<void> _countDialog(Map<String, dynamic> line,
      {required bool recount}) async {
    final ctrl = TextEditingController();
    final sys = pickNum(line, ['bookQty', 'book_qty']);
    await showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        title: Text(recount
            ? '复盘 · ${pickStr(line, ['binCode', 'bin_code'])}'
            : pickStr(line, ['binCode', 'bin_code'])),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('商品：${pickStr(line, ['goodsCode', 'goods_code'])}',
                style: PdaStyles.sub),
            const SizedBox(height: 4),
            Text('系统库存：$sys', style: PdaStyles.sub),
            const SizedBox(height: 12),
            TextField(
              controller: ctrl,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              autofocus: true,
              decoration:
                  InputDecoration(labelText: recount ? '复盘数量' : '实盘数量'),
            ),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('取消')),
          ElevatedButton(
            onPressed: () async {
              final q = num.tryParse(ctrl.text);
              if (q == null) {
                toast(ctx, '请输入数字', error: true);
                return;
              }
              Navigator.pop(ctx);
              final id = line['id']?.toString() ?? '';
              final r = await runWithBusy(
                context,
                () => recount
                    ? _svc.stocktakeRecount(id, q)
                    : _svc.stocktakeCount(id, q),
                successMsg: recount ? '复盘已记录' : '已记录',
              );
              if (r != null) _load();
            },
            child: const Text('确认'),
          ),
        ],
      ),
    );
  }

  Future<void> _submit() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        title: const Text('提交盘点'),
        content: const Text('提交后进入待主管审核状态，确定提交？'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          ElevatedButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('提交')),
        ],
      ),
    );
    if (ok != true) return;
    if (!mounted) return;
    final r = await runWithBusy(
      context,
      () => _svc.stocktakeSubmit(_taskId!),
      successMsg: '已提交，待主管审核',
    );
    if (r != null) _load();
  }

  Future<void> _audit() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        title: const Text('盘点审核'),
        content: const Text('审核通过后差异行将生成调整单并解冻库位，确定？'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          ElevatedButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('审核通过')),
        ],
      ),
    );
    if (ok != true) return;
    if (!mounted) return;
    final r = await runWithBusy(
      context,
      () => _svc.stocktakeAudit(_taskId!),
      successMsg: '审核完成',
    );
    if (r != null) _load();
  }
}
