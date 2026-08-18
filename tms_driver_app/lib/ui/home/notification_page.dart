import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../config/theme.dart';
import '../../models/notification.dart';
import '../../providers/notification_provider.dart';
import '../../widgets/common.dart';
import '../../widgets/offline_banner.dart';

/// 消息中心：司机接收派单、异常回执、交账与拒收返仓结果的统一入口。
///
/// 在此之前司机只能靠反复下拉今日任务来发现新派单，异常上报后也无从知晓
/// 调度是否受理——所有状态变更都是「静默」的，司机得靠打电话问。
/// 本页把这些节点收敛成一条时间线，未读置顶、紧急标红。
///
/// 刻意不做分页而是限制条数（后端 limit 100）：司机关注的是最近动态，
/// 翻到三个月前的旧消息没有业务价值，加分页只是增加交互成本。
class NotificationPage extends ConsumerStatefulWidget {
  const NotificationPage({super.key});

  @override
  ConsumerState<NotificationPage> createState() => _NotificationPageState();
}

class _NotificationPageState extends ConsumerState<NotificationPage> {
  /// 类型筛选值与后端 tms_notification.notify_type 严格一致，不做额外映射。
  static const _typeOptions = <String, String>{
    '': '全部',
    'NEW_TASK': '新任务',
    'EXCEPTION_REPLY': '异常回执',
    'SETTLE_RESULT': '交账',
    'REJECT_RESULT': '拒收',
    'RESCHEDULE_RESULT': '返仓',
  };

  bool _onlyUnread = false;
  String _type = '';

  NotifyListArgs get _args =>
      NotifyListArgs(onlyUnread: _onlyUnread, notifyType: _type);

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(notificationListProvider(_args));
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(
        title: const Text('消息中心'),
        actions: [
          TextButton(
            onPressed: _readAll,
            child: const Text('全部已读',
                style: TextStyle(color: Colors.white, fontSize: 13)),
          ),
        ],
      ),
      body: Column(
        children: [
          const OfflineBanner(),
          _buildFilterBar(),
          Expanded(
            child: async.when(
              data: (page) => _buildList(page),
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => _buildError(e),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildFilterBar() {
    return Container(
      color: Colors.white,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _chipRow(_typeOptions.entries.map((e) => _chip(
                e.value,
                selected: _type == e.key,
                onTap: () => setState(() => _type = e.key),
              ))),
          const SizedBox(height: 6),
          _chipRow([
            _chip('全部消息',
                selected: !_onlyUnread,
                onTap: () => setState(() => _onlyUnread = false)),
            _chip('只看未读',
                selected: _onlyUnread,
                onTap: () => setState(() => _onlyUnread = true)),
          ]),
        ],
      ),
    );
  }

  Widget _chipRow(Iterable<Widget> children) => SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(children: children.toList()),
      );

  Widget _chip(String text,
      {required bool selected, required VoidCallback onTap}) {
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
          decoration: BoxDecoration(
            color: selected ? TmsTheme.accent : const Color(0xFFF3F4F6),
            borderRadius: BorderRadius.circular(16),
          ),
          child: Text(
            text,
            style: TextStyle(
              fontSize: 12,
              color: selected ? Colors.white : TmsTheme.muted,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildList(NotifyList page) {
    if (page.list.isEmpty) {
      return RefreshIndicator(
        onRefresh: _refresh,
        child: ListView(
          children: [
            SizedBox(height: MediaQuery.of(context).size.height * 0.25),
            Center(
              child: Text(
                _onlyUnread ? '没有未读消息' : '暂无消息',
                style: const TextStyle(color: TmsTheme.muted, fontSize: 13),
              ),
            ),
          ],
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: _refresh,
      child: ListView.builder(
        padding: const EdgeInsets.all(14),
        itemCount: page.list.length,
        itemBuilder: (_, i) => _buildItem(page.list[i]),
      ),
    );
  }

  Widget _buildItem(AppNotification n) {
    return MCard(
      // 左侧色条按紧急度区分：司机在列表快速扫视时先看颜色再读文字
      leftBar: n.isRead
          ? null
          : (n.isUrgent
              ? TmsTheme.bad
              : (n.isImportant ? TmsTheme.accent2 : TmsTheme.accent)),
      onTap: () => _openDetail(n),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              if (!n.isRead)
                Container(
                  width: 7,
                  height: 7,
                  margin: const EdgeInsets.only(right: 6),
                  decoration: const BoxDecoration(
                      color: TmsTheme.bad, shape: BoxShape.circle),
                ),
              Expanded(
                child: Text(
                  n.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 14,
                    color: TmsTheme.ink,
                    fontWeight: n.isRead ? FontWeight.w500 : FontWeight.w700,
                  ),
                ),
              ),
              const SizedBox(width: 6),
              _levelTag(n),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            n.content,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 12, color: TmsTheme.muted, height: 1.5),
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              MTag.gray(n.typeName),
              const SizedBox(width: 6),
              if (n.bizNo.isNotEmpty)
                Expanded(
                  child: Text(
                    n.bizNo,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 11, color: TmsTheme.muted),
                  ),
                )
              else
                const Spacer(),
              Text(n.timeShort,
                  style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
            ],
          ),
        ],
      ),
    );
  }

  Widget _levelTag(AppNotification n) {
    if (n.isUrgent) return const MTag.red('紧急');
    if (n.isImportant) return const MTag.orange('重要');
    return const SizedBox.shrink();
  }

  Widget _buildError(Object e) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              e.toString().replaceFirst('Exception: ', ''),
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 13, color: TmsTheme.muted),
            ),
            const SizedBox(height: 12),
            TextButton(onPressed: _refresh, child: const Text('重试')),
          ],
        ),
      ),
    );
  }

  Future<void> _refresh() async {
    ref.invalidate(notificationListProvider(_args));
    await ref.read(notifyUnreadProvider.notifier).refresh();
  }

  /// 点开消息即置已读，并弹出完整内容。
  ///
  /// 正文在列表里被截成两行，而异常处理结论、交账差异原因往往较长，
  /// 必须给一个能看全文的地方，否则关键信息看不到。
  Future<void> _openDetail(AppNotification n) async {
    if (!n.isRead) {
      try {
        await markNotifyRead(n.notifyId);
        ref.read(notifyUnreadProvider.notifier).decrease();
        ref.invalidate(notificationListProvider(_args));
      } catch (_) {
        // 置已读失败不影响看内容，下次进页面会重试
      }
    }
    if (!mounted) return;
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(14)),
      ),
      builder: (_) => Padding(
        padding: const EdgeInsets.fromLTRB(18, 18, 18, 28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(n.title,
                      style: const TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w700,
                          color: TmsTheme.ink)),
                ),
                _levelTag(n),
              ],
            ),
            const SizedBox(height: 10),
            Text(n.content,
                style: const TextStyle(
                    fontSize: 13, color: TmsTheme.ink, height: 1.7)),
            const SizedBox(height: 14),
            const Divider(height: 1, color: TmsTheme.rule),
            const SizedBox(height: 10),
            Row(
              children: [
                MTag.gray(n.typeName),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    n.bizNo.isEmpty ? n.notifyNo : n.bizNo,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 11, color: TmsTheme.muted),
                  ),
                ),
                Text(n.createTime,
                    style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _readAll() async {
    try {
      final n = await markAllNotifyRead();
      ref.read(notifyUnreadProvider.notifier).clear();
      ref.invalidate(notificationListProvider(_args));
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(n > 0 ? '已将 $n 条消息标为已读' : '没有未读消息')),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('操作失败：${e.toString().replaceFirst('Exception: ', '')}')),
      );
    }
  }
}
