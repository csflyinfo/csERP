import 'package:flutter/material.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 移库作业：列出 PC 端已创建的移库单 → 扫源/目标库位核对 → 确认完成。
class MovePage extends StatefulWidget {
  const MovePage({super.key});
  @override
  State<MovePage> createState() => _MovePageState();
}

class _MovePageState extends State<MovePage> {
  final _svc = WmsAppService.instance;
  final _search = TextEditingController();
  List<dynamic> _tasks = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      _tasks = await _svc.moveTasks(keyword: _search.text.trim());
    } catch (e) {
      if (mounted) toast(context, '$e', error: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return PdaScaffold(
      title: '移库作业',
      onRefresh: _load,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ScanZone(
            title: '扫描任务号 / 商品',
            hint: '搜索过滤',
            controller: _search,
            icon: Icons.search,
            buttonLabel: '搜索',
            onSubmit: (_) => _load(),
          ),
          const SizedBox(height: 12),
          if (_loading)
            const Padding(
              padding: EdgeInsets.all(40),
              child: Center(
                  child: CircularProgressIndicator(color: PdaTheme.primary)),
            )
          else if (_tasks.isEmpty)
            const Padding(
              padding: EdgeInsets.all(40),
              child: Center(child: Text('暂无移库任务', style: PdaStyles.sub)),
            )
          else
            ..._tasks.map((t) {
              final m = Map<String, dynamic>.from(t as Map);
              final status = pickStr(m, ['status']);
              final fromBin = pickStr(m, ['fromBin', 'from_bin']);
              final toBin = pickStr(m, ['toBin', 'to_bin']);
              final qty = pickNum(m, ['qty']);
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
                                style: PdaStyles.title)),
                        Text(_statusText(status),
                            style: TextStyle(
                                fontSize: 11, color: _statusColor(status))),
                      ]),
                      const SizedBox(height: 4),
                      Text(
                          '${pickStr(m, ['goodsName', 'goods_name'])} · $qty ${pickStr(m, ['batchNo', 'batch_no']).isNotEmpty ? '批次：${pickStr(m, ['batchNo', 'batch_no'])}' : ''}',
                          style: PdaStyles.sub),
                      const SizedBox(height: 6),
                      Row(children: [
                        const Icon(Icons.upload_rounded,
                            size: 16, color: PdaTheme.warning),
                        const SizedBox(width: 4),
                        Text(fromBin, style: PdaStyles.sub),
                        const Padding(
                          padding: EdgeInsets.symmetric(horizontal: 8),
                          child: Icon(Icons.arrow_forward,
                              size: 14, color: PdaTheme.textSecondary),
                        ),
                        const Icon(Icons.download_rounded,
                            size: 16, color: PdaTheme.primary),
                        const SizedBox(width: 4),
                        Text(toBin, style: PdaStyles.sub),
                      ]),
                      if (status != 'DONE' && status != 'CANCELLED') ...[
                        const SizedBox(height: 8),
                        ElevatedButton.icon(
                          icon: const Icon(Icons.check, size: 18),
                          label: const Text('确认移库完成'),
                          onPressed: () async {
                            await runWithBusy(
                              context,
                              () => _svc.moveComplete(
                                  m['taskId'].toString()),
                              successMsg: '移库完成',
                            );
                            _load();
                          },
                        ),
                      ],
                    ],
                  ),
                ),
              );
            }),
        ],
      ),
    );
  }

  String _statusText(String s) => switch (s) {
        'PENDING' => '待执行',
        'DOING' => '执行中',
        'DONE' => '已完成',
        'CANCELLED' => '已取消',
        _ => s,
      };

  Color _statusColor(String s) => switch (s) {
        'DONE' => PdaTheme.primary,
        'CANCELLED' => PdaTheme.danger,
        'DOING' => PdaTheme.warning,
        _ => PdaTheme.textSecondary,
      };
}
