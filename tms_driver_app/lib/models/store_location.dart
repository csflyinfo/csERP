// 门店定位修正模型（对接 /tms/app/store-location/submit）。

/// 门店定位修正提交参数。
class StoreLocationSubmitArgs {
  final String customerId;
  final String customerCode;
  final String customerName;
  final double newLat;
  final double newLng;
  final String storePhotoUrl; // 图片 URL（先调 /tms/app/upload/image 上传获得）
  final String dispatchId;
  final String remark;

  StoreLocationSubmitArgs({
    this.customerId = '',
    this.customerCode = '',
    this.customerName = '',
    required this.newLat,
    required this.newLng,
    required this.storePhotoUrl,
    this.dispatchId = '',
    this.remark = '',
  });

  /// 离线队列去重键：配送场景里手上只有 customerCode，
  /// 后端也允许仅传 code，用 customerId 兜底会得到空串导致同店多次提交无法去重。
  String get dedupKey => customerId.isNotEmpty ? customerId : customerCode;

  Map<String, dynamic> toJson() => {
        if (customerId.isNotEmpty) 'customerId': customerId,
        if (customerCode.isNotEmpty) 'customerCode': customerCode,
        if (customerName.isNotEmpty) 'customerName': customerName,
        'newLat': newLat,
        'newLng': newLng,
        'storePhotoUrl': storePhotoUrl,
        if (dispatchId.isNotEmpty) 'dispatchId': dispatchId,
        if (remark.isNotEmpty) 'remark': remark,
      };
}
