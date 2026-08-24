import 'package:flutter/material.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';
import '../widgets/common.dart';

/// 补货作业：列出待补货任务 → 领取 → 完成（从储备位补到拣货位）。
class ReplenishPage extends StatefulWidget {
  const ReplenishPage({super.key});
  @override
  State<ReplenishPage> createState() => _ReplenishPageState();
}

class _ReplenishPageState extends State<ReplenishPage> {
  final _svc = WmsAppService.instance;
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
      _tasks = await _svc.replenishTasks(status: 'PENDING');
    } catch (e) {
      if (mounted) toast(context, '$e', error: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return PdaScaffold(
      title: '补货作业',
      onRefresh: _load,
      body: _loading
          ? const Center(
              child: CircularProgressIndicator(color: PdaTheme.primary))
          : _tasks.isEmpty
              ? const Center(
                  child: Text('暂无待补货任务', style: PdaStyles.sub))
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    PdaAlert.info('共 ${_tasks.length} 个待补货任务'),
                    const SizedBox(height: 8),
                    ..._tasks.map((t) {
                      final m = Map<String, dynamic>.from(t as Map);
                      final claimed = pickStr(m, ['assignee']).isNotEmpty;
                      return Card(
                        margin: const EdgeInsets.only(bottom: 8),
                        child: Padding(
                          padding: const EdgeInsets.all(10),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                  pickStr(m, ['goodsName', 'goods_name'],
                                      '未命名商品'),
                                  style: PdaStyles.title),
                              const SizedBox(height: 4),
                              Text(
                                  '${pickStr(m, ['goodsCode', 'goods_code'])} · 数量 ${pickNum(m, ['qty'])}',
                                  style: PdaStyles.sub),
                              const SizedBox(height: 4),
                              Row(children: [
                                Text(
                                    '从：${pickStr(m, ['fromBin', 'from_bin', 'sourceBin'])}',
                                    style: PdaStyles.sub),
                                const Padding(
                                  padding:
                                      EdgeInsets.symmetric(horizontal: 8),
                                  child: Icon(Icons.arrow_forward,
                                      size: 14,
                                      color: PdaTheme.textSecondary),
                                ),
                                Text(
                                    '到：${pickStr(m, ['toBin', 'to_bin', 'targetBin'])}',
                                    style: PdaStyles.sub),
                              ]),
                              const SizedBox(height: 8),
                              if (!claimed)
                                OutlinedButton.icon(
                                  icon: const Icon(Icons.play_arrow, size: 18),
                                  label: const Text('领取'),
                                  onPressed: () async {
                                    await runWithBusy(
                                      context,
                                      () => _svc.replenishClaim(
                                          m['taskId'].toString()),
                                      successMsg: '已领取',
                                    );
                                    _load();
                                  },
                                )
                              else
                                ElevatedButton.icon(
                                  icon: const Icon(Icons.check, size: 18),
                                  label: const Text('补货完成'),
                                  onPressed: () async {
                                    await runWithBusy(
                                      context,
                                      () => _svc.replenishComplete(
                                          m['taskId'].toString()),
                                      successMsg: '补货完成',
                                    );
                                    _load();
                                  },
                                ),
                            ],
                          ),
                        ),
                      );
                    }),
                  ],
                ),
    );
  }
}
