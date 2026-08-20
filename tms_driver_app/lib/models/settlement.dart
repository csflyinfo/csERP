// 交账与结算模型（对接 /tms/app/settlement/* 和 /tms/settlement/*）。

/// 本日交账汇总（司机端预览）。
class SettlementSummary {
  final bool alreadySettled;
  final String settlementId;
  final String settlementNo;
  final String status;
  final int totalStores;
  final int signedStores;
  final num totalAmount;
  final num cashAmount;
  final num onlineAmount;
  final num returnAmount;
  final num returnQty;
  final num creditAmount;
  final num submitAmount;
  final List<DispatchBrief> dispatches;

  SettlementSummary({
    this.alreadySettled = false,
    this.settlementId = '',
    this.settlementNo = '',
    this.status = '',
    this.totalStores = 0,
    this.signedStores = 0,
    this.totalAmount = 0,
    this.cashAmount = 0,
    this.onlineAmount = 0,
    this.returnAmount = 0,
    this.returnQty = 0,
    this.creditAmount = 0,
    this.submitAmount = 0,
    this.dispatches = const [],
  });

  factory SettlementSummary.fromJson(Map<String, dynamic> j) => SettlementSummary(
        alreadySettled: j['alreadySettled'] == true,
        settlementId: j['settlementId']?.toString() ?? '',
        settlementNo: j['settlementNo']?.toString() ?? '',
        status: j['status']?.toString() ?? '',
        totalStores: j['totalStores'] as int? ?? 0,
        signedStores: j['signedStores'] as int? ?? 0,
        totalAmount: j['totalAmount'] as num? ?? 0,
        cashAmount: j['cashAmount'] as num? ?? 0,
        onlineAmount: j['onlineAmount'] as num? ?? 0,
        returnAmount: j['returnAmount'] as num? ?? 0,
        returnQty: j['returnQty'] as num? ?? 0,
        creditAmount: j['creditAmount'] as num? ?? 0,
        submitAmount: j['submitAmount'] as num? ?? 0,
        dispatches: (j['dispatches'] as List? ?? [])
            .map((e) => DispatchBrief.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

/// 调度单摘要（汇总中的子项）。
class DispatchBrief {
  final String dispatchId;
  final String dispatchNo;
  final String driverName;
  final String routeLine;
  final int storeCount;
  final num amount;
  final String status;

  DispatchBrief({
    this.dispatchId = '',
    this.dispatchNo = '',
    this.driverName = '',
    this.routeLine = '',
    this.storeCount = 0,
    this.amount = 0,
    this.status = '',
  });

  factory DispatchBrief.fromJson(Map<String, dynamic> j) => DispatchBrief(
        dispatchId: j['dispatchId']?.toString() ?? '',
        dispatchNo: j['dispatchNo']?.toString() ?? '',
        driverName: j['driverName']?.toString() ?? '',
        routeLine: j['routeLine']?.toString() ?? '',
        storeCount: j['storeCount'] as int? ?? 0,
        amount: j['amount'] as num? ?? 0,
        status: j['status']?.toString() ?? '',
      );
}

/// 交账提交参数。
class SettlementSubmitArgs {
  final String dispatchId;
  final num actualSubmit;
  final String diffReason;
  final String signatureImg;
  final String remark;
  final List<Map<String, dynamic>> photos; // [{url, photoType?}]

  SettlementSubmitArgs({
    this.dispatchId = '',
    required this.actualSubmit,
    this.diffReason = '',
    required this.signatureImg,
    this.remark = '',
    this.photos = const [],
  });

  Map<String, dynamic> toJson() => {
        if (dispatchId.isNotEmpty) 'dispatchId': dispatchId,
        'actualSubmit': actualSubmit,
        if (diffReason.isNotEmpty) 'diffReason': diffReason,
        'signatureImg': signatureImg,
        if (remark.isNotEmpty) 'remark': remark,
        'photos': photos,
      };
}
