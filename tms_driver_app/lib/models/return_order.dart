/// 退货单模型（对接 /tms/app/return/detail）。
class ReturnOrder {
  final String applyId;
  final String applyNo;
  final String customerCode;
  final String customerName;
  final String warehouse;
  final String billDate;
  final num returnQty;
  final num signedQty;
  final String returnReason;
  final String logisticsStatus;
  final String driverName;
  final String dispatchId;
  final List<ReturnItem> details;

  ReturnOrder({
    required this.applyId,
    required this.applyNo,
    this.customerCode = '',
    this.customerName = '',
    this.warehouse = '',
    this.billDate = '',
    this.returnQty = 0,
    this.signedQty = 0,
    this.returnReason = '',
    this.logisticsStatus = '',
    this.driverName = '',
    this.dispatchId = '',
    this.details = const [],
  });

  factory ReturnOrder.fromJson(Map<String, dynamic> j) => ReturnOrder(
        applyId: j['applyId']?.toString() ?? '',
        applyNo: j['applyNo']?.toString() ?? '',
        customerCode: j['customerCode']?.toString() ?? '',
        customerName: j['customerName']?.toString() ?? '',
        warehouse: j['warehouse']?.toString() ?? '',
        billDate: j['billDate']?.toString() ?? '',
        returnQty: j['returnQty'] as num? ?? 0,
        signedQty: j['signedQty'] as num? ?? 0,
        returnReason: j['returnReason']?.toString() ?? '',
        logisticsStatus: j['logisticsStatus']?.toString() ?? '',
        driverName: j['driverName']?.toString() ?? '',
        dispatchId: j['dispatchId']?.toString() ?? '',
        details: (j['details'] as List? ?? [])
            .map((e) => ReturnItem.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

/// 退货明细行（逐商品）。
class ReturnItem {
  final String detailId;
  final String goodsCode;
  final String goodsName;
  final String spec;
  final String unitName;
  final num returnQty;
  final String batchNo;
  /// 司机录入的实收数量（本地编辑用，提交时回传）。
  num signedQty;

  ReturnItem({
    required this.detailId,
    this.goodsCode = '',
    this.goodsName = '',
    this.spec = '',
    this.unitName = '',
    this.returnQty = 0,
    this.batchNo = '',
    this.signedQty = 0,
  });

  factory ReturnItem.fromJson(Map<String, dynamic> j) => ReturnItem(
        detailId: j['detailId']?.toString() ?? '',
        goodsCode: j['goodsCode']?.toString() ?? '',
        goodsName: j['goodsName']?.toString() ?? '',
        spec: j['spec']?.toString() ?? '',
        unitName: j['unitName']?.toString() ?? '',
        returnQty: j['returnQty'] as num? ?? 0,
        batchNo: j['batchNo']?.toString() ?? '',
        signedQty: j['returnQty'] as num? ?? 0, // 默认实收=退货数，司机可改小
      );

  num get diff => signedQty - returnQty;
}
