import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:geolocator/geolocator.dart';
import 'package:image_picker/image_picker.dart';
import '../../config/app_config.dart';
import '../../config/theme.dart';
import '../../models/store_location.dart';
import '../../providers/store_location_provider.dart';
import '../../services/api_service.dart';
import '../../widgets/common.dart';

/// 门店定位修正页面（P4-1）。
///
/// 流程：
///   1. 显示客户原定位
///   2. 点击「获取当前GPS」→ 自动定位
///   3. 手动微调经纬度（可选）
///   4. 拍门头照（用于验证）
///   5. 提交修正申请 → ERP 审核通过后更新客户定位
class StoreLocationPage extends ConsumerStatefulWidget {
  final String customerId;
  final String customerCode;
  final String customerName;
  final String dispatchId;
  final double? oldLat;
  final double? oldLng;

  const StoreLocationPage({
    super.key,
    this.customerId = '',
    required this.customerCode,
    this.customerName = '',
    this.dispatchId = '',
    this.oldLat,
    this.oldLng,
  });

  @override
  ConsumerState<StoreLocationPage> createState() => _StoreLocationPageState();
}

class _StoreLocationPageState extends ConsumerState<StoreLocationPage> {
  final _latCtrl = TextEditingController();
  final _lngCtrl = TextEditingController();
  final _remarkCtrl = TextEditingController();
  XFile? _photo;
  bool _locating = false;
  bool _submitting = false;
  String _accuracyInfo = '';

  @override
  void initState() {
    super.initState();
    // 自动获取定位
    WidgetsBinding.instance.addPostFrameCallback((_) => _getLocation());
  }

  @override
  void dispose() {
    _latCtrl.dispose();
    _lngCtrl.dispose();
    _remarkCtrl.dispose();
    super.dispose();
  }

  Future<void> _getLocation() async {
    setState(() {
      _locating = true;
      _accuracyInfo = '';
    });
    try {
      // 检查定位服务是否开启
      bool serviceEnabled = await Geolocator.isLocationServiceEnabled();
      if (!serviceEnabled) {
        _toast('请先开启手机定位服务');
        return;
      }

      // 检查权限
      LocationPermission permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
        if (permission == LocationPermission.denied) {
          _toast('定位权限被拒绝，请到设置中授权');
          return;
        }
      }
      if (permission == LocationPermission.deniedForever) {
        _toast('定位权限被永久拒绝，请到设置中授权');
        return;
      }

      // 获取定位
      final position = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(accuracy: LocationAccuracy.high),
      );
      _latCtrl.text = position.latitude.toStringAsFixed(7);
      _lngCtrl.text = position.longitude.toStringAsFixed(7);
      setState(() {
        _accuracyInfo = '定位精度：±${position.accuracy.toStringAsFixed(0)}米';
      });
    } catch (e) {
      _toast('定位失败：$e');
    } finally {
      if (mounted) setState(() => _locating = false);
    }
  }

  Future<void> _pickPhoto() async {
    final picker = ImagePicker();
    final photo = await picker.pickImage(
      source: ImageSource.camera,
      maxWidth: AppConfig.photoMaxEdge.toDouble(),
      maxHeight: AppConfig.photoMaxEdge.toDouble(),
      imageQuality: AppConfig.photoQuality,
    );
    if (photo != null) setState(() => _photo = photo);
  }

  Future<void> _submit() async {
    final lat = double.tryParse(_latCtrl.text);
    final lng = double.tryParse(_lngCtrl.text);
    if (lat == null || lng == null) {
      _toast('请获取或输入有效的经纬度');
      return;
    }
    if (lat == 0 || lng == 0) {
      _toast('经纬度不能为 0');
      return;
    }
    if (_photo == null) {
      _toast('请拍摄门头照用于验证');
      return;
    }

    setState(() => _submitting = true);
    try {
      // 先上传门头照获得 URL；离线时返回本地路径占位，
      // 随主单一起入队，由 SyncService 在重放前补传。
      final uploadResult = await ApiService.instance
          .uploadImageOrDefer(File(_photo!.path), bizType: 'STORE');
      final photoUrl = uploadResult['url'] as String;

      final result = await ref.read(storeLocationSubmitProvider(
        StoreLocationSubmitArgs(
          customerId: widget.customerId,
          customerCode: widget.customerCode,
          customerName: widget.customerName,
          newLat: lat,
          newLng: lng,
          storePhotoUrl: photoUrl,
          dispatchId: widget.dispatchId,
          remark: _remarkCtrl.text.trim(),
        ),
      ).future);

      final name = result['customerName']?.toString() ?? '';
      // 离线入队时后端还没受理，不能说「待ERP审核」——
      // 司机会以为已经报上去了，之后发现定位没改还以为是审核卡住。
      if (result['_offline'] == true) {
        _toast('当前无网络，定位修正已暂存本地，联网后自动上传');
      } else {
        _toast('定位修正申请已提交：$name，待ERP审核');
      }
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      _toast('提交失败：${e.toString().replaceFirst("Exception: ", "")}');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(title: const Text('修改门店定位')),
      body: ListView(
        padding: const EdgeInsets.all(14),
        children: [
          // 客户信息
          MCard(
            leftBar: TmsTheme.accent,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                const Icon(Icons.store, size: 18, color: TmsTheme.accent),
                const SizedBox(width: 6),
                Expanded(child: Text(widget.customerName, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
              ]),
              if (widget.customerCode.isNotEmpty)
                Padding(padding: const EdgeInsets.only(top: 4), child: Text('客户编码：${widget.customerCode}', style: const TextStyle(fontSize: 12, color: TmsTheme.muted))),
            ]),
          ),
          const SizedBox(height: 14),

          // 原定位信息
          if (widget.oldLat != null && widget.oldLng != null) ...[
            MCard(
              leftBar: TmsTheme.muted,
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('原定位', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.muted)),
                const SizedBox(height: 6),
                Text('纬度：${widget.oldLat!.toStringAsFixed(7)}', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                Text('经度：${widget.oldLng!.toStringAsFixed(7)}', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
              ]),
            ),
            const SizedBox(height: 14),
          ],

          // 新定位
          MCard(
            leftBar: TmsTheme.accent2,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                const Text('新定位', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
                const Spacer(),
                if (_locating)
                  const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2))
                else
                  TextButton.icon(
                    onPressed: _getLocation,
                    icon: const Icon(Icons.my_location, size: 16),
                    label: const Text('重新定位', style: TextStyle(fontSize: 12)),
                  ),
              ]),
              const SizedBox(height: 8),
              TextField(
                controller: _latCtrl,
                keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
                inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'^-?\d*\.?\d{0,7}'))],
                decoration: const InputDecoration(
                  labelText: '纬度 (Latitude)',
                  border: OutlineInputBorder(),
                  contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                ),
                style: const TextStyle(fontSize: 14),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _lngCtrl,
                keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
                inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'^-?\d*\.?\d{0,7}'))],
                decoration: const InputDecoration(
                  labelText: '经度 (Longitude)',
                  border: OutlineInputBorder(),
                  contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                ),
                style: const TextStyle(fontSize: 14),
              ),
              if (_accuracyInfo.isNotEmpty) ...[
                const SizedBox(height: 8),
                Row(children: [
                  const Icon(Icons.gps_fixed, size: 12, color: TmsTheme.ok),
                  const SizedBox(width: 4),
                  Text(_accuracyInfo, style: const TextStyle(fontSize: 11, color: TmsTheme.ok)),
                ]),
              ],
            ]),
          ),
          const SizedBox(height: 14),

          // 门头照
          MCard(
            leftBar: TmsTheme.accent,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('门店门头照', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(height: 4),
              const Text('用于ERP端审核验证', style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
              const SizedBox(height: 8),
              if (_photo != null)
                Stack(children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(6),
                    child: Image.file(File(_photo!.path), width: double.infinity, height: 180, fit: BoxFit.cover),
                  ),
                  Positioned(
                    right: 8, top: 8,
                    child: GestureDetector(
                      onTap: () => setState(() => _photo = null),
                      child: Container(
                        padding: const EdgeInsets.all(4),
                        decoration: const BoxDecoration(color: Colors.black54, shape: BoxShape.circle),
                        child: const Icon(Icons.close, size: 16, color: Colors.white),
                      ),
                    ),
                  ),
                ])
              else
                GestureDetector(
                  onTap: _pickPhoto,
                  child: Container(
                    width: double.infinity,
                    height: 120,
                    decoration: BoxDecoration(
                      border: Border.all(color: TmsTheme.rule),
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: const Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                      Icon(Icons.camera_alt, size: 28, color: TmsTheme.muted),
                      SizedBox(height: 4),
                      Text('点击拍摄门头照', style: TextStyle(fontSize: 12, color: TmsTheme.muted)),
                    ]),
                  ),
                ),
            ]),
          ),
          const SizedBox(height: 14),

          // 备注
          MCard(
            leftBar: TmsTheme.muted,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('备注', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(height: 8),
              TextField(
                controller: _remarkCtrl,
                maxLines: 2,
                decoration: const InputDecoration(
                  hintText: '如：门店已搬迁、GPS偏移等（选填）',
                  border: OutlineInputBorder(),
                  contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                ),
                style: const TextStyle(fontSize: 13),
              ),
            ]),
          ),
          const SizedBox(height: 14),

          TmsButton.primary(
            _submitting ? '提交中...' : '提交修正申请',
            onPressed: _submitting ? null : _submit,
          ),
          const SizedBox(height: 20),
        ],
      ),
    );
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating));
  }
}
