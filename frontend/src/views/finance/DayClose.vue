<script setup>
/**
 * 业务日结管理（PRD-33，按日封单）。
 * 四个页签：① 日结向导（7 步检查 + 执行/反日结 + 现金实盘备注）② 日结记录（RJ 单打印/反日结/批量反日结）
 * ③ 定版台账（客户应收/供应商应付/资金账户 日余额）④ 操作日志（只增）。
 * 查询一律点「查询」触发；权限：finance.day_close.audit 日结、finance.day_close.unaudit 反日结。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  dayCloseWizard, dayCloseExecute, dayCloseReopen, dayCloseReopenBatch,
  dayClosePage, dayCloseLogPage, dayCloseArDailyPage, dayCloseApDailyPage,
  dayCloseFundDailyPage, dayCloseGoodsDailyPage,
} from '../../api/day-close.js'

const router = useRouter()

const PERM_CLOSE = 'finance.day_close.audit'
const PERM_REOPEN = 'finance.day_close.unaudit'

const tab = ref('wizard')
const feedback = ref('')
const busy = ref(false)

// -- 向导 ------------------------------------------------------------------
const wizDate = ref('')
const wizard = ref(null)
const openingConfirmed = ref(false)
const acknowledgeHanging = ref(false)
const acknowledgeAnomaly = ref(false)
const fundRemark = ref('')
/** 现金实盘录入：账户名 → 金额字符串（仅备注，不阻断） */
const cashInputs = ref({})
const reopenModal = ref({ open: false, date: '', title: '', batch: false, toDate: '', reason: '' })

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 6000)
}
function money(v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function day(v) { return v == null ? '' : String(v).slice(0, 10) }
function time(v) { return v == null ? '' : String(v).slice(0, 19) }
function actionText(a) { return ({ CLOSE: '日结', REOPEN: '反日结' })[a] || a }
function resultText(r) { return ({ SUCCESS: '成功', FAIL: '失败' })[r] || r }
function triggerText(t) { return ({ MANUAL: '手动', AUTO: '自动' })[t] || t || '—' }

function defaultYesterday() {
  const d = new Date()
  d.setDate(d.getDate() - 1)
  return d.toISOString().slice(0, 10)
}

const step1 = computed(() => wizard.value?.step1 || null)
const step2 = computed(() => wizard.value?.step2 || null)
const step3 = computed(() => wizard.value?.step3 || null)
const step4 = computed(() => wizard.value?.step4 || null)
const step5 = computed(() => wizard.value?.step5 || null)
const step6 = computed(() => wizard.value?.step6 || null)
const step7 = computed(() => wizard.value?.step7 || null)
const totals = computed(() => wizard.value?.totals || {})

/** 拉取向导（切日期后仅点查询触发）。 */
async function loadWizard() {
  if (!wizDate.value) return show('请选择日结日期', 'err')
  try {
    wizard.value = await dayCloseWizard(wizDate.value)
    openingConfirmed.value = false
    acknowledgeHanging.value = false
    acknowledgeAnomaly.value = false
    fundRemark.value = wizard.value?.closed ? '' : fundRemark.value
    cashInputs.value = {}
  } catch (e) {
    wizard.value = null
    show('向导加载失败：' + (e?.message || e), 'err')
  }
}

/** 执行日结前的前端确认项（后端同样硬校验，这里提前给中文提示）。 */
function closeChecks() {
  if (!wizard.value?.canClose) return '当前存在硬性未通过项（连续性/DWS 对账/四项滚存勾稽），不能日结'
  if (step1.value?.firstClose && !openingConfirmed.value) return '首次日结请勾选期初基线核对确认'
  if ((step2.value?.count || 0) > 0 && !acknowledgeHanging.value) return '存在跨阶挂账单，请勾选「已知悉挂账单并继续」'
  if ((step3.value?.count || 0) > 0 && !acknowledgeAnomaly.value) return '存在异常数据提示，请勾选「已知悉异常数据并继续」'
  return ''
}

/** 手工执行日结。 */
async function doClose() {
  const err = closeChecks()
  if (err) return show(err, 'err')
  const cashCounts = {}
  for (const [k, v] of Object.entries(cashInputs.value)) {
    if (String(v ?? '').trim() !== '') cashCounts[k] = String(v).trim()
  }
  if (!confirm(`确认对日结日期 ${wizDate.value} 执行日结封单？日结后该日期及之前的已生效业务单据将不能反审核/变更。`)) return
  busy.value = true
  try {
    const r = await dayCloseExecute({
      date: wizDate.value,
      openingConfirmed: openingConfirmed.value,
      acknowledgeHanging: acknowledgeHanging.value,
      acknowledgeAnomaly: acknowledgeAnomaly.value,
      cashCounts,
      fundRemark: fundRemark.value || null,
    })
    show(r.idempotent ? `${r.closeNo} 已存在（幂等跳过）` : `日结完成：${r.closeNo}`)
    await loadWizard()
    if (tab.value === 'records') await loadRecords()
  } catch (e) { show('日结失败：' + (e?.message || e), 'err') }
  finally { busy.value = false }
}

/** 打开反日结原因弹窗（单日/批量）。 */
function openReopen(row, batch = false) {
  if (batch) {
    reopenModal.value = { open: true, batch: true, date: '', toDate: wizDate.value || defaultYesterday(), title: '', reason: '' }
  } else {
    const d = day(row?.closeDate ?? wizDate.value)
    reopenModal.value = { open: true, batch: false, date: d, toDate: d, title: row?.closeNo || d, reason: '' }
  }
}

/** 提交反日结（原因 ≥2 字；单日只能反当前封单日，后端强校验）。 */
async function submitReopen() {
  const reason = reopenModal.value.reason.trim()
  if (reason.length < 2) return show('请填写反日结原因（至少 2 个字，将记入操作日志）', 'err')
  busy.value = true
  try {
    if (reopenModal.value.batch) {
      const r = await dayCloseReopenBatch(reopenModal.value.toDate, reason)
      show(`批量反日结完成：批次 ${r.batchNo}，共反结 ${r.days} 天`)
    } else {
      await dayCloseReopen(reopenModal.value.date, reason)
      show(`已反日结 ${reopenModal.value.date}：当日定版台账已删除，单据恢复可变更（操作留痕）`)
    }
    reopenModal.value.open = false
    await loadWizard()
    if (recordsLoaded.value) await loadRecords()
  } catch (e) { show('反日结失败：' + (e?.message || e), 'err') }
  finally { busy.value = false }
}

/** 新窗口打开 RJ 日结单打印页。 */
function printTicket(date) {
  const url = router.resolve({ path: '/finance/day-close-ticket', query: { date: day(date) } }).href
  window.open(url, '_blank')
}

// -- 日结记录 --------------------------------------------------------------
const recFilter = ref({ from: '', to: '' })
const records = ref([])
const recTotal = ref(0)
const recPageNo = ref(1)
const recPageSize = 20
let recordsLoaded = false

/** 查询日结记录（点查询/翻页触发）。 */
async function loadRecords() {
  try {
    const res = await dayClosePage({
      pageNo: recPageNo.value, pageSize: recPageSize,
      filters: { from: recFilter.value.from || null, to: recFilter.value.to || null },
    })
    records.value = res.records || []
    recTotal.value = res.total || 0
    recordsLoaded = true
  } catch (e) { show('日结记录加载失败：' + (e?.message || e), 'err') }
}
function searchRecords() { recPageNo.value = 1; loadRecords() }

// -- 定版台账 --------------------------------------------------------------
const ledgerTab = ref('ar')
const ledgerFilters = ref({ from: '', to: '', keyword: '' })
const ledgerRows = ref([])
const ledgerTotal = ref(0)
const ledgerPageNo = ref(1)
const ledgerPageSize = 20
const ledgerLoaded = { ar: false, ap: false, fund: false, goods: false }

const LEDGER_COLS = {
  ar: [
    { p: 'customerCode', t: '客户编码' }, { p: 'customerName', t: '客户名称' },
    { p: 'arAmount', t: '应收总额', num: true }, { p: 'receivedAmount', t: '已收金额', num: true },
    { p: 'unreceivedAmount', t: '未收余额', num: true },
    { p: 'advanceAccountBalance', t: '预收余额', num: true },
    { p: 'advanceAmount', t: '预收(重分类)', num: true },
    { p: 'overdueAmount', t: '逾期金额', num: true }, { p: 'billCount', t: '单据数', num: true },
  ],
  ap: [
    { p: 'supplierCode', t: '供应商编码' }, { p: 'supplierName', t: '供应商名称' },
    { p: 'apAmount', t: '应付总额', num: true }, { p: 'paidAmount', t: '已付金额', num: true },
    { p: 'unpaidAmount', t: '未付余额', num: true }, { p: 'prepaidAmount', t: '预付(2202重分类)', num: true },
    { p: 'prepayAccountBalance', t: '预付余额(1123账户)', num: true },
    { p: 'expenseAccountBalance', t: '费用余额(1221)', num: true },
    { p: 'overdueAmount', t: '逾期金额', num: true }, { p: 'billCount', t: '单据数', num: true },
  ],
  fund: [
    { p: 'fundAccountCode', t: '账户编码' }, { p: 'fundAccountName', t: '账户名称' },
    { p: 'openBalance', t: '上日余额', num: true }, { p: 'inAmount', t: '当日收入', num: true },
    { p: 'outAmount', t: '当日支出', num: true }, { p: 'closeBalance', t: '当日余额', num: true },
    { p: 'cashCount', t: '现金实盘数', num: true },
  ],
  goods: [
    { p: 'goodsCode', t: '商品编码' }, { p: 'goodsName', t: '商品名称' },
    { p: 'spec', t: '规格' }, { p: 'baseUnit', t: '单位' }, { p: 'warehouse', t: '仓库' },
    { p: 'openingQty', t: '期初数量', num: true }, { p: 'openingAmount', t: '期初金额', num: true },
    { p: 'purchaseInQty', t: '采购入库', num: true },
    { p: 'salesReturnInQty', t: '销退入库', num: true },
    { p: 'otherInQty', t: '其他入库', num: true },
    { p: 'transferInQty', t: '调入', num: true },
    { p: 'inQty', t: '收入合计', num: true }, { p: 'inAmount', t: '收入金额', num: true },
    { p: 'adjustAmount', t: '成本调整', num: true },
    { p: 'salesOutQty', t: '销售出库', num: true },
    { p: 'purchaseReturnOutQty', t: '采退出库', num: true },
    { p: 'otherOutQty', t: '其他出库', num: true },
    { p: 'transferOutQty', t: '调出', num: true },
    { p: 'outQty', t: '发出合计', num: true }, { p: 'outAmount', t: '发出金额', num: true },
    { p: 'signedQty', t: '签收净量', num: true }, { p: 'signedAmount', t: '签收净额', num: true },
    { p: 'grossProfit', t: '毛利', num: true },
    { p: 'endingQty', t: '期末数量', num: true }, { p: 'endingAmount', t: '期末金额', num: true },
    { p: 'endingCostPrice', t: '单位成本', num: true },
    { p: 'negativeFlag', t: '负库存', tag: { Y: '是' } },
  ],
}

/** 查询当前定版台账（应收/应付/资金，点查询/翻页触发）。 */
async function loadLedger() {
  const body = {
    pageNo: ledgerPageNo.value, pageSize: ledgerPageSize,
    filters: {
      from: ledgerFilters.value.from || null,
      to: ledgerFilters.value.to || null,
      keyword: ledgerFilters.value.keyword || null,
    },
  }
  try {
    const fnMap = { ar: dayCloseArDailyPage, ap: dayCloseApDailyPage, fund: dayCloseFundDailyPage, goods: dayCloseGoodsDailyPage }
    const fn = fnMap[ledgerTab.value]
    const res = await fn(body)
    ledgerRows.value = res.records || []
    ledgerTotal.value = res.total || 0
    ledgerLoaded[ledgerTab.value] = true
  } catch (e) { show('定版台账加载失败：' + (e?.message || e), 'err') }
}
function switchLedger(t) {
  ledgerTab.value = t
  ledgerRows.value = []
  ledgerTotal.value = 0
  ledgerPageNo.value = 1
  if (!ledgerLoaded[t]) loadLedger()
}
function searchLedger() { ledgerPageNo.value = 1; loadLedger() }

// -- 操作日志 --------------------------------------------------------------
const logFilter = ref({ from: '', to: '', action: '' })
const logs = ref([])
const logTotal = ref(0)
const logPageNo = ref(1)
const logPageSize = 20
let logsLoaded = false

/** 查询日结操作日志（只增表，点查询/翻页触发）。 */
async function loadLogs() {
  try {
    const res = await dayCloseLogPage({
      pageNo: logPageNo.value, pageSize: logPageSize,
      filters: {
        from: logFilter.value.from || null,
        to: logFilter.value.to || null,
        action: logFilter.value.action || null,
      },
    })
    logs.value = res.records || []
    logTotal.value = res.total || 0
    logsLoaded = true
  } catch (e) { show('操作日志加载失败：' + (e?.message || e), 'err') }
}
function searchLogs() { logPageNo.value = 1; loadLogs() }

function switchTab(t) {
  tab.value = t
  if (t === 'records' && !recordsLoaded) loadRecords()
  if (t === 'ledger' && !ledgerLoaded[ledgerTab.value]) loadLedger()
  if (t === 'logs' && !logsLoaded) loadLogs()
}

onMounted(() => {
  wizDate.value = defaultYesterday()
  loadWizard()
})
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <div class="tabbar">
        <button :class="['tab-btn', { on: tab === 'wizard' }]" @click="switchTab('wizard')">日结向导</button>
        <button :class="['tab-btn', { on: tab === 'records' }]" @click="switchTab('records')">日结记录</button>
        <button :class="['tab-btn', { on: tab === 'ledger' }]" @click="switchTab('ledger')">定版台账</button>
        <button :class="['tab-btn', { on: tab === 'logs' }]" @click="switchTab('logs')">操作日志</button>
      </div>
    </div>

    <div v-if="feedback" :class="['toast-inline', feedback.level]">{{ feedback.msg }}</div>

    <!-- ================= ① 日结向导 ================= -->
    <template v-if="tab === 'wizard'">
      <div class="page-ops">
        <label>日结日期：</label>
        <input type="date" v-model="wizDate" style="width:150px">
        <button class="btn primary" @click="loadWizard">查询</button>
        <span v-if="wizard" class="muted">
          模式：{{ wizard.mode === 'AUTO' ? '自动日结' : '手动日结' }} ｜
          当前封单日：{{ wizard.lastClosed || '（无）' }} ｜
          {{ wizard.firstClose ? '首次日结' : '常规日结' }}
          <span v-if="!wizard.enabled" class="tag tag-red">封单开关已停用</span>
        </span>
        <span style="flex:1"></span>
        <button v-if="wizard?.closed" class="btn" v-permission="PERM_REOPEN" @click="openReopen(null, false)">反日结</button>
        <button v-if="wizard?.closed" class="btn" @click="printTicket(wizDate)">打印 RJ 单</button>
        <button v-else class="btn primary" v-permission="PERM_CLOSE" :disabled="busy" @click="doClose">执行日结</button>
      </div>

      <div v-if="wizard?.closed" class="card-box ok-box">
        该日期已日结：<b>{{ day(wizard.date) }}</b> 封单中。如需修改当日单据请先反日结（按角色权限控制，全程留痕）。
      </div>

      <!-- 第 1 步 连续性 -->
      <div class="card-box step-card">
        <div class="step-h">
          <span :class="['step-ic', step1?.passed ? 'st-pass' : 'st-fail']">{{ step1?.passed ? '✓' : '✕' }}</span>
          <b>① 连续性 / 首次期初基线（硬项）</b>
        </div>
        <ul v-if="(step1?.errors || []).length" class="err-list">
          <li v-for="(x, i) in step1.errors" :key="'e' + i" class="err-text">{{ x }}</li>
        </ul>
        <ul v-if="(step1?.warnings || []).length" class="warn-list">
          <li v-for="(x, i) in step1.warnings" :key="'w' + i" class="warn-text">{{ x }}</li>
        </ul>
        <table v-if="(step1?.openingPendingBills || []).length" class="data">
          <thead><tr><th>期初入库单号</th><th>仓库</th><th>单据日期</th></tr></thead>
          <tbody>
            <tr v-for="b in step1.openingPendingBills" :key="b.inboundNo">
              <td>{{ b.inboundNo }}</td><td>{{ b.warehouse }}</td><td>{{ day(b.billDate) }}</td>
            </tr>
          </tbody>
        </table>
        <label v-if="step1?.firstClose && !wizard?.closed" class="ck-line">
          <input type="checkbox" v-model="openingConfirmed">
          首次日结期初确认：已核对往来（应收/应付/预收预付）与资金账户期初基线，确认将 {{ wizard.date }} 及之前已生效业务一并封账
        </label>
      </div>

      <!-- 第 2 步 跨阶挂账单（提示） -->
      <div class="card-box step-card">
        <div class="step-h">
          <span :class="['step-ic', (step2?.count || 0) === 0 ? 'st-pass' : 'st-warn']">
            {{ (step2?.count || 0) === 0 ? '✓' : '!' }}
          </span>
          <b>② 跨阶挂账单（提示项，不阻断）</b>
          <span class="step-detail">{{ step2?.count || 0 }} 张；门店结算未交账 {{ step2?.storeSettlementPending || 0 }} 笔</span>
        </div>
        <table v-if="(step2?.items || []).length" class="data">
          <thead>
            <tr><th>类型</th><th>单据号</th><th>上游单号</th><th>往来单位</th><th>仓库</th>
            <th class="num">金额</th><th>上游日期</th><th>挂账天数</th></tr>
          </thead>
          <tbody>
            <tr v-for="(x, i) in step2.items" :key="i">
              <td><span class="tag tag-orange">{{ x.hangTypeName }}</span></td>
              <td>{{ x.billNo }}</td><td>{{ x.upstreamNo }}</td><td>{{ x.partyName }}</td>
              <td>{{ x.warehouse }}</td><td class="num">{{ money(x.billAmount) }}</td>
              <td>{{ x.upstreamDate }}</td><td>{{ x.hangingDays }}</td>
            </tr>
          </tbody>
        </table>
        <label v-if="(step2?.count || 0) > 0 && !wizard?.closed" class="ck-line">
          <input type="checkbox" v-model="acknowledgeHanging">
          已知悉上述跨阶挂账单（不纳入当日封单，将在其审核/签收日生效），确认继续
        </label>
      </div>

      <!-- 第 3 步 异常数据（提示） -->
      <div class="card-box step-card">
        <div class="step-h">
          <span :class="['step-ic', (step3?.count || 0) === 0 ? 'st-pass' : 'st-warn']">
            {{ (step3?.count || 0) === 0 ? '✓' : '!' }}
          </span>
          <b>③ 异常数据（提示项，不阻断）</b>
          <span class="step-detail">
            负库存 {{ (step3?.negativeStock || []).length }} ｜
            零/负成本 {{ (step3?.zeroCost || []).length }} ｜
            未匹配客户 {{ (step3?.unknownCustomers || []).length }} ｜
            未匹配供应商 {{ (step3?.unknownSuppliers || []).length }}
          </span>
        </div>
        <div class="anomaly-grid">
          <div v-if="(step3?.negativeStock || []).length">
            <div class="sub-title">负库存</div>
            <table class="data">
              <thead><tr><th>商品</th><th>仓库</th><th class="num">数量</th><th class="num">金额</th></tr></thead>
              <tbody>
                <tr v-for="(x, i) in step3.negativeStock" :key="'n' + i">
                  <td>{{ x.goodsCode }} {{ x.goodsName }}</td><td>{{ x.warehouse }}</td>
                  <td class="num">{{ money(x.qty) }}</td><td class="num">{{ money(x.amount) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="(step3?.zeroCost || []).length">
            <div class="sub-title">零/负成本</div>
            <table class="data">
              <thead><tr><th>商品</th><th>仓库</th><th class="num">数量</th><th class="num">金额</th></tr></thead>
              <tbody>
                <tr v-for="(x, i) in step3.zeroCost" :key="'z' + i">
                  <td>{{ x.goodsCode }} {{ x.goodsName }}</td><td>{{ x.warehouse }}</td>
                  <td class="num">{{ money(x.qty) }}</td><td class="num">{{ money(x.amount) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
        <div v-if="(step3?.unknownCustomers || []).length" class="chip-box">
          <span class="sub-title">未匹配客户：</span>
          <span v-for="(x, i) in step3.unknownCustomers" :key="'uc' + i" class="entry-chip">{{ x }}</span>
        </div>
        <div v-if="(step3?.unknownSuppliers || []).length" class="chip-box">
          <span class="sub-title">未匹配供应商：</span>
          <span v-for="(x, i) in step3.unknownSuppliers" :key="'us' + i" class="entry-chip">{{ x }}</span>
        </div>
        <label v-if="(step3?.count || 0) > 0 && !wizard?.closed" class="ck-line">
          <input type="checkbox" v-model="acknowledgeAnomaly">已知悉上述异常数据，确认继续日结
        </label>
      </div>

      <!-- 第 4 步 DWS 对账（硬项） -->
      <div class="card-box step-card">
        <div class="step-h">
          <span :class="['step-ic', step4?.passed ? 'st-pass' : 'st-fail']">{{ step4?.passed ? '✓' : '✕' }}</span>
          <b>④ DWS 预聚合对账（硬项）</b>
          <span class="step-detail">结账执行时自动刷新当日分区后再对账</span>
          <span v-if="step4?.error" class="err-text">{{ step4.error }}</span>
        </div>
        <table class="data">
          <thead><tr><th>数据集</th><th style="width:80px">结果</th><th>对账明细（不平差异）</th></tr></thead>
          <tbody>
            <tr v-for="(d, i) in step4?.details || []" :key="i">
              <td>{{ d.kind }}</td>
              <td><span :class="['tag', d.balanced ? 'tag-green' : 'tag-red']">{{ d.balanced ? '平衡' : '不平' }}</span></td>
              <td class="warn-text">{{ JSON.stringify(d.detail || {}) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 第 5 步 四套滚存勾稽（硬项） -->
      <div class="card-box step-card">
        <div class="step-h">
          <span :class="['step-ic', step5?.passed ? 'st-pass' : 'st-fail']">{{ step5?.passed ? '✓' : '✕' }}</span>
          <b>⑤ 库存 / 应收 / 应付 / 资金 滚存勾稽（硬项，容差 0.01）</b>
        </div>

        <div class="tie-title">库存收发存（数量容差 0.001）</div>
        <table class="data">
          <thead><tr><th>商品@仓库</th><th class="num">账面应有数量</th><th class="num">实盘数量</th>
          <th class="num">数量差</th><th class="num">应有金额</th><th class="num">实盘金额</th><th class="num">金额差</th></tr></thead>
          <tbody>
            <tr v-for="(x, i) in step5?.stock?.diffs || []" :key="'ts' + i">
              <td>{{ x.object }}</td><td class="num">{{ money(x.expectQty) }}</td><td class="num">{{ money(x.actualQty) }}</td>
              <td class="num" :class="{ 'err-text': Number(x.qtyDiff) !== 0 }">{{ money(x.qtyDiff) }}</td>
              <td class="num">{{ money(x.expectAmount) }}</td><td class="num">{{ money(x.actualAmount) }}</td>
              <td class="num" :class="{ 'err-text': Number(x.amountDiff) !== 0 }">{{ money(x.amountDiff) }}</td>
            </tr>
            <tr v-if="!(step5?.stock?.diffs || []).length"><td colspan="7" class="empty">全部平衡</td></tr>
          </tbody>
        </table>

        <div class="tie-block" v-for="tie in [
            { k: 'ar', name: '客户应收滚存' }, { k: 'ap', name: '供应商应付滚存' }]" :key="tie.k">
          <div class="tie-title">{{ tie.name }}（前日余额 + 当日立账 − 当日核销 = 当前余额）</div>
          <table class="data">
            <thead><tr><th>口径</th><th class="num">前日定版</th><th class="num">当日立账</th>
            <th class="num">当日核销</th><th class="num">当前余额</th><th class="num">差额</th></tr></thead>
            <tbody>
              <tr>
                <td>合计</td>
                <td class="num">{{ money(step5?.[tie.k]?.prior) }}</td>
                <td class="num">{{ money(step5?.[tie.k]?.added) }}</td>
                <td class="num">{{ money(step5?.[tie.k]?.settled) }}</td>
                <td class="num">{{ money(step5?.[tie.k]?.current) }}</td>
                <td class="num" :class="{ 'err-text': Number(step5?.[tie.k]?.diff) !== 0 }">{{ money(step5?.[tie.k]?.diff) }}</td>
              </tr>
              <tr v-for="(x, i) in step5?.[tie.k]?.diffs || []" :key="tie.k + i">
                <td>{{ x.tie }}</td><td class="num">{{ money(x.prior) }}</td><td class="num">{{ money(x.added) }}</td>
                <td class="num">{{ money(x.settled) }}</td><td class="num">{{ money(x.current) }}</td>
                <td class="num err-text">{{ money(x.diff) }}</td>
              </tr>
            </tbody>
          </table>
          <div v-if="tie.k === 'ar'" class="tie-advance">
            预收定版取客户账户：账户预收余额合计
            <b class="money">{{ money(step5?.ar?.advanceAccountTotal) }}</b>；
            负应收重分类兜底合计
            <b :class="Number(step5?.ar?.advanceReclassTotal) > 0 ? 'warn-text' : ''">{{ money(step5?.ar?.advanceReclassTotal) }}</b>
            <span class="muted">（正常数据应为 0）</span>
            <div v-for="(x, i) in step5?.ar?.advanceReclassDiffs || []" :key="'ra' + i" class="reclass-row">
              ⚠ {{ x.customer }} 负应收 {{ money(x.reclassAmount) }}（建议核对是否应转为客户预收）
            </div>
          </div>
          <div v-if="tie.k === 'ap'" class="tie-advance">
            预付/费用定版取供应商账户：账户预付余额（1123）合计
            <b class="money">{{ money(step5?.ap?.prepayAccountTotal) }}</b>；
            费用余额（1221 厂家费用）合计
            <b class="money">{{ money(step5?.ap?.expenseAccountTotal) }}</b>；
            2202 负应付重分类兜底合计
            <b :class="Number(step5?.ap?.prepayReclassTotal) > 0 ? 'warn-text' : ''">{{ money(step5?.ap?.prepayReclassTotal) }}</b>
            <span class="muted">（资产负债表预付=1123账户余额+2202负余额重分类，两口径不要求相等）</span>
            <div v-for="(x, i) in step5?.ap?.prepayReclassDiffs || []" :key="'rp' + i" class="reclass-row">
              ⚠ {{ x.supplier }} 负应付 {{ money(x.reclassAmount) }}（报表重分类为预付）
            </div>
            <div v-for="(x, i) in step5?.ap?.prepayDoubleCount || []" :key="'dp' + i" class="reclass-row double-count">
              ⚠ {{ x.supplier }} 同时有 2202 负应付 {{ money(x.reclassAmount) }} 和 1123 账户预付 {{ money(x.prepayAccountBalance) }}，
              请确认不是同一笔钱两边重复挂账
            </div>
          </div>
        </div>

        <div class="tie-title">资金滚存（{{ step5?.fund?.accountCount || 0 }} 个账户：上日余额 + 入 − 出 = 末笔余额）</div>
        <table class="data">
          <thead><tr><th>账户</th><th class="num">上日余额</th><th class="num">当日收入</th>
          <th class="num">当日支出</th><th class="num">应有余额</th><th class="num">流水末笔</th><th class="num">差额</th></tr></thead>
          <tbody>
            <tr v-for="(x, i) in step5?.fund?.diffs || []" :key="'tf' + i">
              <td>{{ x.object }}</td><td class="num">{{ money(x.open) }}</td><td class="num">{{ money(x.in) }}</td>
              <td class="num">{{ money(x.out) }}</td><td class="num">{{ money(x.expectClose) }}</td>
              <td class="num">{{ money(x.actualClose) }}</td>
              <td class="num" :class="{ 'err-text': Number(x.diff) !== 0 }">{{ money(x.diff) }}</td>
            </tr>
            <tr v-if="!(step5?.fund?.diffs || []).length"><td colspan="7" class="empty">全部平衡</td></tr>
          </tbody>
        </table>
      </div>

      <!-- 第 6 步 资金档案核对/现金实盘（仅备注） -->
      <div class="card-box step-card">
        <div class="step-h">
          <span class="step-ic st-skip">备</span>
          <b>⑥ 资金账户核对 / 现金实盘（仅备注说明，不阻断日结）</b>
        </div>
        <table v-if="(step6?.archiveDiffs || []).length" class="data">
          <thead><tr><th>账户</th><th class="num">流水末笔余额</th><th class="num">账户档案余额</th><th class="num">差额</th></tr></thead>
          <tbody>
            <tr v-for="(x, i) in step6.archiveDiffs" :key="'ad' + i">
              <td>{{ x.fundAccount }}</td><td class="num">{{ money(x.ledgerBalance) }}</td>
              <td class="num">{{ money(x.archiveBalance) }}</td>
              <td class="num" :class="{ 'warn-text': Number(x.diff) !== 0 }">{{ money(x.diff) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="tip">流水末笔余额与账户档案余额一致。</p>
        <table v-if="(step6?.cashAccounts || []).length" class="data">
          <thead><tr><th>现金账户</th><th class="num">账面余额</th><th style="width:200px">现金实盘数（备注）</th></tr></thead>
          <tbody>
            <tr v-for="c in step6.cashAccounts" :key="c.fundAccount">
              <td>{{ c.fundAccount }}</td><td class="num">{{ money(c.bookBalance) }}</td>
              <td><input type="number" step="0.01" v-model="cashInputs[c.fundAccount]"
                         :disabled="wizard?.closed" placeholder="实点现金金额" style="width:180px"></td>
            </tr>
          </tbody>
        </table>
        <p v-if="!(step6?.cashAccounts || []).length" class="tip">
          未配置现金类账户名单（系统参数 BIZ_CASH_ACCOUNT_NAMES，P0187），无实盘录入项。
        </p>
        <textarea v-model="fundRemark" :disabled="wizard?.closed" rows="3" class="full-input"
                  placeholder="资金情况说明（可填差异原因，随 RJ 单归档，不阻断日结）"></textarea>
      </div>

      <!-- 第 7 步 总账事件 -->
      <div class="card-box step-card">
        <div class="step-h">
          <span :class="['step-ic', (step7?.pendingCount || 0) === 0 ? 'st-pass' : 'st-warn']">
            {{ (step7?.pendingCount || 0) === 0 ? '✓' : '!' }}
          </span>
          <b>⑦ 总账待生成事件（提示项）</b>
          <span class="step-detail">待生成/生成失败事件 {{ step7?.pendingCount || 0 }} 条（本系统财务为业务服务，不阻断日结）</span>
        </div>
      </div>

      <!-- 当日关键合计 -->
      <div class="card-box">
        <div class="step-h"><b>当日关键合计（金额含税）</b></div>
        <div class="kpi-grid">
          <div><span>签收金额</span><b>{{ money(totals.salesSignedAmount) }}</b></div>
          <div><span>收款核销</span><b>{{ money(totals.receiptAmount) }}</b></div>
          <div><span>采购入库</span><b>{{ money(totals.purchaseAmount) }}</b></div>
          <div><span>付款</span><b>{{ money(totals.paymentAmount) }}</b></div>
          <div><span>入库成本</span><b>{{ money(totals.stockInAmount) }}</b></div>
          <div><span>出库成本</span><b>{{ money(totals.stockOutAmount) }}</b></div>
        </div>
      </div>
    </template>

    <!-- ================= ② 日结记录 ================= -->
    <template v-if="tab === 'records'">
      <div class="page-ops">
        <label>日期：</label>
        <input type="date" v-model="recFilter.from" style="width:140px"> 至
        <input type="date" v-model="recFilter.to" style="width:140px">
        <button class="btn primary" @click="searchRecords">查询</button>
        <span style="flex:1"></span>
        <button class="btn" v-permission="PERM_REOPEN" @click="openReopen(null, true)">批量反日结</button>
      </div>
      <div class="tablebox">
        <table class="data">
          <thead>
            <tr>
              <th>日结单号</th><th>封单日期</th>
              <th>DWS</th><th>滚存</th><th>资金档案</th>
              <th class="num">签收金额</th><th class="num">收款</th><th class="num">采购金额</th>
              <th class="num">付款</th><th class="num">入库成本</th><th class="num">出库成本</th>
              <th>挂账单</th><th>日结人</th><th>日结时间</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in records" :key="r.id">
              <td>{{ r.closeNo }}</td><td>{{ day(r.closeDate) }}</td>
              <td><span :class="['tag', r.dwsBalanced === 'Y' ? 'tag-green' : 'tag-red']">{{ r.dwsBalanced === 'Y' ? '平' : '不平' }}</span></td>
              <td><span :class="['tag', r.tieBalanced === 'Y' ? 'tag-green' : 'tag-red']">{{ r.tieBalanced === 'Y' ? '平' : '不平' }}</span></td>
              <td><span :class="['tag', r.fundBalanced === 'Y' ? 'tag-green' : 'tag-gray']">{{ r.fundBalanced === 'Y' ? '一致' : '有差异' }}</span></td>
              <td class="num">{{ money(r.salesSignedAmount) }}</td>
              <td class="num">{{ money(r.receiptAmount) }}</td>
              <td class="num">{{ money(r.purchaseAmount) }}</td>
              <td class="num">{{ money(r.paymentAmount) }}</td>
              <td class="num">{{ money(r.stockInAmount) }}</td>
              <td class="num">{{ money(r.stockOutAmount) }}</td>
              <td>{{ r.pendingBillCount }}</td><td>{{ r.closeName }}</td><td>{{ time(r.closeTime) }}</td>
              <td class="nowrap">
                <a class="lk" @click="printTicket(r.closeDate)">打印</a>
                <a v-if="i === 0" class="lk danger" v-permission="PERM_REOPEN" @click="openReopen(r, false)">反日结</a>
                <span v-else class="muted">仅封单日可单日反</span>
              </td>
            </tr>
            <tr v-if="!records.length"><td colspan="15" class="empty">暂无日结记录</td></tr>
          </tbody>
        </table>
      </div>
      <div class="pager">
        <button class="btn" :disabled="recPageNo <= 1" @click="recPageNo--; loadRecords()">上一页</button>
        <span>第 {{ recPageNo }} 页 / 共 {{ recTotal }} 条</span>
        <button class="btn" :disabled="recPageNo * recPageSize >= recTotal" @click="recPageNo++; loadRecords()">下一页</button>
      </div>
    </template>

    <!-- ================= ③ 定版台账 ================= -->
    <template v-if="tab === 'ledger'">
      <div class="page-ops">
        <div class="tabbar sub">
          <button :class="['tab-btn', { on: ledgerTab === 'ar' }]" @click="switchLedger('ar')">客户应收日余额</button>
          <button :class="['tab-btn', { on: ledgerTab === 'ap' }]" @click="switchLedger('ap')">供应商应付日余额</button>
          <button :class="['tab-btn', { on: ledgerTab === 'fund' }]" @click="switchLedger('fund')">资金账户日余额</button>
          <button :class="['tab-btn', { on: ledgerTab === 'goods' }]" @click="switchLedger('goods')">商品收发存日余额</button>
        </div>
      </div>
      <div class="page-ops">
        <label>封单日期：</label>
        <input type="date" v-model="ledgerFilters.from" style="width:140px"> 至
        <input type="date" v-model="ledgerFilters.to" style="width:140px">
        <input v-model="ledgerFilters.keyword" placeholder="编码/名称关键字" style="width:180px">
        <button class="btn primary" @click="searchLedger">查询</button>
      </div>
      <div class="tablebox">
        <table class="data">
          <thead>
            <tr>
              <th>封单日期</th>
              <th v-for="c in LEDGER_COLS[ledgerTab]" :key="c.p" :class="{ num: c.num }">{{ c.t }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in ledgerRows" :key="i" :class="{ 'row-neg': ledgerTab === 'goods' && r.negativeFlag === 'Y' }">
              <td>
                <template v-if="ledgerTab === 'goods'">{{ day(r.fromDate) }} ~ {{ day(r.toDate) }}</template>
                <template v-else>{{ day(r.closeDate) }}</template>
              </td>
              <td v-for="c in LEDGER_COLS[ledgerTab]" :key="c.p" :class="{ num: c.num }">
                <template v-if="c.tag">{{ c.tag[r[c.p]] || '' }}</template>
                <template v-else>{{ c.num ? money(r[c.p]) : (r[c.p] ?? '—') }}</template>
              </td>
            </tr>
            <tr v-if="!ledgerRows.length"><td :colSpan="LEDGER_COLS[ledgerTab].length + 1" class="empty">暂无定版数据</td></tr>
          </tbody>
        </table>
      </div>
      <div class="pager">
        <button class="btn" :disabled="ledgerPageNo <= 1" @click="ledgerPageNo--; loadLedger()">上一页</button>
        <span>第 {{ ledgerPageNo }} 页 / 共 {{ ledgerTotal }} 条</span>
        <button class="btn" :disabled="ledgerPageNo * ledgerPageSize >= ledgerTotal" @click="ledgerPageNo++; loadLedger()">下一页</button>
      </div>
    </template>

    <!-- ================= ④ 操作日志 ================= -->
    <template v-if="tab === 'logs'">
      <div class="page-ops">
        <label>日期：</label>
        <input type="date" v-model="logFilter.from" style="width:140px"> 至
        <input type="date" v-model="logFilter.to" style="width:140px">
        <select v-model="logFilter.action" style="width:120px">
          <option value="">全部动作</option>
          <option value="CLOSE">日结</option>
          <option value="REOPEN">反日结</option>
        </select>
        <button class="btn primary" @click="searchLogs">查询</button>
      </div>
      <div class="tablebox">
        <table class="data">
          <thead>
            <tr><th>封单日期</th><th>动作</th><th>结果</th><th>触发方式</th><th>操作人</th>
            <th>操作时间</th><th>原因</th><th>反结批次</th><th>详情</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in logs" :key="r.logId">
              <td>{{ day(r.closeDate) }}</td>
              <td><span class="tag tag-blue">{{ actionText(r.action) }}</span></td>
              <td><span :class="['tag', r.result === 'SUCCESS' ? 'tag-green' : 'tag-red']">{{ resultText(r.result) }}</span></td>
              <td>{{ triggerText(r.triggerType) }}</td>
              <td>{{ r.operatorName || '系统' }}</td><td>{{ time(r.operateTime) }}</td>
              <td class="reason-cell">{{ r.reason || '—' }}</td><td>{{ r.batchNo || '—' }}</td>
              <td class="detail-cell">{{ r.detail || '—' }}</td>
            </tr>
            <tr v-if="!logs.length"><td colspan="9" class="empty">暂无日志</td></tr>
          </tbody>
        </table>
      </div>
      <div class="pager">
        <button class="btn" :disabled="logPageNo <= 1" @click="logPageNo--; loadLogs()">上一页</button>
        <span>第 {{ logPageNo }} 页 / 共 {{ logTotal }} 条</span>
        <button class="btn" :disabled="logPageNo * logPageSize >= logTotal" @click="logPageNo++; loadLogs()">下一页</button>
      </div>
    </template>

    <!-- 反日结原因弹窗 -->
    <div v-if="reopenModal.open" class="modal-mask" @click.self="reopenModal.open = false">
      <div class="modal w520">
        <div class="modal-h">
          {{ reopenModal.batch ? '批量反日结（反结到指定日期，含该日）' : '反日结 ' + reopenModal.title }}
          <span class="modal-x" @click="reopenModal.open = false">×</span>
        </div>
        <div class="modal-b">
          <div v-if="reopenModal.batch" class="mb8">
            <label>反结至日期：</label>
            <input type="date" v-model="reopenModal.toDate" style="width:160px">
            <p class="tip">将从当前封单日逐日反结至该日期（含），每日删除定版台账与快照，全部写同一批次日志。</p>
          </div>
          <p class="tip">反日结原因（必填 ≥2 字，将记入操作日志）：</p>
          <textarea v-model="reopenModal.reason" rows="4" class="full-input"
                    placeholder="例如：发现当日一张收款单记账日期录错，需反结修改后重新日结"></textarea>
          <div style="text-align:right;margin-top:12px">
            <button class="btn" @click="reopenModal.open = false">取消</button>
            <button class="btn primary" v-permission="PERM_REOPEN" :disabled="busy" @click="submitReopen">确认反日结</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.muted { color: #888; font-size: 13px; }
.mb8 { margin-bottom: 8px; }
.nowrap { white-space: nowrap; }
.tabbar { display: flex; gap: 4px; }
.tab-btn {
  border: 1px solid #d9d9d9; background: #fafafa; padding: 6px 16px; cursor: pointer;
  border-radius: 4px 4px 0 0; font-size: 13px;
}
.tab-btn.on { background: var(--primary, #1d6fd1); color: #fff; border-color: var(--primary, #1d6fd1); }
.tabbar.sub .tab-btn { padding: 4px 12px; font-size: 12px; border-radius: 4px; }
.ok-box { border-left: 4px solid #1a9e54; margin-bottom: 12px; }
.step-card { margin-bottom: 12px; padding: 12px 16px; }
.step-h { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; flex-wrap: wrap; }
.step-h b { font-size: 14px; }
.step-detail { color: #555; font-size: 13px; }
.step-ic {
  display: inline-flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; border-radius: 50%; font-size: 13px; font-weight: 700; flex: none;
}
.st-pass { background: #e8f8ee; color: #1a9e54; }
.st-warn { background: #fdf2e3; color: #d08010; }
.st-fail { background: #fdeaea; color: #d33; }
.st-skip { background: #e8f1fd; color: #1d6fd1; }
.tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 12px; }
.tag-blue { background: #e8f1fd; color: #1d6fd1; }
.tag-green { background: #e8f8ee; color: #1a9e54; }
.tag-gray { background: #f0f0f0; color: #888; }
.tag-red { background: #fdeaea; color: #d33; }
.tag-orange { background: #fdf2e3; color: #d08010; }
.err-text { color: #d33; }
.warn-text { color: #b07010; font-size: 12px; word-break: break-all; }
.err-list { margin: 4px 0 4px 18px; color: #d33; font-size: 13px; }
.warn-list { margin: 4px 0 4px 18px; font-size: 13px; }
.ck-line { display: block; margin-top: 8px; font-size: 13px; color: #444; }
.tip { color: #888; font-size: 12px; margin: 6px 0; }
.anomaly-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.sub-title { font-weight: 700; font-size: 13px; margin: 6px 0; }
.chip-box { margin-top: 6px; font-size: 12px; }
.entry-chip {
  display: inline-block; margin: 2px 8px 2px 0; padding: 1px 8px;
  background: #fafbfc; border: 1px solid #eee; border-radius: 10px; color: #444;
}
.tie-title { font-weight: 700; font-size: 13px; margin: 10px 0 4px; }
.tie-block { margin-top: 6px; }
.tie-advance { margin: 6px 0 0; font-size: 12px; color: #555; }
.tie-advance .money { color: #cf1322; }
.tie-advance .reclass-row { color: #b07010; margin-top: 2px; }
.tie-advance .reclass-row.double-count { color: #cf1322; }
.full-input { width: 100%; box-sizing: border-box; }
.kpi-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 8px; }
.kpi-grid div { background: #fafbfc; border-radius: 4px; padding: 8px 10px; text-align: center; }
.kpi-grid span { display: block; color: #888; font-size: 12px; }
.kpi-grid b { font-size: 15px; color: #1d6fd1; }
.num { text-align: right; }
.empty { text-align: center; color: #999; padding: 14px; }
.row-neg td { color: #d33; }
.lk { color: #1d6fd1; cursor: pointer; margin-right: 10px; font-size: 13px; }
.lk.danger { color: #d33; }
.reason-cell { max-width: 180px; font-size: 12px; color: #555; }
.detail-cell { max-width: 260px; font-size: 12px; color: #555; }
</style>
