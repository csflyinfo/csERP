import 'package:flutter/material.dart';
import '../services/wms_app_service.dart';
import '../services/api_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 装车发运：扫描波次号 → 列出该波次可装车订单 → 录入车牌 → 确认发运。
/// 列表走 PC 端 /wms/load/loadable 接口（GET）；发运走 /wms/app/load/ship。
class LoadPage extends StatefulWidget {
  const LoadPage({super.key});
  @override
  State<LoadPage> createState() => _LoadPageState();
}

class _LoadPageState extends State<LoadPage> {
  final _waveCtrl = TextEditingController();
  final _plateCtrl = TextEditingController();
  List<dynamic> _orders = const [];
  String? _waveId;
  bool _loading = false;

  Future<void> _load() async {
    final v = _waveCtrl.text.trim();
    if (v.isEmpty) return;
    setState(() => _loading = true);
    try {
      // /wms/load/loadable 用 GET，参数 waveId 支持传 waveId 或 waveNo（后端 findWave 兼容）
      final r = await ApiService.instance
          .get('/wms/load/loadable', query: {'waveId': v});
      final list = (r as List?) ?? const [];
      setState(() {
        _orders = list;
        _waveId = list.isNotEmpty
            ? pickStr(Map<String, dynamic>.from(list.first as Map),
                ['waveId', 'wave_id'], v)
            : v;
      });
      if (list.isEmpty && mounted) toast(context, '该波次暂无可装车订单');
    } catch (e) {
      if (mounted) toast(context, ApiService.friendlyError(e), error: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return PdaScaffold(
      title: '装车发运',
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ScanZone(
            title: '扫描波次号',
            hint: '例 W202606100001',
            controller: _waveCtrl,
            icon: Icons.local_shipping_outlined,
            buttonLabel: '加载',
            onSubmit: (_) => _load(),
          ),
          const SizedBox(height: 12),
          if (_loading)
            const Padding(
              padding: EdgeInsets.all(40),
              child: Center(
                  child: CircularProgressIndicator(color: PdaTheme.primary)),
            )
          else if (_orders.isEmpty)
            const Padding(
              padding: EdgeInsets.all(40),
              child: Center(
                  child: Text('请扫描已复核完成的波次号',
                      style: PdaStyles.sub)),
            )
          else ...[
            PdaAlert.info('共 ${_orders.length} 张订单待装车'),
            const SizedBox(height: 8),
            ..._orders.map((o) {
              final m = Map<String, dynamic>.from(o as Map);
              return ProductRow(
                name: pickStr(m, ['sourceOrderNo', 'source_order_no'], '订单'),
                code:
                    '客户：${pickStr(m, ['customerName', 'customer_name'])} · ${pickNum(m, ['totalQty', 'total_qty'])} 件',
                statusIcon: Icons.check_circle,
                statusColor: PdaTheme.primary,
              );
            }),
            const SizedBox(height: 12),
            TextField(
              controller: _plateCtrl,
              textCapitalization: TextCapitalization.characters,
              decoration: const InputDecoration(
                labelText: '车牌号',
                prefixIcon: Icon(Icons.badge_outlined),
                hintText: '例 京A12345',
              ),
            ),
            const SizedBox(height: 12),
            ElevatedButton.icon(
              icon: const Icon(Icons.local_shipping),
              label: const Text('确认装车发运'),
              onPressed: _ship,
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _ship() async {
    final plate = _plateCtrl.text.trim();
    if (plate.isEmpty) {
      toast(context, '请输入车牌号', error: true);
      return;
    }
    await runWithBusy(
      context,
      () => WmsAppService.instance
          .loadShip(waveId: _waveId!, vehiclePlate: plate),
      successMsg: '装车发运完成',
    );
    setState(() {
      _orders = const [];
      _waveId = null;
    });
    _waveCtrl.clear();
    _plateCtrl.clear();
  }
}
