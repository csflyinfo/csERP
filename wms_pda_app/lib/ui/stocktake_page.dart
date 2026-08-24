import 'package:flutter/material.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 盘点作业：扫描/输入盘点任务号 → 逐行扫库位/商品录实盘。
/// 后端暂无 PDA 盘点任务列表端点（盘点任务由 PC 端创建），因此这里直接走单号。
class StocktakePage extends StatefulWidget {
  const StocktakePage({super.key});
  @override
  State<StocktakePage> createState() => _StocktakePageState();
}

class _StocktakePageState extends State<StocktakePage> {
  final _svc = WmsAppService.instance;
  final _taskCtrl = TextEditingController();
  List<dynamic> _bins = const [];
  String? _taskId;
  int _diffCount = 0;

  Future<void> _load() async {
    final v = _taskCtrl.text.trim();
    if (v.isEmpty) return;
    setState(() => _taskId = v);
    await runWithBusy(context, () async {
      _bins = await _svc.stocktakeBins(v);
      _diffCount = 0;
      if (mounted) setState(() {});
    });
  }

  @override
  Widget build(BuildContext context) {
    return PdaScaffold(
      title: '盘点作业',
      onRefresh: _taskId == null ? null : _load,
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
            PdaAlert.danger(
                '盘点任务：$_taskId · 已盘 ${_bins.where((b) => pickNum(Map<String, dynamic>.from(b as Map), ['realQty', 'counted_qty']) > 0).length}/${_bins.length} · 差异 $_diffCount'),
            const SizedBox(height: 8),
            ..._bins.map((raw) {
              final l = Map<String, dynamic>.from(raw as Map);
              final sys = pickNum(l, ['stockQty', 'system_qty', 'expected_qty']);
              final real = pickNum(l, ['realQty', 'counted_qty']);
              final diff = real - sys;
              final counted = pickStr(l, ['countedAt', 'counted_at']).isNotEmpty;
              return ProductRow(
                name: pickStr(l, ['binCode', 'bin_code'], '未知库位'),
                code:
                    '${pickStr(l, ['goodsCode', 'goods_code'])} · 系统 $sys',
                qtyLabel: counted ? '$real' : '--',
                qtyUnit: counted ? (diff == 0 ? '一致' : '差异 $diff') : '待盘',
                qtyColor: diff == 0
                    ? PdaTheme.primary
                    : (diff < 0 ? PdaTheme.danger : PdaTheme.warning),
                statusIcon: counted
                    ? (diff == 0 ? Icons.check_circle : Icons.warning)
                    : Icons.radio_button_unchecked,
                statusColor: diff == 0
                    ? PdaTheme.primary
                    : (diff < 0 ? PdaTheme.danger : PdaTheme.warning),
                onTap: () => _countDialog(l),
              );
            }),
          ],
        ],
      ),
    );
  }

  Future<void> _countDialog(Map<String, dynamic> line) async {
    final ctrl = TextEditingController();
    final sys = pickNum(line, ['stockQty', 'system_qty', 'expected_qty']);
    await showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        title: Text(pickStr(line, ['binCode', 'bin_code'])),
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
              keyboardType: TextInputType.number,
              autofocus: true,
              decoration: const InputDecoration(labelText: '实盘数量'),
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
              final id = line['id']?.toString() ??
                  line['detailId']?.toString() ??
                  line['detail_id']?.toString() ??
                  '';
              await runWithBusy(
                context,
                () => _svc.stocktakeCount(id, q),
                successMsg: '已记录',
              );
              _load();
            },
            child: const Text('确认'),
          ),
        ],
      ),
    );
  }
}
