import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 新建盘点任务表单（PDA 端）。仓库强制取登录仓（后端 createStocktake 处理）。
///
/// 入参对照后端 WmsInternalService.createStocktake：
/// - countType: DYNAMIC 动盘 / CYCLE 循环盘（排除近 30 天已盘库位）/ SAMPLE 抽盘（20% 随机）
/// - countMode: BLIND 暗盘 / OPEN 明盘；留空走 WMS_COUNT_MODE_DEFAULT
/// - scopeText: 描述性范围文本（可选）
/// - binCodes: 限定盘点库位范围（可选，留空=当前仓全量快照）
/// - freezeFlag: Y/N（留空走 WMS_COUNT_FREEZE_BIN 默认）
/// - remark: 备注
class StocktakeCreatePage extends StatefulWidget {
  const StocktakeCreatePage({super.key});
  @override
  State<StocktakeCreatePage> createState() => _StocktakeCreatePageState();
}

class _StocktakeCreatePageState extends State<StocktakeCreatePage> {
  final _svc = WmsAppService.instance;
  final _auth = AuthService.instance;

  // 表单状态
  String _countType = 'DYNAMIC';
  String _countMode = ''; // 空=跟随系统参数
  bool _freeze = true;
  final _scopeCtrl = TextEditingController();
  final _remarkCtrl = TextEditingController();
  final _binInputCtrl = TextEditingController();
  final List<String> _binCodes = [];

  bool get _canCreate => _auth.can(PdaPerm.takeCreate);

  @override
  void initState() {
    super.initState();
    if (!_canCreate) {
      // 进入页面时即无权限，提示并返回（兜底；正常入口已 hidden）
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) {
          toast(context, '无新建盘点权限', error: true);
          Navigator.of(context).pop(false);
        }
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return PdaScaffold(
      title: '新建盘点',
      bottomButtons: [
        OutlinedButton.icon(
          icon: const Icon(Icons.close, size: 18),
          label: const Text('取消'),
          onPressed: () => Navigator.of(context).pop(false),
        ),
        ElevatedButton.icon(
          icon: const Icon(Icons.check, size: 18),
          label: const Text('提交'),
          onPressed: _submit,
        ),
      ],
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          PdaAlert.info('仓库取登录仓；提交后立即生成盘点单并快照库存。'),
          const SizedBox(height: 12),

          // 盘点类型
          _LabelField(label: '盘点类型 *'),
          const SizedBox(height: 4),
          _SegSelect(
            value: _countType,
            options: const [
              ('DYNAMIC', '动盘'),
              ('CYCLE', '循环盘'),
              ('SAMPLE', '抽盘'),
            ],
            onChanged: (v) => setState(() => _countType = v),
          ),
          const SizedBox(height: 6),
          Text(_countTypeHint(), style: PdaStyles.sub),
          const SizedBox(height: 14),

          // 盘点模式
          _LabelField(label: '盘点模式'),
          const SizedBox(height: 4),
          _SegSelect(
            value: _countMode.isEmpty ? 'AUTO' : _countMode,
            options: const [
              ('AUTO', '跟随参数'),
              ('BLIND', '暗盘'),
              ('OPEN', '明盘'),
            ],
            onChanged: (v) =>
                setState(() => _countMode = v == 'AUTO' ? '' : v),
          ),
          const SizedBox(height: 6),
          Text(_countModeHint(), style: PdaStyles.sub),
          const SizedBox(height: 14),

          // 冻结库位
          SwitchListTile(
            value: _freeze,
            onChanged: (v) => setState(() => _freeze = v),
            title: const Text('冻结被盘库位'),
            subtitle: const Text('盘点期间冻结库位出/入库，防止账实漂移'),
            dense: true,
          ),
          const SizedBox(height: 14),

          // 范围描述
          _LabelField(label: '范围描述（可选）'),
          const SizedBox(height: 4),
          TextField(
            controller: _scopeCtrl,
            decoration: const InputDecoration(
              hintText: '如：A 区货架 1-3',
              prefixIcon: Icon(Icons.text_fields),
            ),
          ),
          const SizedBox(height: 14),

          // 库位范围
          _LabelField(label: '库位范围（可选，留空=当前仓全量）'),
          const SizedBox(height: 4),
          Row(children: [
            Expanded(
              child: TextField(
                controller: _binInputCtrl,
                decoration: const InputDecoration(
                  hintText: '扫/输库位号',
                  prefixIcon: Icon(Icons.location_on),
                ),
                onSubmitted: (_) => _addBin(),
              ),
            ),
            const SizedBox(width: 8),
            ElevatedButton(
              onPressed: _addBin,
              style: ElevatedButton.styleFrom(minimumSize: const Size(60, 48)),
              child: const Text('添加'),
            ),
          ]),
          if (_binCodes.isNotEmpty) ...[
            const SizedBox(height: 8),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (int i = 0; i < _binCodes.length; i++)
                  Chip(
                    label: Text(_binCodes[i]),
                    onDeleted: () => setState(() => _binCodes.removeAt(i)),
                    deleteIconColor: PdaTheme.danger,
                  ),
              ],
            ),
          ],
          const SizedBox(height: 14),

          // 备注
          _LabelField(label: '备注（可选）'),
          const SizedBox(height: 4),
          TextField(
            controller: _remarkCtrl,
            maxLines: 2,
            decoration: const InputDecoration(
              hintText: '可填盘点原因或交接说明',
              prefixIcon: Icon(Icons.note),
            ),
          ),
        ],
      ),
    );
  }

  String _countTypeHint() => switch (_countType) {
        'DYNAMIC' => '动盘：当前仓所有在库库存全部纳入',
        'CYCLE' => '循环盘：排除最近 30 天已盘过的库位',
        'SAMPLE' => '抽盘：随机抽取约 20% 的库位行',
        _ => '',
      };

  String _countModeHint() => switch (_countMode) {
        '' => '暗盘/明盘跟随系统参数 WMS_COUNT_MODE_DEFAULT',
        'BLIND' => '暗盘：录实盘时不展示系统库存',
        'OPEN' => '明盘：录实盘时展示系统库存与差异数',
        _ => '',
      };

  void _addBin() {
    final v = _binInputCtrl.text.trim();
    if (v.isEmpty) return;
    if (_binCodes.contains(v)) {
      toast(context, '已添加过该库位');
      return;
    }
    setState(() {
      _binCodes.add(v);
      _binInputCtrl.clear();
    });
  }

  Future<void> _submit() async {
    final r = await runWithBusy(
      context,
      () => _svc.stocktakeCreate(
        countType: _countType,
        scopeText: _scopeCtrl.text.trim(),
        binCodes: _binCodes,
        countMode: _countMode,
        freezeFlag: _freeze ? 'Y' : 'N',
        remark: _remarkCtrl.text.trim(),
      ),
      successMsg: '盘点单已创建',
    );
    if (r != null && mounted) Navigator.of(context).pop(true);
  }
}

/// 简易分段选择器（在小屏 PDA 上比 DropdownButton 更易点）。
class _SegSelect extends StatelessWidget {
  const _SegSelect({
    required this.value,
    required this.options,
    required this.onChanged,
  });
  final String value;
  final List<(String, String)> options;
  final void Function(String) onChanged;

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 6,
      runSpacing: 6,
      children: [
        for (final opt in options)
          ChoiceChip(
            label: Text(opt.$2),
            selected: opt.$1 == value,
            onSelected: (_) => onChanged(opt.$1),
          ),
      ],
    );
  }
}

/// 表单字段名 + 必填红点。
class _LabelField extends StatelessWidget {
  const _LabelField({required this.label});
  final String label;

  @override
  Widget build(BuildContext context) {
    return Text(label, style: PdaStyles.label);
  }
}
