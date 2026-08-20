import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import '../../services/photo_service.dart';
import '../../config/theme.dart';
import '../../models/delivery.dart';
import '../../models/task.dart';
import '../../providers/delivery_provider.dart';
import '../../services/api_service.dart';
import '../../services/launch_service.dart';
import '../../services/local_db_service.dart';
import '../../widgets/common.dart';
import '../../widgets/offline_banner.dart';
import '../store/store_location_page.dart';
import 'arrive_page.dart';

/// 配送签收页面（对齐原型 Screen G）。
///
/// 流程：
///   1. 进入页面拉取签收 SKU 明细（按 detailId），并回填本地草稿
///   2. 逐商品录入实收数量（默认全收，可改小）
///   3. 拍现场照片（至少 1 张）
///   4. 客户签名
///   5. 「确认签收」→ **只存本地草稿，不回传后台**
///
/// 为什么签收不再回传后台：
///   同一配送点常有多张单据（含退货单），钱要按「整个配送点」一次算清
///   （退货冲减应收、多账户混合收款、挂账）。若每张单签收时就各自
///   更新后台单据与收款状态，退货冲减就没法做了，且会产生多张零散收款单。
///   因此签收只负责「录数量/拍照/签名」，落本地 sign_drafts；
///   真正的后台写入统一由结算页 /tms/app/settle/confirm 一次完成。
///
/// 草稿必须落库而非放内存：司机签完两张单可能退出页面接电话，
/// 回来还得看到之前录的数量。
class DeliverySignPage extends ConsumerStatefulWidget {
  final String detailId;
  const DeliverySignPage({super.key, required this.detailId});

  @override
  ConsumerState<DeliverySignPage> createState() => _DeliverySignPageState();
}

class _DeliverySignPageState extends ConsumerState<DeliverySignPage> {
  final _signerCtrl = TextEditingController();
  final _remarkCtrl = TextEditingController();
  final _sigKey = GlobalKey<SignaturePadState>();
  final List<SignItem> _items = [];
  final List<XFile> _photos = [];
  bool _submitting = false;

  /// 已回填过草稿的标记：草稿读取是异步的，而 build 会跑多次，
  /// 没有这个标记会在每帧覆盖司机正在输入的数量。
  bool _draftLoaded = false;

  /// 草稿里已上传过的照片 URL（含离线占位本地路径）。
  /// 二次进入时不再让司机重拍，直接沿用。
  final List<String> _draftPhotoUrls = [];
  String? _draftSignatureUrl;

  /// 草稿里的数量：goodsCode → [实收, 拒收]。
  final Map<String, List<num>> _draftQty = {};

  @override
  void initState() {
    super.initState();
    _loadDraft();
  }

  /// 回填本地草稿（返回上页再进来要保存前面的签收数据）。
  Future<void> _loadDraft() async {
    final row = await LocalDbService.instance.getSignDraft(widget.detailId);
    if (!mounted) return;
    if (row == null) {
      setState(() => _draftLoaded = true);
      return;
    }
    final draft = jsonDecode(row['draft_json'] as String) as Map<String, dynamic>;
    setState(() {
      _signerCtrl.text = draft['customerSigner']?.toString() ?? '';
      _remarkCtrl.text = draft['remark']?.toString() ?? '';
      _draftSignatureUrl = draft['signatureUrl']?.toString();
      _draftPhotoUrls
        ..clear()
        ..addAll((draft['photos'] as List? ?? [])
            .map((e) => (e as Map)['url']?.toString() ?? '')
            .where((s) => s.isNotEmpty));
      _draftQty.clear();
      for (final e in (draft['items'] as List? ?? [])) {
        final m = e as Map;
        _draftQty[m['goodsCode']?.toString() ?? ''] = [
          (m['signedQty'] as num?) ?? 0,
          (m['rejectQty'] as num?) ?? 0,
        ];
      }
      _applyDraftQty();
      _draftLoaded = true;
    });
  }

  /// 把草稿数量套用到已加载的 SKU 行。
  ///
  /// 草稿读取与明细接口是两条独立的异步链，谁先到都有可能，
  /// 所以两边完成时都调一次，由 _draftQty 是否为空来决定是否生效。
  void _applyDraftQty() {
    if (_draftQty.isEmpty) return;
    for (final it in _items) {
      final q = _draftQty[it.goodsCode];
      if (q == null) continue;
      it.signedQty = q[0];
      it.rejectQty = q[1];
    }
  }

  @override
  void dispose() {
    _signerCtrl.dispose();
    _remarkCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(signItemsProvider(widget.detailId));
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(title: const Text('配送签收')),
      body: Column(
        children: [
          const OfflineBanner(),
          Expanded(
            child: async.when(
              data: (d) {
                // 草稿未读完就先转圈：否则 _items 会用「默认全收」先渲染一帧，
                // 草稿数量随后跳变，司机会看到数字闪一下。
                if (!_draftLoaded) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (_items.length != d.items.length) {
                  _items.clear();
                  for (final it in d.items) {
                    _items.add(SignItem(
                      goodsCode: it.goodsCode,
                      goodsName: it.goodsName,
                      unitName: it.unitName,
                      requiredQty: it.requiredQty,
                      signedQty: it.requiredQty, // 默认全收
                      rejectQty: 0,
                    ));
                  }
                  _applyDraftQty();
                }
                return _buildBody(d);
              },
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Text('加载失败：$e', style: const TextStyle(color: TmsTheme.muted)),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBody(SignDetail d) {
    final totalRequired = _items.fold<num>(0, (s, it) => s + it.requiredQty);
    final totalSigned = _items.fold<num>(0, (s, it) => s + it.signedQty);
    final totalReject = _items.fold<num>(0, (s, it) => s + it.rejectQty);
    final diff = totalSigned - totalRequired;

    return ListView(
      padding: const EdgeInsets.all(14),
      children: [
        Alert.info('📦 配送签收 · 发货单 ${d.sourceBillNo}'),
        const SizedBox(height: 8),
        _buildArriveHint(d),
        // 发货单信息卡
        MCard(
          leftBar: TmsTheme.accent,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              Expanded(child: Text('发货单：${d.sourceBillNo}', style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
              const MTag.blue('待签收'),
            ]),
            const SizedBox(height: 4),
            MLine('客户', d.customerName),
            MLine('地址', d.customerAddress),
            if (d.hasPhone) MLine('联系人', '${d.contactName}  ${d.contactMobile}'.trim()),
            // 结算方式决定这单要不要当场收钱：预付已付、账期挂账，只有货到付款才需要收。
            // 缺这个字段司机只能凭记忆判断，容易对预付客户重复收款。
            if (d.settlementText.isNotEmpty) MLine('结算方式', d.settlementText),
            MLine(
              d.needCollect ? '应收金额（需当场收款）' : '应收金额',
              '¥ ${d.amount.toStringAsFixed(2)}',
              valueColor: d.needCollect ? TmsTheme.accent2 : TmsTheme.muted,
            ),
            const SizedBox(height: 6),
            // 导航 / 拨号 / 改定位三个轻操作，用 Wrap 防窄屏溢出
            Align(
              alignment: Alignment.centerRight,
              child: Wrap(
                alignment: WrapAlignment.end,
                spacing: 4,
                children: [
                  TextButton.icon(
                    onPressed: () => _navigateTo(d),
                    icon: const Icon(Icons.navigation, size: 14),
                    label: const Text('导航前往', style: TextStyle(fontSize: 12)),
                  ),
                  TextButton.icon(
                    onPressed: d.hasPhone ? () => _callCustomer(d) : null,
                    icon: const Icon(Icons.phone, size: 14),
                    label: const Text('联系客户', style: TextStyle(fontSize: 12)),
                  ),
                  TextButton.icon(
                    onPressed: () {
                      Navigator.push(context, MaterialPageRoute(
                        builder: (_) => StoreLocationPage(
                          customerCode: d.customerCode,
                          customerName: d.customerName,
                          dispatchId: d.dispatchId,
                        ),
                      ));
                    },
                    icon: const Icon(Icons.location_on, size: 14),
                    label: const Text('修改定位', style: TextStyle(fontSize: 12)),
                  ),
                ],
              ),
            ),
          ]),
        ),
        const SizedBox(height: 8),
        // 商品清单 · 录入实收数量
        MCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('📦 商品清单 · 录入实收数量', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            const Text('（实收可小于应发，差异自动标记部分签收）', style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
            const SizedBox(height: 8),
            ..._items.asMap().entries.map((e) {
              final i = e.key;
              final it = e.value;
              return _SignItemRow(
                item: it,
                onChanged: (signed, reject) => setState(() {
                  _items[i].signedQty = signed;
                  _items[i].rejectQty = reject;
                }),
              );
            }),
            const SizedBox(height: 8),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(color: TmsTheme.bg, borderRadius: BorderRadius.circular(8)),
              child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                Text('合计：应发 $totalRequired 件', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                Text('实收 $totalSigned 件', style: const TextStyle(fontSize: 12, color: TmsTheme.ok, fontWeight: FontWeight.w700)),
                Text('拒收 $totalReject 件', style: const TextStyle(fontSize: 12, color: TmsTheme.bad, fontWeight: FontWeight.w700)),
              ]),
            ),
            const SizedBox(height: 4),
            if (diff == 0)
              const Alert.ok('✅ 全部一致，可直接签收')
            else if (diff < 0)
              Alert.warn('⚠️ 实收少于应发 $diff 件，将标记为部分签收')
            else
              Alert.warn('⚠️ 实收多于应发 $diff 件，请核对'),
          ]),
        ),
        const SizedBox(height: 8),
        // 金额只读展示：钱在结算页统一收。
        // 这里保留一行是为了让司机在核数量时就知道这单值多少，
        // 但不给任何收款输入，避免又在签收页收一遍、结算页再收一遍。
        MCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              const Text('💰 本单金额', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              Text('¥ ${_signAmount(d).toStringAsFixed(2)}',
                  style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: TmsTheme.accent2)),
            ]),
            const SizedBox(height: 4),
            const Text('按实收数量折算。收款不在本页操作，返回配送点后统一结算。',
                style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
          ]),
        ),
        const SizedBox(height: 8),
        // 现场照片
        MCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Text('📸 现场照片', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(width: 6),
              Text('（至少 1 张，已拍 ${_photos.length} 张）', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
            ]),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                ..._photos.asMap().entries.map((e) => _PhotoTile(
                      photo: e.value,
                      index: e.key + 1,
                      onDelete: () => setState(() => _photos.removeAt(e.key)),
                    )),
                if (_photos.length < 6) _AddPhotoTile(onTap: _pickPhoto),
              ],
            ),
          ]),
        ),
        const SizedBox(height: 8),
        // 客户签名
        MCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('✍️ 客户确认签名（可选）', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            const SizedBox(height: 6),
            _Field('签收人姓名（可选）', _signerCtrl, placeholder: '请输入签收人姓名'),
            const SizedBox(height: 6),
            SignaturePad(key: _sigKey, height: 120, placeholder: '客户确认签收（可选）'),
            const SizedBox(height: 4),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton.icon(
                onPressed: () => _sigKey.currentState?.clear(),
                icon: const Icon(Icons.clear, size: 14),
                label: const Text('清除签名', style: TextStyle(fontSize: 11)),
                style: TextButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  minimumSize: Size.zero,
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
              ),
            ),
          ]),
        ),
        const SizedBox(height: 8),
        _Field('备注说明', _remarkCtrl, placeholder: '可选，如：少件/包装破损情况'),
        const SizedBox(height: 16),
        Row(children: [
          Expanded(child: TmsButton.outline('稍后处理', color: TmsTheme.muted, onPressed: () => Navigator.pop(context))),
          const SizedBox(width: 8),
          Expanded(child: TmsButton.primary(_submitting ? '提交中...' : '确认签收', onPressed: _submitting ? null : () => _submit(d))),
        ]),
        const SizedBox(height: 20),
      ],
    );
  }

  /// 拍摄签收凭证照片。
  ///
  /// 失败必须提示：无相机应用或权限被拒时插件会抛异常，
  /// 若不接住就表现为「点了没反应」，司机会反复空点。
  Future<void> _pickPhoto() async {
    final result = await PhotoService.instance.capture();
    if (!mounted) return;
    if (result.isFailed) {
      _toast(result.error!);
      return;
    }
    if (result.isSuccess) {
      setState(() => _photos.add(result.file!));
      // 相册降级必须让司机知道：这类照片不是现场直拍，取证强度不同。
      if (result.notice != null) _toast(result.notice!);
    }
  }

  /// 本单签收金额：按实收数量占应发数量的比例折算应收。
  ///
  /// 服务端最终也用同一口径，前端算一遍只是给司机看，
  /// 避免签收时对金额毫无感知、到结算页才发现数字不对。
  num _signAmount(SignDetail d) {
    final totalRequired = _items.fold<num>(0, (s, it) => s + it.requiredQty);
    if (totalRequired <= 0 || d.amount <= 0) return 0;
    final totalSigned = _items.fold<num>(0, (s, it) => s + it.signedQty);
    return d.amount * totalSigned / totalRequired;
  }

  /// 到店打卡提示条。
  ///
  /// 遵循「不强制」原则：
  ///   · 已打卡 → 绿色回执，显示到店时间与偏差（GPS 异常时转红）；
  ///   · 未打卡且参数未开启强制 → 灰色软提示 + 「去打卡」入口，不阻断签收；
  ///   · 未打卡且参数已开启强制 → 橙色警示，服务端 /sign 会拒绝，此处提前告知。
  Widget _buildArriveHint(SignDetail d) {
    if (d.hasArrived) {
      final abn = d.gpsAbnormal;
      return Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: abn
            ? Alert.warn('⚠️ 已于 ${d.arriveTime} 到店打卡'
                '${d.arriveDistance == null ? '' : '，偏差 ${d.arriveDistance!.toStringAsFixed(0)} 米'}'
                '，已标记 GPS 异常')
            : Alert.ok('✅ 已于 ${d.arriveTime} 到店打卡'
                '${d.arriveDistance == null ? '' : '，偏差 ${d.arriveDistance!.toStringAsFixed(0)} 米'}'),
      );
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(children: [
        Expanded(
          child: d.arriveRequired
              ? const Alert.warn('⚠️ 当前配置要求先到店打卡才能签收')
              : const Alert.info('ℹ️ 尚未到店打卡（不影响签收）'),
        ),
        const SizedBox(width: 8),
        TmsButton.outline('去打卡', onPressed: () {
          Navigator.push(context, MaterialPageRoute(
            builder: (_) => ArrivePage(
              dispatchId: d.dispatchId,
              detailId: d.detailId,
              customerName: d.customerName,
              customerAddress: d.customerAddress,
              storeLongitude: d.longitude,
              storeLatitude: d.latitude,
            ),
          )).then((ok) {
            if (ok == true) ref.invalidate(signItemsProvider(widget.detailId));
          });
        }),
      ]),
    );
  }

  /// 确认签收 → 只写本地草稿，不调用后台接口。
  ///
  /// 照片仍在此刻上传（而不是拖到结算时才传）：
  ///   · 照片是现场证据，越早落到服务端越不容易因换机/清缓存丢失；
  ///   · 离线时 uploadImagesOrDefer 返回本地路径占位，
  ///     结算提交时由 SyncService 统一补传，草稿里存的是同一份结构，
  ///     所以离线链路不会因为「先存草稿」而断掉。
  Future<void> _submit(SignDetail d) async {
    if (_photos.isEmpty && _draftPhotoUrls.isEmpty) {
      _toast('请至少拍摄 1 张现场照片');
      return;
    }
    setState(() => _submitting = true);
    try {
      // 上传签名图（可选，有签名才上传；无新签名则沿用草稿里的）
      final signatureB64 = await _sigKey.currentState?.exportAsBase64Png();
      String? signatureUrl = _draftSignatureUrl;
      if (signatureB64 != null && signatureB64.isNotEmpty) {
        final sigBytes = base64Decode(signatureB64);
        final tempDir = await Directory.systemTemp.createTemp('sig');
        final sigFile = File('${tempDir.path}/signature.png');
        await sigFile.writeAsBytes(sigBytes);
        final sigResult = await ApiService.instance
            .uploadImageOrDefer(sigFile, bizType: 'SIGNATURE');
        signatureUrl = sigResult['url'] as String;
      }

      // 上传所有现场照片（XFile → File → 上传获得 URL）
      // 有限并发上传：串行时总耗时是各张之和，签收页常拍 3~5 张，
      // 弱网下会让司机干等几十秒并误以为卡死。
      final newUrls = _photos.isEmpty
          ? const <String>[]
          : await ApiService.instance.uploadImagesOrDefer(
              _photos.map((p) => File(p.path)).toList(),
              bizType: 'SIGN',
            );
      final photoUrls = [..._draftPhotoUrls, ...newUrls];

      final items = <Map<String, dynamic>>[];
      for (final it in _items) {
        items.add({
          'goodsCode': it.goodsCode,
          'signedQty': it.signedQty,
          'rejectQty': it.rejectQty,
        });
      }

      final totalSigned = _items.fold<num>(0, (s, it) => s + it.signedQty);
      final totalReject = _items.fold<num>(0, (s, it) => s + it.rejectQty);
      final totalRequired = _items.fold<num>(0, (s, it) => s + it.requiredQty);
      final signType = totalSigned == 0 && totalReject > 0
          ? 'REJECT'
          : (totalSigned < totalRequired ? 'PARTIAL' : 'NORMAL');

      // 草稿结构与 /tms/app/sign 入参保持一致：
      // 结算时可原样打包上传，不必在两处维护两套字段映射。
      await LocalDbService.instance.saveSignDraft(
        detailId: d.detailId,
        dispatchId: d.dispatchId,
        customerCode: d.customerCode,
        sourceBillNo: d.sourceBillNo,
        billType: 'RECEIPT',
        signType: signType,
        signedQty: totalSigned.toDouble(),
        rejectQty: totalReject.toDouble(),
        signAmount: _signAmount(d).toDouble(),
        draft: {
          'dispatchId': d.dispatchId,
          'detailId': d.detailId,
          'sourceBillNo': d.sourceBillNo,
          'items': items,
          'customerSigner': _signerCtrl.text.trim(),
          'remark': _remarkCtrl.text.trim(),
          if (signatureUrl != null && signatureUrl.isNotEmpty) 'signatureUrl': signatureUrl,
          'photos': photoUrls
              .map((u) => {'url': u, 'photoType': 'GOODS', 'bizType': 'SIGN'})
              .toList(),
        },
      );

      if (mounted) {
        _toast('已暂存签收：${_signTypeText(signType)}，请返回配送点完成结算');
        Navigator.pop(context, true);
      }
    } catch (e) {
      _toast('暂存失败：${e.toString().replaceFirst("Exception: ", "")}');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  String _signTypeText(String t) {
    switch (t) {
      case 'NORMAL':
        return '正常签收';
      case 'PARTIAL':
        return '部分签收';
      case 'REJECT':
        return '全部拒收';
      default:
        return t;
    }
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating));
  }

  /// 唤起手机地图导航；门店未维护坐标时降级为按地址搜索。
  Future<void> _navigateTo(SignDetail d) async {
    if (!d.hasGeo && d.customerAddress.trim().isEmpty) {
      _toast('门店未维护坐标与地址，无法导航');
      return;
    }
    final ok = await LaunchService.instance.navigate(
      longitude: d.longitude,
      latitude: d.latitude,
      address: d.customerAddress,
      name: d.customerName,
    );
    if (!mounted) return;
    if (!ok) {
      _toast('未找到可用地图应用');
    } else if (!d.hasGeo) {
      // 坐标缺失时只能按地址模糊定位，需提醒司机核对，避免导错门店
      _toast('门店未维护坐标，已按地址搜索，请核对位置');
    }
  }

  /// 拨打门店联系人电话（只跳拨号盘，由司机确认后再呼出）。
  Future<void> _callCustomer(SignDetail d) async {
    final ok = await LaunchService.instance.dial(d.contactMobile);
    if (!mounted) return;
    if (!ok) _toast('拨号失败，请手动联系 ${d.contactMobile}');
  }
}

/// 签收明细行：商品信息 + 实收/拒收数量录入。
class _SignItemRow extends StatelessWidget {
  final SignItem item;
  final void Function(num signed, num reject) onChanged;
  const _SignItemRow({required this.item, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    final diff = item.signedQty - item.requiredQty;
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 8),
      decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Color(0xFFF0F1F4)))),
      child: Row(children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(item.goodsName, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: TmsTheme.ink)),
            if (item.unitName.isNotEmpty)
              Text(item.unitName, style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
            const SizedBox(height: 2),
            Text('应发 ${fmtQty(item.requiredQty)} 件', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
          ]),
        ),
        SizedBox(
          width: 70,
          child: Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
            const Text('实收', style: TextStyle(fontSize: 10, color: TmsTheme.muted)),
            TextField(
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              textAlign: TextAlign.center,
              controller: TextEditingController(text: item.signedQty.toString()),
              decoration: InputDecoration(
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
                enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
              ),
              onChanged: (v) {
                final n = num.tryParse(v) ?? 0;
                final signed = n < 0 ? 0 : n;
                // 拒收 = max(0, 应发 - 实收)，实收可大于应发时拒收为 0
                final reject = (item.requiredQty - signed).clamp(0, item.requiredQty);
                onChanged(signed, reject);
              },
            ),
          ]),
        ),
        const SizedBox(width: 6),
        SizedBox(
          width: 70,
          child: Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
            const Text('拒收', style: TextStyle(fontSize: 10, color: TmsTheme.muted)),
            TextField(
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              textAlign: TextAlign.center,
              controller: TextEditingController(text: item.rejectQty.toString()),
              decoration: InputDecoration(
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
                enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
              ),
              onChanged: (v) {
                final n = num.tryParse(v) ?? 0;
                final reject = (n < 0 ? 0 : n).clamp(0, item.requiredQty);
                // 实收 = 应发 - 拒收
                onChanged(item.requiredQty - reject, reject);
              },
            ),
          ]),
        ),
        const SizedBox(width: 6),
        SizedBox(
          width: 36,
          child: Text(
            diff == 0 ? '一致' : '${diff > 0 ? "+" : ""}$diff',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: diff == 0 ? TmsTheme.ok : (diff < 0 ? TmsTheme.bad : TmsTheme.accent2)),
          ),
        ),
      ]),
    );
  }
}

/// 已拍照片缩略图（带删除按钮）。
class _PhotoTile extends StatelessWidget {
  final XFile photo;
  final int index;
  final VoidCallback onDelete;
  const _PhotoTile({required this.photo, required this.index, required this.onDelete});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 80,
      height: 80,
      child: Stack(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: Image.file(File(photo.path), width: 80, height: 80, fit: BoxFit.cover),
          ),
          Positioned(
            top: 2,
            right: 2,
            child: GestureDetector(
              onTap: onDelete,
              child: Container(
                padding: const EdgeInsets.all(2),
                decoration: const BoxDecoration(color: Color(0xCC000000), shape: BoxShape.circle),
                child: const Icon(Icons.close, size: 12, color: Colors.white),
              ),
            ),
          ),
          Positioned(
            bottom: 2,
            left: 2,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
              decoration: BoxDecoration(color: const Color(0x88000000), borderRadius: BorderRadius.circular(4)),
              child: Text('$index', style: const TextStyle(fontSize: 9, color: Colors.white, fontWeight: FontWeight.w600)),
            ),
          ),
        ],
      ),
    );
  }
}

/// 拍照添加按钮。
class _AddPhotoTile extends StatelessWidget {
  final VoidCallback onTap;
  const _AddPhotoTile({required this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 80,
        height: 80,
        decoration: BoxDecoration(
          color: const Color(0xFFF9FAFB),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: TmsTheme.rule, width: 1.5, style: BorderStyle.solid),
        ),
        child: const Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.camera_alt_outlined, size: 22, color: TmsTheme.muted),
            SizedBox(height: 2),
            Text('拍照', style: TextStyle(fontSize: 10, color: TmsTheme.muted)),
          ],
        ),
      ),
    );
  }
}

class _Field extends StatelessWidget {
  final String label;
  final TextEditingController ctrl;
  final String placeholder;
  const _Field(this.label, this.ctrl, {this.placeholder = ''});
  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(label, style: const TextStyle(fontSize: 12, color: TmsTheme.muted, fontWeight: FontWeight.w600)),
      const SizedBox(height: 4),
      TextField(
        controller: ctrl,
        keyboardType: label.contains('金额') ? const TextInputType.numberWithOptions(decimal: true) : TextInputType.text,
        decoration: InputDecoration(
          hintText: placeholder,
          filled: true,
          fillColor: Colors.white,
          contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
          border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
          enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
          focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.accent, width: 1.5)),
        ),
      ),
    ]);
  }
}
