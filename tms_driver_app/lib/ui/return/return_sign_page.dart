import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import '../../services/photo_service.dart';
import '../../config/theme.dart';
import '../../models/return_order.dart';
import '../../providers/task_provider.dart';
import '../../services/api_service.dart';
import '../../services/param_service.dart';
import '../../widgets/common.dart';

/// 退货回收处理（V1.2 闭环终点，对齐原型 Screen O）。
///
/// 流程：退货单信息 → 逐商品录入实收数量 → 回收拍照 → 客户确认签名 → 提交
/// 提交后回写 sales_return_apply.signed_qty + logistics_status=司机已回收。
class ReturnSignPage extends ConsumerStatefulWidget {
  final String applyNo;
  const ReturnSignPage({super.key, required this.applyNo});

  @override
  ConsumerState<ReturnSignPage> createState() => _ReturnSignPageState();
}

class _ReturnSignPageState extends ConsumerState<ReturnSignPage> {
  final _signerCtrl = TextEditingController();
  final _remarkCtrl = TextEditingController();
  final _sigKey = GlobalKey<SignaturePadState>();
  final List<num> _signedQties = [];
  final List<XFile> _photos = [];
  bool _submitting = false;

  /// 退货照片张数下限（PRD-26 TMS_RETURN_PHOTO_COUNT，0 表示不校验）。
  int get _requirePhoto => ParamService.instance.current.returnPhotoCount;

  /// 可拍上限：保底 6 张，参数高于 6 时以参数为准，
  /// 避免出现「要求张数大于可拍张数」而永远提交不了。
  int get _maxPhoto => _requirePhoto > 6 ? _requirePhoto : 6;

  /// 是否展示并强制电子签名（PRD-26 TMS_SIGN_ESIGN_REQUIRED）。
  ///
  /// 退货签收与配送签收共用这一个开关：需求里的「司机签收页面」是一个整体口径，
  /// 若两页各用一个参数，运营改一处只生效一半，反而更难解释。
  bool get _esignRequired => ParamService.instance.current.signEsignRequired;

  /// 是否允许送货单与退货单合并结算（PRD-26 TMS_RETURN_MERGE_SETTLE）。
  ///
  /// 为 false 时退货不进结算池、签收即闭环，因此提交前要弹二次确认——
  /// 这一步之后司机没有任何补救入口，数量填错只能走后台冲销。
  bool get _mergeSettle => ParamService.instance.current.returnMergeSettle;

  @override
  void dispose() {
    _signerCtrl.dispose();
    _remarkCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final orderAsync = ref.watch(returnDetailProvider(widget.applyNo));
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(
        backgroundColor: TmsTheme.returnPurple,
        title: Text(widget.applyNo),
      ),
      body: orderAsync.when(
        data: (order) {
          // 初始化实收数量默认=退货数
          if (_signedQties.length != order.details.length) {
            _signedQties.clear();
            for (final it in order.details) {
              _signedQties.add(it.returnQty);
            }
          }
          return _buildBody(order);
        },
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Padding(padding: const EdgeInsets.all(24), child: Text('加载失败：$e', style: const TextStyle(color: TmsTheme.muted)))),
      ),
    );
  }

  Widget _buildBody(ReturnOrder order) {
    final totalReturn = order.details.fold<num>(0, (s, it) => s + it.returnQty);
    final totalSigned = _signedQties.fold<num>(0, (s, v) => s + v);
    final diff = totalSigned - totalReturn;
    return ListView(
      padding: const EdgeInsets.all(14),
      children: [
        Alert.info('📦 退货回收任务 · 退货单 ${order.applyNo}'),
        const SizedBox(height: 8),
        // 退货单信息卡
        MCard(
          leftBar: TmsTheme.returnPurple,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              Text('退货单：${order.applyNo}', style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const MTag.purple('待回收'),
            ]),
            const SizedBox(height: 4),
            MLine('客户', order.customerName),
            MLine('仓库', order.warehouse),
            MLine('退货日期', order.billDate),
            MLine('退货原因', order.returnReason.isEmpty ? '—' : order.returnReason),
            MLine('当前物流状态', order.logisticsStatus),
          ]),
        ),
        const SizedBox(height: 8),
        // 退货商品 · 录入实收数量
        MCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('📦 退货商品 · 录入实收数量', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            const Text('（实收不可超过应退数，少收的差异自动标记待处理）', style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
            const SizedBox(height: 8),
            ...order.details.asMap().entries.map((e) {
              final i = e.key;
              final it = e.value;
              return _ReturnItemRow(
                // 明细行持有输入框状态，必须给稳定 key，否则列表顺序变化时 State 会错配到别的商品
                key: ValueKey(it.detailId),
                item: it,
                signedQty: _signedQties[i],
                onChanged: (v) => setState(() => _signedQties[i] = v),
              );
            }),
            const SizedBox(height: 8),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(color: TmsTheme.bg, borderRadius: BorderRadius.circular(8)),
              child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                Text('合计：退货 $totalReturn 件', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                Text('实收 $totalSigned 件', style: const TextStyle(fontSize: 12, color: TmsTheme.ok, fontWeight: FontWeight.w700)),
                Text('差异 $diff 件', style: TextStyle(fontSize: 12, color: diff < 0 ? TmsTheme.bad : TmsTheme.muted, fontWeight: FontWeight.w700)),
              ]),
            ),
          ]),
        ),
        const SizedBox(height: 8),
        // 回收拍照
        MCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Text('📸 退货实物照片', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(width: 6),
              Text(
                _requirePhoto > 0
                    ? '（至少 $_requirePhoto 张，已拍 ${_photos.length} 张）'
                    : '（选填，已拍 ${_photos.length} 张）',
                style: const TextStyle(fontSize: 11, color: TmsTheme.muted),
              ),
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
                if (_photos.length < _maxPhoto)
                  _AddPhotoTile(onTap: _pickPhoto),
              ],
            ),
          ]),
        ),
        const SizedBox(height: 8),
        // 客户确认签名：签名画板受 TMS_SIGN_ESIGN_REQUIRED 控制，
        // 签收人姓名是后端 /return/sign 的必填项，与开关无关，始终展示。
        MCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(_esignRequired ? '✍️ 客户确认签名' : '✍️ 客户签收信息',
                style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            const SizedBox(height: 6),
            _Field('签收人姓名', _signerCtrl, placeholder: '请输入签收人姓名'),
            if (_esignRequired) ...[
              const SizedBox(height: 6),
              SignaturePad(
                key: _sigKey,
                height: 120,
                placeholder: '客户确认退货退款',
              ),
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
            ],
          ]),
        ),
        const SizedBox(height: 8),
        _Field('备注说明', _remarkCtrl, placeholder: '可选，如：少件/包装破损情况'),
        const SizedBox(height: 16),
        Row(children: [
          Expanded(child: TmsButton.outline('稍后处理', color: TmsTheme.muted, onPressed: () => Navigator.pop(context))),
          const SizedBox(width: 8),
          Expanded(child: TmsButton.purple(_submitting ? '提交中...' : '确认回收', onPressed: _submitting ? null : () => _submit(order))),
        ]),
        const SizedBox(height: 20),
      ],
    );
  }

  /// 拍摄退货凭证照片。失败时给出可执行提示，避免静默无反应。
  Future<void> _pickPhoto() async {
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

  Future<void> _submit(ReturnOrder order) async {
    if (_signerCtrl.text.trim().isEmpty) {
      _toast('请输入签收人姓名');
      return;
    }
    // 张数下限读参数（PRD-26 TMS_RETURN_PHOTO_COUNT），与后端 /return/sign 同一口径
    if (_requirePhoto > 0 && _photos.length < _requirePhoto) {
      _toast('请至少拍摄 $_requirePhoto 张退货实物照片');
      return;
    }
    // 电子签名（PRD-26 TMS_SIGN_ESIGN_REQUIRED）：开关打开即为必签，
    // 关闭时画板未挂载，currentState 为 null，不做校验也不带签名提交。
    if (_esignRequired && (_sigKey.currentState?.isEmpty ?? true)) {
      _toast('请完成客户签名');
      return;
    }
    // 合并结算关闭时（PRD-26 TMS_RETURN_MERGE_SETTLE=N）退货不进结算池，
    // 本次签收就是整个退货回收流程的终点，因此必须让司机再确认一次数量。
    // 取消则留在签收页继续修改，不做任何提交。
    if (!_mergeSettle) {
      final ok = await _confirmFinish();
      if (!ok) return;
      if (!mounted) return;
    }
    setState(() => _submitting = true);
    try {
      // 导出签名并上传获得 URL
      String? signatureUrl;
      final signatureB64 = await _sigKey.currentState?.exportAsBase64Png();
      if (signatureB64 != null && signatureB64.isNotEmpty) {
        final sigBytes = base64Decode(signatureB64);
        final sigFile = File('${Directory.systemTemp.path}/sig_${DateTime.now().millisecondsSinceEpoch}.png');
        await sigFile.writeAsBytes(sigBytes);
        final sigUpResult = await ApiService.instance
            .uploadImageOrDefer(sigFile, bizType: 'SIGNATURE');
        signatureUrl = sigUpResult['url'] as String?;
        // 仅在已成功上到服务器后才删除临时文件。
        // 离线时返回的是这个文件的本地路径，要等 SyncService 重放时才真正上传，
        // 此刻删掉会让签名永久丢失（重放时 File.exists 为假，直接跳过）。
        if (sigUpResult['_deferred'] != true && await sigFile.exists()) {
          await sigFile.delete();
        }
      }
      // 照片上传获得 URL（有限并发 + 离线延后，避免弱网串行阻塞）
      final photoUrlList = await ApiService.instance.uploadImagesOrDefer(
        _photos.map((p) => File(p.path)).toList(),
        bizType: 'RETURN',
      );

      final items = <Map<String, dynamic>>[];
      for (var i = 0; i < order.details.length; i++) {
        items.add({
          'detailId': order.details[i].detailId,
          'goodsCode': order.details[i].goodsCode,
          'signedQty': _signedQties[i],
        });
      }
      final result = await ref.read(returnSignProvider(ReturnSignArgs(
        applyNo: order.applyNo,
        items: items,
        customerSigner: _signerCtrl.text.trim(),
        remark: _remarkCtrl.text.trim(),
        photos: photoUrlList,
        signature: signatureUrl,
      )).future);
      // 刷新今日任务
      ref.invalidate(todayTasksProvider);
      if (mounted) {
        // 离线只是入队，服务端还没改状态，不能谎报「物流状态 → 司机已回收」，
        // 否则司机以为已回传、收工后不再关心同步队列。
        _toast(result['_offline'] == true
            ? '当前无网络，回收已暂存本地，联网后自动上传'
            : '回收成功，物流状态 → 司机已回收');
        Navigator.pop(context, true);
      }
    } catch (e) {
      _toast('提交失败：${e.toString().replaceFirst("Exception: ", "")}');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  /// 退货回收终态二次确认（PRD-26 §P0119「否」分支的提示语，按需求原文）。
  ///
  /// 用 barrierDismissible: false 是刻意的：这是不可撤销操作的最后一道闸，
  /// 误触遮罩就当作「取消」比当作「继续」更安全，但更不能让它含糊地关掉
  /// 而司机不知道到底提交了没有——所以只接受两个按钮的显式选择。
  Future<bool> _confirmFinish() async {
    final ok = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        title: const Text('确认回收数量', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
        content: const Text(
          '确认签收后即完成退货回收，请确认回收数量是否正确',
          style: TextStyle(fontSize: 13, color: TmsTheme.ink, height: 1.5),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('返回修改', style: TextStyle(fontSize: 13, color: TmsTheme.muted)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('确认完成', style: TextStyle(fontSize: 13, color: TmsTheme.returnPurple, fontWeight: FontWeight.w700)),
          ),
        ],
      ),
    );
    return ok == true;
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating));
  }
}

/// 退货明细行：商品信息 + 实收数量录入（步进器 + 手工输入）。
///
/// 实收数量恒被夹取到 [0, 应退数量]：司机不可能从客户处收回比申请单更多的货，
/// 放开上限会让 sales_return_apply.signed_qty 超过 return_qty，污染入库与财务口径。
class _ReturnItemRow extends StatefulWidget {
  final ReturnItem item;
  final num signedQty;
  final ValueChanged<num> onChanged;
  const _ReturnItemRow({super.key, required this.item, required this.signedQty, required this.onChanged});

  @override
  State<_ReturnItemRow> createState() => _ReturnItemRowState();
}

class _ReturnItemRowState extends State<_ReturnItemRow> {
  late final TextEditingController _ctrl;

  @override
  void initState() {
    super.initState();
    _ctrl = TextEditingController(text: _fmt(widget.signedQty));
  }

  @override
  void didUpdateWidget(covariant _ReturnItemRow old) {
    super.didUpdateWidget(old);
    // 控件常驻，只在「输入框内容与外部值语义不一致」时回写，避免每帧重建 controller 导致光标跳到行首。
    // 输入框被清空且外部值为 0 时不回写，否则用户删不掉最后一位。
    if (_ctrl.text.trim().isEmpty && widget.signedQty == 0) return;
    if (num.tryParse(_ctrl.text) != widget.signedQty) {
      _ctrl.text = _fmt(widget.signedQty);
    }
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  /// 整数不显示小数尾巴：10 → "10"，10.5 → "10.5"。
  static String _fmt(num n) => n == n.truncateToDouble() ? n.toInt().toString() : n.toString();

  num get _max => widget.item.returnQty;

  num _clamp(num n) => n < 0 ? 0 : (n > _max ? _max : n);

  void _step(num delta) {
    final next = _clamp(widget.signedQty + delta);
    if (next == widget.signedQty) return;
    _ctrl.text = _fmt(next);
    widget.onChanged(next);
  }

  void _onTyped(String v) {
    final raw = num.tryParse(v);
    if (raw == null) {
      widget.onChanged(0);
      return;
    }
    final n = _clamp(raw);
    if (n != raw) {
      // 超上限时就地纠正文本，并把光标保持在末尾，让司机看到「最多只能这么多」
      final t = _fmt(n);
      _ctrl.value = TextEditingValue(text: t, selection: TextSelection.collapsed(offset: t.length));
    }
    widget.onChanged(n);
  }

  @override
  Widget build(BuildContext context) {
    final diff = widget.signedQty - _max;
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 8),
      decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Color(0xFFF0F1F4)))),
      child: Row(children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(widget.item.goodsName, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: TmsTheme.ink)),
            if (widget.item.spec.isNotEmpty)
              Text('${widget.item.spec} · ${widget.item.unitName}', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
            const SizedBox(height: 2),
            Text('应退 ${_fmt(_max)} 件', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
          ]),
        ),
        const SizedBox(width: 6),
        Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
          const Text('实收', style: TextStyle(fontSize: 10, color: TmsTheme.muted)),
          const SizedBox(height: 2),
          Row(children: [
            _StepBtn(icon: Icons.remove, enabled: widget.signedQty > 0, onTap: () => _step(-1)),
            SizedBox(
              width: 52,
              child: TextField(
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                textAlign: TextAlign.center,
                controller: _ctrl,
                style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink),
                decoration: InputDecoration(
                  isDense: true,
                  contentPadding: const EdgeInsets.symmetric(horizontal: 2, vertical: 8),
                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
                  enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
                ),
                onChanged: _onTyped,
              ),
            ),
            _StepBtn(icon: Icons.add, enabled: widget.signedQty < _max, onTap: () => _step(1)),
          ]),
        ]),
        const SizedBox(width: 6),
        SizedBox(
          width: 40,
          child: Text(
            diff == 0 ? '一致' : '$diff',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: diff == 0 ? TmsTheme.ok : TmsTheme.bad),
          ),
        ),
      ]),
    );
  }
}

/// 数量步进按钮（禁用态置灰，避免司机反复点无效按钮）。
class _StepBtn extends StatelessWidget {
  final IconData icon;
  final bool enabled;
  final VoidCallback onTap;
  const _StepBtn({required this.icon, required this.enabled, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: enabled ? onTap : null,
      borderRadius: BorderRadius.circular(8),
      child: Container(
        width: 30,
        height: 34,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: enabled ? TmsTheme.bg : const Color(0xFFFAFAFB),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: TmsTheme.rule, width: 1.5),
        ),
        child: Icon(icon, size: 16, color: enabled ? TmsTheme.ink : TmsTheme.textMuted),
      ),
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
