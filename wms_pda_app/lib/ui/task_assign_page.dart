import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/api_service.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 主管派工：当前仓全量拣货任务池。
/// task_assign.assign 指派（目标人必须启用且绑定当前仓，后端校验）；
/// task_assign.recall 撤回（已开始拣货不可撤回，后端校验）。
class TaskAssignPage extends StatefulWidget {
  const TaskAssignPage({super.key});
  @override
  State<TaskAssignPage> createState() => _TaskAssignPageState();
}

class _TaskAssignPageState extends State<TaskAssignPage> {
  final _svc = WmsAppService.instance;
  final _auth = AuthService.instance;
  List<dynamic> _tasks = const [];
  bool _loading = true;

  bool get _canAssign => _auth.can(PdaPerm.assignDo);
  bool get _canRecall => _auth.can(PdaPerm.assignRecall);

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      _tasks = await _svc.assignTasks();
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
        title: const Text('主管派工'),
        actions: [
          IconButton(icon: const Icon(Icons.refresh), onPressed: _load),
        ],
      ),
      body: SafeArea(child: _buildBody()),
    );
  }

  Widget _buildBody() {
    if (_loading) {
      return const Center(
          child: CircularProgressIndicator(color: PdaTheme.primary));
    }
    if (_tasks.isEmpty) {
      return const Center(child: Text('当前仓暂无拣货任务', style: PdaStyles.sub));
    }
    final pending = _tasks
        .where((t) =>
            pickStr(Map<String, dynamic>.from(t as Map), ['assignee'])
                .isEmpty)
        .length;
    return RefreshIndicator(
      onRefresh: _load,
      color: PdaTheme.primary,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 16),
        children: [
          PdaAlert.info('共 ${_tasks.length} 个任务，$pending 个待领取'),
          const SizedBox(height: 8),
          for (final t in _tasks)
            _card(Map<String, dynamic>.from(t as Map)),
        ],
      ),
    );
  }

  Widget _card(Map<String, dynamic> m) {
    final status = pickStr(m, ['status']);
    final assignee = pickStr(m, ['assignee']);
    final id = pickStr(m, ['taskId', 'task_id']);
    final picking = status == 'PICKING' || status == 'PICKED';
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
                    style: PdaStyles.title),
              ),
              _statusChip(status),
            ]),
            const SizedBox(height: 4),
            Text(
                [
                  '波次 ${pickStr(m, ['waveNo', 'wave_no'])}',
                  if (pickStr(m, ['zoneCode', 'zone_code']).isNotEmpty)
                    '库区 ${pickStr(m, ['zoneCode', 'zone_code'])}',
                  '${pickNum(m, ['pickedQty', 'picked_qty'])}/${pickNum(m, ['totalQty', 'total_qty'])} 件',
                ].join(' · '),
                style: PdaStyles.sub),
            const SizedBox(height: 2),
            Text(assignee.isEmpty ? '未指派（公共池）' : '指派人：$assignee',
                style: PdaStyles.sub.copyWith(
                    color: assignee.isEmpty
                        ? PdaTheme.warning
                        : PdaTheme.primary)),
            if (_canAssign || _canRecall) ...[
              const SizedBox(height: 8),
              Wrap(spacing: 8, runSpacing: 8, children: [
                if (_canAssign)
                  ElevatedButton.icon(
                    icon: const Icon(Icons.person_add_alt, size: 18),
                    label: Text(assignee.isEmpty ? '指派' : '改派'),
                    onPressed: () => _assignDialog(id, assignee),
                  ),
                if (_canRecall && assignee.isNotEmpty && !picking)
                  OutlinedButton.icon(
                    icon: const Icon(Icons.undo, size: 18),
                    label: const Text('撤回'),
                    onPressed: () => _recall(id),
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
      'PENDING' => ('待领取', PdaTheme.warning),
      'CLAIMED' => ('已指派', PdaTheme.info),
      'PICKING' => ('拣货中', PdaTheme.primary),
      'PICKED' => ('已拣完', PdaTheme.primary),
      'SUSPENDED' => ('已挂起', PdaTheme.danger),
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

  Future<void> _assignDialog(String id, String current) async {
    final ctrl = TextEditingController(text: current);
    String? error;
    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialog) => AlertDialog(
          backgroundColor: PdaTheme.surface,
          title: Text(current.isEmpty ? '指派任务' : '改派任务'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: ctrl,
                autofocus: true,
                decoration: const InputDecoration(
                    labelText: '拣货员工号 *',
                    hintText: '须启用且绑定当前作业仓'),
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
                if (ctrl.text.trim().isEmpty) {
                  setDialog(() => error = '请输入拣货员工号');
                  return;
                }
                Navigator.pop(ctx);
                final r = await runWithBusy(
                  context,
                  () => _svc.assignTask(
                      taskId: id, toAssignee: ctrl.text.trim()),
                  successMsg: '已指派',
                );
                if (r != null) _load();
              },
              child: const Text('确认指派'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _recall(String id) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        title: const Text('撤回指派'),
        content: const Text('撤回后任务回到公共任务池，确认？'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          ElevatedButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('撤回')),
        ],
      ),
    );
    if (ok != true) return;
    if (!mounted) return;
    final r = await runWithBusy(
      context,
      () => _svc.recallTask(id),
      successMsg: '已撤回',
    );
    if (r != null) _load();
  }
}
