import 'package:flutter/material.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 复核出库：直接输入/扫描波次号 → 拉任务详情（同 pickTaskDetail）
/// → 对每张 source_order_no 提交 checkPass / checkFail。
/// 注意 PDA 端没有独立的"待复核列表"端点，这里直接走扫码/输入。
class CheckPage extends StatefulWidget {
  const CheckPage({super.key});
  @override
  State<CheckPage> createState() => _CheckPageState();
}

class _CheckPageState extends State<CheckPage> {
  final _svc = WmsAppService.instance;
  final _waveCtrl = TextEditingController();
  Map<String, dynamic>? _detail;

  Future<void> _load() async {
    final v = _waveCtrl.text.trim();
    if (v.isEmpty) return;
    await runWithBusy(context, () async {
      _detail = await _svc.pickTaskDetail(v);
      if (mounted) setState(() {});
    }, busyMsg: '加载中');
  }

  @override
  Widget build(BuildContext context) {
    return PdaScaffold(
      title: '复核出库',
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ScanZone(
            title: '扫描波次号 / 拣货任务号',
            hint: '例 W202606100001',
            controller: _waveCtrl,
            icon: Icons.qr_code_scanner,
            buttonLabel: '加载',
            onSubmit: (_) => _load(),
          ),
          const SizedBox(height: 12),
          if (_detail == null)
            const Padding(
              padding: EdgeInsets.all(40),
              child: Center(
                child: Text('请扫描波次号或拣货任务号开始复核',
                    style: PdaStyles.sub),
              ),
            )
          else ...[
            PdaAlert.info(
                '波次：${pickStr(_detail!, ['waveNo', 'wave_no'])} · 库区：${pickStr(_detail!, ['zoneCode', 'zone_code'])}'),
            const SizedBox(height: 8),
            ..._groupByOrder(),
          ],
        ],
      ),
    );
  }

  /// 把 lines 按 source_order_no 分组，每张订单生成一张复核卡。
  List<Widget> _groupByOrder() {
    final lines = (_detail!['lines'] as List?) ?? const [];
    final groups = <String, List<Map<String, dynamic>>>{};
    for (final raw in lines) {
      final l = Map<String, dynamic>.from(raw as Map);
      final o = pickStr(l, ['sourceOrderNo', 'source_order_no'], '未知订单');
      groups.putIfAbsent(o, () => []).add(l);
    }
    return groups.entries.map((e) {
      final orderNo = e.key;
      final list = e.value;
      final customer = pickStr(list.first, ['customerName', 'customer_name']);
      return Card(
        margin: const EdgeInsets.only(bottom: 10),
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(orderNo, style: PdaStyles.title),
              if (customer.isNotEmpty) ...[
                const SizedBox(height: 2),
                Text(customer, style: PdaStyles.sub),
              ],
              const SizedBox(height: 8),
              ...list.map((l) {
                final req = pickNum(l, ['requiredQty', 'required_qty']);
                final picked = pickNum(l, ['pickedQty', 'picked_qty']);
                final short = req - picked;
                return ProductRow(
                  name: pickStr(l, ['goodsName', 'goods_name'], '未命名'),
                  code:
                      '${pickStr(l, ['goodsCode', 'goods_code'])} · 应 $req / 拣 $picked',
                  qtyLabel: short > 0 ? '-$short' : '$picked',
                  qtyUnit: short > 0 ? '少发' : '一致',
                  qtyColor: short > 0 ? PdaTheme.danger : PdaTheme.primary,
                  statusIcon: short > 0 ? Icons.warning : Icons.check,
                  statusColor: short > 0 ? PdaTheme.danger : PdaTheme.primary,
                );
              }),
              const SizedBox(height: 6),
              Row(children: [
                Expanded(
                  child: OutlinedButton.icon(
                    icon: const Icon(Icons.error_outline,
                        size: 18, color: PdaTheme.danger),
                    label: const Text('差异登记',
                        style: TextStyle(color: PdaTheme.danger)),
                    onPressed: () => _submit(orderNo, false),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: ElevatedButton.icon(
                    icon: const Icon(Icons.check, size: 18),
                    label: const Text('复核通过'),
                    onPressed: () => _submit(orderNo, true),
                  ),
                ),
              ]),
            ],
          ),
        ),
      );
    }).toList();
  }

  Future<void> _submit(String orderNo, bool pass) async {
    final waveId = _detail!['waveId'].toString();
    final ok = await runWithBusy(
      context,
      () => pass
          ? _svc.checkPass(waveId: waveId, orderNo: orderNo)
          : _svc.checkFail(waveId: waveId, orderNo: orderNo),
      successMsg: pass ? '复核通过，已触发出库' : '差异已登记，开异常单',
    );
    if (ok != null) _load();
  }
}
