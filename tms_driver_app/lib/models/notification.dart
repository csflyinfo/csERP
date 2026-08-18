/// 消息通知模型。
///
/// 字段与后端 tms_notification 表、TmsNotificationController 的 LIST_COLUMNS 一一对应。
class AppNotification {
  final String notifyId;
  final String notifyNo;
  final String notifyType;
  final String level;
  final String title;
  final String content;
  final String linkType;
  final String linkId;
  final String bizNo;
  final bool isRead;
  final String createTime;
  final String sender;

  AppNotification({
    required this.notifyId,
    required this.notifyNo,
    required this.notifyType,
    required this.level,
    required this.title,
    required this.content,
    required this.linkType,
    required this.linkId,
    required this.bizNo,
    required this.isRead,
    required this.createTime,
    required this.sender,
  });

  factory AppNotification.fromJson(Map<String, dynamic> j) => AppNotification(
        notifyId: j['notifyId']?.toString() ?? '',
        notifyNo: j['notifyNo']?.toString() ?? '',
        notifyType: j['notifyType']?.toString() ?? 'SYSTEM',
        level: j['level']?.toString() ?? 'NORMAL',
        title: j['title']?.toString() ?? '',
        content: j['content']?.toString() ?? '',
        linkType: j['linkType']?.toString() ?? '',
        linkId: j['linkId']?.toString() ?? '',
        bizNo: j['bizNo']?.toString() ?? '',
        isRead: j['isRead'] == true,
        createTime: (j['createTime']?.toString() ?? '').replaceFirst('T', ' '),
        sender: j['sender']?.toString() ?? '',
      );

  bool get isUrgent => level == 'URGENT';

  bool get isImportant => level == 'IMPORTANT';

  /// 是否可跳业务详情：类型与 ID 都得有，否则点了会打开空白页。
  bool get canOpen => linkId.isNotEmpty && linkType.isNotEmpty && linkType != 'NONE';

  /// 列表只显示「MM-DD HH:mm」，秒和年份对司机没有意义且挤占标题空间。
  String get timeShort {
    if (createTime.length < 16) return createTime;
    return createTime.substring(5, 16);
  }

  String get typeName {
    switch (notifyType) {
      case 'NEW_TASK':
        return '新任务';
      case 'EXCEPTION_REPLY':
        return '异常回执';
      case 'SETTLE_RESULT':
        return '交账结果';
      case 'REJECT_RESULT':
        return '拒收结果';
      case 'RESCHEDULE_RESULT':
        return '返仓结果';
      case 'EXCEPTION_ALERT':
        return '异常告警';
      default:
        return '系统消息';
    }
  }
}

/// 消息列表返回体。
class NotifyList {
  final List<AppNotification> list;
  final int count;
  final int unreadCount;

  NotifyList({
    required this.list,
    required this.count,
    required this.unreadCount,
  });

  factory NotifyList.fromJson(Map<String, dynamic> j) => NotifyList(
        list: (j['list'] as List? ?? [])
            .map((e) => AppNotification.fromJson(e as Map<String, dynamic>))
            .toList(),
        count: (j['count'] as num?)?.toInt() ?? 0,
        unreadCount: (j['unreadCount'] as num?)?.toInt() ?? 0,
      );
}

/// 未读统计（含服务端下发的轮询间隔，避免 APP 硬编码）。
class NotifyUnread {
  final int unreadCount;
  final int urgentCount;
  final int pollSeconds;

  const NotifyUnread({
    this.unreadCount = 0,
    this.urgentCount = 0,
    this.pollSeconds = 60,
  });

  factory NotifyUnread.fromJson(Map<String, dynamic> j) => NotifyUnread(
        unreadCount: (j['unreadCount'] as num?)?.toInt() ?? 0,
        urgentCount: (j['urgentCount'] as num?)?.toInt() ?? 0,
        pollSeconds: (j['pollSeconds'] as num?)?.toInt() ?? 60,
      );

  bool get hasUnread => unreadCount > 0;
}
