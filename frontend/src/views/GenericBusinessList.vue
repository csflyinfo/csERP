<script setup>
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import QueryBar from '../components/QueryBar.vue'
import ProTable from '../components/ProTable.vue'
import GoodsDrawer from './goods/GoodsDrawer.vue'
import BatchEditDrawer from './goods/components/BatchEditDrawer.vue'
import { useAppStore } from '../stores/app.js'
import FundBillDrawer from '../components/FundBillDrawer.vue'
import ReceiptDrawer from '../components/ReceiptDrawer.vue'
import ExpenseDrawer from '../components/ExpenseDrawer.vue'
import ARSettlementDialog from '../components/ARSettlementDialog.vue'
import CustomerStatementDrawer from '../components/CustomerStatementDrawer.vue'
import StatementSettlementDialog from '../components/StatementSettlementDialog.vue'
import TransferApplyDrawer from '../components/TransferApplyDrawer.vue'
import TransferOutboundDrawer from '../components/TransferOutboundDrawer.vue'
import TransferInboundDrawer from '../components/TransferInboundDrawer.vue'
import DamageDrawer from '../components/DamageDrawer.vue'
import BaseInfoDrawer from '../components/BaseInfoDrawer.vue'
import PriceAdjustDrawer from '../components/PriceAdjustDrawer.vue'
import BillDetailDrawer from '../components/BillDetailDrawer.vue'
import ImportDialog from '../components/ImportDialog.vue'
import { IMPORT_PRESETS, MODULE_DEFAULT_IMPORT, MODULE_IMPORT_MENU } from '../importPresets.js'
import { get, post, upload, downloadBlob, getBlob, saveBlobFile, saveTextFile } from '../api/client.js'
import { getDict } from '../utils/dictionary.js'
import { usePermission } from '../composables/usePermission.js'
import { useColumnSettings } from '../composables/useColumnSettings.js'
import FieldSettingDialog from '../components/FieldSettingDialog.vue'
import { mapRecordToRow, moduleApis, excelModules } from '../module-api.js'
import { moduleConfigs } from '../module-config.js'
import * as XLSX from 'xlsx'

const route = useRoute()
const router = useRouter()
const moduleCode = computed(() => route.meta?.module || '')
const config = computed(() => moduleConfigs[moduleCode.value] || {})
const roleCode = 'ADMIN'

// 基础资料模块（用于列表操作列显示"编辑 删除"）
const BASE_MODULES = ['goods', 'customer', 'supplier', 'warehouse', 'unit', 'brand', 'category', 'priceGroup', 'territory', 'routeLine', 'employee', 'department', 'owner', 'expenseType', 'counterparty', 'counterpartyType', 'fundAccount']
const isBaseModule = computed(() => BASE_MODULES.includes(moduleCode.value))

// 商品编辑抽屉
const showGoodsDrawer = ref(false)
const drawerMode = ref('add') // add/edit
const editGoodsData = ref(null)

// 业务单据抽屉（采购订单/销售订单）
// 业务单据抽屉：全局 store 管理（跨模块保留）
const app = useAppStore()

function openBillDrawer(mode, code, rowData = null) {
  app.openBillDrawer(code, mode, rowData)
}
function closeBillDrawer() {
  app.closeBillDrawer()
}
function onBillSave(result) {
  app.closeBillDrawer()
  loadRows()
  show(`${moduleConfigs[app.billDrawer.moduleCode]?.title || '单据'}保存成功`)
}

// 资金单据抽屉（收付款/核销）
const showFundDrawer = ref(false)
const fundBillType = ref('receipt')
const fundEditData = ref(null)

function openFundDrawer(type, rowData = null) {
  fundBillType.value = type
  fundEditData.value = rowData
  showFundDrawer.value = true
}
function closeFundDrawer() {
  showFundDrawer.value = false
}
function onFundSave(result) {
  showFundDrawer.value = false
  loadRows()
  show('保存成功')
}
function onFundClose() {
  showFundDrawer.value = false
}

// ===== 客户应收收款结算弹窗 =====
const showARSettlement = ref(false)
const arSettlementRows = ref([])  // 勾选的 AR 行 _raw

function openARSettlementDialog() {
  if (selectedRowKeys.value.size === 0) { show('请勾选要结算的应收单据'); return }
  arSettlementRows.value = tableRows.value.filter((_, i) => selectedRowKeys.value.has(i)).map(r => r._raw).filter(Boolean)
  if (arSettlementRows.value.length === 0) { show('未找到有效的应收记录'); return }
  showARSettlement.value = true
}

async function onARSettlementSaved() {
  showARSettlement.value = false
  selectedRowKeys.value.clear()
  await loadRows()
  show('收款结算完成')
}

// ===== 对账单收款/付款结算 =====
const showStatementSettlement = ref(false)
const statementSettleRows = ref([])

function openStatementSettlement() {
  if (selectedRowKeys.value.size === 0) { show('请勾选要结算的对账单'); return }
  statementSettleRows.value = tableRows.value.filter((_, i) => selectedRowKeys.value.has(i))
  showStatementSettlement.value = true
}

function onStatementSettlementSaved() {
  showStatementSettlement.value = false
  selectedRowKeys.value.clear()
  loadRows()
  show('结算完成')
}

// ===== 对账单抽屉 =====
const showStatementDrawer = ref(false)
const statementDrawerMode = ref('add')
const statementEditData = ref(null)

function openStatementDrawer(row = null) {
  statementDrawerMode.value = row ? 'edit' : 'add'
  statementEditData.value = row
  showStatementDrawer.value = true
}

// ===== 费用单抽屉 =====
const showExpenseDrawer = ref(false)
const expenseDrawerMode = ref('add')
const expenseEditData = ref(null)

function openExpenseDrawer(row = null) {
  expenseDrawerMode.value = row ? 'edit' : 'add'
  expenseEditData.value = row
  showExpenseDrawer.value = true
}

// ===== 收款单/付款单抽屉 =====
const showReceiptDrawer = ref(false)
const receiptDrawerMode = ref('add')
const receiptEditData = ref(null)
const currentReceiptModule = ref('receiptPayment') // 'receiptPayment' | 'paymentModule'

function openReceiptDrawer(row = null) {
  currentReceiptModule.value = moduleCode.value  // 记住是收款还是付款
  receiptDrawerMode.value = row ? 'edit' : 'add'
  receiptEditData.value = row
  showReceiptDrawer.value = true
}

// ===== 调拨抽屉 =====
const showTransferApply = ref(false)
const transferApplyMode = ref('add')
const transferApplyEditData = ref(null)

function openTransferApply(row = null) {
  transferApplyMode.value = row ? 'edit' : 'add'
  transferApplyEditData.value = row
  showTransferApply.value = true
}

const showTransferOutbound = ref(false)
const transferOutboundMode = ref('add')
const transferOutboundEditData = ref(null)

function openTransferOutbound(row = null) {
  transferOutboundMode.value = row ? 'edit' : 'add'
  transferOutboundEditData.value = row
  showTransferOutbound.value = true
}

function openTransferOutboundFromApply(applyRow) {
  transferOutboundMode.value = 'add'
  const raw = applyRow._raw || applyRow
  transferOutboundEditData.value = { applyNo: raw.applyNo || raw.c0 }
  showTransferOutbound.value = true
}

const showTransferInbound = ref(false)
const transferInboundEditData = ref(null)

function openTransferInbound(row = null) {
  transferInboundEditData.value = row
  showTransferInbound.value = true
}

async function handleAuditAction(api, row) {
  const raw = row._raw || {}
  const id = raw.receiptId || raw.expenseId || raw.statementId || raw.RECEIPT_ID || row.c0
  const idKey = raw.statementId ? 'statementId' : raw.expenseId ? 'expenseId' : 'receiptId'
  const isExpense = !!raw.expenseId
  const isStmt = !!raw.statementId
  const label = isStmt ? '该对账单' : isExpense ? '该费用单' : '该收款单'
  const detail = isStmt ? '' : '\n\n审核后将生成核销记录、资金流水与往来流水。'
  if (!confirm(`确认审核${label}？${detail}`)) return
  try {
    await post(api.audit, { [idKey]: id })
    show('审核成功')
    await loadRows()
  } catch (e) { show('审核失败：' + (e.message || '未知错误')) }
}

/**
 * 发货单撤销签收：清空签收/拒收登记，并删除自动生成的拒收入库单。
 * 若拒收入库单已审核入库，后端会拒绝，需先反审核那张单。
 */
async function handleReceiptUnsign(row) {
  const raw = row?._raw || {}
  const receiptId = raw.receiptId || raw.receiptNo
  if (!receiptId) { show('请先选择一张发货单'); return }
  const signStatus = raw.signStatus || ''
  if (!signStatus || signStatus === '待签收') { show('该发货单尚未签收，无需撤销'); return }
  if (!confirm(`确认撤销发货单【${raw.receiptNo || receiptId}】的签收登记？\n\n将清空签收/拒收数量，并删除自动生成的拒收入库单。\n若拒收入库单已审核入库，需先反审核该单。`)) return
  try {
    await post('/sales/receipt/unsign', { bizId: receiptId })
    show('已撤销签收')
    await loadRows()
  } catch (e) { show('撤销签收失败：' + (e.message || '未知错误')) }
}

/**
 * 批量签收：勾选的发货单一律「全签」—— 拒收数量 0、签收数量 = 发货数量。
 * 不传 details，后端对没传的行就是这个口径，不必先把明细拉下来。
 * 有拒收的单子必须走行内「确认签收」逐行填数量和拒收原因。
 */
async function batchSignReceipts() {
  if (selectedRowKeys.value.size === 0) { show('请勾选要签收的发货单'); return }
  const rows = tableRows.value.filter((_, i) => selectedRowKeys.value.has(i))
  const targets = rows.filter(r => {
    const raw = r._raw || {}
    return raw.status !== 'CANCELLED' && (!raw.signStatus || raw.signStatus === '待签收')
  })
  const skipped = rows.length - targets.length
  if (targets.length === 0) { show('勾选的发货单都已签收或已作废，没有可签收的单据'); return }
  if (!confirm(`确认批量签收 ${targets.length} 张发货单？`
    + (skipped > 0 ? `（另有 ${skipped} 张已签收/已作废，将跳过）` : '')
    + '\n\n批量签收按【全部签收】处理：拒收数量为 0，签收数量 = 发货数量。'
    + '\n签收后自动审核，按签收金额生成应收。有拒收的单据请用行内「确认签收」逐行登记。')) return
  let ok = 0, fail = 0
  for (const r of targets) {
    const raw = r._raw || {}
    const receiptId = raw.receiptId || raw.receiptNo
    if (!receiptId) { fail++; continue }
    try { await post('/sales/receipt/sign', { receiptId }); ok++ }
    catch (e) { fail++ }
  }
  show(`批量签收完成：成功 ${ok} 张`
    + (fail > 0 ? `，失败 ${fail} 张` : '')
    + (skipped > 0 ? `，跳过 ${skipped} 张` : ''))
  selectedRowKeys.value.clear()
  await loadRows()
}

async function handleCancelAuditAction(api, row) {
  const id = row._raw?.receiptId || row._raw?.RECEIPT_ID || row.c0
  if (!confirm('确认取消审核？\n\n将冲减资金流水、往来流水并删除核销记录，恢复待审核状态。')) return
  try {
    await post('/finance/receipt/cancel-audit', { receiptId: id })
    show('已取消审核')
    await loadRows()
  } catch (e) { show('取消审核失败：' + (e.message || '未知错误')) }
}

async function handleDeleteAction(api, row) {
  const id = row._raw?.receiptId || row._raw?.RECEIPT_ID || row.c0
  if (!confirm('确认删除该收款单？')) return
  try {
    await post('/finance/receipt/delete', { receiptId: id })
    show('已删除')
    await loadRows()
  } catch (e) { show('删除失败：' + (e.message || '未知错误')) }
}

async function batchAuditReceipt() {
  if (selectedRowKeys.value.size === 0) { show('请勾选要审核的收款单'); return }
  const rows = tableRows.value.filter((_, i) => selectedRowKeys.value.has(i))
  const ids = rows.map(r => r._raw?.receiptId || '').filter(Boolean)
  if (ids.length === 0) { show('未找到有效的收款单ID'); return }
  if (!confirm(`确认批量审核 ${ids.length} 张收款单？`)) return
  try {
    const api = isReceipt()
      ? '/finance/receipt/batch-audit'
      : '/finance/payment/batch-audit'
    const res = await post(api, { receiptIds: ids })
    show(`审核完成：成功 ${res?.audited ?? 0} 张，跳过 ${res?.skipped ?? 0} 张`)
    selectedRowKeys.value.clear()
    await loadRows()
  } catch (e) { show('批量审核失败：' + (e.message || '未知错误')) }
}

async function handleStatementDelete(api, row) {
  const id = row._raw?.statementId || row.c0
  if (!confirm('是否确认删除该对账单？')) return
  try {
    const prefix = moduleCode.value === 'supplierStatement' ? 'supplier' : 'customer'
    await post(`/finance/${prefix}-statement/delete`, { statementId: id })
    show('已删除')
    await loadRows()
  } catch (e) { show('删除失败：' + (e.message || '未知错误')) }
}

async function handleExpenseDelete(row) {
  const id = row._raw?.expenseId || row.c0
  if (!confirm('确认删除该费用单？')) return
  try { await post('/finance/expense/delete', { expenseId: id }); show('已删除'); await loadRows() }
  catch (e) { show('删除失败：' + (e.message || '未知错误')) }
}

async function batchAuditExpense() {
  if (selectedRowKeys.value.size === 0) { show('请勾选要审核的费用单'); return }
  const rows = tableRows.value.filter((_, i) => selectedRowKeys.value.has(i))
  const ids = rows.map(r => r._raw?.expenseId || '').filter(Boolean)
  if (ids.length === 0) { show('未找到有效的费用单ID'); return }
  if (!confirm(`确认批量审核 ${ids.length} 张费用单？`)) return
  let ok = 0, fail = 0
  for (const id of ids) {
    try { await post('/finance/expense/audit', { expenseId: id }); ok++ }
    catch (e) { fail++ }
  }
  show(`审核完成：成功 ${ok} 张` + (fail > 0 ? `，失败 ${fail} 张` : ''))
  selectedRowKeys.value.clear(); await loadRows()
}

function isReceipt() {
  return moduleCode.value === 'receiptPayment'
}

// ===== 调拨 handlers =====
const transferLabels = { transferApply: '调拨申请单', transferOutbound: '调拨出库单', transferInbound: '调拨入库单' }

async function handleTransferAudit(api, row, idKey, noKey) {
  const raw = row?._raw || {}
  const id = raw[idKey] || raw[noKey] || row.c0
  if (!id) { show('无法获取单据ID'); return }
  const label = transferLabels[moduleCode.value] || '该单据'
  if (!confirm(`确认审核${label}？`)) return
  try {
    const res = await post(api.audit, { [idKey]: id })
    show(res?.effect || '审核成功')
    await loadRows()
  } catch (e) { show('审核失败：' + (e.message || '未知错误')) }
}

async function handleTransferReverseAudit(api, row, idKey, noKey) {
  const raw = row?._raw || {}
  const id = raw[idKey] || raw[noKey] || row.c0
  if (!id) { show('无法获取单据ID'); return }
  if (!confirm('确认反审核？')) return
  try {
    const res = await post(api.reverseAudit, { [idKey]: id })
    show(res?.effect || '已反审核')
    await loadRows()
  } catch (e) { show('反审核失败：' + (e.message || '未知错误')) }
}

async function handleTransferDelete(api, row, idKey, noKey) {
  const raw = row?._raw || {}
  const id = raw[idKey] || raw[noKey] || row.c0
  if (!id) { show('无法获取单据ID'); return }
  if (!confirm('确认删除？')) return
  try {
    await post(api.delete, { [idKey]: id })
    show('已删除')
    await loadRows()
  } catch (e) { show('删除失败：' + (e.message || '未知错误')) }
}

// ===== 核销弹窗 =====
const showReconcileDialog = ref(false)
const reconcilePendingAmount = ref(0)     // 待核销金额
const reconcileBills = ref([])            // [{ billNo, billTypeKey, billType, counterpartyName, arAmount/apAmount, receivedAmount/paidAmount, unsettled, settleAmount, sourceBill, dueDate, ... }]
const reconcileRow = ref(null)
const reconcileLoading = ref(false)
const reconcileSettleAmounts = ref({})    // key=billNo+billTypeKey → user-entered settlement amount

/** 本次核销合计（仅统计已勾选单据） */
const reconcileTotal = computed(() =>
  Object.entries(reconcileSettleAmounts.value).reduce((s, [k, v]) => {
    const bill = reconcileBills.value.find(b => b._key === k)
    return bill ? s + (Number(v) || 0) : s
  }, 0))

/** 勾选/取消勾选单据 */
function onSettleCheck(key) {
  if (reconcileSettleAmounts.value[key]) {
    // 取消勾选：删除该条
    delete reconcileSettleAmounts.value[key]
  } else {
    // 勾选：默认填入未结金额
    const bill = reconcileBills.value.find(b => b._key === key)
    reconcileSettleAmounts.value[key] = bill ? (Number(bill.unsettled) || 0) : 0
  }
}

async function openReconcileDialog(row) {
  const raw = row?._raw || {}
  reconcileRow.value = raw
  reconcileBills.value = []
  reconcileSettleAmounts.value = {}
  reconcilePendingAmount.value = 0
  showReconcileDialog.value = true
  reconcileLoading.value = true
  try {
    const res = await post('/finance/receipt/unsettled-bills', {
      counterpartyType: raw.counterpartyType || '',
      counterpartyCode: raw.counterpartyCode || '',
      counterpartyName: raw.counterpartyName || '',
      receiptId: raw.receiptId || '',
    })
    reconcilePendingAmount.value = Number(res?.pendingAmount) || 0
    const bills = (res?.bills || []).map(b => {
      const unsettled = Number(b.unsettled) || 0
      const key = (b.billNo || '') + (b.billTypeKey || '')
      // 默认不勾选，用户点击勾选后才填入默认结算金额
      return { ...b, _key: key, unsettled }
    })
    reconcileBills.value = bills
  } catch (e) { show('加载未结算单据失败：' + (e.message || '未知错误')) }
  finally { reconcileLoading.value = false }
}

/** 本次结算金额修改校验 */
function onSettleAmountChange(key, rawVal) {
  const v = parseFloat(rawVal) || 0
  const bill = reconcileBills.value.find(b => b._key === key)
  if (!bill) return
  const unsettled = bill.unsettled
  // 未结>0：本次结算不可≤0，不可>未结
  if (unsettled > 0) {
    if (v <= 0) reconcileSettleAmounts.value[key] = unsettled  // 恢复默认
    else reconcileSettleAmounts.value[key] = Math.min(v, unsettled)
  } else if (unsettled < 0) {
    // 未结<0（负向）：本次结算不可>0
    if (v > 0) reconcileSettleAmounts.value[key] = unsettled
    else reconcileSettleAmounts.value[key] = v
  } else {
    reconcileSettleAmounts.value[key] = 0
  }
}

async function confirmReconcile() {
  const bills = reconcileBills.value.filter(b => {
    const amt = Number(reconcileSettleAmounts.value[b._key]) || 0
    return amt !== 0
  })
  if (bills.length === 0) { show('请选择要核销的单据且本次结算金额不为0'); return }
  const total = bills.reduce((s, b) => s + (Number(reconcileSettleAmounts.value[b._key]) || 0), 0)
  if (reconcilePendingAmount.value > 0 && total > reconcilePendingAmount.value) {
    show(`本次核销合计 ${total.toFixed(2)} 不可大于待核销金额 ${reconcilePendingAmount.value.toFixed(2)}`); return
  }
  if (!confirm(`确认核销 ${bills.length} 笔单据，合计 ￥${total.toFixed(2)}？`)) return
  try {
    await post('/finance/receipt/reconcile', {
      receiptId: reconcileRow.value?.receiptId || '',
      bills: bills.map(b => ({
        billNo: b.billNo, billTypeKey: b.billTypeKey,
        settleAmount: reconcileSettleAmounts.value[b._key],
      })),
    })
    show('核销成功')
    showReconcileDialog.value = false
    await loadRows()
  } catch (e) { show('核销失败：' + (e.message || '未知错误')) }
}

// ===== 核销内创建费用单弹窗 =====
const showQuickExpenseDialog = ref(false)
const quickExpenseForm = ref({ expenseDate: '', handler: '', expenseType: '', amount: '', direction: 'OUT', remark: '' })
const quickExpenseSaving = ref(false)
const quickExpenseEmployees = ref([])
const quickExpenseTypes = ref([])

async function quickCreateExpense() {
  quickExpenseForm.value = {
    expenseDate: new Date().toISOString().slice(0, 10),
    handler: reconcileRow.value?.handler || '',
    expenseType: '', amount: '', direction: 'OUT', remark: '',
  }
  quickExpenseSaving.value = false
  // 加载下拉数据
  try {
    const emp = await post('/base/master/employee/page', { pageNo: 1, pageSize: 200, filters: {} })
    quickExpenseEmployees.value = (emp.records || []).filter(x => x.employeeName || x.name)
  } catch (_) { quickExpenseEmployees.value = [] }
  try {
    const et = await post('/base/master/expense-type/page', { pageNo: 1, pageSize: 500, filters: {} })
    const all = (et.records || []).filter(x => x.expenseTypeName || x.name)
    // 只保留末级
    const parentCodes = new Set(all.map(x => x.parentCode || x.parent_code).filter(Boolean))
    quickExpenseTypes.value = all.filter(x => !parentCodes.has(x.expenseTypeCode || x.code))
  } catch (_) { quickExpenseTypes.value = [] }
  showQuickExpenseDialog.value = true
}

async function confirmQuickExpense() {
  const f = quickExpenseForm.value
  if (!f.expenseDate) { show('请选择费用日期'); return }
  if (!f.handler) { show('请选择经手人'); return }
  if (!f.expenseType) { show('请选择费用类型'); return }
  const amt = Number(f.amount)
  if (!amt || amt <= 0) { show('请填写费用金额'); return }
  quickExpenseSaving.value = true
  try {
    const r = reconcileRow.value || {}
    // 1. 创建费用单
    const createRes = await post('/finance/expense/create', {
      expenseDate: f.expenseDate,
      direction: f.direction,
      counterpartyType: r.counterpartyType || 'CUSTOMER',
      counterpartyCode: r.counterpartyCode || '',
      counterpartyName: r.counterpartyName || '',
      handler: f.handler,
      fundAccount: '',
      remark: f.remark || '核销窗口快速创建',
      details: [{ expenseType: f.expenseType, amount: amt, remark: '' }],
    })
    // 2. 自动审核
    await post('/finance/expense/audit', { expenseId: createRes.expenseId })
    show('费用单已创建并审核，可刷新核销列表查看')
    showQuickExpenseDialog.value = false
    // 刷新核销列表
    reconcileLoading.value = true
    try {
      const res = await post('/finance/receipt/unsettled-bills', {
        counterpartyType: r.counterpartyType || '',
        counterpartyCode: r.counterpartyCode || '',
        counterpartyName: r.counterpartyName || '',
        receiptId: r.receiptId || '',
      })
      reconcilePendingAmount.value = Number(res?.pendingAmount) || 0
      reconcileBills.value = (res?.bills || []).map(b => {
        const key = (b.billNo || '') + (b.billTypeKey || '')
        return { ...b, _key: key, unsettled: Number(b.unsettled) || 0 }
      })
    } catch (_) { }
    reconcileLoading.value = false
  } catch (e) {
    show('创建费用单失败：' + (e.message || '未知错误'))
  } finally {
    quickExpenseSaving.value = false
  }
}

// 基础资料抽屉
const showBaseDrawer = ref(false)
const baseDrawerCode = ref('')
const baseDrawerMode = ref('add')
const baseEditData = ref(null)

function openBaseDrawer(mode, code, rowData = null) {
  baseDrawerMode.value = mode
  baseDrawerCode.value = code
  baseEditData.value = rowData
  showBaseDrawer.value = true
}
function closeBaseDrawer() {
  showBaseDrawer.value = false
}
async function onBaseSave(result) {
  showBaseDrawer.value = false
  show(`${moduleConfigs[baseDrawerCode.value]?.title || '资料'}保存成功`)
  await loadRows()
}

// 调价单抽屉
const showPriceAdjustDrawer = ref(false)
const priceAdjustMode = ref('add')
const priceAdjustData = ref(null)
function openPriceAdjustDrawer(mode, orderData = null) {
  priceAdjustMode.value = mode
  priceAdjustData.value = orderData
  showPriceAdjustDrawer.value = true
}
async function onPriceAdjustSaved() {
  showPriceAdjustDrawer.value = false
  show('调价单已保存')
  await loadRows()
}

// 批量编辑
const showBatchEditDrawer = ref(false)
const selectedRows = ref([])
const selectedRowKeys = ref(new Set())

/** 勾选中是否有待审核记录（全局约定：只有符合条件的记录才显示对应批量按钮） */
const hasPendingSelected = computed(() => {
  if (selectedRowKeys.value.size === 0) return false
  return [...selectedRowKeys.value].some(idx => {
    const r = tableRows.value[idx]?._raw
    const st = r?.status || r?.statusText || ''
    return st === 'PENDING' || st === '待审核'
  })
})

// 销售发货单：勾选里有「待签收」的单子才露出批量签收浮动栏
// （签收会自动审核，所以这里看的是 signStatus 而不是 status）
const hasUnsignedSelected = computed(() => {
  if (selectedRowKeys.value.size === 0) return false
  return [...selectedRowKeys.value].some(idx => {
    const r = tableRows.value[idx]?._raw
    if (!r || r.status === 'CANCELLED') return false
    return !r.signStatus || r.signStatus === '待签收'
  })
})

// 暴露给父组件：打开新增抽屉
function openAddDrawer() {
  drawerMode.value = 'add'
  editGoodsData.value = null
  showGoodsDrawer.value = true
}

// 暴露给父组件：打开编辑抽屉
function openEditDrawer(rowData) {
  drawerMode.value = 'edit'
  editGoodsData.value = rowData || null
  showGoodsDrawer.value = true
}

defineExpose({ openAddDrawer, openEditDrawer })

const { loadFieldScope, canViewField } = usePermission()
const feedback = ref('')
const dialog = ref(null)
const selectedRow = ref(null)
const detailData = ref(null)
const formModel = ref({})
const tableRows = ref([])
const loading = ref(false)
const selectedTreeNode = ref('全部')
const dynamicTree = ref([])   // 动态加载的树节点 [{ code, name, level, hasChildren }]
// columnSettings / visibleColumns / 字段设置相关：由 useColumnSettings composable 管理（见上方）
const pageNo = ref(1)
const pageSize = ref(100)
const total = ref(0)
const queryFilters = ref({})
const sortField = ref('')
const sortOrder = ref('')

// 往来单位类型（用于「单位类型」筛选下拉动态数据）
const counterpartyTypes = ref([])
async function loadCounterpartyTypesForFilter() {
  try {
    const data = await post('/base/master/counterparty-type/page', { pageNo: 1, pageSize: 500, filters: {} })
    counterpartyTypes.value = (data.records || []).map(r => ({
      value: r.typeCode || r.code,
      label: r.typeName || r.name || r.typeCode || r.code,
    }))
  } catch (e) {
    counterpartyTypes.value = []
  }
}

// 供应商模块：交货方式（来自字典）+ 采购员（来自人员）供筛选下拉
const deliveryMethodOptions = ref([])
async function loadDeliveryMethodsForFilter() {
  const list = await getDict('delivery_method')
  deliveryMethodOptions.value = list.map(d => ({ value: d.name || d.code, label: d.name || d.code }))
}
const buyerOptionsFilter = ref([])
async function loadBuyersForFilter() {
  try {
    const data = await post('/base/employee/buyers', {})
    const list = Array.isArray(data) ? data : (data?.records || [])
    // 兼容 H2 大写返回
    buyerOptionsFilter.value = list.map(b => {
      const code = b.code || b.CODE || b.employeeCode || ''
      const name = b.name || b.NAME || b.employeeName || ''
      return { value: name, label: code ? `${code}  ${name}` : name }
    }).filter(o => o.value)
  } catch (e) {
    buyerOptionsFilter.value = []
  }
}

// 客户模块筛选：渠道类型/片区/线路/业务员 动态下拉
const customerChannelFilter = ref([])
const customerTerritoryFilter = ref([])
const customerRouteLineFilter = ref([])
const customerSalesmanFilter = ref([])
async function loadCustomerFilters() {
  const [ch, terr, rl, sm] = await Promise.all([
    getDict('customer_channel'),
    post('/base/master/territory/page', { pageNo: 1, pageSize: 1000, filters: {} }).catch(() => ({ records: [] })),
    post('/base/master/route-line/page', { pageNo: 1, pageSize: 500, filters: {} }).catch(() => ({ records: [] })),
    post('/base/employee/salesmen', {}).catch(() => []),
  ])
  customerChannelFilter.value = ch.map(c => ({ value: c.name, label: c.name }))
  // 片区取末级
  const terrAll = (terr?.records || []).filter(r => (r.status || 'NORMAL') === 'NORMAL')
  const parentSet = new Set(terrAll.map(r => r.parentCode).filter(Boolean))
  customerTerritoryFilter.value = terrAll
    .filter(r => !parentSet.has(r.territoryCode))
    .map(r => ({ value: r.territoryName, label: r.territoryName }))
  customerRouteLineFilter.value = (rl?.records || [])
    .filter(r => (r.status || 'NORMAL') === 'NORMAL')
    .map(r => ({ value: r.routeLineName, label: r.routeLineName }))
  const smList = Array.isArray(sm) ? sm : (sm?.records || [])
  customerSalesmanFilter.value = smList.map(b => {
    const code = b.code || b.CODE || b.employeeCode || ''
    const name = b.name || b.NAME || b.employeeName || ''
    return { value: name, label: code ? `${code}  ${name}` : name }
  }).filter(o => o.value)
}

// 变价查询：价格组 / 品牌 下拉
const priceGroupOptions = ref([])
async function loadPriceGroupOptions() {
  try {
    const data = await post('/base/master/price-group/page', { pageNo: 1, pageSize: 500, filters: {} })
    priceGroupOptions.value = (data.records || []).map(r => ({
      value: r.priceGroupCode || r.code,
      label: r.priceGroupName || r.name || r.priceGroupCode,
    }))
  } catch (e) { priceGroupOptions.value = [] }
}
const brandOptions = ref([])
async function loadBrandOptions() {
  try {
    const data = await post('/base/brand/page', { pageNo: 1, pageSize: 500, filters: {} })
    brandOptions.value = (data.records || []).map(r => ({
      value: r.brandName || r.name || r.brandCode,
      label: r.brandName || r.name || r.brandCode,
    }))
  } catch (e) { brandOptions.value = [] }
}

// 飞单筛选：客户/供应商下拉
const flyCustomerOptions = ref([])
const flySupplierOptions = ref([])
const flyStatusOptions = [
  { value: 'DRAFT', label: '待审核' },
  { value: 'APPROVED', label: '已审核' },
  { value: 'CANCELLED', label: '已作废' },
]
async function loadFlyOrderFilters() {
  const [c, s] = await Promise.all([
    post('/base/customer/page', { pageNo: 1, pageSize: 500, filters: {} }).catch(() => ({ records: [] })),
    post('/base/supplier/page', { pageNo: 1, pageSize: 500, filters: {} }).catch(() => ({ records: [] })),
  ])
  flyCustomerOptions.value = (c.records || []).map(r => ({
    value: r.customerCode || r.customerId, label: r.customerName || r.customerCode,
  }))
  flySupplierOptions.value = (s.records || []).map(r => ({
    value: r.supplierCode || r.supplierId, label: r.supplierName || r.supplierCode,
  }))
}

// 人员筛选：所属部门下拉动态数据
const departmentOptions = ref([])
async function loadDepartmentOptionsForFilter() {
  try {
    const data = await post('/base/master/department/page', { pageNo: 1, pageSize: 500, filters: {} })
    departmentOptions.value = (data.records || []).map(r => ({
      value: r.departmentName || r.name || r.departmentCode || r.code,
      label: r.departmentName || r.name || r.departmentCode || r.code,
    }))
  } catch (e) {
    departmentOptions.value = []
  }
}

const YESNO_OPTS = [
  { value: 'true', label: '是' },
  { value: 'false', label: '否' },
]

// 销售退货单筛选：退货方式 / 流转状态。
// 后端 PageResult 是「整行文本模糊包含」过滤，所以 value 必须用行里真实出现的中文，
// 不能用 DRIVER / WAREHOUSE 这种枚举码（仓库名等字段可能误命中）。
const RETURN_TYPE_OPTS = [
  { value: '司机回收', label: '司机回收' },
  { value: '自提到仓', label: '自提到仓' },
]
const RETURN_LOGISTICS_OPTS = [
  { value: '未安排', label: '未安排（待安排调度/待推送仓库）' },
  { value: '已安排调度', label: '已安排调度' },
  { value: '已调度', label: '已调度' },
  { value: '司机已回收', label: '司机已回收' },
  { value: '已推送仓库', label: '已推送仓库' },
  { value: '已入库', label: '已入库' },
]

// 动态构造 filters：为 counterparty / employee 等模块的下拉注入实际选项
const priceGroupItemViewMode = ref('tree') // 'tree' | 'flat'
// 树是否可见：默认跟随 config.tree；priceGroupItem 在 flat 模式下隐藏
const treeVisible = computed(() => {
  if (!config.value.tree) return false
  if (moduleCode.value === 'priceGroupItem' && priceGroupItemViewMode.value === 'flat') return false
  return true
})
const dynamicFilters = computed(() => {
  const base = config.value.filters || []
  if (moduleCode.value === 'counterparty') {
    return base.map(f => f === '单位类型' ? { label: '单位类型', options: counterpartyTypes.value } : f)
  }
  if (moduleCode.value === 'employee') {
    return base.map(f => {
      if (f === '是否业务员' || f === '是否采购员' || f === '是否库管员' || f === '是否配送员') {
        return { label: f, options: YESNO_OPTS }
      }
      return f
    })
  }
  if (moduleCode.value === 'priceChangeLog') {
    return base.map(f => {
      if (f === '变价时间') return { label: '变价时间', type: 'dateRange', keyFrom: 'dateFrom', keyTo: 'dateTo' }
      if (f === '价格组') return { label: '价格组', options: priceGroupOptions.value }
      if (f === '品牌') return { label: '品牌', options: brandOptions.value }
      return f
    })
  }
  if (moduleCode.value === 'priceGroupItem') {
    // flat 模式加价格组下拉；tree 模式左树代替
    if (priceGroupItemViewMode.value === 'flat') {
      return [{ label: '价格组', options: priceGroupOptions.value }, ...base]
    }
    return base
  }
  if (moduleCode.value === 'supplier') {
    return base.map(f => {
      if (f === '默认采购员') return { label: '默认采购员', options: buyerOptionsFilter.value }
      if (f === '交货方式') return { label: '交货方式', options: deliveryMethodOptions.value }
      return f
    })
  }
  if (moduleCode.value === 'customer') {
    return base.map(f => {
      if (f === '渠道类型') return { label: '渠道类型', options: customerChannelFilter.value }
      if (f === '片区') return { label: '片区', options: customerTerritoryFilter.value }
      if (f === '线路') return { label: '线路', options: customerRouteLineFilter.value }
      if (f === '业务员') return { label: '业务员', options: customerSalesmanFilter.value }
      return f
    })
  }
  if (moduleCode.value === 'flyOrder') {
    return base.map(f => {
      if (f === '日期') return { label: '日期', type: 'dateRange', keyFrom: 'dateFrom', keyTo: 'dateTo' }
      if (f === '客户') return { label: '客户', options: flyCustomerOptions.value }
      if (f === '供应商') return { label: '供应商', options: flySupplierOptions.value }
      if (f === '状态') return { label: '状态', options: flyStatusOptions }
      return f
    })
  }
  if (moduleCode.value === 'salesReturn') {
    // 退货方式 / 流转状态用下拉，避免手输错字匹配不到（后端是整行文本模糊匹配）
    return base.map(f => {
      if (f === '退货方式') return { label: '退货方式', options: RETURN_TYPE_OPTS }
      if (f === '流转状态') return { label: '流转状态', options: RETURN_LOGISTICS_OPTS }
      return f
    })
  }
  return base
})

function threeMonthsAgoStr() {
  const d = new Date()
  d.setMonth(d.getMonth() - 3)
  return d.toISOString().slice(0, 10)
}
function oneMonthAgoStr() {
  const d = new Date()
  d.setMonth(d.getMonth() - 1)
  return d.toISOString().slice(0, 10)
}
function todayStr() { return new Date().toISOString().slice(0, 10) }

// 部分模块默认筛选值（例如 counterparty 默认只看正常）
const filterDefaults = computed(() => {
  if (moduleCode.value === 'counterparty') return { '状态': '正常' }
  if (moduleCode.value === 'employee') return { '状态': '正常' }
  if (moduleCode.value === 'priceChangeLog') return { dateFrom: threeMonthsAgoStr(), dateTo: todayStr() }
  if (moduleCode.value === 'flyOrder') return { dateFrom: oneMonthAgoStr(), dateTo: todayStr(), status: 'DRAFT' }
  return {}
})

const columns = computed(() => (config.value.columns || []).map((title, index) => ({
  key: `c${index}`,
  title,
  num: /金额|数量|库存|单价|成本|余额|已收|未收|已付|未付|原价|现价|进价|税额|毛利|额度/.test(title),
  action: /操作/.test(title),
})))
// 加上「字段级权限」过滤：不能看的字段直接从可选列里排除
const permittedColumns = computed(() =>
    columns.value.filter(col => col.action || canViewField(moduleCode.value, col.title))
)
const {
  columnSettings, pendingSettings, visibleColumns, dialogColumnList,
  dialogOpen: fieldDialogOpen, draggingKey,
  openDialog: openFieldDialog, closeDialog: closeFieldDialog,
  saveSettings: saveColumnSettings, resetSettings: resetColumnSettings,
  moveInDialog,
  onHeaderDragStart, onHeaderDragOver, onHeaderDrop,
  startResize, cellStyle,
} = useColumnSettings({
  storageKey: () => `erp-field-setting-v2:module:${moduleCode.value}`,
  allColumns: permittedColumns,
  // 勾选列（goods / priceGroupItem）占 40px sticky，固定列 stickyLeft 需从 40 起点
  leftBaseOffset: () => (moduleCode.value === 'goods' || moduleCode.value === 'priceGroupItem' || moduleCode.value === 'receiptPayment' || moduleCode.value === 'paymentModule' || moduleCode.value === 'financeExpense' || moduleCode.value === 'flyOrder' || moduleCode.value === 'ar') ? 40 : 0,
})

// ============ 通用导入弹窗（所有模块共享） ============
const importMenuOpen = ref(false)
const importDialog = ref({ visible: false, presetKey: '', title: '', templateHeaders: [], templateName: '', fieldMap: {}, requiredKey: '' })

function toggleImportMenu() {
  importMenuOpen.value = !importMenuOpen.value
}

// 打开导入弹窗；presetKey 缺省时按模块默认
function openImportDialog(presetKey) {
  importMenuOpen.value = false
  const key = presetKey || MODULE_DEFAULT_IMPORT[moduleCode.value]
  if (!key) { show('该模块暂不支持导入'); return }
  const preset = IMPORT_PRESETS[key]
  if (!preset) { show(`导入配置缺失：${key}`); return }
  importDialog.value = {
    visible: true,
    presetKey: key,
    title: preset.title,
    templateHeaders: preset.templateHeaders,
    templateName: preset.templateName,
    fieldMap: preset.fieldMap,
    requiredKey: preset.requiredKey,
  }
}
function closeImportDialog() {
  importDialog.value = { ...importDialog.value, visible: false }
}
async function handleImport(rows) {
  const key = importDialog.value.presetKey
  const preset = IMPORT_PRESETS[key]
  if (!preset) return
  // filter 模式：不发后端 upsert，直接把商品编号列表填进 queryFilters
  if (preset.mode === 'filter') {
    const seen = new Set()
    const codes = []
    rows.forEach(r => {
      const c = String(r.goodsCode ?? '').trim()
      if (c && !seen.has(c)) { seen.add(c); codes.push(c) }
    })
    if (codes.length === 0) { show('未识别到商品编号'); return }
    queryFilters.value = { ...queryFilters.value, goodsCodeList: codes.join(',') }
    pageNo.value = 1
    closeImportDialog()
    await loadRows()
    show(`已按 ${codes.length} 条商品编号筛选`)
    return
  }
  try {
    const extra = preset.extra ? preset.extra(moduleCode.value) : {}
    const res = await post(preset.endpoint, { ...extra, rows })
    const inserted = res?.inserted ?? 0
    const skipped = res?.skipped ?? 0
    show(`导入完成：新增 ${inserted} 条${skipped ? `，跳过 ${skipped} 条` : ''}`)
    closeImportDialog()
    if (preset.afterImport !== 'none') await loadRows()
  } catch (e) {
    show(`导入失败：${e.message || '未知错误'}`)
  }
}

const actionColumnIndex = computed(() => (config.value.columns || []).findIndex(title => /操作/.test(title)))
const statusColumnIndex = computed(() => (config.value.columns || []).findIndex(title => /状态|核销状态|应付生成状态|应收生成|开票状态|勾稽状态/.test(title)))

function buildRow(values = config.value.row || []) {
  return Object.fromEntries((values || []).map((value, index) => [`c${index}`, value]))
}

async function loadRows() {
  const api = moduleApis[moduleCode.value]
  if (!api?.page) {
    tableRows.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    // 树形+客户端过滤的主档（人员按部门树本级+子孙过滤）走大分页，一次性拿全部
    const usingClientTreeFilter = moduleCode.value === 'employee'
    const reqPageSize = usingClientTreeFilter ? 1000 : pageSize.value
    const reqPageNo = usingClientTreeFilter ? 1 : pageNo.value
    const data = await post(api.page, { pageNo: reqPageNo, pageSize: reqPageSize, sortField: sortField.value, sortOrder: sortOrder.value, filters: { ...queryFilters.value, roleCode: roleCode } })
    let records = data.records || []

    // 价格组商品查询：派生 unitLevelText / statusText
    if (moduleCode.value === 'priceGroupItem' && records.length) {
      const map = { 1: '小单位', 2: '中单位', 3: '大单位' }
      records = records.map(r => ({ ...r, unitLevelText: map[Number(r.unitLevel)] || String(r.unitLevel ?? '') }))
    }
    // 变价日志：派生 unitLevelText / oldPriceText / changeRateText / changeTypeText
    if (moduleCode.value === 'priceChangeLog' && records.length) {
      const unitMap = { 1: '小单位', 2: '中单位', 3: '大单位' }
      const typeMap = { ADJUST_INIT: '首次设价', ADJUST_UPDATE: '调价单变更' }
      records = records.map(r => {
        const oldV = r.oldPrice == null ? '-' : `¥${Number(r.oldPrice).toFixed(2)}`
        let rate = '首次设价'
        if (r.oldPrice != null && Number(r.oldPrice) !== 0) {
          const pct = (Number(r.newPrice) - Number(r.oldPrice)) / Number(r.oldPrice) * 100
          const sign = pct > 0 ? '+' : ''
          rate = `${sign}${pct.toFixed(2)}%`
        }
        return {
          ...r,
          unitLevelText: unitMap[Number(r.unitLevel)] || String(r.unitLevel ?? ''),
          oldPriceText: oldV,
          changeRateText: rate,
          changeTypeText: typeMap[r.changeType] || r.changeType,
        }
      })
    }
    // 调价单：派生 statusText
    if (moduleCode.value === 'priceAdjustOrder' && records.length) {
      const statusMap = { DRAFT: '草稿', PENDING: '待审核', APPROVED: '已审核', REJECTED: '已驳回' }
      records = records.map(r => ({ ...r, statusText: statusMap[r.status] || r.status }))
    }
    // 分类模块：本页记录里补齐 parentName（后端可能未升级；用当前页数据+全量拉一次建 code→name 索引兜底）
    if (moduleCode.value === 'category' && records.length) {
      const codeMap = {}
      records.forEach(r => { if (r.categoryCode) codeMap[r.categoryCode] = r.categoryName })
      const missing = records.some(r => r.parentCode && !r.parentName && !codeMap[r.parentCode])
      if (missing) {
        try {
          const all = await post('/base/category/page', { pageNo: 1, pageSize: 1000, filters: {} })
          ;(all.records || []).forEach(r => { if (r.categoryCode) codeMap[r.categoryCode] = r.categoryName })
        } catch (e) { /* 忽略：兜底失败仍可显示 parentCode */ }
      }
      records = records.map(r => (r.parentName || !r.parentCode) ? r : { ...r, parentName: codeMap[r.parentCode] || r.parentCode })
    }
    // 部门模块：同样补齐上级部门名称
    if (moduleCode.value === 'department' && records.length) {
      const codeMap = {}
      records.forEach(r => { if (r.departmentCode) codeMap[r.departmentCode] = r.departmentName })
      const missing = records.some(r => r.parentCode && !r.parentName && !codeMap[r.parentCode])
      if (missing) {
        try {
          const all = await post('/base/master/department/page', { pageNo: 1, pageSize: 1000, filters: {} })
          ;(all.records || []).forEach(r => { if (r.departmentCode) codeMap[r.departmentCode] = r.departmentName })
        } catch (e) { /* 忽略 */ }
      }
      records = records.map(r => (r.parentName || !r.parentCode) ? r : { ...r, parentName: codeMap[r.parentCode] || r.parentCode })
    }
    // 资金账户：补齐上级账户名称
    if (moduleCode.value === 'fundAccount' && records.length) {
      const codeMap = {}
      records.forEach(r => { if (r.fundAccountCode) codeMap[r.fundAccountCode] = r.fundAccountName })
      const missing = records.some(r => r.parentCode && !r.parentName && !codeMap[r.parentCode])
      if (missing) {
        try {
          const all = await post('/base/master/fund-account/page', { pageNo: 1, pageSize: 1000, filters: {} })
          ;(all.records || []).forEach(r => { if (r.fundAccountCode) codeMap[r.fundAccountCode] = r.fundAccountName })
        } catch (e) { /* 忽略 */ }
      }
      records = records.map(r => (r.parentName || !r.parentCode) ? r : { ...r, parentName: codeMap[r.parentCode] || r.parentCode })
    }
    // 费用类型：补齐上级费用类型名称
    if (moduleCode.value === 'expenseType' && records.length) {
      const codeMap = {}
      records.forEach(r => { if (r.expenseTypeCode) codeMap[r.expenseTypeCode] = r.expenseTypeName })
      const missing = records.some(r => r.parentCode && !r.parentName && !codeMap[r.parentCode])
      if (missing) {
        try {
          const all = await post('/base/master/expense-type/page', { pageNo: 1, pageSize: 1000, filters: {} })
          ;(all.records || []).forEach(r => { if (r.expenseTypeCode) codeMap[r.expenseTypeCode] = r.expenseTypeName })
        } catch (e) { /* 忽略 */ }
      }
      records = records.map(r => (r.parentName || !r.parentCode) ? r : { ...r, parentName: codeMap[r.parentCode] || r.parentCode })
    }
    // 片区：补齐上级片区名称
    if (moduleCode.value === 'territory' && records.length) {
      const codeMap = {}
      records.forEach(r => { if (r.territoryCode) codeMap[r.territoryCode] = r.territoryName })
      const missing = records.some(r => r.parentCode && !r.parentName && !codeMap[r.parentCode])
      if (missing) {
        try {
          const all = await post('/base/master/territory/page', { pageNo: 1, pageSize: 1000, filters: {} })
          ;(all.records || []).forEach(r => { if (r.territoryCode) codeMap[r.territoryCode] = r.territoryName })
        } catch (e) { /* 忽略 */ }
      }
      records = records.map(r => (r.parentName || !r.parentCode) ? r : { ...r, parentName: codeMap[r.parentCode] || r.parentCode })
    }
    // 人员：加载完整部门树 + 客户端按选中部门（本级+子孙）过滤
    if (moduleCode.value === 'employee') {
      await ensureDepartmentTree()
      const deptSet = deptNameSetForFilter()
      if (deptSet) {
        records = records.filter(r => deptSet.has(r.department || ''))
      }
    }
    // 商品库存查询 / 批次库存查询：派生大单位换算件数 + 可用库存金额
    if ((moduleCode.value === 'stockBalance' || moduleCode.value === 'batchStock') && records.length) {
      records = records.map(r => {
        // 解析 unit_config JSON，提取大单位换算率
        let largeConvertQty = 1
        try {
          const unitConfig = typeof r.unitConfig === 'string' ? JSON.parse(r.unitConfig) : r.unitConfig
          if (Array.isArray(unitConfig)) {
            const largeUnit = unitConfig.find(u => u.unitType === '大单位' && u.enabled !== false)
            if (largeUnit && Number(largeUnit.convertQty) > 0) {
              largeConvertQty = Number(largeUnit.convertQty)
            }
          }
        } catch (e) { /* 无单位配置时默认 1 */ }

        const physicalQty = Number(r.physicalQty) || 0
        const lockedQty = Number(r.lockedQty) || 0
        const frozenQty = Number(r.frozenQty) || 0
        const availableQty = Number(r.availableQty) || 0
        const costPrice = Number(r.costPrice) || 0

        return {
          ...r,
          // 「件数」派生：有大单位换算率则按大单位显示；否则按小单位数量显示
          physicalQtyPieces: largeConvertQty > 1 ? (physicalQty / largeConvertQty).toFixed(2) : String(physicalQty),
          lockedQtyPieces: largeConvertQty > 1 ? (lockedQty / largeConvertQty).toFixed(2) : String(lockedQty),
          frozenQtyPieces: largeConvertQty > 1 ? (frozenQty / largeConvertQty).toFixed(2) : String(frozenQty),
          availableQtyPieces: largeConvertQty > 1 ? (availableQty / largeConvertQty).toFixed(2) : String(availableQty),
          availableStockAmount: (availableQty * costPrice).toFixed(2),
        }
      })
    }
    tableRows.value = records.length ? records.map(record => mapRecordToRow(record, config.value)) : []
    total.value = data.total || 0
    // 树形模块同步刷新树
    if (moduleCode.value === 'category') {
      buildTreeFor('category', records)
    } else if (moduleCode.value === 'department') {
      buildTreeFor('department', records)
    } else if (moduleCode.value === 'fundAccount') {
      buildTreeFor('fundAccount', records)
    } else if (moduleCode.value === 'expenseType') {
      buildTreeFor('expenseType', records)
    } else if (moduleCode.value === 'territory') {
      buildTreeFor('territory', records)
    } else if (moduleCode.value === 'counterparty') {
      buildCounterpartyTree()
    } else if (moduleCode.value === 'priceGroupItem') {
      buildPriceGroupTree()
    }
  } catch (error) {
    tableRows.value = []
    total.value = 0
    show(`${config.value.title}加载失败：${error.message}`)
  } finally {
    loading.value = false
  }
}

// 从扁平列表构建树形展示（父->子）—— 支持 category、department 等自引用主档
const TREE_MODULE_KEYS = {
  category: { codeKey: 'categoryCode', nameKey: 'categoryName', parentKey: 'parentCode', rootLabel: '全部分类' },
  department: { codeKey: 'departmentCode', nameKey: 'departmentName', parentKey: 'parentCode', rootLabel: '全部部门' },
  fundAccount: { codeKey: 'fundAccountCode', nameKey: 'fundAccountName', parentKey: 'parentCode', rootLabel: '全部资金账户' },
  expenseType: { codeKey: 'expenseTypeCode', nameKey: 'expenseTypeName', parentKey: 'parentCode', rootLabel: '全部费用类型' },
  territory: { codeKey: 'territoryCode', nameKey: 'territoryName', parentKey: 'parentCode', rootLabel: '全部片区' },
}

// ============ 人员 × 部门树：部门树数据 + 选中部门（本级+子孙）过滤 ============
const departmentTreeData = ref([])  // 部门原始记录
async function ensureDepartmentTree() {
  if (departmentTreeData.value.length) return
  try {
    const data = await post('/base/master/department/page', { pageNo: 1, pageSize: 1000, filters: {} })
    departmentTreeData.value = data.records || []
    // 用部门数据构建 dynamicTree（供 employee 侧边栏）
    if (moduleCode.value === 'employee') {
      buildTreeFor('department', departmentTreeData.value)
    }
  } catch (e) {
    departmentTreeData.value = []
  }
}
// 计算「选中部门 + 所有子孙」对应的部门名 Set；空表示不过滤（全部）
function deptNameSetForFilter() {
  if (moduleCode.value !== 'employee') return null
  const code = selectedTreeNode.value
  if (!code) return null
  const byCode = {}
  departmentTreeData.value.forEach(r => { byCode[r.departmentCode] = r })
  const children = {}
  departmentTreeData.value.forEach(r => {
    const p = r.parentCode || ''
    if (!p) return
    ;(children[p] = children[p] || []).push(r.departmentCode)
  })
  const collected = new Set()
  const walk = (c) => {
    if (!c || collected.has(c)) return
    collected.add(c)
    ;(children[c] || []).forEach(walk)
  }
  walk(code)
  // 记录里 department 字段存的是「名字」→ 转成名字集合
  return new Set([...collected].map(c => byCode[c]?.departmentName).filter(Boolean))
}

function buildTreeFor(module, records) {
  const cfg = TREE_MODULE_KEYS[module]
  if (!cfg) return
  if (!records || records.length === 0) {
    dynamicTree.value = []
    return
  }
  const { codeKey, nameKey, parentKey, rootLabel } = cfg
  const map = {}
  records.forEach(r => {
    if (r[codeKey]) {
      map[r[codeKey]] = { code: r[codeKey], name: r[nameKey], parentCode: r[parentKey] || '', children: [] }
    }
  })
  const roots = []
  Object.values(map).forEach(node => {
    if (node.parentCode && map[node.parentCode]) {
      map[node.parentCode].children.push(node)
    } else {
      roots.push(node)
    }
  })
  const sortRec = (list) => {
    list.sort((a, b) => (a.code || '').localeCompare(b.code || ''))
    list.forEach(n => sortRec(n.children))
  }
  sortRec(roots)
  const flat = [{ code: '', name: rootLabel, level: 0 }]
  const walk = (nodes, level) => {
    nodes.forEach(n => {
      flat.push({ code: n.code, name: n.name, level })
      if (n.children.length) walk(n.children, level + 1)
    })
  }
  walk(roots, 1)
  dynamicTree.value = flat
}

// 兼容旧调用：category 单独入口
function buildCategoryTree(records) {
  buildTreeFor('category', records)
}

// 往来单位树：类型→单位 两级结构（从 /base/master/counterparty/tree 加载）
async function buildCounterpartyTree() {
  try {
    const treeData = await post(moduleApis.counterparty.tree, {})
    const flat = [{ code: '', name: '全部往来单位', level: 0 }]
    ;(treeData || []).forEach(typeNode => {
      flat.push({ code: typeNode.code, name: typeNode.name, level: 1 })
      ;(typeNode.children || []).forEach(party => {
        flat.push({ code: party.counterpartyCode || party.code, name: party.counterpartyName || party.name, level: 2 })
      })
    })
    dynamicTree.value = flat
  } catch (e) {
    dynamicTree.value = []
  }
}

// 价格组商品查询：左侧树 = 全部已启用价格组
async function buildPriceGroupTree() {
  try {
    const data = await post(moduleApis.priceGroup.page, { pageNo: 1, pageSize: 200, filters: {} })
    const flat = [{ code: '', name: '全部价格组', level: 0 }]
    ;(data.records || []).filter(r => r.enabled === true || r.enabled === 1).forEach(r => {
      const label = `${r.priceGroupName || r.priceGroupCode}${r.goodsCount ? ` (${r.goodsCount})` : ''}`
      flat.push({ code: r.priceGroupCode, name: label, level: 1 })
    })
    dynamicTree.value = flat
  } catch (e) {
    dynamicTree.value = []
  }
}

function resetRows() {
  loadRows()
}

// 老的 loadColumnSettings / saveColumnSettings / resetColumnSettings
// 已迁移到 useColumnSettings composable（见文件顶部）。此处保留空注释占位便于 git diff 定位。

watch(() => [config.value, roleCode], () => {
  // columnSettings 由 useColumnSettings composable 通过 storageKey watcher 自动加载
  loadFieldScope(moduleCode.value, roleCode)
  // 特定模块的筛选下拉数据
  if (moduleCode.value === 'counterparty') loadCounterpartyTypesForFilter()
  if (moduleCode.value === 'employee') ensureDepartmentTree()
  if (moduleCode.value === 'priceChangeLog') { loadPriceGroupOptions(); loadBrandOptions() }
  if (moduleCode.value === 'supplier') { loadDeliveryMethodsForFilter(); loadBuyersForFilter() }
  if (moduleCode.value === 'customer') { loadCustomerFilters() }
  if (moduleCode.value === 'flyOrder') { loadFlyOrderFilters(); queryFilters.value = { dateFrom: oneMonthAgoStr(), dateTo: todayStr(), status: 'DRAFT' } }
  resetRows()
}, { immediate: true })

// 抽屉保存后：全局 refreshSignal 递增 → 刷新当前页
watch(() => app.refreshSignal, () => {
  if (moduleCode.value) loadRows()
})

const formFields = computed(() => {
  if (config.value.formFields?.length) return config.value.formFields
  const ignored = /操作|商品数|当前库存|库存金额|应收余额|应付余额|逾期金额|已入库|已收|未收|已付|未付|创建|审核|状态|付款状态|到货状态|签收状态|开票状态|勾稽状态|核销状态/
  const fromColumns = (config.value.columns || []).filter(title => !ignored.test(title)).slice(0, 12)
  return fromColumns.length ? fromColumns : (config.value.sections || ['基础信息'])
})

const detailColumns = computed(() => {
  if (config.value.detailColumns?.length) return config.value.detailColumns
  if (config.value.mode === 'bill' || config.value.type !== 'base') {
    return (config.value.columns || []).filter(title => !/操作|状态|创建|审核/.test(title)).slice(0, 10)
  }
  return []
})

function show(message) {
  feedback.value = message
  setTimeout(() => (feedback.value = ''), 2000)
}

// 商品保存后处理：从数据库重新加载列表，保证显示真实持久化的数据
async function handleGoodsSave(goodsData) {
  console.log('handleGoodsSave:', goodsData)
  show('商品保存成功')
  showGoodsDrawer.value = false
  await loadRows()
}

// 将商品对象转换为列表行格式
function buildGoodsRow(data) {
  const u = data.units?.[0] || {}
  const saleFlag = data.canSale ? '是' : '否'
  const purchaseFlag = data.canPurchase ? '是' : '否'
  const returnFlag = data.canReturn ? '是' : '否'
  return {
    c0: '-',
    c1: data.goodsCode || `GD${String(tableRows.value.length + 1).padStart(3, '0')}`,
    c2: data.goodsName || '',
    c3: data.goodsType || '正常商品',
    c4: data.spec || '',
    c5: data.categoryName || '',
    c6: data.brandName || '',
    c7: u.unitName || '瓶',
    c8: u.barcode || '',
    c9: data.shelfLifeDays ? `${data.shelfLifeDays}天` : '',
    c10: data.storageProperty || '常温',
    c11: data.standardPrice || '0.00',
    c12: data.suggestedRetailPrice || '0.00',
    c13: data.referencePurchasePrice || '0.00',
    c14: data.minSalePrice || '0.00',
    c15: data.stockUpperLimit || '0',
    c16: data.stockLowerLimit || '0',
    c17: data.defaultSupplier || '',
    c18: data.defaultWarehouse || '总仓',
    c19: `${saleFlag}/${purchaseFlag}/${returnFlag}`,
    c20: '0',
    c21: data.status || '正常',
    c22: '编辑',
  }
}

// ========== 批量编辑相关 ==========
// 打开批量编辑弹窗
function openBatchEdit() {
  if (selectedRowKeys.value.size === 0) {
    show('请先选择商品')
    return
  }
  selectedRows.value = tableRows.value.filter((row, index) => selectedRowKeys.value.has(index))
  showBatchEditDrawer.value = true
}

// 切换单行勾选
function toggleRowSelection(index, checked) {
  if (checked) selectedRowKeys.value.add(index)
  else selectedRowKeys.value.delete(index)
}

// 全选/取消全选
function toggleSelectAll(checked) {
  if (checked) tableRows.value.forEach((_, index) => selectedRowKeys.value.add(index))
  else selectedRowKeys.value.clear()
}

function clearSelection() {
  selectedRowKeys.value.clear()
}

// 批量保存
function handleBatchSave(updateData) {
  selectedRows.value.forEach(row => Object.assign(row, updateData))
  show(`批量更新完成，共更新 ${selectedRows.value.length} 个商品`)
  showBatchEditDrawer.value = false
  selectedRowKeys.value.clear()
}

function openDialog(type, title, message, row = null) {
  selectedRow.value = row
  formModel.value = type === 'form' ? initFormModel(row) : {}
  dialog.value = { type, title, message }
}

function closeDialog() {
  dialog.value = null
  selectedRow.value = null
  detailData.value = null
  formModel.value = {}
}

function initFormModel(row) {
  return Object.fromEntries(formFields.value.map(field => [field, valueByTitle(field, row)]))
}

function valueByTitle(title, row = selectedRow.value) {
  const index = columns.value.findIndex(col => col.title === title)
  return index >= 0 ? row?.[`c${index}`] || '' : ''
}

async function saveForm() {
  const api = moduleApis[moduleCode.value]
  const endpoint = dialog.value?.title?.includes('编辑') && api?.update ? api.update : api?.save
  if (endpoint) {
    try {
      await post(endpoint, buildPayload())
      await loadRows()
      show(`${dialog.value.title}保存成功`)
      closeDialog()
      return
    } catch (error) {
      show(`${dialog.value.title}保存失败：${error.message}`)
      return
    }
  }
  show('该模块暂不支持保存')
  closeDialog()
}

async function confirmAction() {
  const action = dialog.value?.title || '操作'
  const api = moduleApis[moduleCode.value]
  const endpoint = resolveEndpoint(api, action)
  if (endpoint) {
    try {
      await post(endpoint, buildPayload())
      await loadRows()
      show(`${config.value.title}${action}成功`)
      closeDialog()
      return
    } catch (error) {
      show(`${action}失败：${error.message}`)
      return
    }
  }
  // 无后端接口时前端状态变更
  if (selectedRow.value && statusColumnIndex.value >= 0) {
    if (/反审核/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = '待审核'
    else if (/审核|确认/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = '已审核'
    if (/停用|作废|终止/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = action.replace('确认', '')
    if (/冻结/.test(action) && !/解冻/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = '冻结'
    if (/解冻/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = '正常'
    if (/关闭/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = '已关闭'
    if (/删除/.test(action)) tableRows.value = tableRows.value.filter(row => row !== selectedRow.value)
    if (/核销/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = '已核销'
  }
  show(`${config.value.title}${action}成功`)
  closeDialog()
}

function resolveEndpoint(api, action) {
  if (/确认退货/.test(action)) return api?.confirm
  // 撤销推送要排在推送前面判断，否则「撤销推送」会先命中 /推送/
  if (/撤销推送/.test(action)) return api?.cancelPush
  if (/推送仓库/.test(action)) return api?.pushWarehouse
  if (/驳回/.test(action)) return api?.reject
  if (/反审核/.test(action)) return api?.reverseAudit
  if (/关闭|终止/.test(action)) return api?.close
  if (/删除/.test(action)) return api?.delete
  if (/解冻/.test(action)) return api?.unfreeze
  if (/冻结/.test(action)) return api?.freeze
  if (/核销/.test(action)) return api?.reconcile
  if (/停用/.test(action)) return api?.stop
  if (/作废/.test(action)) return api?.cancel
  return api?.audit
}

async function openDetail(action, row) {
  const api = moduleApis[moduleCode.value]
  detailData.value = null
  if (api?.detail && row?.c0) {
    try {
      // detail 端点统一支持 ?id=（既可传 orderId 也可传单号）；旧调用兼容 ?orderId=
      const separator = api.detail.includes('?') ? '&' : '?'
      // row._raw 里有真实驼峰 key（orderId / receiptId / inboundId / outboundId），优先使用
      const raw = row._raw || {}
      const trueId = raw.orderId || raw.receiptId || raw.inboundId || raw.outboundId
        || raw.applyId || raw.returnId || row.c0
      detailData.value = await get(`${api.detail}${separator}id=${encodeURIComponent(trueId)}`)
    } catch (error) {
      show(`${config.value.title}详情接口暂不可用`)
    }
  }
  if (detailData.value) {
    // 业务单据用新的抽屉样式（上头部 + 下明细网格）
    billDetailDrawer.value = {
      visible: true,
      title: `${config.value.title}详情`,
      data: detailData.value,
    }
  } else {
    // 兜底：老弹窗
    openDialog('view', action, `${config.value.title}详情`, row)
  }
}

// 单据详情抽屉状态
const billDetailDrawer = ref({ visible: false, title: '', data: null })
function closeBillDetail() {
  billDetailDrawer.value = { ...billDetailDrawer.value, visible: false }
}

const importFile = ref(null)

async function uploadImport() {
  const excelApi = excelModules[moduleCode.value]
  if (excelApi?.import && importFile.value) {
    try {
      const formData = new FormData()
      formData.append('file', importFile.value)
      formData.append('taskName', `${config.value.title}导入任务`)
      const result = await upload(excelApi.import, formData)
      show(result?.message || `导入完成：成功${result?.successRows ?? 0}行，失败${result?.failedRows ?? 0}行`)
      if (moduleCode.value === 'importList') await loadRows()
      loadRows()
    } catch (error) {
      show(`${config.value.title}导入失败：${error.message}`)
    }
  } else {
    const api = moduleApis[moduleCode.value]
    if (api?.import) {
      try {
        const result = await post(api.import, { ...buildPayload(), taskName: `${config.value.title}导入任务`, fileName: `${config.value.title}导入模板.xlsx` })
        show(result?.message || `导入完成：成功${result?.successRows ?? 0}行，失败${result?.failedRows ?? 0}行`)
        if (moduleCode.value === 'importList') await loadRows()
      } catch (error) {
        show(`${config.value.title}导入接口暂不可用`)
      }
    } else {
      show('导入校验通过')
    }
  }
  importFile.value = null
  closeDialog()
}

async function downloadTemplate() {
  const excelApi = excelModules[moduleCode.value]
  if (excelApi?.template) {
    try {
      const blob = await getBlob(excelApi.template)
      saveBlobFile(`${config.value.title}_导入模板.xlsx`, blob)
      show('模板下载成功')
    } catch (error) {
      show(`模板下载失败：${error.message}`)
    }
  } else {
    show('该模块暂无导入模板')
  }
}

// 前端 xlsx 导出：拉全量记录，用当前 columns 作表头
// 价格组变价查询：导入商品编码，用于按码批量筛选
// 价格组商品查询：视图切换
function togglePriceGroupItemView() {
  priceGroupItemViewMode.value = priceGroupItemViewMode.value === 'tree' ? 'flat' : 'tree'
  queryFilters.value = { ...queryFilters.value }
  if (priceGroupItemViewMode.value === 'flat') {
    selectedTreeNode.value = ''
    if (queryFilters.value.priceGroupCode) delete queryFilters.value.priceGroupCode
  }
  pageNo.value = 1
  loadRows()
}

async function exportCurrentModuleXlsx() {
  const api = moduleApis[moduleCode.value]
  if (!api?.page) { show(`${config.value.title}暂不支持导出`); return }
  // 拉一次 10000 条（PRD F4 上限）
  const data = await post(api.page, { pageNo: 1, pageSize: 10000, filters: { ...queryFilters.value, roleCode } })
  const records = data.records || []
  if (records.length === 0) { show('无数据可导出'); return }
  // 复用 loadRows 的派生列（保持视图一致）
  let derived = records
  if (moduleCode.value === 'priceGroupItem') {
    const map = { 1: '小单位', 2: '中单位', 3: '大单位' }
    derived = records.map(r => ({ ...r, unitLevelText: map[Number(r.unitLevel)] || String(r.unitLevel ?? '') }))
  } else if (moduleCode.value === 'priceChangeLog') {
    const unitMap = { 1: '小单位', 2: '中单位', 3: '大单位' }
    const typeMap = { ADJUST_INIT: '首次设价', ADJUST_UPDATE: '调价单变更' }
    derived = records.map(r => {
      const oldV = r.oldPrice == null ? '' : Number(r.oldPrice)
      let rate = '首次设价'
      if (r.oldPrice != null && Number(r.oldPrice) !== 0) {
        const pct = (Number(r.newPrice) - Number(r.oldPrice)) / Number(r.oldPrice) * 100
        const sign = pct > 0 ? '+' : ''
        rate = `${sign}${pct.toFixed(2)}%`
      }
      return { ...r, unitLevelText: unitMap[Number(r.unitLevel)] || String(r.unitLevel ?? ''), oldPriceText: oldV, changeRateText: rate, changeTypeText: typeMap[r.changeType] || r.changeType }
    })
  } else if (moduleCode.value === 'priceAdjustOrder') {
    const statusMap = { DRAFT: '草稿', PENDING: '待审核', APPROVED: '已审核', REJECTED: '已驳回' }
    derived = records.map(r => ({ ...r, statusText: statusMap[r.status] || r.status }))
  } else if (moduleCode.value === 'stockBalance' || moduleCode.value === 'batchStock') {
    derived = records.map(r => {
      let largeConvertQty = 1
      try {
        const unitConfig = typeof r.unitConfig === 'string' ? JSON.parse(r.unitConfig) : r.unitConfig
        if (Array.isArray(unitConfig)) {
          const largeUnit = unitConfig.find(u => u.unitType === '大单位' && u.enabled !== false)
          if (largeUnit && Number(largeUnit.convertQty) > 0) largeConvertQty = Number(largeUnit.convertQty)
        }
      } catch (e) { /* 无单位配置 */ }
      const pq = Number(r.physicalQty) || 0
      const lq = Number(r.lockedQty) || 0
      const fq = Number(r.frozenQty) || 0
      const aq = Number(r.availableQty) || 0
      const cp = Number(r.costPrice) || 0
      return {
        ...r,
        physicalQtyPieces: largeConvertQty > 1 ? (pq / largeConvertQty).toFixed(2) : '',
        lockedQtyPieces: largeConvertQty > 1 ? (lq / largeConvertQty).toFixed(2) : '',
        frozenQtyPieces: largeConvertQty > 1 ? (fq / largeConvertQty).toFixed(2) : '',
        availableQtyPieces: largeConvertQty > 1 ? (aq / largeConvertQty).toFixed(2) : '',
        availableStockAmount: (aq * cp).toFixed(2),
      }
    })
  }
  // 用 mapRecordToRow 生成对齐 columns 的行数据（不含"操作"列）
  const visibleCols = (config.value.columns || []).filter(t => !/操作/.test(t))
  const rows = derived.map(record => {
    const row = mapRecordToRow(record, { columns: visibleCols })
    const obj = {}
    visibleCols.forEach((title, i) => { obj[title] = row[`c${i}`] })
    return obj
  })
  const ws = XLSX.utils.json_to_sheet(rows, { header: visibleCols })
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, config.value.title || 'Sheet1')
  const fileName = `${config.value.title || moduleCode.value}_${new Date().toISOString().slice(0, 10).replace(/-/g, '')}.xlsx`
  XLSX.writeFile(wb, fileName)
  show(`已导出 ${rows.length} 条到 ${fileName}`)
}

/**
 * 确认弹窗的提示文案。默认给通用说明，个别动作有额外副作用的单独写清楚，
 * 免得用户点完才发现下游单据被动过。
 */
function confirmHintOf(action) {
  if (moduleCode.value === 'salesOrder') {
    if (/^反审核$/.test(action)) {
      return '反审核只把订单退回待审核，本单占用的库存保持不变（占用从生成起持续到出库）。'
        + '由本单生成且仍未审核的销售出库单会被删除，其占用的批次库存一并释放。'
        + '若出库单已审核（实物库存已扣减、发货单已生成），本次反审核会被拒绝，需先处理出库单。'
    }
    if (/^审核$/.test(action)) {
      return '库存已于生成订单时占用，审核不重复锁定，只校验实物库存是否仍够本单数量。'
    }
    if (/^关闭$/.test(action)) {
      return '关闭后不再允许生成出库单，尚未出库部分的占用库存会被释放。'
    }
    if (/^删除$/.test(action)) {
      return '删除会释放本单尚未出库部分占用的库存。'
    }
  }
  return `${action}会按业务规则校验状态、权限和上下游引用，并写入操作日志。`
}

async function handleAction(action, row = null) {
  const actionStr = String(action || '')

  // 商品模块：新增/编辑走大抽屉，批量编辑走批量弹窗
  if (moduleCode.value === 'goods') {
    if (actionStr === '新建商品' || actionStr === '新增商品') { openAddDrawer(); return }
    if (actionStr === '编辑') { openEditDrawer(row); return }
    if (actionStr === '批量编辑') { openBatchEdit(); return }
  }

  // 价格组调价单：走专用抽屉
  if (moduleCode.value === 'priceAdjustOrder') {
    if (/新建|新增/.test(actionStr)) { openPriceAdjustDrawer('add'); return }
    if (actionStr === '查看' || actionStr === '编辑') {
      const isView = actionStr === '查看' || (row?._raw?.status && row._raw.status !== 'DRAFT')
      openPriceAdjustDrawer(isView ? 'view' : 'edit', row?._raw || null); return
    }
  }

  // 采购订单/销售订单：走专用单据抽屉
  if (moduleCode.value === 'purchaseOrder' || moduleCode.value === 'salesOrder') {
    if (/新建|新增/.test(actionStr)) {
      // 已有该模块的草稿抽屉时禁止重复新建
      if (app.billDrawer.visible && app.billDrawer.moduleCode === moduleCode.value) {
        show(`${moduleCode.value === 'purchaseOrder' ? '采购订单' : '销售订单'}已有正在编辑的草稿，请先保存或关闭`)
        return
      }
      openBillDrawer('add', moduleCode.value); return
    }
    if (actionStr === '编辑') { openBillDrawer('edit', moduleCode.value, row); return }
  }

  // 飞单：走专用飞单抽屉
  if (moduleCode.value === 'flyOrder') {
    const api = moduleApis.flyOrder
    if (/新建|新增/.test(actionStr)) {
      if (app.flyOrderDrawer.visible) {
        show('飞单已有正在编辑的草稿，请先保存或关闭')
        return
      }
      app.openFlyOrderDrawer('add'); return
    }
    if (actionStr === '编辑' || actionStr === '查看') { app.openFlyOrderDrawer('edit', row?._raw || null); return }
    if (actionStr === '审核') {
      const flyId = row?._raw?.flyId || row?._raw?.flyNo
      if (!flyId) { show('无法获取飞单ID，请刷新后重试'); return }
      if (!confirm('确认审核该飞单？\n\n审核后将自动生成采购订单、销售订单、应付和应收单据。')) return
      try {
        const res = await post(api.audit, { flyId })
        show(res?.effect || '审核成功')
        queryFilters.value = { ...queryFilters.value, status: 'APPROVED' }
        await loadRows()
      } catch (e) { show('审核失败：' + (e.message || '未知错误')) }
      return
    }
    if (actionStr === '取消审核' || actionStr === '反审核') {
      const flyId = row?._raw?.flyId || row?._raw?.flyNo
      if (!flyId) { show('无法获取飞单ID，请刷新后重试'); return }
      if (!confirm('确认取消审核？\n\n将删除关联的采购订单、销售订单、应付和应收单据。')) return
      try {
        const res = await post(api.reverseAudit, { flyId })
        show(res?.effect || '已取消审核')
        queryFilters.value = { ...queryFilters.value, status: 'DRAFT' }
        await loadRows()
      } catch (e) { show('取消审核失败：' + (e.message || '未知错误')) }
      return
    }
    if (actionStr === '删除') {
      const flyId = row?._raw?.flyId || row?._raw?.flyNo
      if (!confirm('确认删除该飞单？此操作不可恢复。')) return
      try {
        await post(api.delete, { flyId })
        show('已删除')
        await loadRows()
      } catch (e) { show('删除失败：' + (e.message || '未知错误')) }
      return
    }
    if (actionStr === '批量审核' || actionStr === '批量取消审核' || actionStr === '批量删除') {
      const ids = [...selectedRowKeys.value].map(idx => tableRows.value[idx]?._raw?.flyId).filter(Boolean)
      if (ids.length === 0) { show('请先勾选要操作的飞单'); return }
      const label = actionStr === '批量审核' ? '审核' : actionStr === '批量取消审核' ? '取消审核' : '删除'
      if (!confirm(`确认对选中的 ${ids.length} 张飞单执行【${label}】操作？`)) return
      const endpoint = actionStr === '批量审核' ? api.batchAudit : actionStr === '批量取消审核' ? api.batchUnaudit : api.batchDelete
      try {
        const res = await post(endpoint, { ids })
        show(res?.message || `批量${label}完成`)
        selectedRowKeys.value = new Set()
        selectedRows.value = []
        if (actionStr === '批量审核') queryFilters.value = { ...queryFilters.value, status: 'APPROVED' }
        else if (actionStr === '批量取消审核') queryFilters.value = { ...queryFilters.value, status: 'DRAFT' }
        await loadRows()
      } catch (e) { show(`批量${label}失败：` + (e.message || '未知错误')) }
      return
    }
  }

  // 采购入库：走专用入库抽屉（默认走「入库列表新建」入口 A，让用户在抽屉里选订单）
  if (moduleCode.value === 'purchaseInbound') {
    if (/新建|新增|引入/.test(actionStr)) {
      if (app.inboundDrawer.visible) {
        show('采购入库单已有正在编辑的草稿，请先保存或关闭')
        return
      }
      app.openInboundDrawer(null); return
    }
  }

  // 销售出库：走专用出库抽屉（对称）
  if (moduleCode.value === 'salesOutbound') {
    if (/新建|新增|引入/.test(actionStr)) {
      if (app.outboundDrawer.visible) {
        show('销售出库单已有正在编辑的草稿，请先保存或关闭')
        return
      }
      app.openOutboundDrawer(null); return
    }
  }

  // 采购收货单：走专用抽屉（未审核可改价，已审核只读）
  if (moduleCode.value === 'purchaseReceipt') {
    if (actionStr === '编辑' || actionStr === '查看' || actionStr === '改价') {
      const raw = row?._raw || {}
      const receiptId = raw.receiptId || raw.receiptNo
      if (!receiptId) { show('收货单号缺失'); return }
      app.openReceiptDrawer(receiptId, actionStr === '查看'); return
    }
  }

  // 采购退货申请：走专用申请抽屉
  if (moduleCode.value === 'purchaseReturnApply') {
    if (/新建|新增/.test(actionStr)) {
      if (app.returnApplyDrawer.visible) {
        show('采购退货申请已有正在编辑的草稿，请先保存或关闭')
        return
      }
      app.openReturnApplyDrawer(null, null, false); return
    }
    if (actionStr === '编辑' || actionStr === '查看') {
      const raw = row?._raw || {}
      const applyId = raw.applyId || raw.applyNo
      if (!applyId) { show('申请单号缺失'); return }
      // 点「查看」强制只读；点「编辑」时抽屉内部仍会按单据状态二次判定
      app.openReturnApplyDrawer(null, { applyId, applyNo: raw.applyNo }, actionStr === '查看'); return
    }
  }

  // 盘点单：走专用盘点抽屉
  if (moduleCode.value === 'stockTake') {
    if (/新建/.test(actionStr)) {
      app.openStockTakeDrawer('create'); return
    }
    if (/查看/.test(actionStr)) {
      app.openStockTakeDrawer('view', row); return
    }
    if (/编辑/.test(actionStr)) {
      app.openStockTakeDrawer('edit', row); return
    }
  }

  // 报损单：走专用报损单抽屉
  if (moduleCode.value === 'damage') {
    const api = moduleApis.damage
    if (/新建/.test(actionStr)) {
      if (app.damageDrawer.visible) {
        show('报损单已有正在编辑的单据，请先保存或关闭')
        return
      }
      app.openDamageDrawer('add'); return
    }
    if (actionStr === '编辑' || actionStr === '查看') {
      const raw = row?._raw || {}
      const damageId = raw.damageId || raw.damageNo
      if (!damageId) { show('报损单号缺失'); return }
      app.openDamageDrawer(actionStr === '查看' ? 'view' : 'edit', { damageId, damageNo: raw.damageNo }, actionStr === '查看')
      return
    }
    if (actionStr === '审核') {
      const raw = row?._raw || {}
      const damageId = raw.damageId || raw.damageNo
      if (!damageId) { show('报损单号缺失'); return }
      if (!confirm('确认审核该报损单？审核后将扣减库存并记入成本。')) return
      try {
        const res = await post(api.audit, { bizId: damageId })
        show(res?.effect || '审核成功')
        await loadRows()
      } catch (e) { show('审核失败：' + (e.message || '未知错误')) }
      return
    }
    if (actionStr === '反审核' || actionStr === '取消审核') {
      const raw = row?._raw || {}
      const damageId = raw.damageId || raw.damageNo
      if (!damageId) { show('报损单号缺失'); return }
      if (!confirm('确认反审核该报损单？将恢复库存。')) return
      try {
        const res = await post(api.reverseAudit, { bizId: damageId })
        show(res?.effect || '反审核成功')
        await loadRows()
      } catch (e) { show('反审核失败：' + (e.message || '未知错误')) }
      return
    }
    if (actionStr === '删除') {
      const raw = row?._raw || {}
      const damageId = raw.damageId || raw.damageNo
      if (!damageId) { show('报损单号缺失'); return }
      if (!confirm('确认删除该报损单？此操作不可恢复。')) return
      try {
        const res = await post(api.delete, { bizId: damageId })
        show(res?.effect || '已删除')
        await loadRows()
      } catch (e) { show('删除失败：' + (e.message || '未知错误')) }
      return
    }
    if (actionStr === '作废') {
      const raw = row?._raw || {}
      const damageId = raw.damageId || raw.damageNo
      if (!damageId) { show('报损单号缺失'); return }
      if (!confirm('确认作废该报损单？此操作不可逆。')) return
      try {
        const res = await post(api.cancel, { bizId: damageId })
        show(res?.effect || '已作废')
        await loadRows()
      } catch (e) { show('作废失败：' + (e.message || '未知错误')) }
      return
    }
  }

  // 其他入库单：走专用其他入库单抽屉
  if (moduleCode.value === 'otherInbound') {
    const api = moduleApis.otherInbound
    const pickId = () => {
      const raw = row?._raw || {}
      return { inboundId: raw.inboundId || raw.inboundNo, inboundNo: raw.inboundNo }
    }
    if (/新建/.test(actionStr)) {
      if (app.otherInboundDrawer.visible) {
        show('其他入库单已有正在编辑的草稿，请先保存或关闭')
        return
      }
      app.openOtherInboundDrawer('add'); return
    }
    if (actionStr === '编辑' || actionStr === '查看') {
      const { inboundId, inboundNo } = pickId()
      if (!inboundId) { show('其他入库单号缺失'); return }
      app.openOtherInboundDrawer(actionStr === '查看' ? 'view' : 'edit', { inboundId, inboundNo }, actionStr === '查看')
      return
    }
    if (actionStr === '审核') {
      const { inboundId } = pickId()
      if (!inboundId) { show('其他入库单号缺失'); return }
      if (!confirm('确认审核该其他入库单？审核后将增加库存并记入成本。')) return
      try {
        const res = await post(api.audit, { bizId: inboundId })
        show(res?.effect || '审核成功')
        await loadRows()
      } catch (e) { show('审核失败：' + (e.message || '未知错误')) }
      return
    }
    if (actionStr === '反审核' || actionStr === '取消审核') {
      const { inboundId } = pickId()
      if (!inboundId) { show('其他入库单号缺失'); return }
      if (!confirm('确认取消审核该其他入库单？将扣回此前入库的库存。')) return
      try {
        const res = await post(api.reverseAudit, { bizId: inboundId })
        show(res?.effect || '取消审核成功')
        await loadRows()
      } catch (e) { show('取消审核失败：' + (e.message || '未知错误')) }
      return
    }
    if (actionStr === '删除') {
      const { inboundId } = pickId()
      if (!inboundId) { show('其他入库单号缺失'); return }
      if (!confirm('确认删除该其他入库单？此操作不可恢复。')) return
      try {
        const res = await post(api.delete, { bizId: inboundId })
        show(res?.effect || '已删除')
        await loadRows()
      } catch (e) { show('删除失败：' + (e.message || '未知错误')) }
      return
    }
    if (actionStr === '作废') {
      const { inboundId } = pickId()
      if (!inboundId) { show('其他入库单号缺失'); return }
      if (!confirm('确认作废该其他入库单？此操作不可逆。')) return
      try {
        const res = await post(api.cancel, { bizId: inboundId })
        show(res?.effect || '已作废')
        await loadRows()
      } catch (e) { show('作废失败：' + (e.message || '未知错误')) }
      return
    }
  }

  // 其他出库单：走专用其他出库单抽屉
  if (moduleCode.value === 'otherOutbound') {
    const api = moduleApis.otherOutbound
    const pickId = () => {
      const raw = row?._raw || {}
      return { outboundId: raw.outboundId || raw.outboundNo, outboundNo: raw.outboundNo }
    }
    if (/新建/.test(actionStr)) {
      if (app.otherOutboundDrawer.visible) {
        show('其他出库单已有正在编辑的草稿，请先保存或关闭')
        return
      }
      app.openOtherOutboundDrawer('add'); return
    }
    if (actionStr === '编辑' || actionStr === '查看') {
      const { outboundId, outboundNo } = pickId()
      if (!outboundId) { show('其他出库单号缺失'); return }
      app.openOtherOutboundDrawer(actionStr === '查看' ? 'view' : 'edit', { outboundId, outboundNo }, actionStr === '查看')
      return
    }
    if (actionStr === '审核') {
      const { outboundId } = pickId()
      if (!outboundId) { show('其他出库单号缺失'); return }
      if (!confirm('确认审核该其他出库单？审核后将扣减库存并记入成本。')) return
      try {
        const res = await post(api.audit, { bizId: outboundId })
        show(res?.effect || '审核成功')
        await loadRows()
      } catch (e) { show('审核失败：' + (e.message || '未知错误')) }
      return
    }
    if (actionStr === '反审核' || actionStr === '取消审核') {
      const { outboundId } = pickId()
      if (!outboundId) { show('其他出库单号缺失'); return }
      if (!confirm('确认取消审核该其他出库单？将按审核成本回库。')) return
      try {
        const res = await post(api.reverseAudit, { bizId: outboundId })
        show(res?.effect || '取消审核成功')
        await loadRows()
      } catch (e) { show('取消审核失败：' + (e.message || '未知错误')) }
      return
    }
    if (actionStr === '删除') {
      const { outboundId } = pickId()
      if (!outboundId) { show('其他出库单号缺失'); return }
      if (!confirm('确认删除该其他出库单？此操作不可恢复。')) return
      try {
        const res = await post(api.delete, { bizId: outboundId })
        show(res?.effect || '已删除')
        await loadRows()
      } catch (e) { show('删除失败：' + (e.message || '未知错误')) }
      return
    }
    if (actionStr === '作废') {
      const { outboundId } = pickId()
      if (!outboundId) { show('其他出库单号缺失'); return }
      if (!confirm('确认作废该其他出库单？此操作不可逆。')) return
      try {
        const res = await post(api.cancel, { bizId: outboundId })
        show(res?.effect || '已作废')
        await loadRows()
      } catch (e) { show('作废失败：' + (e.message || '未知错误')) }
      return
    }
  }

  // 采购退货出库：走专用出库抽屉（只能从列表进入，出库单由申请审核自动生成）
  if (moduleCode.value === 'purchaseReturnOutbound') {
    if (actionStr === '编辑' || actionStr === '查看') {
      const raw = row?._raw || {}
      const outboundId = raw.outboundId || raw.outboundNo
      if (!outboundId) { show('出库单号缺失'); return }
      app.openReturnOutboundDrawer(outboundId, actionStr === '查看'); return
    }
  }

  // 商品调价单：走专用调价抽屉
  if (moduleCode.value === 'goodsPriceAdjust') {
    if (/新建/.test(actionStr)) {
      if (app.goodsPriceAdjustDrawer.visible) {
        show('商品调价单已有正在编辑的草稿，请先保存或关闭')
        return
      }
      app.openGoodsPriceAdjustDrawer('', 'add'); return
    }
    if (/编辑|查看/.test(actionStr)) {
      const raw = row?._raw || {}
      const orderId = raw.orderId || row.c0
      if (!orderId) { show('调价单号缺失'); return }
      app.openGoodsPriceAdjustDrawer(orderId, actionStr === '查看' ? 'view' : 'edit'); return
    }
  }

  // 采购退货单：走专用只读抽屉（含审核/反审核按钮）
  if (moduleCode.value === 'purchaseReturn') {
    if (actionStr === '查看' || actionStr === '审核' || actionStr === '反审核') {
      const raw = row?._raw || {}
      const returnId = raw.returnId || raw.returnNo
      if (!returnId) { show('退货单号缺失'); return }
      app.openReturnDrawer(returnId); return
    }
  }

  // 销售退货单：走专用抽屉
  if (moduleCode.value === 'salesReturn') {
    if (/新建|新增/.test(actionStr)) {
      if (app.salesReturnDrawer.visible) {
        show('销售退货单已有正在编辑的草稿，请先保存或关闭')
        return
      }
      app.openSalesReturnDrawer(null, null, false); return
    }
    if (actionStr === '编辑' || actionStr === '查看') {
      const raw = row?._raw || {}
      const applyId = raw.applyId || raw.applyNo
      if (!applyId) { show('退货单号缺失'); return }
      app.openSalesReturnDrawer(null, { applyId, applyNo: raw.applyNo }, actionStr === '查看'); return
    }
    if (actionStr === '确认退货' || actionStr === '驳回' || actionStr === '审核' || actionStr === '反审核'
        || actionStr === '删除' || actionStr === '推送仓库' || actionStr === '撤销推送') {
      // 这些操作走通用 action 流程（需要调用后端 API）
    }
    // 安排调度 / 取消调度落在 TMS 侧接口，入参是 applyNo 而不是通用的 bizId，单独处理
    if (actionStr === '安排调度' || actionStr === '取消调度') {
      const raw = row?._raw || {}
      const applyNo = raw.applyNo
      if (!applyNo) { show('退货单号缺失'); return }
      const url = actionStr === '安排调度' ? '/tms/return-dispatch/arrange' : '/tms/return-dispatch/cancel-arrange'
      try {
        await post(url, { applyNo })
        show(`${applyNo} ${actionStr}成功`)
        await loadRows()
      } catch (error) {
        show(`${actionStr}失败：${error.message}`)
      }
      return
    }
    // 改退货方式：确认后才发现方式选错的场景，只在「未安排」时可切，直接切到另一种，不弹通用确认框
    if (actionStr === '改为司机回收' || actionStr === '改为自提到仓') {
      const raw = row?._raw || {}
      const applyId = raw.applyId || raw.applyNo
      if (!applyId) { show('退货单号缺失'); return }
      try {
        const result = await post(moduleApis.salesReturn.changeReturnType, {
          bizId: applyId,
          returnType: actionStr === '改为司机回收' ? 'DRIVER' : 'WAREHOUSE',
        })
        show(result?.effect || `${actionStr}成功`)
        await loadRows()
      } catch (error) {
        show(`${actionStr}失败：${error.message}`)
      }
      return
    }
  }

  // 销售退货入库：走专用入库抽屉（编辑/审核入库单，补填批次号和生产日期）
  if (moduleCode.value === 'salesReturnInbound') {
    if (actionStr === '编辑' || actionStr === '查看' || actionStr === '审核') {
      const raw = row?._raw || {}
      const inboundId = raw.inboundId || raw.inboundNo
      if (!inboundId) { show('入库单号缺失'); return }
      app.openSalesReturnInboundDrawer(inboundId); return
    }
  }

  // 拒收入库单：走专用抽屉（编辑入库数量/批次，审核按原出库成本回库）
  // 没有「新建」分支 —— 单据只能由发货单签收拒收自动生成
  if (moduleCode.value === 'rejectInbound') {
    if (actionStr === '编辑' || actionStr === '查看' || actionStr === '审核' || actionStr === '反审核') {
      const raw = row?._raw || {}
      const inboundId = raw.rejectInboundId || raw.inboundId || raw.inboundNo
      if (!inboundId) { show('拒收入库单号缺失'); return }
      app.openRejectInboundDrawer(inboundId, actionStr === '查看'); return
    }
  }

  // 销售发货单：确认签收 / 撤销签收（工具栏按钮无 row，回落到当前选中行）
  if (moduleCode.value === 'salesReceipt') {
    if (actionStr === '确认签收') {
      const raw = (row || selectedRow.value)?._raw || {}
      const receiptId = raw.receiptId || raw.receiptNo
      if (!receiptId) { show('请先选择一张发货单'); return }
      const signStatus = raw.signStatus || ''
      if (signStatus && signStatus !== '待签收') {
        show(`该发货单已${signStatus}，如需重新登记请先撤销签收`); return
      }
      app.openReceiptSignDialog(receiptId); return
    }
    if (actionStr === '撤销签收') { handleReceiptUnsign(row || selectedRow.value); return }
    if (actionStr === '批量签收') { batchSignReceipts(); return }
  }

  // 客户应收明细表：收款结算弹窗
  if (moduleCode.value === 'ar') {
    if (actionStr === '收款结算') { openARSettlementDialog(); return }
    if (actionStr === '导出') { exportCurrentModuleXlsx(); return }
  }

  // 费用单：走 ExpenseDrawer
  if (moduleCode.value === 'financeExpense') {
    const api = moduleApis[moduleCode.value]
    if (/新建|编辑/.test(actionStr)) { openExpenseDrawer(row); return }
    if (/审核/.test(actionStr)) { handleAuditAction(api, row); return }
    if (/删除/.test(actionStr)) { handleExpenseDelete(row); return }
    if (actionStr === '批量审核') { batchAuditExpense(); return }
  }

  // 收款单 / 付款单：走 ReceiptDrawer
  if (moduleCode.value === 'receiptPayment' || moduleCode.value === 'paymentModule') {
    const api = moduleApis[moduleCode.value]
    if (actionStr === '批量审核') { batchAuditReceipt(); return }
    if (actionStr === '核销') { openReconcileDialog(row); return }
    if (/新建|编辑/.test(actionStr)) { openReceiptDrawer(row); return }
    if (/审核/.test(actionStr)) { handleAuditAction(api, row); return }
    if (/取消审核/.test(actionStr)) { handleCancelAuditAction(api, row); return }
    if (/删除/.test(actionStr)) { handleDeleteAction(api, row); return }
  }
  // 客户/供应商对账单
  if (moduleCode.value === 'customerStatement' || moduleCode.value === 'supplierStatement') {
    const api = moduleApis[moduleCode.value]
    if (/新建|编辑/.test(actionStr)) { openStatementDrawer(row); return }
    if (/审核/.test(actionStr)) { handleAuditAction(api, row); return }
    if (/删除/.test(actionStr)) { handleStatementDelete(api, row); return }
    if (actionStr === '收款结算' || actionStr === '付款结算') { openStatementSettlement(); return }
    if (actionStr === '导出') { exportCurrentModuleXlsx(); return }
  }

  // 调拨申请单
  if (moduleCode.value === 'transferApply') {
    const api = moduleApis.transferApply
    if (/新建/.test(actionStr)) { openTransferApply(); return }
    if (/编辑|查看/.test(actionStr)) { openTransferApply(row); return }
    if (/审核/.test(actionStr)) { handleTransferAudit(api, row, 'applyId', 'applyNo'); return }
    if (/反审核/.test(actionStr)) { handleTransferReverseAudit(api, row, 'applyId', 'applyNo'); return }
    if (/删除/.test(actionStr)) { handleTransferDelete(api, row, 'applyId', 'applyNo'); return }
    if (actionStr === '核销') { show('调拨申请无核销功能'); return }
  }

  // 调拨出库单
  if (moduleCode.value === 'transferOutbound') {
    const api = moduleApis.transferOutbound
    if (/编辑|查看/.test(actionStr)) { openTransferOutbound(row); return }
    if (/审核/.test(actionStr)) { handleTransferAudit(api, row, 'outboundId', 'outboundNo'); return }
    if (/反审核/.test(actionStr)) { handleTransferReverseAudit(api, row, 'outboundId', 'outboundNo'); return }
    if (actionStr === '核销') { show('调拨出库无核销功能'); return }
  }

  // 调拨入库单
  if (moduleCode.value === 'transferInbound') {
    const api = moduleApis.transferInbound
    if (/查看|编辑/.test(actionStr)) { openTransferInbound(row); return }
    if (/审核/.test(actionStr)) { handleTransferAudit(api, row, 'inboundId', 'inboundNo'); return }
    if (/反审核/.test(actionStr)) { handleTransferReverseAudit(api, row, 'inboundId', 'inboundNo'); return }
    if (actionStr === '核销') { show('调拨入库无核销功能'); return }
  }

  if (moduleCode.value === 'receiptVerify' || moduleCode.value === 'arSettlement' || moduleCode.value === 'counterpartyAr') {
    if (/核销|收款/.test(actionStr)) { openFundDrawer('receiveVerify', row); return }
  }
  if (moduleCode.value === 'paymentVerify' || moduleCode.value === 'apSettlement' || moduleCode.value === 'counterpartyAp') {
    if (/核销|付款/.test(actionStr)) { openFundDrawer('payVerify', row); return }
  }

  // 基础资料：走专用抽屉
  const baseModules = ['customer', 'supplier', 'warehouse', 'unit', 'brand', 'category', 'priceGroup', 'territory', 'routeLine', 'employee', 'department', 'owner', 'expenseType', 'counterparty', 'counterpartyType', 'fundAccount']
  if (baseModules.includes(moduleCode.value)) {
    if (/新建|新增/.test(actionStr) && !/类型管理/.test(actionStr)) { openBaseDrawer('add', moduleCode.value); return }
    if (actionStr === '编辑') { openBaseDrawer('edit', moduleCode.value, row); return }
    if (/^(查看|详情)$/.test(actionStr)) { openBaseDrawer('view', moduleCode.value, row); return }
    if (actionStr === '类型管理') { router.push('/counterparty-type'); return }
  }

  if (/刷新/.test(action)) {
    pageNo.value = 1
    await loadRows()
    show(`${config.value.title}已刷新`)
  } else if (/查看|详情|历史|库存|日志|来源/.test(action)) {
    await openDetail(action, row)
  } else if (/新建|编辑|复制|引入/.test(action)) {
    openDialog('form', action, `${config.value.title}：按PRD打开${config.value.mode === 'modal' ? '小弹窗' : config.value.mode === 'drawer' ? '右侧抽屉' : '独立页面'}。`, row)
  } else if (/审核|确认签收|确认退货|推送仓库|撤销推送|驳回|停用|作废|终止|核销|反审核|冻结|解冻|关闭|删除/.test(action)) {
    openDialog('confirm', action, confirmHintOf(action), row)
  } else if (/导入/.test(action)) {
    openDialog('import', action, `${config.value.title}导入：先下载模板，上传后预校验，失败行可下载原因。`, row)
  } else if (/下载|失败原因/.test(action)) {
    const api = moduleApis[moduleCode.value]
    if (api?.download) {
      try {
        const result = await post(api.download, buildPayload())
        if (result?.fileContent) saveTextFile(result.fileName, result.fileContent, result.mimeType)
        show(result?.message ? `${result.message}：${result.fileName}` : `${config.value.title}下载已准备好`)
      } catch (error) {
        show(`${config.value.title}下载失败：${error.message}`)
      }
    } else {
      show(`${config.value.title}文件下载已开始`)
    }
  } else if (/导出/.test(action)) {
    // 飞单导出：主单+明细合并导出
    if (moduleCode.value === 'flyOrder') {
      try {
        const api = moduleApis.flyOrder
        const mainData = await post(api.exportAll, {})
        const wb = XLSX.utils.book_new()
        // 主单 sheet
        const mainRows = (mainData || []).map(r => {
          const row = {}
          for (const [k, v] of Object.entries(r)) {
            if (k === 'STATUS' || k === '状态') continue
            row[k] = v
          }
          return row
        })
        const ws1 = XLSX.utils.json_to_sheet(mainRows.length ? mainRows : [{ 提示: '无数据' }])
        XLSX.utils.book_append_sheet(wb, ws1, '飞单主单')
        // 明细 sheet
        const detailRows = []
        for (const m of (mainData || [])) {
          const flyId = m['FLY_ID'] || m['flyId']
          if (!flyId) continue
          try {
            const details = await get(`${api.exportDetail || '/sales/fly-order/export-detail'}?flyId=${encodeURIComponent(flyId)}`)
            for (const d of (details || [])) {
              detailRows.push({ 飞单号: m['飞单号'] || m['FLY_NO'] || '', ...d })
            }
          } catch (_) {}
        }
        const ws2 = XLSX.utils.json_to_sheet(detailRows.length ? detailRows : [{ 提示: '无明细' }])
        XLSX.utils.book_append_sheet(wb, ws2, '飞单明细')
        XLSX.writeFile(wb, `飞单导出_${Date.now()}.xlsx`)
        show(`飞单导出成功：${mainRows.length} 张主单，${detailRows.length} 条明细`)
      } catch (error) {
        show('飞单导出失败：' + (error.message || '未知错误'))
      }
      return
    }
    const excelApi = excelModules[moduleCode.value]
    if (excelApi?.export) {
      try {
        const blob = await downloadBlob(excelApi.export, { moduleCode: moduleCode.value, filters: queryFilters.value })
        const fileName = `${config.value.title}_导出_${Date.now()}.xlsx`
        saveBlobFile(fileName, blob)
        show(`${config.value.title}导出成功：${fileName}`)
      } catch (error) {
        show(`${config.value.title}导出失败：${error.message}`)
      }
    } else {
      // 前端 xlsx 直接导出当前页数据：拉取全量后一次性写 sheet（上限 10000）
      try {
        await exportCurrentModuleXlsx()
      } catch (error) {
        show(`${config.value.title}导出失败：${error.message || '未知错误'}`)
      }
    }
  } else if (/打印/.test(action)) {
    show(`${config.value.title}打印预览已打开`)
  } else if (/字段设置/.test(action)) {
    openFieldDialog()
  } else {
    show(`${config.value.title}：${action}`)
  }
}

function buildPayload() {
  // 优先取 _raw 里真正的 PK（orderId / receiptId / inboundId / outboundId / applyId / returnId），
  // 兜底用列表首列 c0（通常是单据号）—— 后端端点已兼容单号
  const raw = selectedRow.value?._raw || {}
  const trueId = raw.orderId || raw.receiptId || raw.inboundId || raw.outboundId
    || raw.applyId || raw.returnId || raw.sheetNo || raw.countSheetId || selectedRow.value?.c0
  const base = {
    moduleCode: moduleCode.value,
    bizId: trueId || `${moduleCode.value}-demo`,
    orderId: trueId,
    sheetNo: raw.sheetNo || selectedRow.value?.c0,
    taskNo: trueId,
    remark: `${config.value.title}操作`,
    customerId: 'CUS001',
    supplierId: 'SUP001',
    warehouseId: 'WH001',
    objectId: 'OBJ001',
    fundAccountId: 'A001',
    amount: 100,
    parentId: 'ROOT',
    parentCode: '01',
    categoryCode: '01',
    categoryName: `${config.value.title}新增`,
    effectiveMode: 'IMMEDIATE',
    validType: 'LONG_TERM',
    details: [{ goodsId: 'G001', goodsName: '农夫山泉500ml*24', unitId: '箱', lineType: '正常', discountRate: '100%', taxRate: '13%', qty: 1, price: 1, currentPrice: 1 }],
    priceIds: ['PRICE001'],
    reason: '页面操作停用',
  }
  return { ...base, ...modulePayload() }
}

function modulePayload() {
  if (moduleCode.value === 'goods') {
    const code = text('商品编码') || selectedRow.value?.c1 || `GD${Date.now()}`
    return {
      goodsCode: code,
      goodsId: selectedRow.value?.c1 || code,
      goodsName: text('商品名称') || '新商品',
      goodsType: text('商品类型') || '正常商品',
      spec: text('规格'),
      categoryName: text('分类') || '默认分类',
      brandName: text('品牌'),
      baseUnit: text('基本单位') || '箱',
      barcode: text('条码'),
      shelfLifeDays: numberValue('保质期', 0),
      storageProperty: text('存储属性') || '常温',
      standardPrice: numberValue('标准售价', 0),
      suggestedRetailPrice: numberValue('建议零售价', 0),
      latestPurchasePrice: numberValue('参考进价', 0),
      minSalePrice: numberValue('最低售价', 0),
      stockUpperLimit: numberValue('库存上限', 0),
      stockLowerLimit: numberValue('库存下限', 0),
      defaultSupplier: text('默认供应商'),
      defaultWarehouse: text('默认仓库'),
      canReturn: !/否/.test(text('可售/可采购/可退')),
    }
  }
  if (moduleCode.value === 'customer') {
    const code = text('客户编码') || selectedRow.value?.c0 || `CT${Date.now()}`
    return {
      customerId: selectedRow.value?.c0 || code,
      customerCode: code,
      customerName: text('客户名称') || '新客户',
      channelType: text('渠道类型') || '零售商超',
      contactName: text('联系人'),
      mobile: text('手机号'),
      territory: text('片区'),
      routeLine: text('线路'),
      salesman: text('业务员'),
      customerLevel: text('客户等级') || '普通',
      accountPeriodType: text('账期类型') || text('结算方式') || '现结',
      cutoffDay: text('截账日'),
      paymentDay: text('付款日'),
      creditLimit: numberValue('信用额度', 0),
      invoiceTitle: text('发票抬头'),
      taxNo: text('税号'),
    }
  }
  if (moduleCode.value === 'supplier') {
    const code = text('供应商编码') || selectedRow.value?.c0 || `SP${Date.now()}`
    return {
      supplierId: selectedRow.value?.c0 || code,
      supplierCode: code,
      supplierName: text('供应商名称') || '新供应商',
      shortName: text('供应商简称'),
      supplierType: text('供应商类型') || text('类型') || '普通供应商',
      contactName: text('联系人'),
      phone: text('电话'),
      deliveryDays: numberValue('到货天数', 0),
      settlementMethod: text('结算方式') || '现结',
      accountPeriodDays: numberValue('账期天数', 0),
      defaultBuyer: text('默认采购员'),
      defaultReceiptAccount: text('默认收款账户'),
      invoiceTitle: text('发票抬头'),
      taxNo: text('税号'),
    }
  }
  if (moduleCode.value === 'purchaseOrder') {
    return {
      orderId: selectedRow.value?.c0,
      supplierId: text('供应商') || '农夫山泉杭州经销',
      warehouseId: text('收货仓库') || text('仓库') || '总仓',
      buyer: text('采购员') || '李四',
      ownerName: text('货主') || '平台货主',
      settlementMethod: text('结算方式') || '月结30天',
      details: [{ goodsId: 'SP001', goodsName: '农夫山泉500ml*24', unitId: '箱', lineType: '正常', taxRate: '13%', qty: 1, price: 35 }],
    }
  }
  if (moduleCode.value === 'salesOrder') {
    return {
      orderId: selectedRow.value?.c0,
      customerId: text('客户') || '华联超市',
      warehouseId: text('仓库') || '总仓',
      salesman: text('业务员') || '张三',
      lineType: text('行类型') || '正常',
      details: [{ goodsId: 'SP001', goodsName: '农夫山泉500ml*24', unitId: '箱', lineType: text('行类型') || '正常', discountRate: '100%', taxRate: '13%', qty: 1, price: 35 }],
    }
  }
  if (moduleCode.value === 'purchaseInbound') {
    return { sourceOrder: text('采购单号') || selectedRow.value?.c2 || selectedRow.value?.c0 || 'PO202606140001' }
  }
  if (moduleCode.value === 'salesOutbound') {
    return { sourceOrder: text('销售单号') || selectedRow.value?.c1 || selectedRow.value?.c0 || 'SO202606140001' }
  }
  return {}
}

function text(field) {
  return String(formModel.value[field] ?? '').trim()
}

function numberValue(field, fallback = 0) {
  const value = Number(String(formModel.value[field] ?? '').replace(/[^0-9.-]/g, ''))
  return Number.isFinite(value) ? value : fallback
}

// ============ 价格组：启用 / 停用 / 查看关联客户 ============
function isEnabled(row) {
  const v = row._raw?.enabled
  return v === true || v === 1 || v === '1' || v === 'true'
}

async function togglePriceGroup(row, enable) {
  const code = row._raw?.priceGroupCode || row.c0
  const name = row._raw?.priceGroupName || row.c1 || code
  const promptMsg = enable
    ? `确认启用价格组【${name}】？\n\n价格组启用后，需要去价格组调价模块设置对应商品价格。`
    : `停用价格组【${name}】后，将会自动清空关联门店的价格方案，是否确认停用？`
  if (!confirm(promptMsg)) return
  try {
    const api = moduleApis.priceGroup
    await post(api.enable, { moduleCode: 'priceGroup', priceGroupCode: code, bizId: code, enabled: enable })
    show(enable ? `${name} 已启用` : `${name} 已停用`)
    await loadRows()
  } catch (e) {
    show(`操作失败：${e.message || '未知错误'}`)
  }
}

// 关联客户弹窗
const priceGroupCustomerDialog = ref(null) // { code, name, rows }
async function openPriceGroupCustomers(row) {
  const code = row._raw?.priceGroupCode || row.c0
  const name = row._raw?.priceGroupName || row.c1 || code
  try {
    const rows = await post(moduleApis.priceGroup.customers, { priceGroupCode: code })
    priceGroupCustomerDialog.value = { code, name, rows: rows || [] }
  } catch (e) {
    show(`加载关联客户失败：${e.message || '未知错误'}`)
  }
}

function closePriceGroupCustomers() { priceGroupCustomerDialog.value = null }

function exportPriceGroupCustomers() {
  const d = priceGroupCustomerDialog.value
  if (!d) return
  const rows = d.rows.map(r => ({
    '门店编号': r.customerCode,
    '门店名称': r.customerName,
    '业务员': r.salesman || '',
    '渠道': r.channelType || '',
    '片区': r.territory || '',
  }))
  const ws = XLSX.utils.json_to_sheet(rows, { header: ['门店编号', '门店名称', '业务员', '渠道', '片区'] })
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '关联门店')
  XLSX.writeFile(wb, `价格组_${d.name}_关联门店.xlsx`)
  show(`已导出 ${d.rows.length} 条门店信息`)
}

// ============ 价格组商品查询：启停单条价格 ============
async function togglePriceItem(row, active) {
  const id = row._raw?.id
  if (!id) return
  try {
    await post(moduleApis.priceGroupItem.toggle, { id, isActive: active })
    show(active ? '已启用' : '已停用')
    await loadRows()
  } catch (e) {
    show(`操作失败：${e.message || '未知错误'}`)
  }
}

// 批量启停：并发上限 8，逐条调后端 toggle
async function batchToggleActive(active) {
  const rows = tableRows.value.filter((_, index) => selectedRowKeys.value.has(index))
  const ids = rows.map(r => r._raw?.id).filter(Boolean)
  if (ids.length === 0) { show('请先选择记录'); return }
  const actionText = active ? '启用' : '停用'
  if (!confirm(`确定批量${actionText} ${ids.length} 条价格记录？`)) return
  let ok = 0, fail = 0
  const CONCURRENCY = 8
  for (let i = 0; i < ids.length; i += CONCURRENCY) {
    const batch = ids.slice(i, i + CONCURRENCY)
    const results = await Promise.allSettled(
      batch.map(id => post(moduleApis.priceGroupItem.toggle, { id, isActive: active }))
    )
    results.forEach(r => { if (r.status === 'fulfilled') ok++; else fail++ })
  }
  selectedRowKeys.value.clear()
  await loadRows()
  show(`批量${actionText}完成：成功 ${ok} 条${fail ? `，失败 ${fail} 条` : ''}`)
}

// ============ 调价单：提交 / 审核 / 驳回 / 删除 ============
// 从采购订单生成入库单：打开入库单抽屉，预填 sourceOrder
async function generateInboundFromOrder(row) {
  const orderNo = row._raw?.orderNo
  const orderId = row._raw?.orderId
  if (!orderNo) { show('订单号缺失'); return }
  // 通过全局事件让 AppShell 中的 PurchaseInboundDrawer 打开
  app.openInboundFromOrder({ orderId, orderNo })
}

// 从销售订单生成出库单（对称）
async function generateOutboundFromOrder(row) {
  const orderNo = row._raw?.orderNo || row._raw?.orderno
  const orderId = row._raw?.orderId || row._raw?.orderid
  if (!orderNo) { show('订单号缺失'); return }
  app.openOutboundFromOrder({ orderId, orderNo })
}

/** 编辑销售出库单：打开抽屉，传入 editData */
async function openOutboundEditDrawer(row) {
  const raw = row._raw || {}
  const outboundNo = raw.outboundNo || raw.outbound_no
  const outboundId = raw.outboundId || raw.outbound_id
  if (!outboundNo && !outboundId) { show('出库单号缺失'); return }
  app.openOutboundDrawer(null)  // 先清空 sourceOrder
  // 直接设置 editData
  app.outboundDrawer.editData = { outboundNo, outboundId }
}

async function submitAdjustOrder(row) {
  if (!confirm(`将【${row._raw?.orderNo}】提交审核？`)) return
  try {
    await post(moduleApis.priceAdjustOrder.submit, { orderId: row._raw?.orderId })
    show('已提交待审核')
    await loadRows()
  } catch (e) { show(`提交失败：${e.message || '未知错误'}`) }
}

async function approveAdjustOrder(row) {
  if (!confirm(`审核通过【${row._raw?.orderNo}】？\n\n审核通过后价格将立即生效，并写入变价日志。`)) return
  try {
    await post(moduleApis.priceAdjustOrder.audit, { orderId: row._raw?.orderId })
    show('已审核通过，价格已更新')
    await loadRows()
  } catch (e) { show(`审核失败：${e.message || '未知错误'}`) }
}

async function rejectAdjustOrder(row) {
  const reason = prompt(`驳回【${row._raw?.orderNo}】，请填写驳回原因：`, '')
  if (reason === null) return
  try {
    await post(moduleApis.priceAdjustOrder.reject, { orderId: row._raw?.orderId, rejectReason: reason })
    show('已驳回')
    await loadRows()
  } catch (e) { show(`驳回失败：${e.message || '未知错误'}`) }
}

async function deleteAdjustOrder(row) {
  if (!confirm(`删除草稿【${row._raw?.orderNo}】？`)) return
  try {
    await post(moduleApis.priceAdjustOrder.delete, { orderId: row._raw?.orderId })
    show('已删除')
    await loadRows()
  } catch (e) { show(`删除失败：${e.message || '未知错误'}`) }
}

function selectTreeNode(nodeOrCode) {
  const value = typeof nodeOrCode === 'string' ? nodeOrCode.trim() : ''
  selectedTreeNode.value = value
  if (moduleCode.value === 'employee') {
    // 人员：选中部门 → 客户端按本级+子孙过滤，重新走 loadRows
    pageNo.value = 1
    loadRows()
    show(value ? `已切换到部门：${value}` : '已显示全部部门人员')
    return
  }
  if (moduleCode.value === 'category' || moduleCode.value === 'department' || moduleCode.value === 'fundAccount' || moduleCode.value === 'expenseType' || moduleCode.value === 'territory') {
    // 树状主档：点击树节点=视觉高亮，不做后台过滤（列表显示所有记录）
    show(value ? `已高亮：${value}` : '已显示全部')
    return
  }
  if (moduleCode.value === 'counterparty') {
    // 往来单位树：点击类型节点=筛选列表
    queryFilters.value = { ...queryFilters.value, treeNode: value || '' }
    pageNo.value = 1
    loadRows()
    show(value ? `已筛选：${value}` : '已显示全部往来单位')
    return
  }
  if (moduleCode.value === 'priceGroupItem') {
    // 价格组商品查询：选中价格组 → 后端按 priceGroupCode 过滤
    queryFilters.value = { ...queryFilters.value, priceGroupCode: value || '' }
    pageNo.value = 1
    loadRows()
    show(value ? `已筛选价格组：${value}` : '已显示全部价格组商品')
    return
  }
  queryFilters.value = { ...queryFilters.value, treeNode: value }
  pageNo.value = 1
  loadRows()
  show(value ? `已切换到：${value}` : '已显示全部')
}
async function handleQuery(filters = {}) {
  // 状态中文值 → 后端枚举
  const statusMap = { '正常': 'NORMAL', '停用': 'STOPPED', '冻结': 'FROZEN' }
  // 中文业务来源 → 后端值
  const bizSrcMap = { '后台制单': 'BACKOFFICE', '结算生成': 'AR_SETTLEMENT', '对账生成': 'RECONCILE' }
  // 中文往来单位类型 → 后端值
  const cpTypeMap = { '客户': 'CUSTOMER', '供应商': 'SUPPLIER', '往来单位': 'COUNTERPARTY' }
  const normalized = { ...filters }
  for (const k of Object.keys(normalized)) {
    if (k.includes('状态') && statusMap[normalized[k]]) normalized[k] = statusMap[normalized[k]]
    if (k === '往来单位类型' && cpTypeMap[normalized[k]]) { normalized['counterpartyType'] = cpTypeMap[normalized[k]]; delete normalized[k] }
    if (k === '业务来源' && bizSrcMap[normalized[k]]) { normalized['businessSource'] = bizSrcMap[normalized[k]]; delete normalized[k] }
    if (k === '核销状态' && normalized[k] === '全部') { delete normalized[k] }
  }
  // 飞单：中文筛选键 → 后端字段名
  if (moduleCode.value === 'flyOrder') {
    if (normalized['客户']) { normalized['customerCode'] = normalized['客户']; delete normalized['客户'] }
    if (normalized['供应商']) { normalized['supplierCode'] = normalized['供应商']; delete normalized['供应商'] }
    if (normalized['状态']) { normalized['status'] = normalized['状态']; delete normalized['状态'] }
  }
  queryFilters.value = normalized
  pageNo.value = 1
  await loadRows()
  show(`${config.value.title}查询完成`)
}
function handleReset() {
  // 飞单重置时恢复默认筛选（最近一个月 + 待审核）
  if (moduleCode.value === 'flyOrder') {
    queryFilters.value = { dateFrom: oneMonthAgoStr(), dateTo: todayStr(), status: 'DRAFT' }
  } else {
    queryFilters.value = {}
  }
  pageNo.value = 1; loadRows(); show(`${config.value.title}查询条件已重置`)
}
function handleMore(fields) { openDialog('more', '更多查询条件', fields.join('、')) }
function handleRowAction(action, row) { handleAction(action, row) }
function handlePageChange(nextPageNo) { pageNo.value = Math.max(1, nextPageNo); loadRows() }
function handlePageSizeChange(nextPageSize) { pageSize.value = nextPageSize; pageNo.value = 1; loadRows() }
function handleSortChange(field) {
  if (sortField.value !== field) {
    sortField.value = field
    sortOrder.value = 'asc'
  } else if (sortOrder.value === 'asc') {
    sortOrder.value = 'desc'
  } else {
    sortField.value = ''
    sortOrder.value = ''
  }
  pageNo.value = 1
  loadRows()
}

// 点击外部关闭 导入 下拉菜单
function onDocClick() { if (importMenuOpen.value) importMenuOpen.value = false }
onMounted(() => document.addEventListener('click', onDocClick))
onUnmounted(() => document.removeEventListener('click', onDocClick))
</script>

<template>
  <div class="module-body" :class="{ 'with-tree': treeVisible }">
    <aside v-if="treeVisible" class="module-tree">
      <!-- 树形模块（category/department/fundAccount/expenseType/territory/counterparty/employee/priceGroupItem）：动态树 -->
      <template v-if="moduleCode === 'category' || moduleCode === 'department' || moduleCode === 'fundAccount' || moduleCode === 'expenseType' || moduleCode === 'territory' || moduleCode === 'counterparty' || moduleCode === 'employee' || moduleCode === 'priceGroupItem'">
        <div v-if="dynamicTree.length === 0" class="tree-empty">
          {{ moduleCode === 'department' ? '暂无部门，请点击右侧【新建部门】'
             : moduleCode === 'fundAccount' ? '暂无资金账户，请点击右侧【新建账户】'
             : moduleCode === 'expenseType' ? '暂无费用类型，请点击右侧【新建费用类型】'
             : moduleCode === 'territory' ? '暂无片区，请点击右侧【新建片区】'
             : moduleCode === 'counterparty' ? '暂无往来单位，请点击右侧【新建】'
             : moduleCode === 'employee' ? '暂无部门，请先在【部门管理】维护'
             : moduleCode === 'priceGroupItem' ? '暂无启用中的价格组，请先在【价格组设置】启用'
             : '暂无分类，请点击右侧【新建分类】' }}
        </div>
        <div
          v-for="node in dynamicTree"
          :key="node.code || 'root'"
          class="tree-node"
          :class="{ active: selectedTreeNode === node.code }"
          :style="{ paddingLeft: (12 + node.level * 16) + 'px' }"
          @click="selectTreeNode(node.code)"
        >
          <span v-if="node.level > 0" class="tree-indent">└</span>{{ node.name }}
        </div>
      </template>
      <!-- 其他模块：静态 treeNodes -->
      <template v-else>
        <div v-for="node in config.treeNodes" :key="node" class="tree-node" :class="{ active: selectedTreeNode === node.trim() }" @click="selectTreeNode(node)">{{ node }}</div>
      </template>
    </aside>

    <section class="module-list">
      <div class="page-ops">
        <template v-for="action in config.actions" :key="action">
          <!-- 有子菜单的模块（如 counterparty）：导入按钮变下拉 -->
          <span v-if="action === '导入' && MODULE_IMPORT_MENU[moduleCode]" class="dropdown-wrap" @click.stop>
            <button class="btn" @click="toggleImportMenu">导入 ▾</button>
            <ul v-if="importMenuOpen" class="dropdown-menu">
              <li v-for="item in MODULE_IMPORT_MENU[moduleCode]" :key="item.key" @click="openImportDialog(item.key)">{{ item.label }}</li>
            </ul>
          </span>
          <!-- 简单模块：导入按钮直接打开导入弹窗 -->
          <button v-else-if="action === '导入' && MODULE_DEFAULT_IMPORT[moduleCode]" class="btn" @click="openImportDialog()">导入</button>
          <button v-else class="btn" :class="{ primary: /^新建|新增商品|审核并打印/.test(action) }" @click="handleAction(action)">
            {{ action }}
            <span v-if="action === '批量编辑' && moduleCode === 'goods' && selectedRowKeys.size > 0">({{ selectedRowKeys.size }})</span>
          </button>
        </template>
      </div>

      <QueryBar :fields="dynamicFilters" :defaults="filterDefaults" :max-visible="moduleCode === 'priceChangeLog' ? 6 : 4" @query="handleQuery" @reset="handleReset" @more="handleMore">
        <template #after-reset>
          <template v-if="moduleCode === 'priceGroupItem'">
            <button
              class="btn"
              title="点击该按钮可以切换价格组商品展示模式，树状结构或列表模式"
              @click="togglePriceGroupItemView"
            >
              {{ priceGroupItemViewMode === 'tree' ? '切换为列表' : '切换为树+表' }}
            </button>
            <button class="btn" :disabled="selectedRowKeys.size === 0" @click="batchToggleActive(true)">
              批量启用<span v-if="selectedRowKeys.size">({{ selectedRowKeys.size }})</span>
            </button>
            <button class="btn" :disabled="selectedRowKeys.size === 0" @click="batchToggleActive(false)">
              批量停用<span v-if="selectedRowKeys.size">({{ selectedRowKeys.size }})</span>
            </button>
            <button class="btn" @click="openImportDialog('priceGroupItemQuery')">导入查询</button>
            <button class="btn" @click="loadRows">刷新</button>
            <button class="btn" @click="exportCurrentModuleXlsx">导出</button>
          </template>
          <template v-else-if="moduleCode === 'priceChangeLog'">
            <button class="btn" @click="loadRows">刷新</button>
            <button class="btn" @click="exportCurrentModuleXlsx">导出</button>
            <button class="btn" @click="openImportDialog('priceLogQuery')">导入查询</button>
          </template>
          <template v-else-if="moduleCode === 'flyOrder'">
            <button class="btn" @click="loadRows">刷新</button>
          </template>
        </template>
      </QueryBar>

      <div v-if="config.tips?.length" class="tips-inline">
        <span v-for="tip in config.tips" :key="tip">⚠ {{ tip }}</span>
      </div>

      <div v-if="loading" class="tips-inline"><span>正在加载 {{ config.title }} 数据...</span></div>
      <ProTable :title="config.title + '列表'" :columns="visibleColumns" :rows="tableRows" :page-no="pageNo" :page-size="pageSize" :total="total" :sort-field="sortField" :sort-order="sortOrder"
                :cell-style="cellStyle" :dragging-key="draggingKey"
                :on-header-drag-start="onHeaderDragStart" :on-header-drag-over="onHeaderDragOver" :on-header-drop="onHeaderDrop"
                :on-start-resize="startResize"
                @field-setting="handleAction('字段设置')" @export="handleAction('导出')" @row-action="handleRowAction" @page-change="handlePageChange" @page-size-change="handlePageSizeChange" @sort-change="handleSortChange">
        <!-- 商品/价格组商品查询列表勾选列 -->
        <template v-if="moduleCode === 'goods' || moduleCode === 'priceGroupItem' || moduleCode === 'receiptPayment' || moduleCode === 'paymentModule' || moduleCode === 'financeExpense' || moduleCode === 'flyOrder' || moduleCode === 'ar' || moduleCode === 'customerStatement' || moduleCode === 'supplierStatement' || moduleCode === 'salesReceipt'" #checkbox-header>
          <input type="checkbox" :checked="tableRows.length > 0 && selectedRowKeys.size === tableRows.length" @change="toggleSelectAll($event.target.checked)" />
        </template>
        <template v-if="moduleCode === 'goods' || moduleCode === 'priceGroupItem' || moduleCode === 'receiptPayment' || moduleCode === 'paymentModule' || moduleCode === 'financeExpense' || moduleCode === 'flyOrder' || moduleCode === 'ar' || moduleCode === 'customerStatement' || moduleCode === 'supplierStatement' || moduleCode === 'salesReceipt'" #checkbox-cell="{ rowIndex }">
          <input type="checkbox" :checked="selectedRowKeys.has(rowIndex)" @change="toggleRowSelection(rowIndex, $event.target.checked)" />
        </template>

        <template v-for="col in visibleColumns" #[col.key]="{ row }" :key="col.key">
          <span v-if="/状态/.test(col.title)" class="badge wait">{{ row[col.key] }}</span>
          <!-- 关联客户列：0 显示灰色；> 0 显示可点击链接（弹窗查看门店） -->
          <span v-else-if="moduleCode === 'priceGroup' && col.title === '关联客户'">
            <button v-if="Number(row[col.key]) > 0" class="link link-btn" @click="openPriceGroupCustomers(row)">{{ row[col.key] }}</button>
            <span v-else style="color:#909399">{{ row[col.key] || 0 }}</span>
          </span>
          <span v-else-if="/操作/.test(col.title)">
            <!-- 价格组：编辑 + 启用/停用（互斥） -->
            <template v-if="moduleCode === 'priceGroup'">
              <button class="link link-btn" @click="handleAction('编辑', row)">编辑</button>
              <button v-if="!isEnabled(row)" class="link link-btn" @click="togglePriceGroup(row, true)">启用</button>
              <button v-else class="link link-btn danger-link" @click="togglePriceGroup(row, false)">停用</button>
            </template>
            <!-- 价格组商品查询：启停单条价格 -->
            <template v-else-if="moduleCode === 'priceGroupItem'">
              <button v-if="row._raw?.isActive" class="link link-btn danger-link" @click="togglePriceItem(row, false)">停用</button>
              <button v-else class="link link-btn" @click="togglePriceItem(row, true)">启用</button>
            </template>
            <!-- 商品调价单：状态化操作 -->
            <template v-else-if="moduleCode === 'goodsPriceAdjust'">
              <template v-if="row._raw?.status === 'DRAFT'">
                <button class="link link-btn" @click="handleAction('编辑', row)">编辑</button>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
              <template v-else-if="row._raw?.status === 'PENDING'">
                <button class="link link-btn" @click="handleAction('编辑', row)">编辑/审核</button>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
            </template>
            <!-- 调价单：按 status 展示不同操作 -->
            <template v-else-if="moduleCode === 'priceAdjustOrder'">
              <template v-if="row._raw?.status === 'DRAFT'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="submitAdjustOrder(row)">提交</button>
                <button class="link link-btn danger-link" @click="deleteAdjustOrder(row)">删除</button>
              </template>
              <template v-else-if="row._raw?.status === 'PENDING'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="approveAdjustOrder(row)">审核通过</button>
                <button class="link link-btn danger-link" @click="rejectAdjustOrder(row)">驳回</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
            </template>
            <!-- 采购订单：按 status 状态化操作 -->
            <template v-else-if="moduleCode === 'purchaseOrder'">
              <template v-if="row._raw?.status === 'PENDING'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('编辑', row)">编辑</button>
                <button class="link link-btn" @click="handleAction('审核', row)">审核</button>
                <button class="link link-btn danger-link" @click="handleAction('删除', row)">删除</button>
              </template>
              <template v-else-if="row._raw?.status === 'APPROVED'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <!-- 已入库 → 不显示生成入库单；未/部分入库 → 显示 -->
                <button v-if="row._raw?.inboundStatus !== '已入库'" class="link link-btn"
                        @click="generateInboundFromOrder(row)">生成入库单</button>
                <!-- 未入库才允许反审核（已生成过入库单再反审核会污染库存） -->
                <button v-if="!row._raw?.inboundStatus || row._raw.inboundStatus === '未入库' || row._raw.inboundStatus === '待入库'"
                        class="link link-btn" @click="handleAction('反审核', row)">反审核</button>
                <button class="link link-btn danger-link" @click="handleAction('终止', row)">终止</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
            </template>
            <!-- 采购收货单：按 status 状态化操作（PENDING → 审核；APPROVED → 反审核，仅当未付款） -->
            <template v-else-if="moduleCode === 'purchaseReceipt'">
              <template v-if="row._raw?.status === 'PENDING'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('改价', row)">改价/审核</button>
              </template>
              <template v-else-if="row._raw?.status === 'APPROVED'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('反审核', row)">反审核</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
            </template>
            <!-- 盘点单：PENDING 可查看/编辑/审核/删除；APPROVED 可查看/反审核 -->
            <template v-else-if="moduleCode === 'stockTake'">
              <template v-if="row._raw?.status === 'PENDING' || row._raw?.statusText === '待审核'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('编辑', row)">编辑</button>
                <button class="link link-btn" @click="handleAction('审核', row)">审核</button>
                <button class="link link-btn danger-link" @click="handleAction('删除', row)">删除</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('反审核', row)">反审核</button>
              </template>
            </template>
            <!-- 报损单：未审核可编辑/审核/删除；已审核可反审核/作废；已作废无操作（双击行查看详情） -->
            <template v-else-if="moduleCode === 'damage'">
              <template v-if="row._raw?.status === 'DRAFT' || row._raw?.status === 'PENDING'">
                <button class="link link-btn" @click="handleAction('编辑', row)">编辑</button>
                <button class="link link-btn" @click="handleAction('审核', row)">审核</button>
                <button class="link link-btn danger-link" @click="handleAction('删除', row)">删除</button>
              </template>
              <template v-else-if="row._raw?.status === 'APPROVED'">
                <button class="link link-btn" @click="handleAction('反审核', row)">反审核</button>
                <button class="link link-btn danger-link" @click="handleAction('作废', row)">作废</button>
              </template>
              <span v-else style="color:#909399">—</span>
            </template>
            <!-- 其他入库单：无草稿态。未审核 可编辑/审核/删除；已审核 可取消审核/作废；已作废 只可查看 -->
            <template v-else-if="moduleCode === 'otherInbound'">
              <template v-if="row._raw?.status === 'PENDING' || row._raw?.status === 'DRAFT'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('编辑', row)">编辑</button>
                <button class="link link-btn" @click="handleAction('审核', row)">审核</button>
                <button class="link link-btn danger-link" @click="handleAction('删除', row)">删除</button>
              </template>
              <template v-else-if="row._raw?.status === 'APPROVED'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('取消审核', row)">取消审核</button>
                <button class="link link-btn danger-link" @click="handleAction('作废', row)">作废</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
            </template>
            <!-- 其他出库单：无草稿态。未审核 可编辑/审核/删除；已审核 可取消审核/作废；已作废 只可查看 -->
            <template v-else-if="moduleCode === 'otherOutbound'">
              <template v-if="row._raw?.status === 'PENDING' || row._raw?.status === 'DRAFT'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('编辑', row)">编辑</button>
                <button class="link link-btn" @click="handleAction('审核', row)">审核</button>
                <button class="link link-btn danger-link" @click="handleAction('删除', row)">删除</button>
              </template>
              <template v-else-if="row._raw?.status === 'APPROVED'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('取消审核', row)">取消审核</button>
                <button class="link link-btn danger-link" @click="handleAction('作废', row)">作废</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
            </template>
            <!-- 采购入库单：按 status 状态化操作（PENDING → 审核；APPROVED → 只读查看） -->
            <template v-else-if="moduleCode === 'purchaseInbound'">
              <template v-if="row._raw?.status === 'PENDING'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('审核', row)">审核</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
            </template>
            <!-- 采购退货申请：DRAFT/PENDING 可编辑审核删除；APPROVED 可反审核；已出库/已完成只读 -->
            <template v-else-if="moduleCode === 'purchaseReturnApply'">
              <template v-if="row._raw?.status === 'DRAFT' || row._raw?.status === 'PENDING'">
                <button class="link link-btn" @click="handleAction('编辑', row)">查看/编辑</button>
                <button class="link link-btn" @click="handleAction('审核', row)">审核</button>
                <button v-if="row._raw?.status === 'DRAFT'" class="link link-btn danger-link" @click="handleAction('删除', row)">删除</button>
              </template>
              <template v-else-if="row._raw?.status === 'APPROVED'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('反审核', row)">反审核</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
            </template>
            <!--
              销售退货单行操作：
                DRAFT     可编辑/删除
                PENDING   可编辑/确认/驳回
                CONFIRMED 按退货方式 + 流转状态分叉（司机回收走安排调度，自提到仓走推送仓库；
                          审核按钮只在货已到位时出现，具体前置由后端按入账时点参数兜底校验）
                APPROVED  可反审核
                REJECTED  只读
            -->
            <template v-else-if="moduleCode === 'salesReturn'">
              <template v-if="row._raw?.status === 'DRAFT'">
                <button class="link link-btn" @click="handleAction('编辑', row)">查看/编辑</button>
                <button class="link link-btn danger-link" @click="handleAction('删除', row)">删除</button>
              </template>
              <template v-else-if="row._raw?.status === 'PENDING'">
                <button class="link link-btn" @click="handleAction('编辑', row)">查看/编辑</button>
                <button class="link link-btn primary-link" @click="handleAction('确认退货', row)">确认退货</button>
                <button class="link link-btn danger-link" @click="handleAction('驳回', row)">驳回</button>
              </template>
              <template v-else-if="row._raw?.status === 'CONFIRMED'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <!-- 司机回收链路 -->
                <template v-if="row._raw?.returnType === 'DRIVER'">
                  <button v-if="row._raw?.logisticsStatus === '未安排'" class="link link-btn primary-link" @click="handleAction('安排调度', row)">安排调度</button>
                  <button v-if="row._raw?.logisticsStatus === '未安排'" class="link link-btn" @click="handleAction('改为自提到仓', row)">改为自提到仓</button>
                  <button v-if="row._raw?.logisticsStatus === '已安排调度'" class="link link-btn" @click="handleAction('取消调度', row)">取消调度</button>
                  <button v-if="row._raw?.logisticsStatus === '司机已回收' || row._raw?.logisticsStatus === '已入库'" class="link link-btn primary-link" @click="handleAction('审核', row)">审核</button>
                </template>
                <!-- 自提到仓链路 -->
                <template v-else>
                  <button v-if="row._raw?.logisticsStatus === '未安排'" class="link link-btn primary-link" @click="handleAction('推送仓库', row)">推送仓库</button>
                  <button v-if="row._raw?.logisticsStatus === '未安排'" class="link link-btn" @click="handleAction('改为司机回收', row)">改为司机回收</button>
                  <button v-if="row._raw?.logisticsStatus === '已推送仓库'" class="link link-btn" @click="handleAction('撤销推送', row)">撤销推送</button>
                  <button v-if="row._raw?.logisticsStatus === '已入库'" class="link link-btn primary-link" @click="handleAction('审核', row)">审核</button>
                </template>
              </template>
              <template v-else-if="row._raw?.status === 'APPROVED'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('反审核', row)">反审核</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
            </template>
            <!-- 销售退货入库：PENDING 可编辑（补填批次号/生产日期）和审核；APPROVED 只读 -->
            <template v-else-if="moduleCode === 'salesReturnInbound'">
              <template v-if="row._raw?.status === 'PENDING'">
                <button class="link link-btn" @click="handleAction('编辑', row)">编辑</button>
                <button class="link link-btn" @click="handleAction('审核', row)">审核</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
            </template>
            <!-- 拒收入库单：PENDING 可编辑（改入库数量/批次）和审核；APPROVED 可查看+反审核 -->
            <template v-else-if="moduleCode === 'rejectInbound'">
              <template v-if="row._raw?.status === 'PENDING'">
                <button class="link link-btn" @click="handleAction('编辑', row)">编辑</button>
                <button class="link link-btn primary-link" @click="handleAction('审核', row)">审核</button>
              </template>
              <template v-else-if="row._raw?.status === 'APPROVED'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('反审核', row)">反审核</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
            </template>
            <!-- 采购退货出库单：PENDING 可编辑数量+审核；APPROVED 只读查看 -->
            <template v-else-if="moduleCode === 'purchaseReturnOutbound'">
              <template v-if="row._raw?.status === 'PENDING'">
                <button class="link link-btn" @click="handleAction('编辑', row)">确认出库</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
            </template>
            <!-- 采购退货单：PENDING 可审核（冲减应付）；APPROVED 可反审核 -->
            <template v-else-if="moduleCode === 'purchaseReturn'">
              <template v-if="row._raw?.status === 'PENDING'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('审核', row)">审核</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
            </template>
            <!-- 销售订单：按 status 状态化操作（对称采购订单） -->
            <template v-else-if="moduleCode === 'salesOrder'">
              <template v-if="row._raw?.status === 'PENDING'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('编辑', row)">编辑</button>
                <button class="link link-btn" @click="handleAction('审核', row)">审核</button>
                <button class="link link-btn danger-link" @click="handleAction('删除', row)">删除</button>
              </template>
              <template v-else-if="row._raw?.status === 'APPROVED'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <!-- 已生成出库单（含未审核的）→ 不再显示【生成出库单】：一张订单只出一次库 -->
                <button v-if="!(Number(row._raw?.outboundCount) > 0)" class="link link-btn"
                        @click="generateOutboundFromOrder(row)">生成出库单</button>
                <!-- 已出库（存在已审核出库单）→ 不显示【反审核】：实物已扣、发货单已生成，退不回来 -->
                <button v-if="!(Number(row._raw?.outboundAuditedCount) > 0)" class="link link-btn"
                        @click="handleAction('反审核', row)">反审核</button>
                <button class="link link-btn danger-link" @click="handleAction('关闭', row)">关闭</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
            </template>
            <!-- 销售出库单：按 status 状态化；PENDING 可编辑 -->
            <template v-else-if="moduleCode === 'salesOutbound'">
              <template v-if="row._raw?.status === 'PENDING'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="openOutboundEditDrawer(row)">编辑</button>
                <button class="link link-btn" @click="handleAction('审核', row)">审核</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
            </template>
            <!-- 销售发货单：按签收状态 + 单据状态状态化。
                 待签收 → 确认签收（弹窗改拒收/签收数量）；已签收/部分拒收/全部拒收 → 撤销签收；
                 签收会自动审核生成应收，所以「审核」只在「已签收但没生成应收」的补救场景露出 -->
            <template v-else-if="moduleCode === 'salesReceipt'">
              <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              <template v-if="row._raw?.status === 'CANCELLED'"></template>
              <template v-else-if="!row._raw?.signStatus || row._raw?.signStatus === '待签收'">
                <button class="link link-btn" @click="handleAction('确认签收', row)">确认签收</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('撤销签收', row)">撤销签收</button>
                <button v-if="row._raw?.status === 'PENDING' && row._raw?.signStatus !== '全部拒收'"
                        class="link link-btn" @click="handleAction('审核', row)">审核</button>
                <button v-if="row._raw?.status === 'APPROVED'"
                        class="link link-btn" @click="handleAction('反审核', row)">反审核</button>
              </template>
            </template>
            <!-- 飞单：DRAFT → 查看/编辑/审核/删除；APPROVED → 查看/取消审核；CANCELLED → 查看 -->
            <template v-else-if="moduleCode === 'flyOrder'">
              <template v-if="row._raw?.status === 'DRAFT'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('编辑', row)">编辑</button>
                <button class="link link-btn" @click="handleAction('审核', row)">审核</button>
                <button class="link link-btn danger-link" @click="handleAction('删除', row)">删除</button>
              </template>
              <template v-else-if="row._raw?.status === 'APPROVED'">
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
                <button class="link link-btn" @click="handleAction('取消审核', row)">取消审核</button>
              </template>
              <template v-else>
                <button class="link link-btn" @click="handleAction('查看', row)">查看</button>
              </template>
            </template>
            <template v-else-if="isBaseModule">
              <!-- 系统默认记录：不允许编辑/删除，仅显示只读标记 -->
              <span v-if="row._raw && row._raw.isSystem" class="link" style="color:#999">系统默认</span>
              <template v-else>
                <button class="link link-btn" @click="handleAction('编辑', row)">编辑</button>
                <button class="link link-btn danger-link" @click="handleAction('删除', row)">删除</button>
              </template>
            </template>
            <template v-else>
              <button v-for="action in String(row[col.key]).split(' ')" :key="action" class="link link-btn" @click="handleAction(action, row)">{{ action }}</button>
            </template>
          </span>
          <span v-else>{{ row[col.key] }}</span>
        </template>
      </ProTable>

      <!-- 飞单批量操作浮动栏 -->
      <div v-if="moduleCode === 'flyOrder' && selectedRowKeys.size > 0" class="fly-batch-bar">
        <span class="fly-batch-count">已选 {{ selectedRowKeys.size }} 条</span>
        <button class="btn btn-primary" @click="handleAction('批量审核')">批量审核</button>
        <button class="btn" @click="handleAction('批量取消审核')">批量取消审核</button>
        <button class="btn btn-danger" @click="handleAction('批量删除')">批量删除</button>
        <button class="btn btn-text" @click="clearSelection">取消选择</button>
      </div>

      <!-- 费用单批量审核 -->
      <div v-if="moduleCode === 'financeExpense' && selectedRowKeys.size > 0 && hasPendingSelected" class="fly-batch-bar">
        <span class="fly-batch-count">已选 {{ selectedRowKeys.size }} 条</span>
        <button class="btn btn-primary" @click="batchAuditExpense">批量审核</button>
        <button class="btn btn-text" @click="selectedRowKeys.clear()">取消选择</button>
      </div>

      <!-- 收款单/付款单批量操作浮动栏 -->
      <div v-if="(moduleCode === 'receiptPayment' || moduleCode === 'paymentModule') && selectedRowKeys.size > 0 && hasPendingSelected" class="fly-batch-bar">
        <span class="fly-batch-count">已选 {{ selectedRowKeys.size }} 条</span>
        <button class="btn btn-primary" @click="batchAuditReceipt">批量审核</button>
        <button class="btn btn-text" @click="selectedRowKeys.clear()">取消选择</button>
      </div>

      <!-- 销售发货单批量签收（按全签处理：拒收 0、签收 = 发货数量） -->
      <div v-if="moduleCode === 'salesReceipt' && selectedRowKeys.size > 0 && hasUnsignedSelected" class="fly-batch-bar">
        <span class="fly-batch-count">已选 {{ selectedRowKeys.size }} 条</span>
        <button class="btn btn-primary" @click="batchSignReceipts">批量签收</button>
        <button class="btn btn-text" @click="selectedRowKeys.clear()">取消选择</button>
      </div>

      <!-- 核销弹窗 -->
      <div v-if="showReconcileDialog" class="modal-lite" @click.self="showReconcileDialog = false">
        <div class="modal-lite-box" style="width:min(900px,96vw);max-height:85vh">
          <div class="modal-lite-head">
            <b>核销结算 — {{ reconcileRow?.receiptNo || '' }} | {{ reconcileRow?.counterpartyName || '' }}</b>
            <div class="actions">
              <button class="btn" @click="quickCreateExpense">创建费用单</button>
              <button class="btn" @click="showReconcileDialog = false">取消</button>
              <button class="btn primary" @click="confirmReconcile">确认核销</button>
            </div>
          </div>
          <div class="modal-lite-body">
            <div style="display:flex;gap:24px;margin-bottom:10px;font-size:13px">
              <span>待核销金额：<b style="color:#409eff">{{ reconcilePendingAmount.toFixed(2) }}</b></span>
              <span>本次核销：<b style="color:#e6a23c">{{ reconcileTotal.toFixed(2) }}</b></span>
              <span v-if="reconcileTotal > reconcilePendingAmount && reconcilePendingAmount > 0" style="color:#f56c6c">⚠ 超出待核销金额</span>
            </div>
            <div v-if="reconcileLoading" style="text-align:center;padding:30px;color:#909399">加载中...</div>
            <div v-else-if="reconcileBills.length === 0" style="text-align:center;padding:30px;color:#909399">该往来单位暂无未结算单据</div>
            <div v-else class="detail-scroll" style="max-height:55vh">
              <table>
                <thead><tr>
                  <th style="width:30px"></th>
                  <th>应收单号</th>
                  <th>原业务单号</th>
                  <th>往来单位</th>
                  <th style="text-align:right">应收金额</th>
                  <th style="text-align:right">已收金额</th>
                  <th style="text-align:right">未收款金额</th>
                  <th style="text-align:right;width:100px">本次结算</th>
                </tr></thead>
                <tbody>
                  <tr v-for="b in reconcileBills" :key="b._key" @click="onSettleCheck(b._key)" style="cursor:pointer">
                    <td><input type="checkbox" :checked="!!reconcileSettleAmounts[b._key]" @click.stop="onSettleCheck(b._key)" /></td>
                    <td>{{ b.billNo }}</td>
                    <td>{{ b.sourceBill || '-' }}</td>
                    <td>{{ b.counterpartyName || '-' }}</td>
                    <td style="text-align:right">{{ Number(b.arAmount || b.apAmount || 0).toFixed(2) }}</td>
                    <td style="text-align:right">{{ Number(b.receivedAmount || b.paidAmount || 0).toFixed(2) }}</td>
                    <td style="text-align:right;font-weight:700" :style="{ color: b.unsettled > 0 ? '#409eff' : '#f56c6c' }">{{ Number(b.unsettled || 0).toFixed(2) }}</td>
                    <td style="text-align:right">
                      <input v-if="!!reconcileSettleAmounts[b._key]" type="number" step="0.01"
                        :value="reconcileSettleAmounts[b._key]"
                        @input="onSettleAmountChange(b._key, $event.target.value)"
                        @click.stop
                        style="width:88px;height:24px;text-align:right;font-size:12px" />
                      <span v-else style="color:#c0c4cc">—</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>

      <!-- 快速创建费用单弹窗（核销窗口内） -->
      <div v-if="showQuickExpenseDialog" class="modal-lite" @click.self="showQuickExpenseDialog = false">
        <div class="modal-lite-box" style="width:min(440px,94vw)">
          <div class="modal-lite-head">
            <b>创建费用单</b>
            <button class="btn" @click="showQuickExpenseDialog = false">关闭</button>
          </div>
          <div class="modal-lite-body" style="display:flex;flex-direction:column;gap:8px">
            <div class="fi"><label>往来单位类型</label><input :value="reconcileRow?.counterpartyTypeText || reconcileRow?.counterpartyType || ''" readonly /></div>
            <div class="fi"><label>往来单位</label><input :value="(reconcileRow?.counterpartyCode||'') + ' ' + (reconcileRow?.counterpartyName||'')" readonly /></div>
            <div class="fi"><label>费用日期 <span class="req">*</span></label><input type="date" v-model="quickExpenseForm.expenseDate" /></div>
            <div class="fi"><label>经手人 <span class="req">*</span></label>
              <select v-model="quickExpenseForm.handler"><option value="">请选择</option><option v-for="e in quickExpenseEmployees" :key="e.employeeCode || e.code" :value="e.employeeName || e.name">{{ e.employeeName || e.name }}</option></select></div>
            <div class="fi"><label>费用类型 <span class="req">*</span></label>
              <select v-model="quickExpenseForm.expenseType"><option value="">请选择</option><option v-for="et in quickExpenseTypes" :key="et.expenseTypeCode || et.code" :value="et.expenseTypeName || et.name">{{ et.expenseTypeName || et.name }}</option></select></div>
            <div class="fi"><label>费用金额 <span class="req">*</span></label><input type="number" min="0" step="0.01" v-model="quickExpenseForm.amount" /></div>
            <div class="fi"><label>收支类型</label>
              <select v-model="quickExpenseForm.direction"><option value="OUT">支出</option><option value="IN">收入</option></select></div>
            <div class="fi"><label>备注</label><input v-model="quickExpenseForm.remark" placeholder="选填" /></div>
            <div style="display:flex;justify-content:flex-end;gap:8px;margin-top:4px">
              <button class="btn" @click="showQuickExpenseDialog = false">取消</button>
              <button class="btn primary" @click="confirmQuickExpense" :disabled="quickExpenseSaving">确认创建并审核</button>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>

  <div v-if="feedback" class="toast-inline">{{ feedback }}</div>

  <div v-if="dialog" class="modal-lite" :class="{ 'drawer-lite': config.mode === 'drawer' && dialog.type === 'form', 'page-lite': config.mode === 'bill' && dialog.type === 'form' }">
    <div class="modal-lite-box">
      <div class="modal-lite-head"><b>{{ dialog.title }} - {{ config.title }}</b><button class="btn" @click="closeDialog">×</button></div>
      <div class="modal-lite-body">
        <p>{{ dialog.message }}</p>

        <div v-if="dialog.type === 'form'" class="form-edit-area">
          <div :class="config.mode === 'modal' ? 'form-vertical' : 'grid4'">
            <div v-for="field in formFields" :key="field" class="field">
              <label>{{ field }}<span v-if="/编码|名称|单号|客户|供应商|仓库|日期|数量|单价|金额|状态/.test(field)" style="color:#ef4444"> *</span></label>
              <select v-if="/状态|类型|方式|仓库|客户|供应商|单位|分类|品牌|业务员|采购员/.test(field)" v-model="formModel[field]"><option>{{ formModel[field] || field }}</option></select>
              <textarea v-else-if="/备注|说明|原因/.test(field)" v-model="formModel[field]" :placeholder="field"></textarea>
              <input v-else v-model="formModel[field]" :placeholder="field" />
            </div>
          </div>

          <div v-if="detailColumns.length" class="section-block">
            <b>明细信息</b>
            <div class="scroll mini-scroll">
              <table>
                <tr><th v-for="field in detailColumns" :key="field">{{ field }}</th><th>操作</th></tr>
                <tr><td v-for="field in detailColumns" :key="field" contenteditable>{{ field.includes('数量') ? '10' : field.includes('金额') ? '350.00' : field }}</td><td><button class="link link-btn">删除</button></td></tr>
              </table>
            </div>
            <button class="btn" style="margin-top:8px" @click="show('已新增明细行')">新增明细行</button>
          </div>
        </div>

        <div v-if="dialog.type === 'view'" class="section-block">
          <div class="grid4">
            <div v-for="col in columns.filter(c => !/操作/.test(c.title))" :key="col.key" class="field"><label>{{ col.title }}</label><input readonly :value="selectedRow?.[col.key] || ''" /></div>
          </div>
          <div v-if="detailData?.details?.length" class="section-block">
            <b>后端明细</b>
            <div class="scroll mini-scroll">
              <table>
                <tr><th>商品编码</th><th>商品名称</th><th>单位</th><th>数量</th><th>单价</th><th>金额</th><th>成本金额</th></tr>
                <tr v-for="detail in detailData.details" :key="detail.goodsCode + detail.goodsName + detail.qty">
                  <td>{{ detail.goodsCode }}</td><td>{{ detail.goodsName }}</td><td>{{ detail.unit }}</td><td>{{ detail.qty }}</td><td>{{ detail.price }}</td><td>{{ detail.amount }}</td><td>{{ detail.costAmount }}</td>
                </tr>
              </table>
            </div>
          </div>
        </div>

        <div v-if="dialog.type === 'confirm'" class="field"><label>原因/备注</label><textarea placeholder="请输入原因或备注"></textarea></div>

        <div v-if="dialog.type === 'import'" class="section-block">
          <button class="btn" @click="downloadTemplate">下载模板</button>
          <input style="margin-left:8px" type="file" accept=".xlsx,.xls" @change="importFile = $event.target.files?.[0] || null" />
          <p v-if="importFile" style="color:var(--primary);font-size:12px">已选择：{{ importFile.name }}</p>
          <p style="color:#5d7896">上传后先校验，不直接入库；失败行可下载失败原因。</p>
        </div>

        <!-- 老「字段设置」块已删除；改由 <FieldSettingDialog> 独立弹窗承接（在模板末尾） -->
      </div>
      <div class="modal-lite-foot">
        <button class="btn" @click="closeDialog">取消</button>
        <button v-if="dialog.type === 'form'" class="btn primary" @click="saveForm">保存</button>
        <button v-else-if="dialog.type === 'confirm'" class="btn primary" @click="confirmAction">确认</button>
        <button v-else-if="dialog.type === 'import'" class="btn primary" @click="uploadImport">上传并校验</button>
        <button v-else class="btn primary" @click="closeDialog">确定</button>
      </div>
    </div>
  </div>

  <!-- 商品编辑大抽屉 -->
  <GoodsDrawer
    :visible="showGoodsDrawer && moduleCode === 'goods'"
    :mode="drawerMode"
    :goods-data="editGoodsData"
    @close="showGoodsDrawer = false"
    @save="handleGoodsSave"
  />

  <!-- 调拨申请单抽屉 -->
  <TransferApplyDrawer
    :visible="showTransferApply && moduleCode === 'transferApply'"
    :mode="transferApplyMode"
    :edit-data="transferApplyEditData"
    @close="showTransferApply = false"
    @save="showTransferApply = false; loadRows(); show('保存成功')"
  />

  <!-- 调拨出库单抽屉 -->
  <TransferOutboundDrawer
    :visible="showTransferOutbound && moduleCode === 'transferOutbound'"
    :mode="transferOutboundMode"
    :edit-data="transferOutboundEditData"
    @close="showTransferOutbound = false"
    @save="showTransferOutbound = false; loadRows(); show('保存成功')"
  />

  <!-- 调拨入库单抽屉 -->
  <TransferInboundDrawer
    :visible="showTransferInbound && moduleCode === 'transferInbound'"
    :edit-data="transferInboundEditData"
    @close="showTransferInbound = false"
    @save="showTransferInbound = false; loadRows(); show('保存成功')"
  />

  <!-- 业务单据抽屉已提升至 AppShell 全局挂载 —— 见 stores/app.js#billDrawer 与 layout/AppShell.vue -->

  <!-- 资金单据抽屉（收付款/核销） -->
  <FundBillDrawer
    :visible="showFundDrawer"
    :bill-type="fundBillType"
    :edit-data="fundEditData"
    @close="closeFundDrawer"
    @save="onFundSave"
  />

  <!-- 客户应收收款结算弹窗 -->
  <ARSettlementDialog :visible="showARSettlement" :ar-rows="arSettlementRows" @close="showARSettlement = false" @saved="onARSettlementSaved" />

  <!-- 对账单收款/付款结算 -->
  <StatementSettlementDialog :visible="showStatementSettlement" :module-code="moduleCode" :selected-rows="statementSettleRows" @close="showStatementSettlement = false" @saved="onStatementSettlementSaved" />

  <!-- 对账单抽屉 -->
  <CustomerStatementDrawer
    :visible="showStatementDrawer" :mode="statementDrawerMode" :edit-data="statementEditData"
    :module-code="moduleCode"
    @close="showStatementDrawer = false" @save="showStatementDrawer = false; loadRows(); show('保存成功')" />

  <!-- 费用单抽屉 -->
  <ExpenseDrawer
    :visible="showExpenseDrawer"
    :mode="expenseDrawerMode"
    :edit-data="expenseEditData"
    @close="showExpenseDrawer = false"
    @save="showExpenseDrawer = false; loadRows(); show('保存成功')"
  />

  <!-- 收款单 / 付款单抽屉 -->
  <ReceiptDrawer
    :visible="showReceiptDrawer"
    :mode="receiptDrawerMode"
    :edit-data="receiptEditData"
    :module-code="currentReceiptModule"
    @close="showReceiptDrawer = false"
    @save="showReceiptDrawer = false; loadRows(); show('保存成功')"
  />

  <!-- 基础资料抽屉 -->
  <BaseInfoDrawer
    :visible="showBaseDrawer"
    :mode="baseDrawerMode"
    :module-code="baseDrawerCode"
    :edit-data="baseEditData"
    @close="closeBaseDrawer"
    @save="onBaseSave"
  />

  <!-- 通用导入弹窗（往来单位模块使用） -->
  <ImportDialog
    :visible="importDialog.visible"
    :title="importDialog.title"
    :template-headers="importDialog.templateHeaders"
    :template-name="importDialog.templateName"
    :field-map="importDialog.fieldMap"
    :required-key="importDialog.requiredKey"
    @close="closeImportDialog"
    @import="handleImport"
  />

  <!-- 批量编辑弹窗 -->
  <BatchEditDrawer
    :visible="showBatchEditDrawer && moduleCode === 'goods'"
    :selected-rows="selectedRows"
    @close="showBatchEditDrawer = false"
    @save="handleBatchSave"
  />

  <!-- 价格组调价单抽屉 -->
  <PriceAdjustDrawer
    :visible="showPriceAdjustDrawer"
    :mode="priceAdjustMode"
    :order-data="priceAdjustData"
    @close="showPriceAdjustDrawer = false"
    @saved="onPriceAdjustSaved"
  />

  <!-- 业务单据详情抽屉（双击行 / 点查看 触发） -->
  <BillDetailDrawer
    :visible="billDetailDrawer.visible"
    :title="billDetailDrawer.title"
    :data="billDetailDrawer.data"
    @close="closeBillDetail"
  />

  <!-- 价格组关联客户弹窗 -->
  <div v-if="priceGroupCustomerDialog" class="drawer-overlay" @click.self="closePriceGroupCustomers">
    <div class="modal-lite-box" style="width:min(720px,92vw);max-height:80vh">
      <div class="modal-lite-head">
        <b>{{ priceGroupCustomerDialog.name }} — 关联门店（{{ priceGroupCustomerDialog.rows.length }}）</b>
        <div class="actions">
          <button class="btn" @click="closePriceGroupCustomers">关闭</button>
          <button class="btn primary" :disabled="!priceGroupCustomerDialog.rows.length" @click="exportPriceGroupCustomers">导出</button>
        </div>
      </div>
      <div class="modal-lite-body">
        <div v-if="!priceGroupCustomerDialog.rows.length" class="tips-inline"><span>暂无关联门店</span></div>
        <div v-else class="scroll" style="max-height:56vh">
          <table>
            <tr><th>门店编号</th><th>门店名称</th><th>业务员</th><th>渠道</th><th>片区</th></tr>
            <tr v-for="r in priceGroupCustomerDialog.rows" :key="r.customerCode">
              <td>{{ r.customerCode }}</td>
              <td>{{ r.customerName }}</td>
              <td>{{ r.salesman || '-' }}</td>
              <td>{{ r.channelType || '-' }}</td>
              <td>{{ r.territory || '-' }}</td>
            </tr>
          </table>
        </div>
      </div>
    </div>
  </div>

  <!-- 通用字段设置弹窗（显示/顺序/宽度/固定列） —— 独立在 priceGroupCustomerDialog 之外，否则 v-if=false 时永不渲染 -->
  <FieldSettingDialog v-if="fieldDialogOpen"
    :title="`字段设置 —— ${config.title}`"
    :pending-settings="pendingSettings"
    :dialog-column-list="dialogColumnList"
    @save="saveColumnSettings"
    @reset="resetColumnSettings"
    @close="closeFieldDialog"
    @move="moveInDialog" />
</template>

<style scoped>
.dropdown-wrap { position: relative; display: inline-block; }
.dropdown-menu {
  position: absolute;
  right: 0;
  top: 100%;
  margin-top: 4px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  box-shadow: 0 6px 20px rgba(15, 46, 88, 0.12);
  list-style: none;
  padding: 4px 0;
  min-width: 148px;
  z-index: 60;
}
.dropdown-menu li {
  padding: 8px 14px;
  font-size: 12px;
  color: #303133;
  cursor: pointer;
  white-space: nowrap;
}
.dropdown-menu li:hover { background: #eff6ff; color: #409eff; }

/* 飞单批量操作浮动栏 */
.fly-batch-bar {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 14px; margin-top: 8px;
  background: #f0f7ff;
  border: 1px solid #b3d8ff;
  border-radius: 6px;
}
.fly-batch-count {
  font-size: 13px; color: #666; margin-right: 4px;
}
.fly-batch-bar .btn-primary {
  background: #409eff; border-color: #409eff; color: #fff;
}
.fly-batch-bar .btn-danger {
  background: #ff4d4f; border-color: #ff4d4f; color: #fff;
}
.fly-batch-bar .btn-text {
  border: none; background: none; color: #999; cursor: pointer;
  font-size: 12px;
}
.fly-batch-bar .btn-text:hover { color: #666; }
</style>
