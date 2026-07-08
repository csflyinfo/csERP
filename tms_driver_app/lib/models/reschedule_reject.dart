// 改派返仓 + 客户拒收单模型（对接 /tms/app/reschedule-return/* 和 /tms/app/customer-reject/*）。

/// 改派原因枚举（与后端 reason 字段对应）。
class RescheduleReason {
  static const customerAbsent = 'CUSTOMER_ABSENT';   // 客户不在
  static const addressError = 'ADDRESS_ERROR';        // 地址错误
  static const unreachable = 'UNREACHABLE';           // 联系不上
  static const customerRequest = 'CUSTOMER_REQUEST';  // 客户要求改期
  static const other = 'OTHER';

  static const labels = {
    customerAbsent: '客户不在',
    addressError: '地址错误',
    unreachable: '联系不上',
    customerRequest: '客户要求改期',
    other: '其他',
  };

  static String label(String code) => labels[code] ?? code;
}

/// 客户拒收原因枚举（与后端 rejectReason 字段对应）。
class CustomerRejectReason {
  static const customerReject = 'CUSTOMER_REJECT';    // 客户拒收
  static const goodsDamaged = 'GOODS_DAMAGED';        // 货物破损
  static const specMismatch = 'SPEC_MISMATCH';        // 规格不符
  static const qtyMismatch = 'QTY_MISMATCH';          // 数量不符
  static const other = 'OTHER';

  static const labels = {
    customerReject: '客户拒收',
    goodsDamaged: '货物破损',
    specMismatch: '规格不符',
    qtyMismatch: '数量不符',
    other: '其他',
  };

  static String label(String code) => labels[code] ?? code;
}

/// 改派返仓单（待返仓列表项）。
class RescheduleReturn {
  final String returnId;
  final String returnNo;
  final String tripId;
  final String dispatchId;
  final String receiptNo;
  final String customerCode;
  final String customerName;
  final String customerAddress;
  final String reason;
  final String reasonDetail;
  final num totalQty;
  final String rescheduleDate;
  final int rescheduleCount;
  final String status;
  final String createTime;
  final String remark;

  RescheduleReturn({
    this.returnId = '',
    this.returnNo = '',
    this.tripId = '',
    this.dispatchId = '',
    this.receiptNo = '',
    this.customerCode = '',
    this.customerName = '',
    this.customerAddress = '',
    this.reason = '',
    this.reasonDetail = '',
    this.totalQty = 0,
    this.rescheduleDate = '',
    this.rescheduleCount = 1,
    this.status = '',
    this.createTime = '',
    this.remark = '',
  });

  factory RescheduleReturn.fromJson(Map<String, dynamic> j) => RescheduleReturn(
        returnId: j['returnId']?.toString() ?? '',
        returnNo: j['returnNo']?.toString() ?? '',
        tripId: j['tripId']?.toString() ?? '',
        dispatchId: j['dispatchId']?.toString() ?? '',
        receiptNo: j['receiptNo']?.toString() ?? '',
        customerCode: j['customerCode']?.toString() ?? '',
        customerName: j['customerName']?.toString() ?? '',
        customerAddress: j['customerAddress']?.toString() ?? '',
        reason: j['reason']?.toString() ?? '',
        reasonDetail: j['reasonDetail']?.toString() ?? '',
        totalQty: j['totalQty'] as num? ?? 0,
        rescheduleDate: j['rescheduleDate']?.toString() ?? '',
        rescheduleCount: j['rescheduleCount'] as int? ?? 1,
        status: j['status']?.toString() ?? '',
        createTime: j['createTime']?.toString() ?? '',
        remark: j['remark']?.toString() ?? '',
      );
}

/// 客户拒收单（待返仓列表项）。
class CustomerReject {
  final String rejectId;
  final String rejectNo;
  final String tripId;
  final String dispatchId;
  final String receiptNo;
  final String customerCode;
  final String customerName;
  final String customerAddress;
  final String rejectReason;
  final String reasonDetail;
  final num totalQty;
  final num totalAmount;
  final String status;
  final String createTime;
  final String remark;

  CustomerReject({
    this.rejectId = '',
    this.rejectNo = '',
    this.tripId = '',
    this.dispatchId = '',
    this.receiptNo = '',
    this.customerCode = '',
    this.customerName = '',
    this.customerAddress = '',
    this.rejectReason = '',
    this.reasonDetail = '',
    this.totalQty = 0,
    this.totalAmount = 0,
    this.status = '',
    this.createTime = '',
    this.remark = '',
  });

  factory CustomerReject.fromJson(Map<String, dynamic> j) => CustomerReject(
        rejectId: j['rejectId']?.toString() ?? '',
        rejectNo: j['rejectNo']?.toString() ?? '',
        tripId: j['tripId']?.toString() ?? '',
        dispatchId: j['dispatchId']?.toString() ?? '',
        receiptNo: j['receiptNo']?.toString() ?? '',
        customerCode: j['customerCode']?.toString() ?? '',
        customerName: j['customerName']?.toString() ?? '',
        customerAddress: j['customerAddress']?.toString() ?? '',
        rejectReason: j['rejectReason']?.toString() ?? '',
        reasonDetail: j['reasonDetail']?.toString() ?? '',
        totalQty: j['totalQty'] as num? ?? 0,
        totalAmount: j['totalAmount'] as num? ?? 0,
        status: j['status']?.toString() ?? '',
        createTime: j['createTime']?.toString() ?? '',
        remark: j['remark']?.toString() ?? '',
      );
}

/// 拒收商品行（客户拒收单创建时录入）。
class RejectItem {
  String goodsCode;
  String goodsName;
  String spec;
  String unitName;
  num rejectQty;
  num price;
  String batchNo;

  RejectItem({
    this.goodsCode = '',
    this.goodsName = '',
    this.spec = '',
    this.unitName = '',
    this.rejectQty = 1,
    this.price = 0,
    this.batchNo = '',
  });

  num get amount => (rejectQty * price).toDouble();

  Map<String, dynamic> toJson() => {
        'goodsCode': goodsCode,
        'goodsName': goodsName,
        'spec': spec,
        'unitName': unitName,
        'rejectQty': rejectQty,
        'price': price,
        'batchNo': batchNo,
      };
}
