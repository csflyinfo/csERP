import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../config/pda_perms.dart';
import '../services/api_service.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 绩效查询（PDA-006）：默认强制本人；view_team 主管可切「全仓」（仅当前仓绑定用户）；
/// performance.export 导出 CSV 到剪贴板。越权 scope=team / export=true 由后端 403 兜底。
class PerformancePage extends StatefulWidget {
  const PerformancePage({super.key});
  @override
  State<PerformancePage> createState() => _PerformancePageState();
}

class _PerformancePageState extends State<PerformancePage> {
  final _svc = WmsAppService.instance;
  final _auth = AuthService.instance;

  bool get _canTeam => _auth.can(PdaPerm.perfTeam);
  bool get _canExport => _auth.can(PdaPerm.perfExport);

  late DateTime _from;
  late DateTime _to;
  String _scope = 'self'; // self | team
  List<dynamic> _rows = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    _from = now;
    _to = now;
    _load();
  }

  String _d(DateTime d) =>
      '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  Future<void> _load({bool export = false}) async {
    setState(() => _loading = true);
    try {
      _rows = await _svc.performance(
        scope: _scope,
        from: _d(_from),
        to: _d(_to),
        export: export,
      );
      if (export) {
        await Clipboard.setData(ClipboardData(text: _toCsv(_rows)));
        if (mounted) toast(context, '已导出 ${_rows.length} 行 CSV（已复制到剪贴板）');
      }
    } catch (e) {
      if (mounted) toast(context, ApiService.friendlyError(e), error: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  String _toCsv(List<dynamic> rows) {
    const head = [
      '日期', '工号', '角色', '收货量', '收货行', '上架量', '上架行',
      '拣货量', '拣货行', '复核单', '补货次数', '差错数', '工时(分)'
    ];
    final buf = StringBuffer(head.join(','))..writeln();
    for (final raw in rows) {
      final m = Map<String, dynamic>.from(raw as Map);
      buf.writeln([
        pickStr(m, ['statDate', 'stat_date']),
        pickStr(m, ['operator']),
        pickStr(m, ['roleCode', 'role_code']),
        pickNum(m, ['receiveQty', 'receive_qty']),
        pickNum(m, ['receiveLines', 'receive_lines']),
        pickNum(m, ['putawayQty', 'putaway_qty']),
        pickNum(m, ['putawayLines', 'putaway_lines']),
        pickNum(m, ['pickQty', 'pick_qty']),
        pickNum(m, ['pickLines', 'pick_lines']),
        pickNum(m, ['checkOrders', 'check_orders']),
        pickNum(m, ['replenishCount', 'replenish_count']),
        pickNum(m, ['errorCount', 'error_count']),
        pickNum(m, ['workMinutes', 'work_minutes']),
      ].join(','));
    }
    return buf.toString();
  }

  void _quickRange(int days) {
    final now = DateTime.now();
    _to = now;
    _from = now.subtract(Duration(days: days - 1));
    _load();
  }

  Future<void> _pickDate(bool isFrom) async {
    final init = isFrom ? _from : _to;
    final d = await showDatePicker(
      context: context,
      initialDate: init,
      firstDate: DateTime(2024),
      lastDate: DateTime.now().add(const Duration(days: 1)),
    );
    if (d == null) return;
    setState(() {
      if (isFrom) {
        _from = d;
        if (_from.isAfter(_to)) _to = _from;
      } else {
        _to = d;
        if (_to.isBefore(_from)) _from = _to;
      }
    });
    _load();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('作业绩效'),
        actions: [
          if (_canExport)
            IconButton(
              tooltip: '导出 CSV',
              icon: const Icon(Icons.ios_share),
              onPressed: _loading ? null : () => _load(export: true),
            ),
        ],
      ),
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
              child: Row(children: [
                if (_canTeam)
                  SegmentedButton<String>(
                    segments: const [
                      ButtonSegment(value: 'self', label: Text('本人')),
                      ButtonSegment(value: 'team', label: Text('全仓')),
                    ],
                    selected: {_scope},
                    style: const ButtonStyle(visualDensity: VisualDensity.compact),
                    onSelectionChanged: (s) {
                      _scope = s.first;
                      _load();
                    },
                  )
                else
                  PdaAlert.info('仅可查看本人绩效'),
              ]),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              child: Wrap(spacing: 8, runSpacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
                OutlinedButton(
                    onPressed: () => _pickDate(true),
                    child: Text(_d(_from))),
                const Text('至'),
                OutlinedButton(
                    onPressed: () => _pickDate(false),
                    child: Text(_d(_to))),
                ActionChip(
                    label: const Text('今天'), onPressed: () => _quickRange(1)),
                ActionChip(
                    label: const Text('近7天'), onPressed: () => _quickRange(7)),
                ActionChip(
                    label: const Text('近30天'), onPressed: () => _quickRange(30)),
              ]),
            ),
            const Divider(height: 1),
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
      return const Center(child: Text('所选区间暂无绩效数据', style: PdaStyles.sub));
    }
    return RefreshIndicator(
      onRefresh: () => _load(),
      color: PdaTheme.primary,
      child: ListView.builder(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 16),
        itemCount: _rows.length,
        itemBuilder: (_, i) =>
            _card(Map<String, dynamic>.from(_rows[i] as Map)),
      ),
    );
  }

  Widget _card(Map<String, dynamic> m) {
    final errorCount = pickNum(m, ['errorCount', 'error_count']);
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Expanded(
                child: Text(
                    '${pickStr(m, ['statDate', 'stat_date'])}${_scope == 'team' ? ' · ${pickStr(m, ['operator'])}' : ''}',
                    style: PdaStyles.title),
              ),
              if (errorCount > 0)
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: PdaTheme.danger.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text('差错 $errorCount',
                      style: const TextStyle(
                          fontSize: 11,
                          color: PdaTheme.danger,
                          fontWeight: FontWeight.w600)),
                ),
            ]),
            const SizedBox(height: 8),
            Wrap(spacing: 10, runSpacing: 6, children: [
              _metric(Icons.move_to_inbox, '收货',
                  pickNum(m, ['receiveQty', 'receive_qty'])),
              _metric(Icons.shelves, '上架',
                  pickNum(m, ['putawayQty', 'putaway_qty'])),
              _metric(Icons.shopping_basket_outlined, '拣货',
                  pickNum(m, ['pickQty', 'pick_qty'])),
              _metric(Icons.fact_check_outlined, '复核单',
                  pickNum(m, ['checkOrders', 'check_orders'])),
              _metric(Icons.restore, '补货',
                  pickNum(m, ['replenishCount', 'replenish_count'])),
              _metric(Icons.timer_outlined, '工时(分)',
                  pickNum(m, ['workMinutes', 'work_minutes'])),
            ]),
          ],
        ),
      ),
    );
  }

  Widget _metric(IconData icon, String label, num v) => Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 15, color: PdaTheme.textSecondary),
          const SizedBox(width: 3),
          Text('$label ', style: PdaStyles.sub),
          Text('$v',
              style: PdaStyles.label
                  .copyWith(color: PdaTheme.primary)),
        ],
      );
}
