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

  /// 已确认装车的配送点数（后端 loadedStoreCount）。
  final int loadedStoreCount;

  /// 尚未确认装车的配送点数（后端 pendingStoreCount）。
  final int pendingStoreCount;

  /// 只要有一个配送点装好就能发车——发车按钮的启用条件，
  /// 不能再用 allChecked：那会退回「必须全装完才许发车」的旧逻辑。
  final bool anyLoaded;
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
    this.loadedStoreCount = 0,
    this.pendingStoreCount = 0,
    this.anyLoaded = false,
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
        loadedStoreCount: (j['loadedStoreCount'] as num?)?.toInt() ?? 0,
        pendingStoreCount: (j['pendingStoreCount'] as num?)?.toInt() ?? 0,
        anyLoaded: j['anyLoaded'] as bool? ?? false,
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

  /// 该配送点的装车状态：PENDING / LOADED（后端 tms_dispatch_detail.load_status）。
  final String loadStatus;

  /// 确认装车时间，未装车为空。
  final String loadTime;
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
    this.loadStatus = 'PENDING',
    this.loadTime = '',
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
        loadStatus: j['loadStatus']?.toString() ?? 'PENDING',
        loadTime: j['loadTime']?.toString() ?? '',
        items: (j['items'] as List? ?? [])
            .map((e) => LoadingItem.fromJson(e as Map<String, dynamic>))
            .toList(),
      );

  /// 已确认装车（配送点粒度）。
  ///
  /// 与旧的 checked 语义不同：checked 只表示「数量录够了」，
  /// loaded 表示司机点过【装车】、后端已落 load_status，可以发车。
  bool get loaded => loadStatus == 'LOADED';

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

/// 调度任务配送点清单（对接 /tms/app/loading/stores）。
///
/// 与 LoadingDispatch 并存而不合并：LoadingDispatch 是「发货单 + SKU」的装车核对视图，
/// 只含发货单；本模型是「一行 = 一个配送点」的行程视图，退货单也要出现。
/// 司机接单后先看行程（跑哪几个点、顺序对不对），装车时才需要 SKU 粒度。
class LoadingStores {
  final String dispatchId;
  final String dispatchNo;
  final String status;
  final String vehiclePlate;
  final String routeLine;
  final String dispatchDate;

  /// 是否追加单（挂在某趟在途车上的加塞任务）。
  final bool appended;

  /// 后端是否允许调序：仅未发车且待配送门店多于 1 家时为 true。
  ///
  /// 由后端算而不是前端拼状态：调序的合法性还取决于「已完成门店被锁定后
  /// 还剩几家可动」，这个信息只有后端有。
  final bool canSort;

  final List<LoadingStore> stores;
  final int storeCount;
  final int pendingStore;
  final int billCount;
  final num totalQty;
  final num collectAmount;

  LoadingStores({
    required this.dispatchId,
    this.dispatchNo = '',
    this.status = '',
    this.vehiclePlate = '',
    this.routeLine = '',
    this.dispatchDate = '',
    this.appended = false,
    this.canSort = false,
    this.stores = const [],
    this.storeCount = 0,
    this.pendingStore = 0,
    this.billCount = 0,
    this.totalQty = 0,
    this.collectAmount = 0,
  });

  factory LoadingStores.fromJson(Map<String, dynamic> j) {
    final s = j['summary'] as Map<String, dynamic>? ?? {};
    return LoadingStores(
      dispatchId: j['dispatchId']?.toString() ?? '',
      dispatchNo: j['dispatchNo']?.toString() ?? '',
      status: j['status']?.toString() ?? '',
      vehiclePlate: j['vehiclePlate']?.toString() ?? '',
      routeLine: j['routeLine']?.toString() ?? '',
      dispatchDate: (j['dispatchDate']?.toString() ?? '').split('T').first,
      appended: j['appended'] == true,
      canSort: j['canSort'] == true,
      stores: (j['stores'] as List? ?? [])
          .map((e) => LoadingStore.fromJson(e as Map<String, dynamic>))
          .toList(),
      storeCount: s['storeCount'] as int? ?? 0,
      pendingStore: s['pendingStore'] as int? ?? 0,
      billCount: s['billCount'] as int? ?? 0,
      totalQty: s['totalQty'] as num? ?? 0,
      collectAmount: s['collectAmount'] as num? ?? 0,
    );
  }

  /// 当前状态是否还能进装车流程（ASSIGNED 需先接单，ACCEPTED/LOADED 可装车）。
  bool get beforeDepart =>
      status == 'ASSIGNED' || status == 'ACCEPTED' || status == 'LOADED';
}

/// 清单中的一个配送点（同门店的多张单据已合并）。
class LoadingStore {
  final String customerCode;
  final String customerName;
  final String customerAddress;
  final String contactName;
  final String contactMobile;
  final String settlementText;
  final double? longitude;
  final double? latitude;

  /// 门店序号（后端按 seqNo 排序后赋的 1..N，用于界面上的 ①②③）。
  final int orderNo;

  /// 明细行上的原始 seq_no（同店取最小值），提交调序时不用它，仅供排查。
  final int seqNo;

  final int receiptCount;
  final int returnCount;

  /// 未签收单据数；为 0 即整店已完成。
  final int pendingCount;

  final num totalQty;
  final num collectAmount;
  final bool needCollect;

  /// 整店是否已完成（后端按 pendingCount == 0 判定）。
  final bool done;

  /// 是否可拖拽调序；已完成门店恒为 false（货已卸，挪顺序无意义）。
  final bool sortable;

  final List<LoadingStoreBill> bills;

  LoadingStore({
    required this.customerCode,
    this.customerName = '',
    this.customerAddress = '',
    this.contactName = '',
    this.contactMobile = '',
    this.settlementText = '',
    this.longitude,
    this.latitude,
    this.orderNo = 0,
    this.seqNo = 0,
    this.receiptCount = 0,
    this.returnCount = 0,
    this.pendingCount = 0,
    this.totalQty = 0,
    this.collectAmount = 0,
    this.needCollect = false,
    this.done = false,
    this.sortable = true,
    this.bills = const [],
  });

  factory LoadingStore.fromJson(Map<String, dynamic> j) => LoadingStore(
        customerCode: j['customerCode']?.toString() ?? '',
        customerName: j['customerName']?.toString() ?? '',
        customerAddress: j['customerAddress']?.toString() ?? '',
        contactName: j['contactName']?.toString() ?? '',
        contactMobile: j['contactMobile']?.toString() ?? '',
        settlementText: j['settlementText']?.toString() ?? '',
        longitude: _num(j['longitude']),
        latitude: _num(j['latitude']),
        orderNo: j['orderNo'] as int? ?? 0,
        seqNo: j['seqNo'] as int? ?? 0,
        receiptCount: j['receiptCount'] as int? ?? 0,
        returnCount: j['returnCount'] as int? ?? 0,
        pendingCount: j['pendingCount'] as int? ?? 0,
        totalQty: j['totalQty'] as num? ?? 0,
        collectAmount: j['collectAmount'] as num? ?? 0,
        needCollect: j['needCollect'] == true,
        done: j['done'] == true,
        sortable: j['sortable'] != false,
        bills: (j['bills'] as List? ?? [])
            .map((e) => LoadingStoreBill.fromJson(e as Map<String, dynamic>))
            .toList(),
      );

  /// 后端 DECIMAL 可能序列化成 num 或字符串，统一容错。
  static double? _num(Object? v) {
    if (v == null) return null;
    if (v is num) return v.toDouble();
    return double.tryParse(v.toString());
  }

  bool get hasPhone => contactMobile.trim().isNotEmpty;
}

/// 配送点下的一张单据（发货单或退货取件单）。
class LoadingStoreBill {
  final String detailId;
  final String billType;
  final String billTypeText;
  final String sourceBillNo;
  final num qty;
  final int skuCount;
  final int seqNo;
  final String status;
  final bool done;
  final num receivableAmount;

  LoadingStoreBill({
    required this.detailId,
    this.billType = 'RECEIPT',
    this.billTypeText = '发货',
    this.sourceBillNo = '',
    this.qty = 0,
    this.skuCount = 0,
    this.seqNo = 0,
    this.status = '',
    this.done = false,
    this.receivableAmount = 0,
  });

  factory LoadingStoreBill.fromJson(Map<String, dynamic> j) => LoadingStoreBill(
        detailId: j['detailId']?.toString() ?? '',
        billType: j['billType']?.toString() ?? 'RECEIPT',
        billTypeText: j['billTypeText']?.toString() ?? '发货',
        sourceBillNo: j['sourceBillNo']?.toString() ?? '',
        qty: j['qty'] as num? ?? 0,
        skuCount: j['skuCount'] as int? ?? 0,
        seqNo: j['seqNo'] as int? ?? 0,
        status: j['status']?.toString() ?? '',
        done: j['done'] == true,
        receivableAmount: j['receivableAmount'] as num? ?? 0,
      );

  bool get isReturn => billType == 'RETURN';
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
