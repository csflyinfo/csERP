import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/api_service.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 实物库存查询（当前登录仓，PDA-007）。
/// 批次列：stock_query.view_batch；成本：view_cost 功能点 + VIEW_COST/VIEW_COST_AMOUNT
/// 字段双控。后端已做列裁剪/清零，前端再隐藏列，双保险。
class StockQueryPage extends StatefulWidget {
  const StockQueryPage({super.key});
  @override
  State<StockQueryPage> createState() => _StockQueryPageState();
}

class _StockQueryPageState extends State<StockQueryPage> {
  final _svc = WmsAppService.instance;
  final _auth = AuthService.instance;
  final _keywordCtrl = TextEditingController();
  List<dynamic> _rows = const [];
  bool _loading = false;
  bool _discrepancyOnly = false;

  bool get _canBatch => _auth.can(PdaPerm.stockBatch);
  bool get _canCost =>
      _auth.can(PdaPerm.stockCost) && _auth.hasField('VIEW_COST');
  bool get _canCostAmount => _canCost && _auth.hasField('VIEW_COST_AMOUNT');

  Future<void> _query() async {
    FocusScope.of(context).unfocus();
    setState(() => _loading = true);
    try {
      _rows = await _svc.stockQuery(
        keyword: _keywordCtrl.text.trim(),
        discrepancyOnly: _discrepancyOnly,
      );
    } catch (e) {
      if (mounted) toast(context, ApiService.friendlyError(e), error: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  void initState() {
    super.initState();
    _query();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('库存查询')),
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 6),
              child: Row(children: [
                Expanded(
                  child: TextField(
                    controller: _keywordCtrl,
                    textInputAction: TextInputAction.search,
                    onSubmitted: (_) => _query(),
                    decoration: const InputDecoration(
                      hintText: '商品编码 / 名称',
                      prefixIcon: Icon(Icons.search),
                      isDense: true,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                ElevatedButton(
                    onPressed: _loading ? null : _query,
                    child: const Text('查询')),
              ]),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 6),
              child: Row(children: [
                Checkbox(
                  value: _discrepancyOnly,
                  activeColor: PdaTheme.primary,
                  onChanged: (v) {
                    _discrepancyOnly = v ?? false;
                    _query();
                  },
                ),
                GestureDetector(
                  onTap: () {
                    _discrepancyOnly = !_discrepancyOnly;
                    _query();
                  },
                  child: const Text('只看账实不符', style: PdaStyles.sub),
                ),
                const Spacer(),
                if (_rows.isNotEmpty)
                  Text('${_rows.length} 条', style: PdaStyles.sub),
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
      return const Center(child: Text('无符合条件的库存', style: PdaStyles.sub));
    }
    return RefreshIndicator(
      onRefresh: _query,
      color: PdaTheme.primary,
      child: ListView.builder(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 16),
        itemCount: _rows.length,
        itemBuilder: (_, i) =>
            _row(Map<String, dynamic>.from(_rows[i] as Map)),
      ),
    );
  }

  Widget _row(Map<String, dynamic> m) {
    final binQty = pickNum(m, ['binQty', 'bin_qty']);
    final physical = pickNum(m, ['physicalQty', 'physical_qty']);
    final locked = pickNum(m, ['lockedQty', 'locked_qty']);
    final batch = pickStr(m, ['batchNo', 'batch_no']);
    final unitCost = m['unitCost'];
    final costAmount = m['costAmount'];
    final diff = physical - binQty;
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Expanded(
                child: Text(pickStr(m, ['goodsName', 'goods_name'], '未命名商品'),
                    style: PdaStyles.title),
              ),
              Text('库位 ${pickStr(m, ['binCode', 'bin_code'])}',
                  style: PdaStyles.sub),
            ]),
            const SizedBox(height: 2),
            Text(pickStr(m, ['goodsCode', 'goods_code']),
                style: PdaStyles.sub),
            if (_canBatch && batch.isNotEmpty) ...[
              const SizedBox(height: 2),
              Text('批次：$batch', style: PdaStyles.sub),
            ],
            const SizedBox(height: 6),
            Wrap(spacing: 14, runSpacing: 4, children: [
              _kv('库位量', '$binQty'),
              _kv('锁定', '$locked'),
              _kv('实物量', '$physical',
                  color: diff == 0 ? PdaTheme.primary : PdaTheme.danger),
              if (diff != 0) _kv('账实差', '$diff', color: PdaTheme.danger),
              if (_canCost && unitCost != null)
                _kv('成本单价', '$unitCost'),
              if (_canCostAmount && costAmount != null)
                _kv('成本金额', '$costAmount'),
            ]),
          ],
        ),
      ),
    );
  }

  Widget _kv(String k, String v, {Color? color}) => Text.rich(
        TextSpan(children: [
          TextSpan(text: '$k ', style: PdaStyles.sub),
          TextSpan(
            text: v,
            style: PdaStyles.label.copyWith(color: color ?? PdaTheme.textPrimary),
          ),
        ]),
      );
}
