import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/api_service.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';
import '../widgets/multi_unit_qty_field.dart';
import '../widgets/unit_qty_text.dart';
import 'stocktake_create_page.dart';

/// 盘点作业：当前仓任务列表 → 进入明细逐行录实盘 → 整单提交 → 主管审核。
///
/// 三态切换：
/// - 列表态：状态 Tab + 任务卡片，点击进入明细；保留扫任务号快捷进入路径。
/// - 详情态：bins 列表 + 录入/复盘/提交/审核。
/// - 建单态：FAB 弹出 StocktakeCreatePage（需 stocktake.create 权限）。
///
/// 按钮裁剪：scan 扫码进入 / input 录数+复盘 / submit 提交 / audit 审核 /
/// create 新建；后端 @RequirePerm 同口径强制。
class StocktakePage extends StatefulWidget {
  const StocktakePage({super.key});
  @override
  State<StocktakePage> createState() => _StocktakePageState();
}

class _StocktakePageState extends State<StocktakePage> {
  final _svc = WmsAppService.instance;
  final _auth = AuthService.instance;
  final _searchCtrl = TextEditingController(); // 列表态：扫任务号快捷进入

  // ===== 列表态 =====
  List<dynamic> _tasks = const [];
  bool _loading = true;
  String _statusTab = 'COUNTING'; // 默认盘点中

  // ===== 详情态 =====
  Map<String, dynamic>? _master;
  List<dynamic> _bins = const [];
  String? _taskId;

  // 权限
  bool get _canInput => _auth.can(PdaPerm.takeInput);
  bool get _canSubmit => _auth.can(PdaPerm.takeSubmit);
  bool get _canAudit => _auth.can(PdaPerm.takeAudit);
  bool get _canCreate => _auth.can(PdaPerm.takeCreate);

  /// 状态 Tab 配置（key=后端 status，value=展示文案）。
  /// 注意 createStocktake 创建后立即 SET status='COUNTING'，列表上基本不会出现 PENDING，
  /// 但保留作为「未启动」兜底。
  static const _statusTabs = <String, String>{
    'COUNTING': '盘点中',
    'PENDING_APPROVAL': '待审核',
    'APPROVED': '已审核',
    'PENDING': '待盘',
  };

  @override
  void initState() {
    super.initState();
    _loadList();
  }

  /// 接收全局扫码路由参数：arguments={'taskId':...} 时直接进入详情态，
  /// 省去"列表里再找任务"的步骤（智能路由）。
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final args = ModalRoute.of(context)?.settings.arguments;
    if (args is Map && _taskId == null) {
      final taskId = args['taskId']?.toString() ?? '';
      if (taskId.isNotEmpty) {
        _enterDetail(taskId);
      }
    }
  }

  // ==================== 列表态 ====================

  Future<void> _loadList() async {
    setState(() => _loading = true);
    try {
      _tasks = await _svc.stocktakeList(status: _statusTab);
    } catch (e) {
      if (mounted) toast(context, ApiService.friendlyError(e), error: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  /// 列表态顶部扫任务号 → 直接进入详情态（保留快捷路径，符合「扫一码进一单」习惯）。
  Future<void> _searchEnter() async {
    final v = _searchCtrl.text.trim();
    if (v.isEmpty) return;
    await _enterDetail(v);
  }

  // ==================== 详情态 ====================

  Future<void> _enterDetail(String taskId) async {
    setState(() {
      _taskId = taskId;
      _master = null;
      _bins = const [];
    });
    await _loadDetail();
  }

  Future<void> _exitDetail() async {
    setState(() {
      _taskId = null;
      _master = null;
      _bins = const [];
    });
    await _loadList();
  }

  Future<void> _loadDetail() async {
    if (_taskId == null) return;
    final r = await runWithBusy(context, () => _svc.stocktakeDetail(_taskId!));
    if (r != null) {
      setState(() {
        final m = r['master'];
        _master = m is Map ? Map<String, dynamic>.from(m) : null;
        final b = r['bins'];
        _bins = b is List ? b : const [];
      });
    }
  }

  int get _counted =>
      _bins.where((b) => (Map<String, dynamic>.from(b as Map))['realQty'] != null).length;
  int get _diffCount => _bins.where((b) {
        final m = Map<String, dynamic>.from(b as Map);
        final d = m['diffQty'];
        return d is num && d != 0;
      }).length;

  // ==================== Build ====================

  @override
  Widget build(BuildContext context) {
    final isDetail = _taskId != null;
    final bottoms = <Widget>[
      if (isDetail && _canSubmit)
        ElevatedButton.icon(
          icon: const Icon(Icons.upload_file, size: 18),
          label: const Text('提交盘点'),
          onPressed: _submit,
        ),
      if (isDetail && _canAudit)
        ElevatedButton.icon(
          icon: const Icon(Icons.verified, size: 18),
          label: const Text('审核'),
          onPressed: _audit,
        ),
    ];
    return PdaScaffold(
      title: isDetail ? '盘点明细' : '盘点作业',
      onRefresh: isDetail ? _loadDetail : _loadList,
      bottomButtons: bottoms.isEmpty ? null : bottoms,
      body: isDetail ? _buildDetail() : _buildList(),
      floatingActionButton: !isDetail && _canCreate
          ? FloatingActionButton.extended(
              onPressed: _openCreate,
              icon: const Icon(Icons.add),
              label: const Text('新建盘点'),
            )
          : null,
    );
  }

  Widget _buildList() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ScanZone(
          title: '扫描盘点任务号',
          hint: '扫/输任务号直接进入明细',
          controller: _searchCtrl,
          icon: Icons.numbers,
          buttonLabel: '加载',
          onSubmit: (_) => _searchEnter(),
        ),
        const SizedBox(height: 8),
        // 状态 Tab（点击切换后立即查询，符合 PDA 简化交互）
        SizedBox(
          height: 38,
          child: ListView(
            scrollDirection: Axis.horizontal,
            children: _statusTabs.entries.map((e) {
              final selected = e.key == _statusTab;
              return Padding(
                padding: const EdgeInsets.only(right: 6),
                child: ChoiceChip(
                  label: Text(e.value),
                  selected: selected,
                  onSelected: (_) {
                    if (selected) return;
                    setState(() => _statusTab = e.key);
                    _loadList();
                  },
                ),
              );
            }).toList(),
          ),
        ),
        const SizedBox(height: 8),
        if (_loading)
          const Padding(
            padding: EdgeInsets.all(40),
            child: Center(
                child: CircularProgressIndicator(color: PdaTheme.primary)),
          )
        else if (_tasks.isEmpty)
          const Padding(
            padding: EdgeInsets.all(40),
            child: Center(child: Text('暂无盘点任务', style: PdaStyles.sub)),
          )
        else
          ..._tasks.map((t) {
            final m = Map<String, dynamic>.from(t as Map);
            return _TaskCard(
              data: m,
              onTap: () => _enterDetail(pickStr(m, ['taskId', 'task_id'])),
            );
          }),
      ],
    );
  }

  Widget _buildDetail() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // 头部摘要：返回列表 + 任务号 + 进度
        Row(children: [
          IconButton(
            icon: const Icon(Icons.arrow_back),
            onPressed: _exitDetail,
            tooltip: '返回列表',
          ),
          Expanded(
            child: Text(
              '任务 ${pickStr(_master ?? const {}, ['taskNo', 'task_no'], _taskId ?? '')}',
              style: PdaStyles.title,
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ]),
        PdaAlert.info(
            '已盘 $_counted/${_bins.length} · 差异 $_diffCount · '
            '${_master?['warehouse'] ?? ''}'),
        if (_master != null) ...[
          const SizedBox(height: 6),
          Text(
              '类型 ${_countTypeText(pickStr(_master!, ['countType', 'count_type']))} · '
              '模式 ${_countModeText(pickStr(_master!, ['countMode', 'count_mode']))} · '
              '状态 ${pickStr(_master!, ['status'])}',
              style: PdaStyles.sub),
        ],
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
            code: '${pickStr(l, ['goodsCode', 'goods_code'])} · 系统 $sys',
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
            onTap: !_canInput ? null : () => _countDialog(l, recount: false),
            trailing: counted && _canInput
                ? PopupMenuButton<String>(
                    icon: const Icon(Icons.more_vert, size: 20),
                    onSelected: (v) {
                      if (v == 'recount') {
                        _countDialog(l, recount: true);
                      }
                    },
                    itemBuilder: (_) => const [
                      PopupMenuItem(value: 'recount', child: Text('复盘本行')),
                    ],
                  )
                : null,
          );
        }),
      ],
    );
  }

  // ==================== 录入/复盘/提交/审核 ====================

  Future<void> _countDialog(Map<String, dynamic> line,
      {required bool recount}) async {
    final sys = pickNum(line, ['bookQty', 'book_qty']);
    // ????????????????????????????
    final ctrl = TextEditingController(
        text: sys == sys.toInt() ? sys.toInt().toString() : sys.toString());
    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheet) => AlertDialog(
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
              Row(crossAxisAlignment: CrossAxisAlignment.center, children: [
                Text('系统库存 ', style: PdaStyles.sub),
                const SizedBox(width: 4),
                UnitQtyText(
                  value: sys,
                  unitConfig: line['unitConfig'],
                  baseUnit: pickStr(line, ['baseUnit','base_unit']),
                  numberStyle: PdaStyles.label.copyWith(color: PdaTheme.textPrimary),
                  unitStyle: PdaStyles.sub,
                ),
              ]),
              const SizedBox(height: 12),
              MultiUnitQtyField(
                value: num.tryParse(ctrl.text) ?? 0,
                label: recount ? '复盘数量' : '实盘数量',
                unitConfig: line['unitConfig'],
                baseUnit: pickStr(line, ['baseUnit','base_unit']),
                onChanged: (v) => setSheet(
                  () => ctrl.text =
                      v == v.toInt() ? v.toInt().toString() : v.toString(),
                ),
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
                if (r != null) _loadDetail();
              },
              child: const Text('确认'),
            ),
          ],
        ),
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
    if (r != null) _loadDetail();
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
    if (r != null) _loadDetail();
  }

  // ==================== 新建盘点 ====================

  Future<void> _openCreate() async {
    final created = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => const StocktakeCreatePage()),
    );
    if (created == true && mounted) _loadList();
  }

  // ==================== 文案 ====================

  String _countTypeText(String s) => switch (s) {
        'DYNAMIC' => '动盘',
        'CYCLE' => '循环盘',
        'SAMPLE' => '抽盘',
        _ => s,
      };

  String _countModeText(String s) => switch (s) {
        'BLIND' => '暗盘',
        'OPEN' => '明盘',
        _ => s,
      };
}

/// 列表态任务卡片：taskNo / warehouse / totalBins / countedBins / diffCount / createdAt。
class _TaskCard extends StatelessWidget {
  const _TaskCard({required this.data, required this.onTap});
  final Map<String, dynamic> data;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final taskNo = pickStr(data, ['taskNo', 'task_no']);
    final warehouse = pickStr(data, ['warehouse']);
    final total = pickNum(data, ['totalBins', 'total_bins']);
    final counted = pickNum(data, ['countedBins', 'counted_bins']);
    final diff = pickNum(data, ['diffCount', 'diff_count']);
    final status = pickStr(data, ['status']);
    final freeze = pickStr(data, ['freezeFlag', 'freeze_flag']) == 'Y';
    final createdAt = pickStr(data, ['createdAt', 'created_at']);

    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(children: [
                Expanded(
                    child: Text(taskNo.isEmpty ? '(无单号)' : taskNo,
                        style: PdaStyles.title)),
                if (freeze)
                  const Padding(
                    padding: EdgeInsets.only(right: 4),
                    child: Icon(Icons.ac_unit, size: 14, color: PdaTheme.info),
                  ),
                Text(_statusText(status),
                    style:
                        TextStyle(fontSize: 11, color: _statusColor(status))),
              ]),
              const SizedBox(height: 4),
              Text('$warehouse · 类型 ${pickStr(data, ['countType', 'count_type'])} · '
                  '${pickStr(data, ['countMode', 'count_mode']) == 'BLIND' ? '暗盘' : '明盘'}',
                  style: PdaStyles.sub),
              const SizedBox(height: 6),
              Row(children: [
                Icon(Icons.inventory_2, size: 14, color: PdaTheme.textSecondary),
                const SizedBox(width: 4),
                Text('已盘 $counted/$total · 差异 $diff',
                    style: PdaStyles.sub),
                const Spacer(),
                if (createdAt.isNotEmpty)
                  Text(createdAt,
                      style: const TextStyle(
                          fontSize: 11, color: PdaTheme.textSecondary)),
              ]),
            ],
          ),
        ),
      ),
    );
  }

  String _statusText(String s) => switch (s) {
        'PENDING' => '待盘',
        'COUNTING' => '盘点中',
        'PENDING_APPROVAL' => '待审核',
        'APPROVED' => '已审核',
        _ => s,
      };

  Color _statusColor(String s) => switch (s) {
        'APPROVED' => PdaTheme.primary,
        'PENDING_APPROVAL' => PdaTheme.warning,
        'COUNTING' => PdaTheme.info,
        _ => PdaTheme.textSecondary,
      };
}
