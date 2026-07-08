import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../config/theme.dart';
import '../services/connectivity_service.dart';
import '../services/sync_service.dart';

/// 离线提示条 + 同步状态组件（P6 离线能力）。
///
/// 放置在页面顶部，显示：
/// - 离线时：红色横幅「离线模式 - 操作已暂存，联网后自动同步」
/// - 同步中：蓝色横幅「正在同步...」
/// - 待同步数量：橙色徽标「N 条待同步」
class OfflineBanner extends ConsumerWidget {
  const OfflineBanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final online = ref.watch(isOnlineProvider).valueOrNull ?? true;
    final syncState = ref.watch(syncStateProvider).valueOrNull ?? SyncState.idle;

    // 离线提示
    if (!online) {
      return Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        color: TmsTheme.accent2,
        child: Row(children: [
          const Icon(Icons.cloud_off, size: 16, color: Colors.white),
          const SizedBox(width: 8),
          const Expanded(
            child: Text(
              '离线模式 - 操作已暂存，联网后自动同步',
              style: TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w500),
            ),
          ),
        ]),
      );
    }

    // 同步中提示
    if (syncState == SyncState.syncing) {
      return Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        color: TmsTheme.primary,
        child: Row(children: [
          const SizedBox(
            width: 14,
            height: 14,
            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
          ),
          const SizedBox(width: 8),
          const Text(
            '正在同步离线数据...',
            style: TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w500),
          ),
        ]),
      );
    }

    return const SizedBox.shrink();
  }
}
