<script setup>
/**
 * 总账 M2——会计凭证
 * 状态机：草稿 → 已审核（生成凭证号）→ 已过账（入账簿）；草稿/已审核可作废；已过账红冲。
 * 凭证号「字-yyyyMM-4 位」审核时生成，作废/红冲占号不补。
 */
import { ref, computed, onMounted } from 'vue'
import { post, downloadBlob, saveBlobFile } from '../../api/client.js'

const AUX_DIMS = [
  { v: 'customer', l: '客户', key: 'customers' },
  { v: 'supplier', l: '供应商', key: 'suppliers' },
  { v: 'department', l: '部门', key: 'departments' },
  { v: 'employee', l: '员工', key: 'employees' },
  { v: 'goods', l: '商品', key: 'goods' },
  { v: 'project', l: '项目', key: 'projects' },
  { v: 'area', l: '片区', key: null }, // 片区为自由文本
]
const auxField = dim => 'aux' + dim.charAt(0).toUpperCase() + dim.slice(1)

const list = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = 20
const loading = ref(false)
const feedback = ref('')

const filters = ref({ period: '', voucherWord: '', status: '', source: '', dateFrom: '', dateTo: '', keyword: '' })

const STATUS_OPTS = ['草稿', '已审核', '已过账', '已作废', '已冲销']
const WORD_OPTS = ['记', '收', '付', '转']

const leafAccounts = ref([])
const accountMap = computed(() => Object.fromEntries(leafAccounts.value.map(a => [a.accountCode, a])))
const auxOptions = ref({ customers: [], suppliers: [], departments: [], employees: [], goods: [], projects: [], cashFlowItems: [] })

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 4000)
}
function money(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function periodOfMonth(v) {
  return v ? v.replace('-', '') : ''
}

async function load() {
  loading.value = true
  try {
    const body = {
      pageNo: pageNo.value, pageSize,
      period: periodOfMonth(filters.value.period),
      voucherWord: filters.value.voucherWord,
      status: filters.value.status,
      source: filters.value.source,
      dateFrom: filters.value.dateFrom,
      dateTo: filters.value.dateTo,
      keyword: filters.value.keyword,
    }
    const res = await post('/finance/gl/voucher/page', body)
    list.value = res.records || []
    total.value = res.total || 0
  } catch (e) { show('凭证加载失败：' + (e?.message || e), 'err') }
  finally { loading.value = false }
}
function search() { pageNo.value = 1; load() }

async function loadMeta() {
  try {
    leafAccounts.value = (await post('/finance/gl/account/leaf-options', {})) || []
    auxOptions.value = await post('/finance/gl/voucher/aux-options')
  } catch (e) { show('基础档案加载失败：' + (e?.message || e), 'err') }
}

// ================= 凭证编辑/查看 =================
const drawer = ref({ open: false, readonly: false, title: '' })
const form = ref(blankVoucher())
function blankVoucher() {
  return {
    id: '', voucherNo: '', voucherWord: '记',
    voucherDate: new Date().toISOString().slice(0, 10),
    attachments: 0, summary: '', status: '草稿', isRed: false,
    entries: [blankEntry(), blankEntry()],
  }
}
function blankEntry() {
  return {
    summary: '', accountCode: '', debitAmount: '', creditAmount: '', qty: '', price: '',
    auxCustomer: '', auxSupplier: '', auxDepartment: '', auxEmployee: '',
    auxGoods: '', auxProject: '', auxArea: '', cashFlowItem: '',
  }
}

function dimsOf(entry) {
  const acc = accountMap.value[entry.accountCode]
  return acc && acc.auxDimensions ? acc.auxDimensions.split(',') : []
}
function isQty(entry) {
  return !!accountMap.value[entry.accountCode]?.isQty
}
function isCash(entry) {
  return !!accountMap.value[entry.accountCode]?.isCash
}
function onAccountChange(entry) {
  // 切换科目后清空与新科目维度不符的辅助值
  const dims = dimsOf(entry)
  for (const d of AUX_DIMS) {
    if (!dims.includes(d.v)) entry[auxField(d.v)] = ''
  }
  if (!isQty(entry)) { entry.qty = ''; entry.price = '' }
  if (!isCash(entry)) entry.cashFlowItem = ''
}
function onAmount(entry, side) {
  // 借贷互斥：输借方清贷方，反之亦然
  if (side === 'debit' && Number(entry.debitAmount) > 0) entry.creditAmount = ''
  if (side === 'credit' && Number(entry.creditAmount) > 0) entry.debitAmount = ''
}
function addEntry() { form.value.entries.push(blankEntry()) }
function removeEntry(i) {
  if (form.value.entries.length <= 2) return show('凭证至少保留 2 条分录', 'err')
  form.value.entries.splice(i, 1)
}
const totals = computed(() => {
  let d = 0, c = 0
  for (const e of form.value.entries) { d += Number(e.debitAmount) || 0; c += Number(e.creditAmount) || 0 }
  return { debit: d, credit: c, diff: d - c }
})

function openCreate() {
  form.value = blankVoucher()
  drawer.value = { open: true, readonly: false, title: '新增凭证（草稿）' }
}

/** 打印：新窗口打开独立打印页（自带打印样式，onMounted 自动调起打印）。 */
function printVoucher(row) {
  window.open('/gl-voucher-print?id=' + encodeURIComponent(row.id), '_blank')
}
async function openEdit(row, readonly) {
  try {
    const detail = await post('/finance/gl/voucher/detail', { id: row.id })
    form.value = {
      id: detail.id, voucherNo: detail.voucherNo || '', voucherWord: detail.voucherWord || '记',
      voucherDate: String(detail.voucherDate || '').slice(0, 10),
      attachments: detail.attachments || 0, summary: detail.summary || '',
      status: detail.status, isRed: !!detail.isRed,
      entries: (detail.entries || []).map(e => ({
        summary: e.summary || '', accountCode: e.accountCode,
        debitAmount: Number(e.debitAmount) || '', creditAmount: Number(e.creditAmount) || '',
        qty: Number(e.qty) || '', price: Number(e.price) || '',
        auxCustomer: e.auxCustomer || '', auxSupplier: e.auxSupplier || '',
        auxDepartment: e.auxDepartment || '', auxEmployee: e.auxEmployee || '',
        auxGoods: e.auxGoods || '', auxProject: e.auxProject || '', auxArea: e.auxArea || '',
        cashFlowItem: e.cashFlowItem || '',
      })),
    }
    drawer.value = {
      open: true, readonly,
      title: readonly ? `查看凭证 ${detail.voucherNo || '（草稿）'}` : `编辑凭证 ${detail.voucherNo || '（草稿）'}`,
    }
  } catch (e) { show('凭证加载失败：' + (e?.message || e), 'err') }
}

async function onSave() {
  if (!form.value.voucherDate) return show('请选择凭证日期', 'err')
  const body = {
    id: form.value.id || undefined,
    voucherWord: form.value.voucherWord,
    voucherDate: form.value.voucherDate,
    attachments: Number(form.value.attachments) || 0,
    summary: form.value.summary,
    entries: form.value.entries.map(e => ({
      summary: e.summary, accountCode: e.accountCode,
      debitAmount: e.debitAmount === '' ? 0 : e.debitAmount,
      creditAmount: e.creditAmount === '' ? 0 : e.creditAmount,
      qty: e.qty === '' ? 0 : e.qty, price: e.price === '' ? 0 : e.price,
      auxCustomer: e.auxCustomer, auxSupplier: e.auxSupplier, auxDepartment: e.auxDepartment,
      auxEmployee: e.auxEmployee, auxGoods: e.auxGoods, auxProject: e.auxProject, auxArea: e.auxArea,
      cashFlowItem: e.cashFlowItem,
    })),
  }
  try {
    await post('/finance/gl/voucher/save', body)
    show('草稿已保存')
    drawer.value.open = false
    load()
  } catch (e) { show('保存失败：' + (e?.message || e), 'err') }
}

// ================= 状态流转 =================
async function doAction(row, action, label, body = {}) {
  try {
    await post('/finance/gl/voucher/' + action, { id: row.id, ...body })
    show(label + '成功')
    load()
  } catch (e) { show(label + '失败：' + (e?.message || e), 'err') }
}
function onAudit(row) { if (confirm(`确认审核凭证 ${row.voucherNo || '（草稿）'}？审核后生成凭证号。`)) doAction(row, 'audit', '审核') }
function onUnaudit(row) { if (confirm(`确认反审核凭证 ${row.voucherNo}？反审核后凭证号作废。`)) doAction(row, 'unaudit', '反审核') }
function onPost(row) { if (confirm(`确认过账凭证 ${row.voucherNo}？过账后进入账簿。`)) doAction(row, 'post', '过账') }
function onVoid(row) {
  const reason = prompt(`作废凭证 ${row.voucherNo || '（草稿）'}，可填写作废原因（留空跳过）：`, '')
  if (reason === null) return
  doAction(row, 'void', '作废', { reason })
}
async function onRedReverse(row) {
  if (!confirm(`确认红冲凭证 ${row.voucherNo}？将生成金额为负的红字凭证并直接过账，原凭证标记「已冲销」。`)) return
  try {
    const res = await post('/finance/gl/voucher/red-reverse', { id: row.id })
    show(`红冲成功，红字凭证号：${res.voucherNo}`)
    load()
  } catch (e) { show('红冲失败：' + (e?.message || e), 'err') }
}

function statusClass(s) {
  return { 草稿: 'warn', 已审核: 'info', 已过账: 'ok', 已作废: 'muted', 已冲销: 'danger' }[s] || 'warn'
}

// ================= 导出（金蝶 KIS / 用友 T+ CSV） =================
const expDlg = ref({ open: false, busy: false, format: 'KINGDEE', periodFrom: '', periodTo: '', voucherWord: '' })
const logsDlg = ref({ open: false, logs: [] })

function openExport() {
  // 默认带上列表筛选的期间
  expDlg.value = {
    open: true, busy: false, format: 'KINGDEE',
    periodFrom: filters.value.period || '', periodTo: filters.value.period || '',
    voucherWord: filters.value.voucherWord || '',
  }
}
async function doExport() {
  if (!expDlg.value.periodFrom || !expDlg.value.periodTo) return show('请选择导出期间范围', 'err')
  const from = periodOfMonth(expDlg.value.periodFrom)
  const to = periodOfMonth(expDlg.value.periodTo)
  if (from > to) return show('起始期间不能晚于截止期间', 'err')
  expDlg.value.busy = true
  try {
    const blob = await downloadBlob('/finance/gl/export/csv', {
      format: expDlg.value.format, periodFrom: from, periodTo: to,
      voucherWord: expDlg.value.voucherWord || '',
    })
    // 业务异常时后端返回 JSON（ApiResponse），文件流才是 CSV
    if (blob.type && blob.type.indexOf('application/json') >= 0) {
      let msg = '导出失败'
      try { msg = JSON.parse(await blob.text()).message || msg } catch { /* ignore */ }
      throw new Error(msg)
    }
    const sysName = expDlg.value.format === 'KINGDEE' ? '金蝶KIS' : '用友T+'
    saveBlobFile(`凭证导出-${sysName}-${from}-${to}.csv`, blob)
    show('导出成功（仅含已过账凭证，凭证已标记导出）')
    expDlg.value.open = false
    load()
  } catch (e) { show('导出失败：' + (e?.message || e), 'err') }
  finally { expDlg.value.busy = false }
}
async function openLogs() {
  logsDlg.value.open = true
  try {
    logsDlg.value.logs = await post('/finance/gl/export/logs', {})
  } catch (e) { show('导出日志加载失败：' + (e?.message || e), 'err') }
}
function fmtTime(t) { return String(t || '').replace('T', ' ').slice(0, 19) }

onMounted(() => { loadMeta(); load() })
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <b>会计凭证</b>
      <input type="month" v-model="filters.period" title="期间" />
      <select v-model="filters.voucherWord">
        <option value="">全部字</option>
        <option v-for="w in WORD_OPTS" :key="w" :value="w">{{ w }}字</option>
      </select>
      <select v-model="filters.status">
        <option value="">全部状态</option>
        <option v-for="s in STATUS_OPTS" :key="s" :value="s">{{ s }}</option>
      </select>
      <select v-model="filters.source">
        <option value="">全部来源</option>
        <option value="手工">手工</option>
        <option value="自动">自动</option>
      </select>
      <input type="date" v-model="filters.dateFrom" title="开始日期" />
      <span class="range-sep">至</span>
      <input type="date" v-model="filters.dateTo" title="截止日期" />
      <input v-model="filters.keyword" placeholder="凭证号/摘要/单据号" class="kw" />
      <button class="btn" @click="search">查询</button>
      <div class="spacer"></div>
      <button class="btn" @click="load">刷新</button>
      <button class="btn" @click="openExport">导出 CSV</button>
      <button class="btn" @click="openLogs">导出日志</button>
      <button class="btn primary" @click="openCreate">＋ 新增凭证</button>
    </div>
    <div v-if="feedback" class="toast-inline" :class="feedback.level">{{ feedback.msg }}</div>

    <div class="card-box">
      <table class="data">
        <thead>
          <tr>
            <th style="width:150px">凭证号</th><th style="width:100px">日期</th><th style="width:44px">字</th>
            <th>摘要</th><th style="width:60px">来源</th><th style="width:48px">分录</th>
            <th style="width:110px" class="num">借方合计</th><th style="width:110px" class="num">贷方合计</th>
            <th style="width:76px">状态</th><th style="width:130px">制单/审核</th>
            <th class="ops" style="width:210px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="v in list" :key="v.id" :class="{ voided: v.status === '已作废', reversed: v.status === '已冲销' }">
            <td>
              <a class="vno" @click="openEdit(v, true)">{{ v.voucherNo || '（草稿）' }}</a>
              <span v-if="v.isRed" class="red-tag">红字</span>
            </td>
            <td>{{ String(v.voucherDate || '').slice(0, 10) }}</td>
            <td>{{ v.voucherWord }}</td>
            <td class="summary-cell">{{ v.summary || '—' }}</td>
            <td><span class="src-tag" :class="v.source === '自动' ? 'auto' : ''">{{ v.source }}</span></td>
            <td class="num">{{ v.entryCount }}</td>
            <td class="num">{{ money(v.debitTotal) }}</td>
            <td class="num">{{ money(v.creditTotal) }}</td>
            <td><span class="tag" :class="statusClass(v.status)">{{ v.status }}</span></td>
            <td class="muted small">{{ v.makerName || '—' }}<br />{{ v.auditorName || '' }}</td>
            <td class="ops">
              <a v-if="v.status === '草稿'" @click="openEdit(v, false)">编辑</a>
              <a v-if="v.status !== '草稿'" @click="openEdit(v, true)">查看</a>
              <a @click="printVoucher(v)">打印</a>
              <a v-if="v.status === '草稿'" @click="onAudit(v)">审核</a>
              <a v-if="v.status === '已审核'" @click="onUnaudit(v)">反审核</a>
              <a v-if="v.status === '已审核'" @click="onPost(v)">过账</a>
              <a v-if="v.status === '草稿' || v.status === '已审核'" class="danger" @click="onVoid(v)">作废</a>
              <a v-if="v.status === '已过账' && !v.isRed" class="danger" @click="onRedReverse(v)">红冲</a>
            </td>
          </tr>
          <tr v-if="!list.length"><td colspan="11" class="empty">{{ loading ? '加载中...' : '暂无凭证' }}</td></tr>
        </tbody>
      </table>
      <div class="pager">
        <span class="muted">共 {{ total }} 条</span>
        <div class="spacer"></div>
        <button class="btn" :disabled="pageNo <= 1" @click="pageNo--; load()">上一页</button>
        <span class="page-no">第 {{ pageNo }} 页</span>
        <button class="btn" :disabled="pageNo * pageSize >= total" @click="pageNo++; load()">下一页</button>
      </div>
    </div>

    <!-- 凭证编辑/查看弹窗 -->
    <div v-if="drawer.open" class="modal-mask" @click.self="drawer.open=false">
      <div class="modal w1100">
        <div class="modal-h">
          {{ drawer.title }}
          <span class="x" @click="drawer.open=false">×</span>
        </div>
        <div class="modal-b">
          <div class="v-head">
            <label>凭证字
              <select v-model="form.voucherWord" :disabled="drawer.readonly">
                <option v-for="w in WORD_OPTS" :key="w" :value="w">{{ w }}字</option>
              </select>
            </label>
            <label>凭证日期 *
              <input type="date" v-model="form.voucherDate" :disabled="drawer.readonly" />
            </label>
            <label>附件张数
              <input type="number" min="0" v-model="form.attachments" :disabled="drawer.readonly" />
            </label>
            <label>凭证号
              <input :value="form.voucherNo || '审核后生成'" disabled />
            </label>
            <label class="head-summary">凭证摘要
              <input v-model="form.summary" placeholder="可留空，默认取首条分录摘要" :disabled="drawer.readonly" />
            </label>
          </div>

          <table class="entry-table">
            <thead>
              <tr>
                <th style="width:36px">#</th>
                <th style="width:150px">摘要</th>
                <th style="width:220px">会计科目</th>
                <th style="width:110px">借方金额</th>
                <th style="width:110px">贷方金额</th>
                <th style="width:80px">数量</th>
                <th style="width:90px">单价</th>
                <th>辅助核算 / 现金流量</th>
                <th v-if="!drawer.readonly" style="width:50px"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(e, i) in form.entries" :key="i">
                <td class="num muted">{{ i + 1 }}</td>
                <td><input v-model="e.summary" :disabled="drawer.readonly" placeholder="默认取凭证摘要" /></td>
                <td>
                  <select v-if="!drawer.readonly" v-model="e.accountCode" @change="onAccountChange(e)">
                    <option value="">请选择末级科目</option>
                    <option v-for="a in leafAccounts" :key="a.accountCode" :value="a.accountCode">
                      {{ a.accountCode }} {{ a.accountName }}
                    </option>
                  </select>
                  <span v-else class="code-text">{{ e.accountCode }}</span>
                </td>
                <td><input type="number" step="0.01" min="0" v-model="e.debitAmount"
                           :disabled="drawer.readonly || Number(e.creditAmount) > 0"
                           @input="onAmount(e, 'debit')" :class="{ redline: form.isRed }" /></td>
                <td><input type="number" step="0.01" min="0" v-model="e.creditAmount"
                           :disabled="drawer.readonly || Number(e.debitAmount) > 0"
                           @input="onAmount(e, 'credit')" :class="{ redline: form.isRed }" /></td>
                <td><input type="number" step="0.0001" min="0" v-model="e.qty" :disabled="drawer.readonly || !isQty(e)" /></td>
                <td><input type="number" step="0.000001" min="0" v-model="e.price" :disabled="drawer.readonly || !isQty(e)" /></td>
                <td class="aux-cell">
                  <div v-for="d in AUX_DIMS.filter(d => dimsOf(e).includes(d.v))" :key="d.v" class="aux-item">
                    <span class="aux-label">{{ d.l }}</span>
                    <select v-if="d.key" v-model="e[auxField(d.v)]" :disabled="drawer.readonly">
                      <option value="">请选择</option>
                      <option v-for="o in (auxOptions[d.key] || [])" :key="o.code" :value="o.code">{{ o.name }}</option>
                    </select>
                    <input v-else v-model="e[auxField(d.v)]" :disabled="drawer.readonly" placeholder="填写片区" />
                  </div>
                  <div v-if="isCash(e)" class="aux-item">
                    <span class="aux-label">现金流</span>
                    <select v-model="e.cashFlowItem" :disabled="drawer.readonly">
                      <option value="">未指定</option>
                      <optgroup v-for="cat in ['经营','投资','筹资']" :key="cat" :label="cat + '活动'">
                        <option v-for="cf in auxOptions.cashFlowItems.filter(x => x.category === cat && (e.debitAmount > 0 ? x.direction === '流入' : x.direction === '流出'))"
                                :key="cf.code" :value="cf.code">{{ cf.name }}</option>
                      </optgroup>
                    </select>
                  </div>
                </td>
                <td v-if="!drawer.readonly">
                  <a class="danger del-link" @click="removeEntry(i)">删行</a>
                </td>
              </tr>
            </tbody>
            <tfoot>
              <tr>
                <td colspan="3" class="num total-label">合计</td>
                <td class="num total-amt" :class="{ unbalanced: totals.diff !== 0 }">{{ money(totals.debit) }}</td>
                <td class="num total-amt" :class="{ unbalanced: totals.diff !== 0 }">{{ money(totals.credit) }}</td>
                <td colspan="4" class="muted diff-cell">
                  <span v-if="totals.diff === 0" class="balanced">借贷平衡 ✓</span>
                  <span v-else class="unbalanced">差额 {{ money(Math.abs(totals.diff)) }}，不平 ✗</span>
                  <button v-if="!drawer.readonly" class="btn small add-btn" @click="addEntry">＋ 增行</button>
                </td>
              </tr>
            </tfoot>
          </table>
          <div v-if="form.isRed" class="red-note">本凭证为红字冲销凭证，金额以负数入账。</div>
        </div>
        <div class="modal-f">
          <button class="btn" @click="drawer.open=false">{{ drawer.readonly ? '关闭' : '取消' }}</button>
          <button v-if="!drawer.readonly" class="btn primary" @click="onSave">保存草稿</button>
        </div>
      </div>
    </div>

    <!-- 导出对话框 -->
    <div v-if="expDlg.open" class="modal-mask" @click.self="expDlg.open=false">
      <div class="modal">
        <div class="modal-h">凭证导出（财务软件导入）<span class="x" @click="expDlg.open=false">×</span></div>
        <div class="modal-b">
          <div class="exp-tip">
            仅导出<b>已过账</b>凭证，一行一条分录摊平为 CSV（UTF-8 BOM，Excel 可直接打开）。
            导出后凭证打「已导出」标记并写导出日志；重复导出不会重复打标。
          </div>
          <div class="exp-form">
            <label>导出格式
              <select v-model="expDlg.format">
                <option value="KINGDEE">金蝶 KIS</option>
                <option value="YONYOU">用友 T+ / U8</option>
              </select>
            </label>
            <label>起始期间
              <input type="month" v-model="expDlg.periodFrom" />
            </label>
            <label>截止期间
              <input type="month" v-model="expDlg.periodTo" />
            </label>
            <label>凭证字
              <select v-model="expDlg.voucherWord">
                <option value="">全部字</option>
                <option v-for="w in WORD_OPTS" :key="w" :value="w">{{ w }}字</option>
              </select>
            </label>
          </div>
        </div>
        <div class="modal-f">
          <button class="btn" @click="expDlg.open=false">取消</button>
          <button class="btn primary" :disabled="expDlg.busy" @click="doExport">{{ expDlg.busy ? '导出中...' : '导出下载' }}</button>
        </div>
      </div>
    </div>

    <!-- 导出日志对话框 -->
    <div v-if="logsDlg.open" class="modal-mask" @click.self="logsDlg.open=false">
      <div class="modal w860">
        <div class="modal-h">导出日志<span class="x" @click="logsDlg.open=false">×</span></div>
        <div class="modal-b">
          <table class="data">
            <thead>
              <tr>
                <th style="width:150px">导出时间</th><th style="width:90px">格式</th>
                <th style="width:140px">期间范围</th><th style="width:44px">字</th>
                <th>文件名</th><th style="width:70px" class="num">凭证数</th><th style="width:70px" class="num">分录数</th>
                <th style="width:90px">操作人</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="l in logsDlg.logs" :key="l.id">
                <td class="muted small">{{ fmtTime(l.createTime) }}</td>
                <td>{{ l.exportFormat === 'KINGDEE' ? '金蝶KIS' : '用友T+' }}</td>
                <td>{{ l.periodFrom }} ~ {{ l.periodTo }}</td>
                <td>{{ l.voucherWord || '全' }}</td>
                <td class="small">{{ l.fileName }}</td>
                <td class="num">{{ l.voucherCount }}</td>
                <td class="num">{{ l.entryCount }}</td>
                <td class="small">{{ l.creator }}</td>
              </tr>
              <tr v-if="!logsDlg.logs.length"><td colspan="8" class="empty">暂无导出记录</td></tr>
            </tbody>
          </table>
        </div>
        <div class="modal-f">
          <button class="btn" @click="logsDlg.open=false">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.spacer { flex: 1; }
.page-ops select, .page-ops input { padding: 5px 8px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 13px; }
.page-ops input.kw { width: 160px; }
.range-sep { color: #999; font-size: 12px; }
.card-box { background: #fff; border: 1px solid #f0f0f0; border-radius: 8px; overflow: auto; margin-top: 10px; }
.data { width: 100%; border-collapse: collapse; font-size: 13px; }
.data th, .data td { padding: 7px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; white-space: nowrap; }
.data th { background: #fafafa; font-weight: 600; color: #555; }
.num { text-align: right; font-variant-numeric: tabular-nums; }
.data tr.voided { color: #bbb; text-decoration: line-through; }
.data tr.reversed { color: #cf1322; }
.vno { color: #1677ff; cursor: pointer; font-family: monospace; }
.red-tag { display: inline-block; font-size: 10px; background: #fff1f0; color: #cf1322; border: 1px solid #ffa39e; border-radius: 8px; padding: 0 6px; margin-left: 4px; }
.summary-cell { max-width: 260px; overflow: hidden; text-overflow: ellipsis; }
.src-tag { font-size: 11px; background: #f5f5f5; border-radius: 8px; padding: 1px 8px; color: #666; }
.src-tag.auto { background: #f0f5ff; color: #1677ff; }
.tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; }
.tag.ok { background: #f6ffed; color: #389e0d; }
.tag.info { background: #e6f4ff; color: #1677ff; }
.tag.warn { background: #fffbe6; color: #d48806; }
.tag.muted { background: #f5f5f5; color: #999; }
.tag.danger { background: #fff1f0; color: #cf1322; }
.muted { color: #999; }
.small { font-size: 12px; }
.ops { white-space: nowrap; text-align: right; }
.ops a { color: #1677ff; cursor: pointer; margin-left: 10px; font-size: 12px; }
.ops a.danger, .del-link.danger { color: #cf1322; }
.empty { text-align: center; color: #bbb; padding: 30px; font-size: 13px; }
.pager { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-top: 1px solid #f0f0f0; }
.pager .btn { padding: 3px 12px; }
.page-no { font-size: 13px; color: #666; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 10px; width: 560px; max-width: 96vw; max-height: 92vh; overflow: auto; }
.modal.w1100 { width: 1100px; }
.modal.w860 { width: 860px; }
.exp-tip { background: #f7faff; border: 1px solid #d6e4ff; border-radius: 6px; padding: 8px 12px; font-size: 12px; color: #555; line-height: 1.7; margin-bottom: 14px; }
.exp-form { display: flex; flex-wrap: wrap; gap: 12px 20px; }
.exp-form label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #777; }
.exp-form select, .exp-form input { padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 13px; min-width: 150px; }
.modal-h { padding: 14px 18px; font-weight: 700; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; position: sticky; top: 0; background: #fff; z-index: 2; }
.modal-h .x { cursor: pointer; color: #999; }
.modal-b { padding: 16px 18px; }
.modal-f { padding: 12px 18px; border-top: 1px solid #f0f0f0; text-align: right; position: sticky; bottom: 0; background: #fff; }
.modal-f .btn { margin-left: 8px; }
.v-head { display: flex; flex-wrap: wrap; gap: 12px 20px; margin-bottom: 14px; }
.v-head label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #777; }
.v-head label.head-summary { flex: 1; min-width: 240px; }
.v-head select, .v-head input { padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 13px; min-width: 130px; }
.v-head input:disabled { background: #f5f5f5; color: #999; }
.entry-table { width: 100%; border-collapse: collapse; font-size: 13px; border: 1px solid #f0f0f0; }
.entry-table th { background: #fafafa; padding: 7px 8px; font-weight: 600; color: #555; border-bottom: 1px solid #f0f0f0; text-align: left; white-space: nowrap; }
.entry-table td { padding: 5px 8px; border-bottom: 1px solid #f5f5f5; vertical-align: top; }
.entry-table input, .entry-table select { width: 100%; padding: 5px 8px; border: 1px solid #d9d9d9; border-radius: 5px; font-size: 12px; box-sizing: border-box; }
.entry-table input:disabled, .entry-table select:disabled { background: #fafafa; color: #666; }
.entry-table input.redline { color: #cf1322; }
.code-text { font-family: monospace; font-size: 12px; color: #333; }
.aux-cell { min-width: 200px; }
.aux-item { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
.aux-label { font-size: 11px; color: #999; width: 34px; flex-shrink: 0; text-align: right; }
.aux-item select, .aux-item input { font-size: 12px; }
.del-link { cursor: pointer; font-size: 12px; white-space: nowrap; }
.total-label { font-weight: 600; }
.total-amt { font-weight: 700; font-size: 14px; }
.total-amt.unbalanced { color: #cf1322; }
.diff-cell { white-space: nowrap; }
.balanced { color: #389e0d; font-size: 12px; margin-right: 12px; }
.unbalanced { color: #cf1322; font-size: 12px; margin-right: 12px; }
.btn.small { padding: 3px 10px; font-size: 12px; }
.add-btn { float: right; }
.red-note { margin-top: 10px; color: #cf1322; font-size: 12px; background: #fff1f0; border: 1px solid #ffa39e; border-radius: 6px; padding: 6px 10px; }
.toast-inline { padding: 8px 12px; border-radius: 6px; margin-bottom: 10px; font-size: 13px; background: #f6ffed; border: 1px solid #b7eb8f; color: #389e0d; }
.toast-inline.err { background: #fff1f0; border-color: #ffa39e; color: #cf1322; }
</style>
