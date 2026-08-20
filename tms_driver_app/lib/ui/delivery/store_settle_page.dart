import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../../config/theme.dart';
import '../../services/api_service.dart';
import '../../services/local_db_service.dart';
import '../../services/photo_service.dart';
import '../../widgets/common.dart';
import '../../widgets/offline_banner.dart';

/// 门店结算页（配送点级收款，对应后端 /tms/app/settle）。
///
/// 为什么结算要独立成页，而不是在签收页顺手收款：
///   同一个配送点常常同时有发货单和退货单，客户只按「发货 - 退货」的净额
///   付钱。若逐单收款，金额对不上账，还会给一次上门生成好几张零散收款单。
///   因此签收页只写本地草稿（sign_drafts），钱在本页一次算清、一次提交。
///
/// 本页流程：
///   1. 读本地草稿 → 得到本店已签收待结算的单据（勾选源）
///   2. 勾选单据 → 调 /settle/preview 由服务端算应收/退货冲减/净额
///   3. 分配收款：多个司机收款账户可混合收款，剩余部分挂账
///   4. 拍结算现场照片（必填）
///   5. 提交 /settle/submit → 成功后删除本地草稿
///
/// 金额一律以服务端 preview 的结果为准，端上不自算净额：
/// 收款金额涉及资金安全，两处算法一旦漂移就会产生对不上的收款单。
class StoreSettlePage extends ConsumerStatefulWidget {
  final String? dispatchId;
  final String customerCode;
  final String customerName;

  const StoreSettlePage({
    super.key,
    this.dispatchId,
    required this.customerCode,
    this.customerName = '',
  });

  @override
  ConsumerState<StoreSettlePage> createState() => _StoreSettlePageState();
}

class _StoreSettlePageState extends ConsumerState<StoreSettlePage> {
  final _signerCtrl = TextEditingController();
  final _remarkCtrl = TextEditingController();

  /// 本地草稿原文，key = detailId。提交时整条回传给后端转正。
  final Map<String, Map<String, dynamic>> _drafts = {};

  /// 草稿的展示信息（单号/类型/数量/金额），从 draft_json 之外的列直接取。
  final List<_SettleBill> _bills = [];
  final Set<String> _checked = {};

  /// 司机可用收款账户；为空表示未配置，只能挂账。
  final List<_Account> _accounts = [];

  /// 各账户本次收款金额输入框，key = fundAccountId。
  final Map<String, TextEditingController> _amtCtrls = {};

  final List<XFile> _photos = [];

  bool _loading = true;
  bool _submitting = false;
  String _error = '';

  /// 服务端预览结果。null 表示还没预览过（勾选为空或预览失败）。
  _Preview? _preview;
  bool _previewing = false;

  /// 强制挂账：净额 <= 0（退货多于发货）或纯退货单，此时不允许收钱。
  bool get _creditOnly => _preview?.creditOnly ?? true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _signerCtrl.dispose();
    _remarkCtrl.dispose();
    for (final c in _amtCtrls.values) {
      c.dispose();
    }
    super.dispose();
  }

  // ==========================================================================
  // 取数
  // ==========================================================================

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = '';
    });
    try {
      final rows = await LocalDbService.instance.getSignDrafts(widget.customerCode);
      _drafts.clear();
      _bills.clear();
      _checked.clear();
      for (final r in rows) {
        final detailId = (r['detail_id'] ?? '').toString();
        if (detailId.isEmpty) continue;
        final draft = _decodeDraft(r['draft_json']);
        if (draft == null) continue;
        _drafts[detailId] = draft;
        _bills.add(_SettleBill(
          detailId: detailId,
          sourceBillNo: (r['source_bill_no'] ?? '').toString(),
          billType: (r['bill_type'] ?? 'RECEIPT').toString(),
          signType: (r['sign_type'] ?? 'NORMAL').toString(),
          signedQty: _num(r['signed_qty']),
          rejectQty: _num(r['reject_qty']),
          signAmount: _num(r['sign_amount']),
        ));
        // 默认全选：司机进结算页的常态是「这家店的单一起结」，
        // 逐个勾反而多操作；要单结再手动取消。
        _checked.add(detailId);
      }
      await _loadAccounts();
      if (!mounted) return;
      setState(() => _loading = false);
      if (_checked.isNotEmpty) await _refreshPreview();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = '待结算单据加载失败：$e';
      });
    }
  }

  Future<void> _loadAccounts() async {
    _accounts.clear();
    try {
      final res = await ApiService.instance.post('/tms/app/settle/accounts', body: {});
      final data = res['data'];
      if (data is Map) {
        for (final a in (data['accounts'] as List? ?? const [])) {
          if (a is! Map) continue;
          final acc = _Account(
            fundAccountId: (a['fundAccountId'] ?? '').toString(),
            fundAccountCode: (a['fundAccountCode'] ?? '').toString(),
            fundAccountName: (a['fundAccountName'] ?? '').toString(),
            accountType: (a['accountType'] ?? '').toString(),
            // 后端 is_default 是 'Y'/'N' 字符串（V71 建表），不是数字。
            // 这里兼容几种写法，避免默认账户判断失效导致金额不预填。
            isDefault: const {'Y', 'y', '1', 'true'}
                .contains((a['isDefault'] ?? '').toString().trim()),
          );
          if (acc.fundAccountId.isEmpty) continue;
          _accounts.add(acc);
          _amtCtrls.putIfAbsent(acc.fundAccountId, () => TextEditingController());
        }
      }
    } catch (_) {
      // 账户拉取失败不阻断结算：挂账恒可用，司机至少能把单结掉。
      // 这里不弹错，避免离线进店时每次都被无关提示打断。
    }
  }

  /// 勾选变化后重算金额。
  ///
  /// 每次勾选都请求服务端而不是本地累加：退货冲减、部分签收折算的口径都在
  /// 服务端，端上自算一遍必然出现两套结果，收款单就对不上了。
  Future<void> _refreshPreview() async {
    if (_checked.isEmpty) {
      setState(() {
        _preview = null;
        _clearAmounts();
      });
      return;
    }
    setState(() => _previewing = true);
    try {
      final res = await ApiService.instance.post('/tms/app/settle/preview', body: {
        if (widget.dispatchId != null) 'dispatchId': widget.dispatchId,
        'customerCode': widget.customerCode,
        'detailIds': _checked.toList(),
      });
      if (!mounted) return;
      if (res['code']?.toString() != '0') {
        setState(() {
          _previewing = false;
          _preview = null;
        });
        _toast(res['message']?.toString() ?? '金额试算失败');
        return;
      }
      final data = res['data'] as Map? ?? const {};
      setState(() {
        _previewing = false;
        _preview = _Preview(
          receiptAmount: _num(data['receiptAmount']),
          returnAmount: _num(data['returnAmount']),
          settleAmount: _num(data['settleAmount']),
          billCount: _num(data['billCount']).toInt(),
          creditOnly: data['creditOnly'] == true,
        );
        _applyDefaultAllocation();
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _previewing = false;
        _preview = null;
      });
      _toast('金额试算失败，请检查网络');
    }
  }

  /// 默认收款分配：净额全额落到默认账户（无默认则第一个）。
  ///
  /// 现场绝大多数是「一个账户收全款」，预填能省掉司机手输金额；
  /// 需要混合收款时改小某个账户，剩余自动进挂账。
  void _applyDefaultAllocation() {
    _clearAmounts();
    final p = _preview;
    if (p == null || p.creditOnly || p.settleAmount <= 0) return;
    if (_accounts.isEmpty) return;
    final target = _accounts.firstWhere((a) => a.isDefault, orElse: () => _accounts.first);
    _amtCtrls[target.fundAccountId]?.text = p.settleAmount.toStringAsFixed(2);
  }

  void _clearAmounts() {
    for (final c in _amtCtrls.values) {
      c.clear();
    }
  }

  // ==========================================================================
  // 金额计算（仅用于界面校验提示，最终以服务端为准）
  // ==========================================================================

  num get _receivedAmount {
    num s = 0;
    for (final a in _accounts) {
      s += num.tryParse(_amtCtrls[a.fundAccountId]?.text.trim() ?? '') ?? 0;
    }
    return s;
  }

  num get _settleAmount => _preview?.settleAmount ?? 0;

  /// 挂账 = 应结 - 各账户实收。负数说明司机填多了，界面会拦。
  num get _creditAmount {
    if (_creditOnly) return _settleAmount;
    return _settleAmount - _receivedAmount;
  }

  // ==========================================================================
  // 界面
  // ==========================================================================

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(
        title: Text(widget.customerName.isEmpty ? '门店结算' : '结算 · ${widget.customerName}'),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                const OfflineBanner(),
                Expanded(child: _body()),
                _bottomBar(),
              ],
            ),
    );
  }

  Widget _body() {
    if (_error.isNotEmpty) {
      return ListView(
        padding: const EdgeInsets.all(12),
        children: [
          Alert.warn(_error),
          const SizedBox(height: 12),
          TmsButton.outline('重新加载', onPressed: _load),
        ],
      );
    }
    if (_bills.isEmpty) {
      return ListView(
        padding: const EdgeInsets.all(12),
        children: const [
          Alert.info('本配送点暂无待结算单据。请先完成各单据签收，再回到本页结算。'),
        ],
      );
    }
    return ListView(
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 24),
      children: [
        _billsCard(),
        const SizedBox(height: 8),
        _amountCard(),
        const SizedBox(height: 8),
        _payCard(),
        const SizedBox(height: 8),
        _photoCard(),
        const SizedBox(height: 8),
        _infoCard(),
      ],
    );
  }

  Widget _billsCard() {
    final allChecked = _checked.length == _bills.length;
    return MCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Text('待结算单据',
                  style: TextStyle(
                      fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(width: 6),
              MTag.gray('${_bills.length} 张'),
              const Spacer(),
              GestureDetector(
                onTap: () {
                  setState(() {
                    if (allChecked) {
                      _checked.clear();
                    } else {
                      _checked
                        ..clear()
                        ..addAll(_bills.map((b) => b.detailId));
                    }
                  });
                  _refreshPreview();
                },
                child: Text(allChecked ? '取消全选' : '全选',
                    style: const TextStyle(
                        fontSize: 12, fontWeight: FontWeight.w700, color: TmsTheme.accent)),
              ),
            ],
          ),
          const Divider(height: 16),
          for (final b in _bills) _billRow(b),
        ],
      ),
    );
  }

  Widget _billRow(_SettleBill b) {
    final checked = _checked.contains(b.detailId);
    return InkWell(
      onTap: () {
        setState(() {
          if (checked) {
            _checked.remove(b.detailId);
          } else {
            _checked.add(b.detailId);
          }
        });
        _refreshPreview();
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(
          children: [
            Icon(checked ? Icons.check_box : Icons.check_box_outline_blank,
                size: 20, color: checked ? TmsTheme.accent : TmsTheme.muted),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      b.isReturn ? const MTag.purple('取退') : const MTag.blue('发货'),
                      const SizedBox(width: 6),
                      Expanded(
                        child: Text(b.sourceBillNo,
                            style: const TextStyle(
                                fontSize: 13,
                                fontWeight: FontWeight.w700,
                                color: TmsTheme.ink)),
                      ),
                    ],
                  ),
                  const SizedBox(height: 2),
                  Text(b.summaryText,
                      style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                ],
              ),
            ),
            const SizedBox(width: 6),
            // 退货显示负号：司机要一眼看出这张是冲减应收的，而不是要多收的钱
            Text(
              b.isReturn
                  ? '-¥${b.signAmount.abs().toStringAsFixed(2)}'
                  : '¥${b.signAmount.toStringAsFixed(2)}',
              style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w700,
                  color: b.isReturn ? TmsTheme.returnPurple : TmsTheme.accent2),
            ),
          ],
        ),
      ),
    );
  }

  Widget _amountCard() {
    final p = _preview;
    return MCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Text('结算金额',
                  style: TextStyle(
                      fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(width: 6),
              if (_previewing)
                const SizedBox(
                    width: 12,
                    height: 12,
                    child: CircularProgressIndicator(strokeWidth: 2)),
            ],
          ),
          const Divider(height: 16),
          if (p == null)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 8),
              child: Text('请勾选要结算的单据',
                  style: TextStyle(fontSize: 12, color: TmsTheme.muted)),
            )
          else ...[
            _amtRow('发货应收', p.receiptAmount, TmsTheme.ink),
            if (p.returnAmount > 0) _amtRow('退货冲减', -p.returnAmount, TmsTheme.returnPurple),
            const Divider(height: 16),
            _amtRow('本次应结', p.settleAmount, TmsTheme.accent2, bold: true),
            if (p.creditOnly) ...[
              const SizedBox(height: 6),
              const Alert.warn('本次应结金额不大于 0（退货多于发货），只能挂账，由后台走退款流程。'),
            ],
          ],
        ],
      ),
    );
  }

  Widget _amtRow(String label, num value, Color color, {bool bold = false}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Text(label,
              style: TextStyle(
                  fontSize: bold ? 13 : 12,
                  fontWeight: bold ? FontWeight.w700 : FontWeight.normal,
                  color: bold ? TmsTheme.ink : TmsTheme.muted)),
          const Spacer(),
          Text('${value < 0 ? '-' : ''}¥${value.abs().toStringAsFixed(2)}',
              style: TextStyle(
                  fontSize: bold ? 16 : 13,
                  fontWeight: FontWeight.w700,
                  color: color)),
        ],
      ),
    );
  }

  Widget _payCard() {
    return MCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('收款方式',
              style: TextStyle(
                  fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
          const SizedBox(height: 2),
          const Text('可多账户混合收款，未收部分自动挂账',
              style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
          const Divider(height: 16),
          if (_accounts.isEmpty)
            const Alert.info('未配置收款账户，本次只能挂账。请联系后台在「司机收款账户」中配置。')
          else if (_creditOnly)
            const Alert.info('当前只能挂账，无需分配收款账户。')
          else
            for (final a in _accounts) _accountRow(a),
          const SizedBox(height: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            decoration: BoxDecoration(
              color: const Color(0xFFFFF7ED),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Row(
              children: [
                const Icon(Icons.receipt_long, size: 16, color: TmsTheme.accent2),
                const SizedBox(width: 6),
                const Text('挂账（欠款）',
                    style: TextStyle(
                        fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
                const Spacer(),
                Text('¥${_creditAmount.toStringAsFixed(2)}',
                    style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        color: _creditAmount < 0 ? TmsTheme.bad : TmsTheme.accent2)),
              ],
            ),
          ),
          if (_creditAmount < 0) ...[
            const SizedBox(height: 6),
            const Alert.danger('收款金额已超出应结金额，请调小。'),
          ],
        ],
      ),
    );
  }

  Widget _accountRow(_Account a) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(a.fundAccountName,
                        style: const TextStyle(
                            fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
                    if (a.isDefault) ...[
                      const SizedBox(width: 4),
                      const MTag.green('默认'),
                    ],
                  ],
                ),
                if (a.subtitle.isNotEmpty)
                  Text(a.subtitle,
                      style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
              ],
            ),
          ),
          const SizedBox(width: 8),
          SizedBox(
            width: 110,
            child: TextField(
              controller: _amtCtrls[a.fundAccountId],
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9.]'))],
              textAlign: TextAlign.right,
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
              decoration: const InputDecoration(
                prefixText: '¥',
                isDense: true,
                contentPadding: EdgeInsets.symmetric(horizontal: 8, vertical: 8),
                border: OutlineInputBorder(),
              ),
              onChanged: (_) => setState(() {}),
            ),
          ),
        ],
      ),
    );
  }

  Widget _photoCard() {
    return MCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Text('结算现场照片',
                  style: TextStyle(
                      fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(width: 4),
              const Text('*', style: TextStyle(fontSize: 13, color: TmsTheme.bad)),
              const Spacer(),
              Text('${_photos.length} 张',
                  style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
            ],
          ),
          const Divider(height: 16),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (int i = 0; i < _photos.length; i++)
                Stack(
                  children: [
                    ClipRRect(
                      borderRadius: BorderRadius.circular(6),
                      child: Image.file(File(_photos[i].path),
                          width: 76, height: 76, fit: BoxFit.cover),
                    ),
                    Positioned(
                      right: 0,
                      top: 0,
                      child: GestureDetector(
                        onTap: () => setState(() => _photos.removeAt(i)),
                        child: Container(
                          padding: const EdgeInsets.all(2),
                          decoration: const BoxDecoration(
                            color: Colors.black54,
                            borderRadius:
                                BorderRadius.only(bottomLeft: Radius.circular(6)),
                          ),
                          child: const Icon(Icons.close, size: 14, color: Colors.white),
                        ),
                      ),
                    ),
                  ],
                ),
              GestureDetector(
                onTap: _takePhoto,
                child: Container(
                  width: 76,
                  height: 76,
                  decoration: BoxDecoration(
                    border: Border.all(color: TmsTheme.rule),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: const Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(Icons.photo_camera, size: 22, color: TmsTheme.muted),
                      SizedBox(height: 2),
                      Text('拍照', style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _infoCard() {
    return MCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('结算信息',
              style: TextStyle(
                  fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
          const Divider(height: 16),
          TextField(
            controller: _signerCtrl,
            decoration: const InputDecoration(
              labelText: '结算人（客户方）',
              isDense: true,
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 10),
          TextField(
            controller: _remarkCtrl,
            maxLines: 2,
            decoration: const InputDecoration(
              labelText: '备注',
              isDense: true,
              border: OutlineInputBorder(),
            ),
          ),
        ],
      ),
    );
  }

  Widget _bottomBar() {
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
      decoration: const BoxDecoration(
        color: Colors.white,
        border: Border(top: BorderSide(color: TmsTheme.rule)),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('已选 ${_checked.length} 张',
                    style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
                Text('应结 ¥${_settleAmount.toStringAsFixed(2)}',
                    style: const TextStyle(
                        fontSize: 17, fontWeight: FontWeight.w700, color: TmsTheme.accent2)),
              ],
            ),
          ),
          SizedBox(
            width: 150,
            child: TmsButton.primary(_submitting ? '提交中...' : '确认结算',
                onPressed: _submitting ? null : _submit),
          ),
        ],
      ),
    );
  }

  // ==========================================================================
  // 动作
  // ==========================================================================

  Future<void> _takePhoto() async {
    final result = await PhotoService.instance.capture();
    if (!mounted) return;
    if (result.isFailed) {
      _toast(result.error!);
      return;
    }
    if (result.isSuccess) {
      setState(() => _photos.add(result.file!));
      if (result.notice != null) _toast(result.notice!);
    }
  }

  Future<void> _submit() async {
    if (_checked.isEmpty) {
      _toast('请先勾选要结算的单据');
      return;
    }
    if (_preview == null) {
      _toast('金额尚未试算完成，请稍候');
      return;
    }
    if (_photos.isEmpty) {
      _toast('请先拍摄结算现场照片');
      return;
    }
    if (_creditAmount < 0) {
      _toast('收款金额超出应结金额，请调小');
      return;
    }
    // 挂账要客户确认：全额挂账等于把货赊出去，误操作代价高
    if (_creditAmount > 0 && !_creditOnly) {
      final ok = await _confirmCredit();
      if (ok != true) return;
    }

    setState(() => _submitting = true);
    try {
      // 照片走签收同一条上传通道：离线时会自动转本地占位 + 入队补传，
      // 司机在地下车库也能把单结掉。
      final photoUrls = await ApiService.instance.uploadImagesOrDefer(
        _photos.map((p) => File(p.path)).toList(),
        bizType: 'SETTLEMENT',
      );

      final bills = <Map<String, dynamic>>[];
      for (final b in _bills) {
        if (!_checked.contains(b.detailId)) continue;
        // 整条草稿原文回传：签收时录的数量/签名/照片都在里面，
        // 后端据此补写 tms_sign_record（草稿转正）。
        final draft = Map<String, dynamic>.from(_drafts[b.detailId] ?? const {});
        draft['detailId'] = b.detailId;
        draft['sourceBillNo'] = b.sourceBillNo;
        draft['billType'] = b.billType;
        draft['signType'] = b.signType;
        draft['signedQty'] = b.signedQty;
        draft['rejectQty'] = b.rejectQty;
        draft['signAmount'] = b.signAmount;
        bills.add(draft);
      }

      final accounts = <Map<String, dynamic>>[];
      if (!_creditOnly) {
        for (final a in _accounts) {
          final amt = num.tryParse(_amtCtrls[a.fundAccountId]?.text.trim() ?? '') ?? 0;
          if (amt <= 0) continue;
          accounts.add({
            'fundAccountId': a.fundAccountId,
            'fundAccountCode': a.fundAccountCode,
            'fundAccountName': a.fundAccountName,
            'amount': amt,
          });
        }
      }

      final res = await ApiService.instance.post('/tms/app/settle/submit', body: {
        if (widget.dispatchId != null) 'dispatchId': widget.dispatchId,
        'customerCode': widget.customerCode,
        'signer': _signerCtrl.text.trim(),
        'remark': _remarkCtrl.text.trim(),
        'bills': bills,
        'accounts': accounts,
        'creditAmount': _creditAmount,
        'photos': [
          for (final u in photoUrls) {'url': u, 'photoType': 'SETTLEMENT'},
        ],
      });
      if (!mounted) return;
      if (res['code']?.toString() != '0') {
        setState(() => _submitting = false);
        _toast(res['message']?.toString() ?? '结算失败');
        return;
      }

      // 结算成功才删草稿：删早了一旦提交失败，司机录的数量就白填了
      await LocalDbService.instance.deleteSignDrafts(_checked.toList());
      if (!mounted) return;
      final data = res['data'] as Map? ?? const {};
      _toast('结算成功：${data['settleNo'] ?? ''}');
      Navigator.pop(context, true);
    } catch (e) {
      if (!mounted) return;
      setState(() => _submitting = false);
      _toast('结算失败：$e');
    }
  }

  Future<bool?> _confirmCredit() {
    return showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('确认挂账'),
        content: Text('本次应结 ¥${_settleAmount.toStringAsFixed(2)}，'
            '实收 ¥${_receivedAmount.toStringAsFixed(2)}，'
            '挂账 ¥${_creditAmount.toStringAsFixed(2)}。\n\n'
            '挂账部分将计入客户欠款，确认继续？'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('返回修改')),
          TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('确认挂账')),
        ],
      ),
    );
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(msg), duration: const Duration(seconds: 2)));
  }

  static num _num(Object? v) {
    if (v == null) return 0;
    if (v is num) return v;
    return num.tryParse(v.toString()) ?? 0;
  }

  /// 解析草稿 JSON。解析失败返回 null 让调用方跳过该条：
  /// 一条脏草稿不该让整个结算页打不开，司机还能结其余单据。
  static Map<String, dynamic>? _decodeDraft(Object? raw) {
    final s = (raw ?? '').toString();
    if (s.isEmpty) return null;
    try {
      final v = jsonDecode(s);
      return v is Map<String, dynamic> ? v : null;
    } catch (_) {
      return null;
    }
  }
}

// ============================================================================
// 页面内模型
// ============================================================================

class _SettleBill {
  final String detailId;
  final String sourceBillNo;
  final String billType;
  final String signType;
  final num signedQty;
  final num rejectQty;
  final num signAmount;

  _SettleBill({
    required this.detailId,
    required this.sourceBillNo,
    required this.billType,
    required this.signType,
    required this.signedQty,
    required this.rejectQty,
    required this.signAmount,
  });

  bool get isReturn => billType == 'RETURN';

  String get summaryText {
    final parts = <String>['实收 $signedQty 件'];
    if (rejectQty > 0) parts.add('拒收 $rejectQty 件');
    parts.add(switch (signType) {
      'REJECT' => '整单拒收',
      'PARTIAL' => '部分签收',
      _ => '正常签收',
    });
    return parts.join(' · ');
  }
}

class _Account {
  final String fundAccountId;
  final String fundAccountCode;
  final String fundAccountName;
  final String accountType;
  final bool isDefault;

  _Account({
    required this.fundAccountId,
    required this.fundAccountCode,
    required this.fundAccountName,
    required this.accountType,
    required this.isDefault,
  });

  String get subtitle =>
      [accountType, fundAccountCode].where((s) => s.isNotEmpty).join(' · ');
}

class _Preview {
  final num receiptAmount;
  final num returnAmount;
  final num settleAmount;
  final int billCount;
  final bool creditOnly;

  _Preview({
    required this.receiptAmount,
    required this.returnAmount,
    required this.settleAmount,
    required this.billCount,
    required this.creditOnly,
  });
}
