import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAppStore = defineStore('app', () => {
  const menus = ref([])
  const activeTop = ref('')
  const openTabs = ref([])
  const currentModule = ref('')
  const toastText = ref('')
  /** 自增信号：被保存后递增，GenericBusinessList 可 watch 此值触发刷新 */
  const refreshSignal = ref(0)

  // 采购/销售订单抽屉状态：跨模块保留，只允许一个实例
  const billDrawer = ref({
    visible: false,
    moduleCode: '',       // 'purchaseOrder' | 'salesOrder'
    mode: 'add',
    editData: null,
  })

  function openBillDrawer(moduleCode, mode = 'add', editData = null) {
    billDrawer.value = { visible: true, moduleCode, mode, editData }
  }
  function closeBillDrawer() {
    billDrawer.value = { ...billDrawer.value, visible: false }
  }

  // 采购入库单抽屉状态：跨模块保留（点击订单「生成入库单」后即使切到入库页面也能继续填）
  // sourceOrder: { orderId, orderNo } 用于「引入订单」预填；为 null 时是「新建入库单」
  const inboundDrawer = ref({
    visible: false,
    sourceOrder: null,
  })

  function openInboundDrawer(sourceOrder = null) {
    inboundDrawer.value = { visible: true, sourceOrder }
  }
  function closeInboundDrawer() {
    inboundDrawer.value = { ...inboundDrawer.value, visible: false }
  }
  /** 采购订单「生成入库单」入口 —— 携带 orderId/orderNo 打开入库单抽屉。 */
  function openInboundFromOrder(sourceOrder) {
    openInboundDrawer(sourceOrder)
  }

  // 销售出库单抽屉状态（对称于 inboundDrawer）
  const outboundDrawer = ref({
    visible: false,
    sourceOrder: null,
    editData: null,
  })

  function openOutboundDrawer(sourceOrder = null) {
    outboundDrawer.value = { visible: true, sourceOrder, editData: null }
  }
  function closeOutboundDrawer() {
    outboundDrawer.value = { ...outboundDrawer.value, visible: false }
  }
  /** 销售订单「生成出库单」入口 —— 携带 orderId/orderNo 打开出库单抽屉。 */
  function openOutboundFromOrder(sourceOrder) {
    openOutboundDrawer(sourceOrder)
  }

  // 采购收货单抽屉：未审核可改单价，已审核只读（readonly 由列表「查看」传入）
  const receiptDrawer = ref({
    visible: false,
    receiptId: '',
    readonly: false,
  })

  function openReceiptDrawer(receiptId, readonly = false) {
    receiptDrawer.value = { visible: true, receiptId, readonly }
  }
  function closeReceiptDrawer() {
    receiptDrawer.value = { ...receiptDrawer.value, visible: false }
  }

  // 采购退货申请抽屉：editData 用于编辑/查看已有单据（{ applyId, applyNo }）
  // 商品由抽屉内的【按单添加商品】/【添加商品】两个选择窗口添加，不再从外部预填源单
  // readonly：列表点「查看」时传 true，强制只读（已审核单据抽屉内部也会自行判定）
  const returnApplyDrawer = ref({
    visible: false,
    sourceReceipt: null,   // 保留字段占位，当前未使用
    editData: null,
    readonly: false,
  })

  function openReturnApplyDrawer(sourceReceipt = null, editData = null, readonly = false) {
    returnApplyDrawer.value = { visible: true, sourceReceipt, editData, readonly }
  }
  function closeReturnApplyDrawer() {
    returnApplyDrawer.value = { ...returnApplyDrawer.value, visible: false }
  }

  // 调价单抽屉：从列表或「快速调价」入口打开
  const priceAdjustDrawer = ref({
    visible: false,
    orderId: '',
    mode: 'add',   // add | edit | view
  })

  function openPriceAdjustDrawer(orderId, mode = 'add') {
    priceAdjustDrawer.value = { visible: true, orderId, mode }
  }
  function closePriceAdjustDrawer() {
    priceAdjustDrawer.value = { ...priceAdjustDrawer.value, visible: false }
  }

  // 商品调价单抽屉：从列表或「快速调价」入口打开
  const goodsPriceAdjustDrawer = ref({
    visible: false,
    orderId: '',
    mode: 'add',
  })

  function openGoodsPriceAdjustDrawer(orderId, mode = 'add') {
    goodsPriceAdjustDrawer.value = { visible: true, orderId, mode }
  }
  function closeGoodsPriceAdjustDrawer() {
    goodsPriceAdjustDrawer.value = { ...goodsPriceAdjustDrawer.value, visible: false }
  }

  // 盘点单抽屉：从库存盘点列表打开
  const stockTakeDrawer = ref({
    visible: false,
    mode: 'create',   // create | edit | view
    editData: null,
  })

  function openStockTakeDrawer(mode = 'create', editData = null) {
    stockTakeDrawer.value = { visible: true, mode, editData }
  }
  function closeStockTakeDrawer() {
    stockTakeDrawer.value = { ...stockTakeDrawer.value, visible: false }
  }

  // 采购退货出库抽屉：只能从列表「编辑/查看」进入（出库单由申请审核自动生成）
  const returnOutboundDrawer = ref({
    visible: false,
    outboundId: '',
    readonly: false,
  })

  function openReturnOutboundDrawer(outboundId, readonly = false) {
    returnOutboundDrawer.value = { visible: true, outboundId, readonly }
  }
  function closeReturnOutboundDrawer() {
    returnOutboundDrawer.value = { ...returnOutboundDrawer.value, visible: false }
  }

  // 采购退货单抽屉：只读查看 + 审核
  const returnDrawer = ref({
    visible: false,
    returnId: '',
  })

  function openReturnDrawer(returnId) {
    returnDrawer.value = { visible: true, returnId }
  }
  function closeReturnDrawer() {
    returnDrawer.value = { ...returnDrawer.value, visible: false }
  }

  // ============ 销售退货单抽屉 ============
  const salesReturnDrawer = ref({
    visible: false,
    sourceReceipt: null,
    editData: null,
    readonly: false,
  })

  function openSalesReturnDrawer(sourceReceipt = null, editData = null, readonly = false) {
    salesReturnDrawer.value = { visible: true, sourceReceipt, editData, readonly }
  }
  function closeSalesReturnDrawer() {
    salesReturnDrawer.value = { ...salesReturnDrawer.value, visible: false }
  }

  // ============ 销售退货入库抽屉（列表进入，编辑/审核入库单）============
  const salesReturnInboundDrawer = ref({
    visible: false,
    inboundId: '',
  })

  function openSalesReturnInboundDrawer(inboundId) {
    salesReturnInboundDrawer.value = { visible: true, inboundId }
  }
  function closeSalesReturnInboundDrawer() {
    salesReturnInboundDrawer.value = { ...salesReturnInboundDrawer.value, visible: false }
  }

  // ============ 拒收入库单抽屉（列表进入，编辑/审核入库单）============
  // 单据由发货单签收拒收自动生成，所以没有「新建」入口，只能带 inboundId 打开
  const rejectInboundDrawer = ref({
    visible: false,
    inboundId: '',
    readonly: false,
  })

  function openRejectInboundDrawer(inboundId, readonly = false) {
    rejectInboundDrawer.value = { visible: true, inboundId, readonly }
  }
  function closeRejectInboundDrawer() {
    rejectInboundDrawer.value = { ...rejectInboundDrawer.value, visible: false }
  }

  // ============ 发货单确认签收弹窗 ============
  const receiptSignDialog = ref({
    visible: false,
    receiptId: '',
  })

  function openReceiptSignDialog(receiptId) {
    receiptSignDialog.value = { visible: true, receiptId }
  }
  function closeReceiptSignDialog() {
    receiptSignDialog.value = { ...receiptSignDialog.value, visible: false }
  }

  // ============ 飞单抽屉 ============
  const flyOrderDrawer = ref({
    visible: false,
    mode: 'add',
    editData: null,
  })

  function openFlyOrderDrawer(mode = 'add', editData = null) {
    flyOrderDrawer.value = { visible: true, mode, editData }
  }
  function closeFlyOrderDrawer() {
    flyOrderDrawer.value = { ...flyOrderDrawer.value, visible: false }
  }

  // ============ 报损单抽屉 ============
  const damageDrawer = ref({
    visible: false,
    mode: 'add',       // add | edit | view
    editData: null,
    readonly: false,
  })

  function openDamageDrawer(mode = 'add', editData = null, readonly = false) {
    damageDrawer.value = { visible: true, mode, editData, readonly }
  }
  function closeDamageDrawer() {
    damageDrawer.value = { ...damageDrawer.value, visible: false }
  }

  // ============ 其他入库单抽屉 ============
  const otherInboundDrawer = ref({
    visible: false,
    mode: 'add',       // add | edit | view
    editData: null,
    readonly: false,
  })

  function openOtherInboundDrawer(mode = 'add', editData = null, readonly = false) {
    otherInboundDrawer.value = { visible: true, mode, editData, readonly }
  }
  function closeOtherInboundDrawer() {
    otherInboundDrawer.value = { ...otherInboundDrawer.value, visible: false }
  }

  // ============ 其他出库单抽屉 ============
  const otherOutboundDrawer = ref({
    visible: false,
    mode: 'add',       // add | edit | view
    editData: null,
    readonly: false,
  })

  function openOtherOutboundDrawer(mode = 'add', editData = null, readonly = false) {
    otherOutboundDrawer.value = { visible: true, mode, editData, readonly }
  }
  function closeOtherOutboundDrawer() {
    otherOutboundDrawer.value = { ...otherOutboundDrawer.value, visible: false }
  }

  function addTab(route) {
    if (!route.meta?.title) return
    const exists = openTabs.value.find(t => t.path === route.path)
    if (!exists) {
      openTabs.value.push({ path: route.path, name: route.meta.title, module: route.meta.module || '' })
    }
  }

  function removeTab(path) {
    openTabs.value = openTabs.value.filter(t => t.path !== path)
  }

  function showToast(msg) {
    toastText.value = msg
    setTimeout(() => { toastText.value = '' }, 2000)
  }

  return {
    menus, activeTop, openTabs, currentModule, toastText, refreshSignal,
    billDrawer, openBillDrawer, closeBillDrawer,
    inboundDrawer, openInboundDrawer, closeInboundDrawer, openInboundFromOrder,
    outboundDrawer, openOutboundDrawer, closeOutboundDrawer, openOutboundFromOrder,
    receiptDrawer, openReceiptDrawer, closeReceiptDrawer,
    returnApplyDrawer, openReturnApplyDrawer, closeReturnApplyDrawer,
    returnOutboundDrawer, openReturnOutboundDrawer, closeReturnOutboundDrawer,
    returnDrawer, openReturnDrawer, closeReturnDrawer,
    salesReturnDrawer, openSalesReturnDrawer, closeSalesReturnDrawer,
    salesReturnInboundDrawer, openSalesReturnInboundDrawer, closeSalesReturnInboundDrawer,
    rejectInboundDrawer, openRejectInboundDrawer, closeRejectInboundDrawer,
    receiptSignDialog, openReceiptSignDialog, closeReceiptSignDialog,
    flyOrderDrawer, openFlyOrderDrawer, closeFlyOrderDrawer,
    priceAdjustDrawer, openPriceAdjustDrawer, closePriceAdjustDrawer,
    goodsPriceAdjustDrawer, openGoodsPriceAdjustDrawer, closeGoodsPriceAdjustDrawer,
    stockTakeDrawer, openStockTakeDrawer, closeStockTakeDrawer,
    damageDrawer, openDamageDrawer, closeDamageDrawer,
    otherInboundDrawer, openOtherInboundDrawer, closeOtherInboundDrawer,
    otherOutboundDrawer, openOtherOutboundDrawer, closeOtherOutboundDrawer,
    addTab, removeTab, showToast,
  }
})
