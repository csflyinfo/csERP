import 'package:flutter/material.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 拣货作业：我的 / 可支援 / 全部 三个 tab，
/// 点任务进详情 → 逐行扫商品（pickItem）→ 全部拣完 completeTask。
class PickPage extends StatefulWidget {
  const PickPage({super.key});
  @override
  State<PickPage> createState() => _PickPageState();
}

class _PickPageState extends State<PickPage>
    with SingleTickerProviderStateMixin {
  final _svc = WmsAppService.instance;
  late final TabController _tab = TabController(length: 3, vsync: this);
  final List<List<dynamic>> _data = [[], [], []];
  bool _loading = true;
  Map<String, dynamic>? _detail;

  @override
  void initState() {
    super.initState();
    _tab.addListener(() {
      if (!_tab.indexIsChanging) _load();
    });
    _load();
  }

  Future<void> _load() async {
    if (_detail != null) {
      // 详情页刷新
      final id = _detail!['taskId'].toString();
      try {
        _detail = await _svc.pickTaskDetail(id);
      } catch (_) {}
      if (mounted) setState(() {});
      return;
    }
    setState(() => _loading = true);
    final scopes = ['mine', 'help', 'all'];
    try {
      final r = await Future.wait(scopes.map((s) => _svc.pickTasks(scope: s)));
      _data[0] = r[0];
      _data[1] = r[1];
      _data[2] = r[2];
    } catch (e) {
      if (mounted) toast(context, '$e', error: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_detail != null) return _buildDetail();
    return PdaScaffold(
      title: '拣货作业',
      onRefresh: _load,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Container(
            color: PdaTheme.surface,
            child: TabBar(
              controller: _tab,
              labelColor: PdaTheme.primary,
              indicatorColor: PdaTheme.primary,
              unselectedLabelColor: PdaTheme.textSecondary,
              tabs: [
                Tab(text: '我的 (${_data[0].length})'),
                Tab(text: '可支援 (${_data[1].length})'),
                Tab(text: '全部 (${_data[2].length})'),
              ],
            ),
          ),
          const SizedBox(height: 8),
          Expanded(
            child: _loading
                ? const Center(
                    child: CircularProgressIndicator(color: PdaTheme.primary))
                : TabBarView(
                    controller: _tab,
                    children: List.generate(3, (i) => _taskList(_data[i])),
                  ),
          ),
        ],
      ),
    );
  }

  Widget _taskList(List<dynamic> tasks) {
    if (tasks.isEmpty) {
      return const Center(child: Text('暂无任务', style: PdaStyles.sub));
    }
    return ListView(
      children: tasks.map((t) {
        final m = Map<String, dynamic>.from(t as Map);
        final status = pickStr(m, ['status']);
        final help = pickStr(m, ['helpTask', 'help_task']) == 'Y';
        final total = pickNum(m, ['totalQty', 'total_qty']);
        final picked = pickNum(m, ['pickedQty', 'picked_qty']);
        return Card(
          margin: const EdgeInsets.only(bottom: 8),
          child: ListTile(
            title: Row(children: [
              Expanded(
                  child: Text(pickStr(m, ['taskNo', 'task_no']),
                      style: PdaStyles.title)),
              if (help)
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: PdaTheme.warning.withValues(alpha: 0.2),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: const Text('支援',
                      style:
                          TextStyle(fontSize: 11, color: PdaTheme.warning)),
                ),
              const SizedBox(width: 6),
              Text(_statusText(status),
                  style: TextStyle(
                      fontSize: 11, color: _statusColor(status))),
            ]),
            subtitle: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const SizedBox(height: 4),
                Text(
                    '库区：${pickStr(m, ['zoneCode', 'zone_code'])} · 波次：${pickStr(m, ['waveNo', 'wave_no'])}',
                    style: PdaStyles.sub),
                const SizedBox(height: 6),
                PdaProgress(
                    current: picked,
                    total: total,
                    leftLabel: '$picked / $total 件'),
              ],
            ),
            onTap: () => _openTask(m),
          ),
        );
      }).toList(),
    );
  }

  Future<void> _openTask(Map<String, dynamic> task) async {
    final taskId = task['taskId'].toString();
    final status = pickStr(task, ['status']);
    final needClaim = status == 'PENDING' && pickStr(task, ['assignee']).isEmpty;
    if (needClaim) {
      final ok = await runWithBusy(
        context,
        () => _svc.pickClaim(taskId, help: _tab.index == 1),
        successMsg: '已领取',
      );
      if (ok == null) return;
    }
    if (!mounted) return;
    setState(() => _loading = true);
    try {
      _detail = await _svc.pickTaskDetail(taskId);
    } catch (e) {
      if (mounted) toast(context, '$e', error: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Widget _buildDetail() {
    final m = _detail!;
    final lines = (m['lines'] as List?) ?? const [];
    final total = lines.fold<num>(0, (s, l) {
      final lm = Map<String, dynamic>.from(l as Map);
      return s + pickNum(lm, ['requiredQty', 'required_qty']);
    });
    final picked = lines.fold<num>(0, (s, l) {
      final lm = Map<String, dynamic>.from(l as Map);
      return s + pickNum(lm, ['pickedQty', 'picked_qty']);
    });
    final pickMethod = pickStr(m, ['pickMethod', 'pick_method'], 'DEFAULT');
    final sortMode = pickStr(m, ['sortMode', 'sort_mode']);
    return PdaScaffold(
      title: pickStr(m, ['taskNo', 'task_no'], '拣货详情'),
      actions: [
        IconButton(
            onPressed: () => setState(() => _detail = null),
            icon: const Icon(Icons.list_alt)),
      ],
      onRefresh: _load,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          PdaAlert.warning(
              '${_pickMethodText(pickMethod)}'
              '${sortMode.isNotEmpty ? '（${_sortModeText(sortMode)}）' : ''}'),
          const SizedBox(height: 8),
          PdaProgress(
              current: picked, total: total, leftLabel: '已拣 $picked / $total'),
          const SizedBox(height: 8),
          ...lines.map((l) {
            final lm = Map<String, dynamic>.from(l as Map);
            final required = pickNum(lm, ['requiredQty', 'required_qty']);
            final got = pickNum(lm, ['pickedQty', 'picked_qty']);
            final done = got >= required && required > 0;
            final bin = pickStr(lm, ['allocBinCode', 'alloc_bin_code']);
            final zone = pickStr(lm, ['allocZoneCode', 'alloc_zone_code']);
            final sortDest = pickStr(lm, ['sortDestination', 'sort_destination']);
            return ProductRow(
              name: pickStr(lm, ['goodsName', 'goods_name'], '未命名'),
              code:
                  '${pickStr(lm, ['goodsCode', 'goods_code'])} · $zone $bin 应拣 $required'
                  '${sortDest.isNotEmpty ? '\n分播→$sortDest' : ''}',
              qtyLabel: done ? '$got' : (got > 0 ? '$got/$required' : '--'),
              qtyUnit: done ? '已拣' : '待拣',
              qtyColor: done ? PdaTheme.primary : null,
              statusIcon: done ? Icons.check_circle : Icons.radio_button_unchecked,
              statusColor: done ? PdaTheme.primary : PdaTheme.textSecondary,
              onTap: done ? null : () => _pickItemDialog(lm),
            );
          }),
          const SizedBox(height: 12),
          ElevatedButton.icon(
            icon: const Icon(Icons.done_all),
            label: const Text('拣货完成'),
            onPressed: () async {
              await runWithBusy(
                context,
                () => _svc.pickComplete(m['taskId'].toString()),
                successMsg: '拣货完成，任务已提交',
              );
              setState(() => _detail = null);
              _load();
            },
          ),
        ],
      ),
    );
  }

  Future<void> _pickItemDialog(Map<String, dynamic> line) async {
    final qtyCtrl = TextEditingController(text: '1');
    final binCtrl = TextEditingController(
        text: pickStr(line, ['allocBinCode', 'alloc_bin_code']));
    final batchCtrl = TextEditingController(
        text: pickStr(line, ['allocBatchNo', 'alloc_batch_no']));
    await showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        title: Text(pickStr(line, ['goodsName', 'goods_name'])),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
                '库位：${pickStr(line, ['allocBinCode', 'alloc_bin_code'])} · 批次：${pickStr(line, ['allocBatchNo', 'alloc_batch_no'])}',
                style: PdaStyles.sub),
            const SizedBox(height: 12),
            TextField(
              controller: qtyCtrl,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: '本次拣货数量'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: binCtrl,
              decoration: const InputDecoration(labelText: '实拣库位（空=推荐）'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: batchCtrl,
              decoration: const InputDecoration(labelText: '实拣批次（空=推荐）'),
            ),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('取消')),
          ElevatedButton(
            onPressed: () async {
              final q = num.tryParse(qtyCtrl.text) ?? 1;
              Navigator.pop(ctx);
              final r = await runWithBusy(
                context,
                () => _svc.pickItem(
                  taskId: _detail!['taskId'].toString(),
                  detailId: line['detailId'].toString(),
                  qty: q,
                  actualBin: binCtrl.text.trim(),
                  actualBatchNo: batchCtrl.text.trim(),
                ),
                successMsg: '已登记拣货',
              );
              if (r != null) _load();
            },
            child: const Text('确认拣货'),
          ),
        ],
      ),
    );
  }

  String _statusText(String s) {
    switch (s) {
      case 'PENDING':
        return '待领取';
      case 'CLAIMED':
        return '拣货中';
      case 'PICKING':
        return '拣货中';
      case 'PICKED':
        return '已拣';
      case 'CANCELLED':
        return '已取消';
      default:
        return s;
    }
  }

  Color _statusColor(String s) {
    switch (s) {
      case 'PENDING':
        return PdaTheme.textSecondary;
      case 'CLAIMED':
      case 'PICKING':
        return PdaTheme.warning;
      case 'PICKED':
        return PdaTheme.primary;
      case 'CANCELLED':
        return PdaTheme.danger;
      default:
        return PdaTheme.textSecondary;
    }
  }

  String _pickMethodText(String m) {
    switch (m) {
      case 'CASE_PICK':
        return '整件单拣：整件区商品按订单分拣';
      case 'CASE_MERGE':
        return '整件合拣：整件区商品合并拣货';
      case 'BULK_PICK':
        return '拆零单拣：拆零区商品按订单分拣';
      case 'BULK_MERGE':
        return '拆零合拣：拆零区商品合并拣货';
      default:
        return '按单拣货';
    }
  }

  String _sortModeText(String s) {
    switch (s) {
      case 'PICK_THEN_SORT':
        return '先拣后分';
      case 'SORT_WHILE_PICK':
        return '边拣边分';
      default:
        return s;
    }
  }
}
