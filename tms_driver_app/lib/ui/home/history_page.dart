import 'package:flutter/material.dart';
import '../../config/theme.dart';

/// 配送历史页（P1 骨架，P3 补真实历史接口）。
class HistoryPage extends StatelessWidget {
  const HistoryPage({super.key});
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(title: const Text('配送历史')),
      body: const Center(child: Text('历史回单查询 · P3 阶段接入', style: TextStyle(color: TmsTheme.muted))),
    );
  }
}
