import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../config/theme.dart';
import '../services/connectivity_service.dart';
import '../services/sync_service.dart';
import '../ui/home/sync_center_page.dart';

/// 离线提示条 + 同步状态组件（P6 离线能力）。
///
/// 放置在页面顶部，按严重程度取第一条命中的状态显示：
/// - 同步中：蓝色横幅「正在同步离线数据...」
/// - 离线：红色横幅「离线模式 - 操作已暂存」（带待同步条数）
/// - 有失败项：红色横幅「N 条操作同步失败」——必须单独一档，
///   因为失败项已达重试上限、不会自动重传，不介入就是永久丢单，
///   跟「等联网就好」的待同步完全不是一回事。
/// - 仅有待同步：橙色横幅「N 条待同步」
///
/// 除同步中之外均可点击进入同步中心：横幅是司机唯一会注意到的同步入口，
/// 只显示数字却点不动，等于告诉他「有问题但你没法处理」。
class OfflineBanner extends ConsumerWidget {
  const OfflineBanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final online = ref.watch(isOnlineProvider).valueOrNull ?? true;
    final syncState = ref.watch(syncStateProvider).valueOrNull ?? SyncState.idle;
    final pending = ref.watch(pendingCountProvider).valueOrNull ?? 0;
    final failed = ref.watch(failedCountProvider).valueOrNull ?? 0;

    // 同步中提示（此时不可跳转，避免司机在同步过程中重复触发）
    if (syncState == SyncState.syncing) {
      return _bar(
        color: TmsTheme.primary,
        leading: const SizedBox(
          width: 14,
          height: 14,
          child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
        ),
        text: '正在同步离线数据...',
      );
    }

    // 离线提示
    if (!online) {
      return _bar(
        color: TmsTheme.accent2,
        leading: const Icon(Icons.cloud_off, size: 16, color: Colors.white),
        text: pending > 0
            ? '离线模式 - $pending 条操作已暂存，联网后自动同步'
            : '离线模式 - 操作已暂存，联网后自动同步',
        onTap: () => _openSyncCenter(context),
      );
    }

    // 有失败项（在线才提示：离线时提醒也无从处理，只会造成焦虑）
    if (failed > 0) {
      return _bar(
        color: TmsTheme.bad,
        leading:
            const Icon(Icons.error_outline, size: 16, color: Colors.white),
        text: '$failed 条操作同步失败，需手动重试',
        onTap: () => _openSyncCenter(context),
      );
    }

    // 仅有待同步
    if (pending > 0) {
      return _bar(
        color: TmsTheme.accent2,
        leading: const Icon(Icons.cloud_upload_outlined,
            size: 16, color: Colors.white),
        text: '$pending 条待同步',
        onTap: () => _openSyncCenter(context),
      );
    }

    return const SizedBox.shrink();
  }

  void _openSyncCenter(BuildContext context) {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => const SyncCenterPage()),
    );
  }

  Widget _bar({
    required Color color,
    required Widget leading,
    required String text,
    VoidCallback? onTap,
  }) {
    final content = Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      color: color,
      child: Row(children: [
        leading,
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            text,
            style: const TextStyle(
                color: Colors.white,
                fontSize: 12,
                fontWeight: FontWeight.w500),
          ),
        ),
        if (onTap != null)
          const Icon(Icons.chevron_right, size: 16, color: Colors.white),
      ]),
    );
    if (onTap == null) return content;
    return InkWell(onTap: onTap, child: content);
  }
}
