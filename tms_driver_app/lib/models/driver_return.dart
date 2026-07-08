// 司机现场退货模型（对接 /tms/app/return/* 和 /tms/app/warehouse-return/*）。

/// 退货商品行（司机现场录入）。
class ReturnGoodsItem {
  String goodsCode;
  String goodsName;
  String spec;
  String unitName;
  num qty;
  num price;
  String batchNo;
  String remark;

  ReturnGoodsItem({
    this.goodsCode = '',
    this.goodsName = '',
    this.spec = '',
    this.unitName = '',
    this.qty = 1,
    this.price = 0,
    this.batchNo = '',
    this.remark = '',
  });

  num get amount => (qty * price).toDouble();

  Map<String, dynamic> toJson() => {
        'goodsCode': goodsCode,
        'goodsName': goodsName,
        'spec': spec,
        'unitName': unitName,
        'qty': qty,
        'price': price,
        'batchNo': batchNo,
        'remark': remark,
      };
}

/// 商品搜索结果行。
class GoodsSearchResult {
  final String goodsCode;
  final String goodsName;
  final String spec;
  final String unitName;
  final num price;
  final num stockQty;

  GoodsSearchResult({
    this.goodsCode = '',
    this.goodsName = '',
    this.spec = '',
    this.unitName = '',
    this.price = 0,
    this.stockQty = 0,
  });

  factory GoodsSearchResult.fromJson(Map<String, dynamic> j) => GoodsSearchResult(
        goodsCode: j['goodsCode']?.toString() ?? '',
        goodsName: j['goodsName']?.toString() ?? '',
        spec: j['spec']?.toString() ?? '',
        unitName: j['unitName']?.toString() ?? '',
        price: j['price'] as num? ?? 0,
        stockQty: j['stockQty'] as num? ?? 0,
      );
}

/// 待回收任务（预开退货单，logistics_status=已调度）。
class PendingRecycleTask {
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
  final String dispatchId;
  final String tripId;

  PendingRecycleTask({
    this.applyId = '',
    this.applyNo = '',
    this.customerCode = '',
    this.customerName = '',
    this.warehouse = '',
    this.billDate = '',
    this.returnQty = 0,
    this.signedQty = 0,
    this.returnReason = '',
    this.logisticsStatus = '',
    this.dispatchId = '',
    this.tripId = '',
  });

  factory PendingRecycleTask.fromJson(Map<String, dynamic> j) => PendingRecycleTask(
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
        dispatchId: j['dispatchId']?.toString() ?? '',
        tripId: j['tripId']?.toString() ?? '',
      );
}

/// 已回收待返仓退货单。
class LoadedReturn {
  final String driverReturnId;
  final String driverReturnNo;
  final String returnApplyNo;
  final String customerCode;
  final String customerName;
  final String returnDate;
  final num qty;
  final String status;
  final String returnReason;
  final List<LoadedReturnDetail> details;

  LoadedReturn({
    this.driverReturnId = '',
    this.driverReturnNo = '',
    this.returnApplyNo = '',
    this.customerCode = '',
    this.customerName = '',
    this.returnDate = '',
    this.qty = 0,
    this.status = '',
    this.returnReason = '',
    this.details = const [],
  });

  factory LoadedReturn.fromJson(Map<String, dynamic> j) => LoadedReturn(
        driverReturnId: j['driverReturnId']?.toString() ?? '',
        driverReturnNo: j['driverReturnNo']?.toString() ?? '',
        returnApplyNo: j['returnApplyNo']?.toString() ?? '',
        customerCode: j['customerCode']?.toString() ?? '',
        customerName: j['customerName']?.toString() ?? '',
        returnDate: j['returnDate']?.toString() ?? '',
        qty: j['qty'] as num? ?? 0,
        status: j['status']?.toString() ?? '',
        returnReason: j['returnReason']?.toString() ?? '',
        details: (j['details'] as List? ?? [])
            .map((e) => LoadedReturnDetail.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

class LoadedReturnDetail {
  final String goodsCode;
  final String goodsName;
  final String spec;
  final String unitName;
  final num qty;
  final String batchNo;

  LoadedReturnDetail({
    this.goodsCode = '',
    this.goodsName = '',
    this.spec = '',
    this.unitName = '',
    this.qty = 0,
    this.batchNo = '',
  });

  factory LoadedReturnDetail.fromJson(Map<String, dynamic> j) => LoadedReturnDetail(
        goodsCode: j['goodsCode']?.toString() ?? '',
        goodsName: j['goodsName']?.toString() ?? '',
        spec: j['spec']?.toString() ?? '',
        unitName: j['unitName']?.toString() ?? '',
        qty: j['qty'] as num? ?? 0,
        batchNo: j['batchNo']?.toString() ?? '',
      );
}
