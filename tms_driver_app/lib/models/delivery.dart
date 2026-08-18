// 配送装车 & 签收模型（对接 /tms/app/loading/items、/tms/app/sign/items）。

/// 装车清单（按调度单聚合，含多个发货单的 SKU 明细）。
class LoadingDispatch {
  final String dispatchId;
  final String dispatchNo;
  final String status;
  final String vehiclePlate;
  final String routeLine;
  final int storeCount;
  final num totalRequired;
  final num totalLoaded;
  final bool allChecked;
  final List<LoadingReceipt> receipts;

  LoadingDispatch({
    required this.dispatchId,
    this.dispatchNo = '',
    this.status = '',
    this.vehiclePlate = '',
    this.routeLine = '',
    this.storeCount = 0,
    this.totalRequired = 0,
    this.totalLoaded = 0,
    this.allChecked = false,
    this.receipts = const [],
  });

  factory LoadingDispatch.fromJson(Map<String, dynamic> j) => LoadingDispatch(
        dispatchId: j['dispatchId']?.toString() ?? '',
        dispatchNo: j['dispatchNo']?.toString() ?? '',
        status: j['status']?.toString() ?? '',
        vehiclePlate: j['vehiclePlate']?.toString() ?? '',
        routeLine: j['routeLine']?.toString() ?? '',
        storeCount: j['storeCount'] as int? ?? 0,
        totalRequired: j['totalRequired'] as num? ?? 0,
        totalLoaded: j['totalLoaded'] as num? ?? 0,
        allChecked: j['allChecked'] as bool? ?? false,
        receipts: (j['receipts'] as List? ?? [])
            .map((e) => LoadingReceipt.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

/// 装车清单中的发货单分组。
class LoadingReceipt {
  final String detailId;
  final String sourceBillNo;
  final String customerName;
  final String customerAddress;
  final int seqNo;
  final String status;
  final num requiredQty;
  final num loadedQty;
  final List<LoadingItem> items;

  LoadingReceipt({
    required this.detailId,
    this.sourceBillNo = '',
    this.customerName = '',
    this.customerAddress = '',
    this.seqNo = 0,
    this.status = '',
    this.requiredQty = 0,
    this.loadedQty = 0,
    this.items = const [],
  });

  factory LoadingReceipt.fromJson(Map<String, dynamic> j) => LoadingReceipt(
        detailId: j['detailId']?.toString() ?? '',
        sourceBillNo: j['sourceBillNo']?.toString() ?? '',
        customerName: j['customerName']?.toString() ?? '',
        customerAddress: j['customerAddress']?.toString() ?? '',
        seqNo: j['seqNo'] as int? ?? 0,
        status: j['status']?.toString() ?? '',
        requiredQty: j['requiredQty'] as num? ?? 0,
        loadedQty: j['loadedQty'] as num? ?? 0,
        items: (j['items'] as List? ?? [])
            .map((e) => LoadingItem.fromJson(e as Map<String, dynamic>))
            .toList(),
      );

  bool get checked => loadedQty > 0 && loadedQty >= requiredQty;
}

/// 装车 SKU 行（应装 / 实装 / 差异）。
class LoadingItem {
  final String goodsCode;
  final String goodsName;
  final String unitName;
  final num requiredQty;
  final num loadedQty;
  final num diffQty;
  final bool checked;

  LoadingItem({
    this.goodsCode = '',
    this.goodsName = '',
    this.unitName = '',
    this.requiredQty = 0,
    this.loadedQty = 0,
    this.diffQty = 0,
    this.checked = false,
  });

  factory LoadingItem.fromJson(Map<String, dynamic> j) => LoadingItem(
        goodsCode: j['goodsCode']?.toString() ?? '',
        goodsName: j['goodsName']?.toString() ?? '',
        unitName: j['unitName']?.toString() ?? '',
        requiredQty: j['requiredQty'] as num? ?? 0,
        loadedQty: j['loadedQty'] as num? ?? 0,
        diffQty: j['diffQty'] as num? ?? 0,
        checked: j['checked'] as bool? ?? false,
      );
}

/// 签收明细（按调度明细 detailId 拉取的发货单 SKU 明细）。
class SignDetail {
  final String detailId;
  final String dispatchId;
  final String sourceBillNo;
  final String customerCode;
  final String customerName;
  final String customerAddress;
  final int seqNo;
  final String status;
  final num requiredQty;
  final num signedQty;
  final num amount;
  final List<SignItem> items;

  /// 到达打卡状态（后端透传，未打卡时 arriveTime 为空串）。
  final String arriveTime;
  final double? arriveDistance;
  final bool gpsAbnormal;

  /// 是否要求先打卡才能签收（系统参数 TMS_ARRIVE_REQUIRED，默认 false 不强制）。
  final bool arriveRequired;

  /// 门店档案坐标（来自 base_customer，未维护时为 null，打卡将降级为无围栏模式）。
  final double? longitude;
  final double? latitude;

  /// 门店联系人（来自 base_customer.contact_name / mobile），供「联系客户」拨号。
  final String contactName;
  final String contactMobile;

  /// 结算方式短文案（预付 / 货到付款 / 账期），门店未维护时为空串。
  final String settlementText;

  /// 是否需要当场收款：仅货到付款为 true。预付已付、账期挂账，误收会造成重复收款。
  final bool needCollect;

  SignDetail({
    required this.detailId,
    this.dispatchId = '',
    this.sourceBillNo = '',
    this.customerCode = '',
    this.customerName = '',
    this.customerAddress = '',
    this.seqNo = 0,
    this.status = '',
    this.requiredQty = 0,
    this.signedQty = 0,
    this.amount = 0,
    this.items = const [],
    this.arriveTime = '',
    this.arriveDistance,
    this.gpsAbnormal = false,
    this.arriveRequired = false,
    this.longitude,
    this.latitude,
    this.contactName = '',
    this.contactMobile = '',
    this.settlementText = '',
    this.needCollect = false,
  });

  factory SignDetail.fromJson(Map<String, dynamic> j) => SignDetail(
        detailId: j['detailId']?.toString() ?? '',
        dispatchId: j['dispatchId']?.toString() ?? '',
        sourceBillNo: j['sourceBillNo']?.toString() ?? '',
        customerCode: j['customerCode']?.toString() ?? '',
        customerName: j['customerName']?.toString() ?? '',
        customerAddress: j['customerAddress']?.toString() ?? '',
        seqNo: j['seqNo'] as int? ?? 0,
        status: j['status']?.toString() ?? '',
        requiredQty: j['requiredQty'] as num? ?? 0,
        signedQty: j['signedQty'] as num? ?? 0,
        amount: j['amount'] as num? ?? 0,
        items: (j['items'] as List? ?? [])
            .map((e) => SignItem.fromJson(e as Map<String, dynamic>))
            .toList(),
        arriveTime: (j['arriveTime']?.toString() ?? '').replaceFirst('T', ' '),
        arriveDistance: j['arriveDistance'] == null
            ? null
            : double.tryParse(j['arriveDistance'].toString()),
        gpsAbnormal: j['gpsAbnormal']?.toString() == 'Y',
        arriveRequired: j['arriveRequired'] == true,
        longitude: _toDouble(j['longitude']),
        latitude: _toDouble(j['latitude']),
        contactName: j['contactName']?.toString() ?? '',
        contactMobile: j['contactMobile']?.toString() ?? '',
        settlementText: j['settlementText']?.toString() ?? '',
        needCollect: j['needCollect'] == true,
      );

  /// 后端 DECIMAL 字段可能序列化为 num 或字符串，统一容错转换。
  static double? _toDouble(Object? v) {
    if (v == null) return null;
    if (v is num) return v.toDouble();
    return double.tryParse(v.toString());
  }

  /// 是否已到达打卡。
  bool get hasArrived => arriveTime.isNotEmpty;

  /// 门店是否维护了坐标（决定打卡能否做围栏比对）。
  bool get hasGeo => longitude != null && latitude != null;

  /// 门店电话是否可拨；为空时「联系客户」按钮应置灰。
  bool get hasPhone => contactMobile.trim().isNotEmpty;
}

/// 签收 SKU 行。
class SignItem {
  final String goodsCode;
  final String goodsName;
  final String unitName;
  final num requiredQty;
  /// 司机录入的实收数量（本地编辑用，提交时回传）。
  num signedQty;
  /// 司机录入的拒收数量（本地编辑用）。
  num rejectQty;

  SignItem({
    this.goodsCode = '',
    this.goodsName = '',
    this.unitName = '',
    this.requiredQty = 0,
    this.signedQty = 0,
    this.rejectQty = 0,
  });

  factory SignItem.fromJson(Map<String, dynamic> j) => SignItem(
        goodsCode: j['goodsCode']?.toString() ?? '',
        goodsName: j['goodsName']?.toString() ?? '',
        unitName: j['unitName']?.toString() ?? '',
        requiredQty: j['requiredQty'] as num? ?? 0,
        signedQty: j['signedQty'] as num? ?? 0,
        rejectQty: j['rejectQty'] as num? ?? 0,
      );

  num get diff => signedQty - requiredQty;
}
