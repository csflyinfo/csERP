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
      );
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
