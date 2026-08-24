import 'package:flutter/material.dart';
import '../theme/pda_theme.dart';

class PlaceholderPage extends StatelessWidget {
  final String title;
  final String reason;
  const PlaceholderPage({super.key, required this.title, required this.reason});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.computer_outlined,
                  size: 64, color: PdaTheme.textSecondary),
              const SizedBox(height: 16),
              Text('该作业请在 PC 端完成',
                  style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 12),
              Text(reason,
                  textAlign: TextAlign.center,
                  style: PdaStyles.sub.copyWith(fontSize: 14, height: 1.6)),
              const SizedBox(height: 24),
              OutlinedButton.icon(
                icon: const Icon(Icons.arrow_back),
                label: const Text('返回'),
                onPressed: () => Navigator.pop(context),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
