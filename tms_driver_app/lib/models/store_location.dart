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
    required this.customerId,
    this.customerCode = '',
    this.customerName = '',
    required this.newLat,
    required this.newLng,
    required this.storePhotoUrl,
    this.dispatchId = '',
    this.remark = '',
  });

  Map<String, dynamic> toJson() => {
        'customerId': customerId,
        if (customerCode.isNotEmpty) 'customerCode': customerCode,
        if (customerName.isNotEmpty) 'customerName': customerName,
        'newLat': newLat,
        'newLng': newLng,
        'storePhotoUrl': storePhotoUrl,
        if (dispatchId.isNotEmpty) 'dispatchId': dispatchId,
        if (remark.isNotEmpty) 'remark': remark,
      };
}
