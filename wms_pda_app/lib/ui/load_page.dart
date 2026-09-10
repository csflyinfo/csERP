import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/api_service.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 装车发运（PRD-28 卡片9）：
/// 待装车波次来自 /wms/app/load/tasks（当前仓 CHECKED 且已生成出库单）；
/// 扫码核对走 action=scan（load.scan），录入车牌后确认发运（load.confirm）。
/// 不再使用 PC 端 GET /wms/load/loadable（无 PDA 仓隔离）。
class LoadPage extends StatefulWidget {
  const LoadPage({super.key});
  @override
  State<LoadPage> createState() => _LoadPageState();
}

class _LoadPageState extends State<LoadPage> {
  final _svc = WmsAppService.instance;
  final _auth = AuthService.instance;
  List<dynamic> _waves = const [];
  bool _loading = true;
  final Set<String> _expanded = {};
  final Map<String, TextEditingController> _plateCtrls = {};

  bool get _canScan => _auth.can(PdaPerm.loadScan);
  bool get _canConfirm => _auth.can(PdaPerm.loadConfirm);

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    for (final c in _plateCtrls.values) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      _waves = await _svc.loadTasks();
    } catch (e) {
      if (mounted) toast(context, ApiService.friendlyError(e), error: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  TextEditingController _plateCtrl(String waveId) =>
      _plateCtrls.putIfAbsent(waveId, () => TextEditingController());

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('装车发运'),
        actions: [
          IconButton(icon: const Icon(Icons.refresh), onPressed: _load),
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
            child: Center(
                child: Text('暂无待装车波次（需已复核完成）',
                    style: PdaStyles.sub)))
      ]);
    }
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
      itemCount: _waves.length,
      itemBuilder: (_, i) =>
          _waveCard(Map<String, dynamic>.from(_waves[i] as Map)),
    );
  }

  Widget _waveCard(Map<String, dynamic> w) {
    final waveId = pickStr(w, ['waveId', 'wave_id']);
    final waveNo = pickStr(w, ['waveNo', 'wave_no']);
    final route = pickStr(w, ['routeLine', 'route_line']);
    final driver = pickStr(w, ['driver']);
    final orderCount = pickNum(w, ['orderCount', 'order_count']);
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
              child: Row(children: [
                const Icon(Icons.local_shipping_outlined,
                    color: PdaTheme.primary),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(waveNo.isEmpty ? '波次 $waveId' : waveNo,
                          style: PdaStyles.title),
                      const SizedBox(height: 2),
                      Text(
                        [
                          '订单 $orderCount',
                          if (route.isNotEmpty) '线路 $route',
                          if (driver.isNotEmpty) '司机 $driver',
                        ].join(' · '),
                        style: PdaStyles.sub,
                      ),
                    ],
                  ),
                ),
                Icon(open ? Icons.expand_less : Icons.expand_more,
                    color: PdaTheme.textSecondary),
              ]),
            ),
          ),
          if (open) ...[
            const Divider(height: 1),
            Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  TextField(
                    controller: _plateCtrl(waveId),
                    textCapitalization: TextCapitalization.characters,
                    enabled: _canConfirm,
                    decoration: const InputDecoration(
                      labelText: '车牌号',
                      prefixIcon: Icon(Icons.badge_outlined),
                      hintText: '例 京A12345',
                    ),
                  ),
                  const SizedBox(height: 10),
                  Wrap(spacing: 8, runSpacing: 8, children: [
                    if (_canScan)
                      OutlinedButton.icon(
                        icon: const Icon(Icons.qr_code_scanner, size: 18),
                        label: const Text('扫码核对'),
                        onPressed: () => _scanAck(waveId),
                      ),
                    if (_canConfirm)
                      ElevatedButton.icon(
                        icon: const Icon(Icons.local_shipping, size: 18),
                        label: const Text('确认装车发运'),
                        onPressed: () => _ship(waveId, waveNo),
                      ),
                  ]),
                  if (!_canScan && !_canConfirm)
                    const Padding(
                      padding: EdgeInsets.only(top: 4),
                      child: Text('当前账号无扫码/发运操作权限',
                          style: PdaStyles.sub),
                    ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _scanAck(String waveId) async {
    await runWithBusy(
      context,
      () => _svc.loadShip(waveId: waveId, vehiclePlate: '', action: 'scan'),
      successMsg: '扫码核对通过',
    );
  }

  Future<void> _ship(String waveId, String waveNo) async {
    final plate = _plateCtrl(waveId).text.trim();
    if (plate.isEmpty) {
      toast(context, '请输入车牌号', error: true);
      return;
    }
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        title: const Text('确认发运'),
        content: Text('波次 $waveNo\n车牌 $plate\n确认后生成销售出库并发车？'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          ElevatedButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('确认发运')),
        ],
      ),
    );
    if (ok != true) return;
    if (!mounted) return;
    final r = await runWithBusy(
      context,
      () =>
          _svc.loadShip(waveId: waveId, vehiclePlate: plate),
      successMsg: '装车发运完成',
    );
    if (r != null) {
      _plateCtrl(waveId).clear();
      _expanded.remove(waveId);
      _load();
    }
  }
}
