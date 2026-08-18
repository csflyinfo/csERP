// 推送载荷解析测试。
//
// 为什么测这里、而不是做整机烟雾测试：
// TmsDriverApp 的 initState 会调 _bootstrap()，里面串了 SharedPreferences、
// sqflite、connectivity、个推 SDK 四类平台通道，在纯 Dart 测试环境全部不可用，
// pumpWidget 必然抛 MissingPluginException。要覆盖启动流程得先把这些服务抽成
// 可注入的接口，属于另一件事，不该混在「让 flutter test 跑起来」里做。
//
// PushPayload 是零平台依赖的纯逻辑，且是后端 TmsNotifyService.buildPushPayload
// 与 APP 之间的契约边界——字段名对不上时 APP 不会报错，只会静默退化成
// 「把原文当正文」，这种问题在真机上极难发现，正好用测试守住。

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:tms_driver_app/models/notification.dart';

void main() {
  group('PushPayload.parse', () {
    test('解析后端下发的完整透传 JSON', () {
      // 这段 JSON 的字段名必须与后端 buildPushPayload 保持一致，
      // 两边任一侧改字段名，此用例应当失败
      final raw = jsonEncode({
        'notifyId': 'NT20260818001',
        'notifyType': 'NEW_TASK',
        'level': 'URGENT',
        'title': '新派车单',
        'content': '您有一张新的派车单待接收',
        'linkType': 'DISPATCH',
        'linkId': 'DP20260818001',
      });

      final p = PushPayload.parse(raw);

      expect(p.notifyId, 'NT20260818001');
      expect(p.notifyType, 'NEW_TASK');
      expect(p.level, 'URGENT');
      expect(p.title, '新派车单');
      expect(p.content, '您有一张新的派车单待接收');
      expect(p.linkType, 'DISPATCH');
      expect(p.linkId, 'DP20260818001');
    });

    test('非 JSON 文本退化为正文，不丢消息', () {
      final p = PushPayload.parse('系统维护通知');

      expect(p.content, '系统维护通知');
      expect(p.title, '');
      // 退化时按普通系统消息处理，避免误判成紧急消息弹横幅
      expect(p.notifyType, 'SYSTEM');
      expect(p.level, 'NORMAL');
      expect(p.isUrgent, isFalse);
    });

    test('JSON 残缺时也要能弹通知', () {
      // 厂商通道可能截断内容，此时不能整条丢弃
      final p = PushPayload.parse('{"title":"标题","content":');

      expect(p.content, '{"title":"标题","content":');
    });

    test('空串与空白串返回默认载荷', () {
      expect(PushPayload.parse('').content, '');
      expect(PushPayload.parse('   ').content, '');
      expect(PushPayload.parse('').notifyType, 'SYSTEM');
    });

    test('缺字段时逐项落默认值', () {
      final p = PushPayload.parse(jsonEncode({'title': '仅有标题'}));

      expect(p.title, '仅有标题');
      expect(p.notifyId, '');
      expect(p.notifyType, 'SYSTEM');
      expect(p.level, 'NORMAL');
      expect(p.linkType, '');
    });

    test('数字型字段被转成字符串而不抛异常', () {
      // 后端若把 linkId 写成数字，fromJson 的 toString() 应兜住
      final p = PushPayload.parse('{"notifyId":123,"linkId":456}');

      expect(p.notifyId, '123');
      expect(p.linkId, '456');
    });
  });

  group('PushPayload.isUrgent / canOpen', () {
    test('仅 URGENT 判定为紧急', () {
      expect(const PushPayload(level: 'URGENT').isUrgent, isTrue);
      expect(const PushPayload(level: 'IMPORTANT').isUrgent, isFalse);
      expect(const PushPayload(level: 'NORMAL').isUrgent, isFalse);
    });

    test('linkType 与 linkId 齐备才可跳转', () {
      expect(
        const PushPayload(linkType: 'DISPATCH', linkId: 'DP001').canOpen,
        isTrue,
      );
      // 缺 ID 会打开空白页
      expect(const PushPayload(linkType: 'DISPATCH').canOpen, isFalse);
      expect(const PushPayload(linkId: 'DP001').canOpen, isFalse);
      // NONE 是后端显式表达「不可跳转」的约定值
      expect(
        const PushPayload(linkType: 'NONE', linkId: 'DP001').canOpen,
        isFalse,
      );
    });
  });

  group('AppNotification.fromJson', () {
    test('按后端 LIST_COLUMNS 解析并规整时间', () {
      final n = AppNotification.fromJson({
        'notifyId': 'NT001',
        'notifyNo': 'XXTZ202608180001',
        'notifyType': 'SETTLE_RESULT',
        'level': 'IMPORTANT',
        'title': '交账已确认',
        'content': '您的交账单已确认',
        'linkType': 'SETTLE',
        'linkId': 'ST001',
        'bizNo': 'ST202608180001',
        'isRead': false,
        'createTime': '2026-08-18T09:30:15',
        'sender': '调度员',
      });

      expect(n.notifyNo, 'XXTZ202608180001');
      expect(n.isImportant, isTrue);
      expect(n.isUrgent, isFalse);
      expect(n.canOpen, isTrue);
      // ISO 里的 T 要换成空格，否则列表显示成 "18T09:30"
      expect(n.createTime, '2026-08-18 09:30:15');
      expect(n.timeShort, '08-18 09:30');
    });

    test('isRead 只认布尔 true，避免字符串误判成已读', () {
      expect(AppNotification.fromJson({'isRead': true}).isRead, isTrue);
      expect(AppNotification.fromJson({'isRead': 'false'}).isRead, isFalse);
      expect(AppNotification.fromJson({'isRead': 0}).isRead, isFalse);
      expect(AppNotification.fromJson(const {}).isRead, isFalse);
    });

    test('时间串过短时原样返回，不越界截取', () {
      expect(AppNotification.fromJson({'createTime': '2026-08'}).timeShort,
          '2026-08');
    });

    test('typeName 覆盖各业务类型并兜底系统消息', () {
      String name(String t) =>
          AppNotification.fromJson({'notifyType': t}).typeName;

      expect(name('NEW_TASK'), '新任务');
      expect(name('EXCEPTION_REPLY'), '异常回执');
      expect(name('SETTLE_RESULT'), '交账结果');
      expect(name('REJECT_RESULT'), '拒收结果');
      expect(name('RESCHEDULE_RESULT'), '返仓结果');
      expect(name('EXCEPTION_ALERT'), '异常告警');
      expect(name('WHATEVER_NEW_TYPE'), '系统消息');
    });
  });

  group('NotifyList.fromJson', () {
    test('解析列表与计数', () {
      final r = NotifyList.fromJson({
        'list': [
          {'notifyId': 'NT001', 'title': 'A'},
          {'notifyId': 'NT002', 'title': 'B'},
        ],
        'count': 2,
        'unreadCount': 1,
      });

      expect(r.list.length, 2);
      expect(r.list.first.title, 'A');
      expect(r.count, 2);
      expect(r.unreadCount, 1);
    });

    test('list 缺失或为 null 时返回空列表而非抛异常', () {
      expect(NotifyList.fromJson(const {}).list, isEmpty);
      expect(NotifyList.fromJson(const {}).count, 0);
      expect(NotifyList.fromJson({'list': null}).list, isEmpty);
    });
  });
}
