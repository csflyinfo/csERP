import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/api_service.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 拣货作业：我的 / 可支援 /（主管）全部 tab。
///
/// PRD-28 卡片9：tab 数量与按钮全部按功能点裁剪——
/// - pick.view 看列表，task_assign.view 才有"全部"池；
/// - pick.start 领取、pick.scan 扫码拣货、pick.confirm 完成；
/// - pick.short_pick 缺货上报、pick.skip 跳过、pick.transfer 转交；
/// 隐藏按钮直调仍会被后端 403（PDA-004）。
class PickPage extends StatefulWidget {
  const PickPage({super.key});
  @override
  State<PickPage> createState() => _PickPageState();
}

class _PickPageState extends State<PickPage>
    with SingleTickerProviderStateMixin {
  final _svc = WmsAppService.instance;
  final _auth = AuthService.instance;

  late final List<String> _scopes = [
    'mine',
    'help',
    if (_auth.can(PdaPerm.assignView)) 'all',
  ];
  late final TabController _tab =
      TabController(length: _scopes.length, vsync: this);
  late final List<List<dynamic>> _data =
      List.generate(_scopes.length, (_) => const []);
  bool _loading = true;
  Map<String, dynamic>? _detail;

  bool get _canClaim => _auth.can(PdaPerm.pickStart);
  bool get _canScan => _auth.can(PdaPerm.pickScan);
  bool get _canComplete => _auth.can(PdaPerm.pickConfirm);
  bool get _canShort => _auth.can(PdaPerm.pickShort);
  bool get _canSkip => _auth.can(PdaPerm.pickSkip);
  bool get _canTransfer => _auth.can(PdaPerm.pickTransfer);

  String _scopeLabel(String s) => switch (s) {
        'mine' => '我的',
        'help' => '可支援',
        'all' => '全部',
        _ => s,
      };

  @override
  void initState() {
    super.initState();
    _tab.addListener(() {
      if (!_tab.indexIsChanging) setState(() {});
    });
    _loadList();
  }

  @override
  void dispose() {
    _tab.dispose();
    super.dispose();
  }

  /// 每个 scope 独立容错：主管池 403/网络失败不影响"我的/可支援"。
  Future<void> _loadList() async {
    setState(() => _loading = true);
    final results = await Future.wait(
      _scopes.map((s) async {
        try {
          return await _svc.pickTasks(scope: s);
        } catch (_) {
          return <dynamic>[];
        }
      }),
    );
    for (var i = 0; i < results.length; i++) {
      _data[i] = results[i];
    }
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _refreshDetail() async {
    final id = _detail!['taskId'].toString();
    try {
      _detail = await _svc.pickTaskDetail(id);
    } catch (e) {
      if (mounted) toast(context, ApiService.friendlyError(e), error: true);
    }
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    if (_detail != null) return _buildDetail();
    // 不用 PdaScaffold：内部 Expanded 与它的 ListView 包裹冲突（见 receive_page 注释）。
    return Scaffold(
      appBar: AppBar(
        title: const Text('拣货作业'),
        bottom: TabBar(
          controller: _tab,
          labelColor: PdaTheme.primary,
          indicatorColor: PdaTheme.primary,
          unselectedLabelColor: PdaTheme.textSecondary,
          tabs: [
            for (var i = 0; i < _scopes.length; i++)
              Tab(text: '${_scopeLabel(_scopes[i])} (${_data[i].length})'),
          ],
        ),
      ),
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _loadList,
          color: PdaTheme.primary,
          child: _loading
              ? ListView(children: const [
                  SizedBox(
                      height: 200,
                      child: Center(
                          child: CircularProgressIndicator(
                              color: PdaTheme.primary)))
                ])
              : TabBarView(
                  controller: _tab,
                  children: List.generate(
                      _scopes.length, (i) => _taskList(_data[i])),
                ),
        ),
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
                  style:
                      TextStyle(fontSize: 11, color: _statusColor(status))),
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
    final needClaim =
        status == 'PENDING' && pickStr(task, ['assignee']).isEmpty;
    if (needClaim) {
      if (!_canClaim) {
        toast(context, '无领取拣货任务权限：wms_pda.pick.start', error: true);
        return;
      }
      final ok = await runWithBusy(
        context,
        () => _svc.pickClaim(taskId, help: _tab.index == 1),
        successMsg: '已领取',
      );
      if (ok == null) return;
    }
    if (!mounted) return;
    try {
      _detail = await _svc.pickTaskDetail(taskId);
      setState(() {});
    } catch (e) {
      if (mounted) toast(context, ApiService.friendlyError(e), error: true);
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
        if (_canTransfer)
          IconButton(
            tooltip: '转交任务',
            onPressed: _transferDialog,
            icon: const Icon(Icons.swap_horiz),
          ),
        IconButton(
            onPressed: () => setState(() => _detail = null),
            icon: const Icon(Icons.list_alt)),
      ],
      onRefresh: _refreshDetail,
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
            final lineStatus = pickStr(lm, ['status']);
            final short = lineStatus == 'SHORT';
            final done = short || (got >= required && required > 0);
            final bin = pickStr(lm, ['allocBinCode', 'alloc_bin_code']);
            final zone = pickStr(lm, ['allocZoneCode', 'alloc_zone_code']);
            final sortDest =
                pickStr(lm, ['sortDestination', 'sort_destination']);
            return ProductRow(
              name: pickStr(lm, ['goodsName', 'goods_name'], '未命名'),
              code:
                  '${pickStr(lm, ['goodsCode', 'goods_code'])} · $zone $bin 应拣 $required'
                  '${sortDest.isNotEmpty ? '\n分播→$sortDest' : ''}',
              qtyLabel: short ? '缺' : (done ? '$got' : (got > 0 ? '$got/$required' : '--')),
              qtyUnit: short ? '缺货' : (done ? '已拣' : '待拣'),
              qtyColor: short
                  ? PdaTheme.danger
                  : (done ? PdaTheme.primary : null),
              statusIcon: short
                  ? Icons.error_outline
                  : (done
                      ? Icons.check_circle
                      : Icons.radio_button_unchecked),
              statusColor: short
                  ? PdaTheme.danger
                  : (done ? PdaTheme.primary : PdaTheme.textSecondary),
              onTap: done ? null : () => _pickItemDialog(lm),
              trailing: (!done && (_canShort || _canSkip))
                  ? PopupMenuButton<String>(
                      icon: const Icon(Icons.more_vert, size: 20),
                      onSelected: (v) {
                        if (v == 'short') _shortDialog(lm);
                        if (v == 'skip') _skipDialog(lm);
                      },
                      itemBuilder: (_) => [
                        if (_canShort)
                          const PopupMenuItem(
                              value: 'short',
                              child: Text('缺货上报')),
                        if (_canSkip)
                          const PopupMenuItem(
                              value: 'skip', child: Text('跳过商品')),
                      ],
                    )
                  : null,
            );
          }),
          const SizedBox(height: 12),
          if (_canComplete)
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
                _loadList();
              },
            ),
          if (!_canScan && !_canComplete)
            const PdaAlert(
              text: '当前账号只有查看权限，不能扫码拣货或提交完成',
              color: PdaTheme.textSecondary,
              icon: Icons.lock_outline,
            ),
        ],
      ),
    );
  }

  Future<void> _pickItemDialog(Map<String, dynamic> line) async {
    if (!_canScan) {
      toast(context, '无扫码拣货权限：wms_pda.pick.scan', error: true);
      return;
    }
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
              if (r != null) _refreshDetail();
            },
            child: const Text('确认拣货'),
          ),
        ],
      ),
    );
  }

  /// 缺货上报：缺货数量可空（整行），原因必填。
  Future<void> _shortDialog(Map<String, dynamic> line) async {
    final qtyCtrl = TextEditingController();
    final reasonCtrl = TextEditingController();
    String? error;
    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialog) => AlertDialog(
          backgroundColor: PdaTheme.surface,
          title: const Text('缺货上报'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(pickStr(line, ['goodsName', 'goods_name']),
                  style: PdaStyles.title),
              const SizedBox(height: 10),
              TextField(
                controller: qtyCtrl,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                    labelText: '缺货数量（空=按整行未拣计）'),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: reasonCtrl,
                maxLines: 2,
                decoration: const InputDecoration(
                    labelText: '缺货原因 *', hintText: '例如：库位无货、破损'),
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
                if (reasonCtrl.text.trim().isEmpty) {
                  setDialog(() => error = '请填写缺货原因');
                  return;
                }
                final shortQty = num.tryParse(qtyCtrl.text.trim());
                Navigator.pop(ctx);
                final r = await runWithBusy(
                  context,
                  () => _svc.pickShort(
                    detailId: line['detailId'].toString(),
                    shortQty: shortQty,
                    reason: reasonCtrl.text.trim(),
                  ),
                  successMsg: '已上报缺货',
                );
                if (r != null) _refreshDetail();
              },
              child: const Text('提交上报'),
            ),
          ],
        ),
      ),
    );
  }

  /// 跳过商品：不改明细状态，仅异常单留痕，原因必填。
  Future<void> _skipDialog(Map<String, dynamic> line) async {
    final reasonCtrl = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        title: const Text('跳过商品'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(pickStr(line, ['goodsName', 'goods_name']),
                style: PdaStyles.title),
            const SizedBox(height: 10),
            TextField(
              controller: reasonCtrl,
              maxLines: 2,
              decoration: const InputDecoration(
                  labelText: '跳过原因 *', hintText: '例如：找不到商品，留待后续处理'),
            ),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          ElevatedButton(
            onPressed: () {
              if (reasonCtrl.text.trim().isEmpty) {
                toast(ctx, '请填写跳过原因', error: true);
                return;
              }
              Navigator.pop(ctx, true);
            },
            child: const Text('确认跳过'),
          ),
        ],
      ),
    );
    if (ok == true) {
      if (!mounted) return;
      final r = await runWithBusy(
        context,
        () => _svc.pickSkip(
          detailId: line['detailId'].toString(),
          reason: reasonCtrl.text.trim(),
        ),
        successMsg: '已登记跳过',
      );
      if (r != null) _refreshDetail();
    }
  }

  /// 转交：目标人必须启用且绑定当前仓（后端裁决）。
  Future<void> _transferDialog() async {
    final userCtrl = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        title: const Text('转交拣货任务'),
        content: TextField(
          controller: userCtrl,
          decoration: const InputDecoration(
            labelText: '接收人工号 / 账号 *',
            hintText: '对方必须已绑定当前仓库',
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          ElevatedButton(
            onPressed: () {
              if (userCtrl.text.trim().isEmpty) {
                toast(ctx, '请填写接收人', error: true);
                return;
              }
              Navigator.pop(ctx, true);
            },
            child: const Text('确认转交'),
          ),
        ],
      ),
    );
    if (ok == true) {
      if (!mounted) return;
      final r = await runWithBusy(
        context,
        () => _svc.pickTransfer(
          taskId: _detail!['taskId'].toString(),
          toAssignee: userCtrl.text.trim(),
        ),
        successMsg: '任务已转交',
      );
      if (r != null) {
        setState(() => _detail = null);
        _loadList();
      }
    }
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
