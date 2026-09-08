<script setup>
/**
 * 总账 M3——会计事件池 / 待生成凭证工作台
 * 业务单据审核后落事件池（待生成），会计勾选批量生成草稿凭证；失败有中文原因，可补配置后重试。
 */
import { ref, onMounted } from 'vue'
import { post } from '../../api/client.js'

const EVENT_CODES = {
  PUR_IN: '采购收货', PUR_RETURN: '采购退货', SALE_OUT: '销售出库', SALE_SIGN: '销售签收',
  SALE_RETURN: '销售退货', SALE_RETURN_COST: '退货成本红冲', RECEIPT: '收款', PAYMENT: '付款',
  EXPENSE: '费用', OTHER_INCOME: '其他收入', FLY_ORDER: '飞单', STOCK_CHECK: '存货盘点',
  OTHER_OUT: '其他出库', OTHER_IN: '其他入库', DAMAGE: '存货报损', INVOICE_AUTH: '发票认证',
}
const STATUS_OPTS = ['待生成', '已生成', '已忽略', '生成失败', '已冲回']
const STATUS_CLASS = { 待生成: 'tag-blue', 已生成: 'tag-green', 已忽略: 'tag-gray', 生成失败: 'tag-red', 已冲回: 'tag-orange' }

const list = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = 20
const pending = ref(0)
const feedback = ref('')
const filters = ref({ eventCode: '', status: '', period: '', dateFrom: '', dateTo: '', keyword: '', reverseOnly: false })
const checked = ref(new Set())

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 5000)
}
function money(v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function periodOfMonth(v) { return v ? v.replace('-', '') : '' }
function eventName(code) { return EVENT_CODES[code] || code }
function canGenerate(row) { return row.status === '待生成' || row.status === '生成失败' }

async function load() {
  try {
    const res = await post('/finance/gl/event/page', {
      pageNo: pageNo.value, pageSize,
      eventCode: filters.value.eventCode,
      status: filters.value.status,
      period: periodOfMonth(filters.value.period),
      dateFrom: filters.value.dateFrom,
      dateTo: filters.value.dateTo,
      keyword: filters.value.keyword,
      reverseFlag: filters.value.reverseOnly ? '1' : '',
    })
    list.value = res.records || []
    total.value = res.total || 0
  } catch (e) { show('事件加载失败：' + (e?.message || e), 'err') }
}
async function loadPending() {
  try {
    const r = await post('/finance/gl/event/pending-count', {})
    pending.value = r.count || 0
  } catch (e) { /* 角标失败静默 */ }
}
function search() { pageNo.value = 1; load() }

function toggleCheck(row) {
  if (checked.value.has(row.id)) checked.value.delete(row.id)
  else checked.value.add(row.id)
}
function allChecked() {
  return list.value.length > 0
    && list.value.every(r => checked.value.has(r.id) || !canGenerate(r))
    && list.value.some(r => checked.value.has(r.id))
}
function toggleAll(e) {
  if (e.target.checked) list.value.forEach(r => { if (canGenerate(r)) checked.value.add(r.id) })
  else list.value.forEach(r => checked.value.delete(r.id))
}

async function generate(ids, allPending = false) {
  try {
    const body = allPending ? { allPending: true } : { ids }
    const r = await post('/finance/gl/event/generate', body)
    const parts = []
    parts.push(`成功 ${r.success} 张`)
    if (r.ignored) parts.push(`忽略 ${r.ignored} 条`)
    if (r.fail) parts.push(`失败 ${r.fail} 条`)
    const firstFail = (r.results || []).find(x => x.status === '生成失败')
    show('批量生成完成：' + parts.join('，') + (firstFail ? `；首条失败原因：${firstFail.errMsg}` : ''),
      r.fail ? 'err' : 'ok')
  } catch (e) { show('生成失败：' + (e?.message || e), 'err') }
  checked.value = new Set()
  await Promise.all([load(), loadPending()])
}
async function generateOne(row) { await generate([row.id]) }
async function generateChecked() {
  const ids = [...checked.value]
  if (!ids.length) return show('请先勾选待生成/失败的事件', 'err')
  await generate(ids)
}

async function ignore(row) {
  try { await post('/finance/gl/event/ignore', { id: row.id }); show('已忽略') }
  catch (e) { show(e?.message || String(e), 'err') }
  await Promise.all([load(), loadPending()])
}
async function unignore(row) {
  try { await post('/finance/gl/event/unignore', { id: row.id }); show('已取消忽略，回到待生成') }
  catch (e) { show(e?.message || String(e), 'err') }
  await Promise.all([load(), loadPending()])
}
async function reset(row) {
  if (!confirm('重置将删除已生成的草稿凭证，事件回到「待生成」。确定？')) return
  try { await post('/finance/gl/event/reset', { id: row.id }); show('已重置') }
  catch (e) { show(e?.message || String(e), 'err') }
  await Promise.all([load(), loadPending()])
}

// payload / 凭证联查
const payloadModal = ref({ open: false, data: null })
async function viewPayload(row) {
  try {
    const r = await post('/finance/gl/event/payload', { id: row.id })
    payloadModal.value = { open: true, data: r }
  } catch (e) { show(e?.message || String(e), 'err') }
}
const voucherModal = ref({ open: false, data: null })
async function viewVoucher(row) {
  if (!row.voucherId) return
  try {
    const r = await post('/finance/gl/voucher/detail', { id: row.voucherId })
    voucherModal.value = { open: true, data: r }
  } catch (e) { show('凭证联查失败：' + (e?.message || e), 'err') }
}

onMounted(() => { load(); loadPending() })
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <select v-model="filters.eventCode" @change="search">
        <option value="">全部事件</option>
        <option v-for="(name, code) in EVENT_CODES" :key="code" :value="code">{{ name }}（{{ code }}）</option>
      </select>
      <select v-model="filters.status" @change="search">
        <option value="">全部状态</option>
        <option v-for="s in STATUS_OPTS" :key="s" :value="s">{{ s }}</option>
      </select>
      <input type="month" v-model="filters.period" @change="search" title="会计期间" />
      <input type="date" v-model="filters.dateFrom" @change="search" />
      <input type="date" v-model="filters.dateTo" @change="search" />
      <input placeholder="单号 / 错误原因" v-model="filters.keyword" @keyup.enter="search" style="width:180px" />
      <label class="chk"><input type="checkbox" v-model="filters.reverseOnly" @change="search" /> 仅红字反向</label>
      <button class="btn" @click="search">查询</button>
      <span style="flex:1"></span>
      <span v-if="pending > 0" class="pending-badge">待处理 {{ pending }} 条</span>
      <button class="btn primary" @click="generate(null, true)">一键生成全部待处理</button>
      <button class="btn" @click="generateChecked">生成勾选（{{ checked.size }}）</button>
    </div>

    <div v-if="feedback" :class="['toast-inline', feedback.level]">{{ feedback.msg }}</div>

    <div class="card-box">
      <table class="data">
        <thead>
          <tr>
            <th style="width:36px"><input type="checkbox" :checked="allChecked()" @change="toggleAll" /></th>
            <th>事件</th>
            <th>来源单据</th>
            <th>业务日期</th>
            <th class="num">金额</th>
            <th>状态</th>
            <th style="width:260px">原因 / 警告</th>
            <th>凭证</th>
            <th style="width:230px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in list" :key="row.id">
            <td><input type="checkbox" :disabled="!canGenerate(row)" :checked="checked.has(row.id)" @change="toggleCheck(row)" /></td>
            <td>
              {{ eventName(row.eventCode) }}
              <div class="sub">{{ row.eventCode }}</div>
              <span v-if="row.reverseFlag === '1'" class="tag tag-orange">红字</span>
            </td>
            <td>{{ row.sourceBillNo }}<div class="sub">{{ row.sourceBillType }}</div></td>
            <td>{{ row.bizDate }}</td>
            <td class="num">{{ money(row.amount) }}</td>
            <td><span :class="['tag', STATUS_CLASS[row.status]]">{{ row.status }}</span></td>
            <td class="err-cell">
              <div v-if="row.errMsg" class="err-text">{{ row.errMsg }}</div>
              <div v-if="row.warnMsg" class="warn-text">{{ row.warnMsg }}</div>
            </td>
            <td>
              <a v-if="row.voucherId" @click="viewVoucher(row)" class="lk">查看凭证</a>
              <span v-else class="sub">—</span>
            </td>
            <td>
              <a v-if="canGenerate(row)" @click="generateOne(row)" class="lk">生成凭证</a>
              <a v-if="row.status === '已忽略'" @click="unignore(row)" class="lk">取消忽略</a>
              <a v-if="row.status === '待生成' || row.status === '生成失败'" @click="ignore(row)" class="lk">忽略</a>
              <a v-if="row.status === '已生成' || row.status === '生成失败'" @click="reset(row)" class="lk">重置</a>
              <a @click="viewPayload(row)" class="lk">数据</a>
            </td>
          </tr>
          <tr v-if="!list.length"><td colspan="9" class="empty">暂无事件</td></tr>
        </tbody>
      </table>
    </div>

    <div class="pager">
      <button class="btn" :disabled="pageNo <= 1" @click="pageNo--; load()">上一页</button>
      <span>第 {{ pageNo }} 页 / 共 {{ total }} 条</span>
      <button class="btn" :disabled="pageNo * pageSize >= total" @click="pageNo++; load()">下一页</button>
    </div>

    <!-- payload 详情 -->
    <div v-if="payloadModal.open" class="modal-mask" @click.self="payloadModal.open = false">
      <div class="modal w720">
        <div class="modal-h">事件数据 {{ payloadModal.data?.sourceBillNo }}<span class="modal-x" @click="payloadModal.open = false">×</span></div>
        <div class="modal-b">
          <pre class="json-box">{{ JSON.stringify(payloadModal.data?.payload, null, 2) }}</pre>
        </div>
      </div>
    </div>

    <!-- 凭证联查 -->
    <div v-if="voucherModal.open" class="modal-mask" @click.self="voucherModal.open = false">
      <div class="modal w900">
        <div class="modal-h">
          凭证 {{ voucherModal.data?.voucherNo || '（草稿未编号）' }}
          <span class="tag tag-blue">{{ voucherModal.data?.status }}</span>
          <span v-if="voucherModal.data?.isRed" class="tag tag-orange">红字</span>
          <span class="modal-x" @click="voucherModal.open = false">×</span>
        </div>
        <div class="modal-b">
          <p class="sub">日期 {{ voucherModal.data?.voucherDate }}　摘要：{{ voucherModal.data?.summary }}</p>
          <table class="data">
            <thead><tr><th>行</th><th>摘要</th><th>科目</th><th class="num">借方</th><th class="num">贷方</th><th>辅助</th></tr></thead>
            <tbody>
              <tr v-for="e in voucherModal.data?.entries || []" :key="e.id">
                <td>{{ e.lineNo }}</td>
                <td>{{ e.summary }}</td>
                <td>{{ e.accountCode }} {{ e.accountName }}</td>
                <td class="num">{{ money(e.debitAmount) }}</td>
                <td class="num">{{ money(e.creditAmount) }}</td>
                <td>{{ e.auxText }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pending-badge { color: #c0392b; font-weight: 600; margin-right: 8px; }
.tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 12px; }
.tag-blue { background: #e8f1fd; color: #1d6fd1; }
.tag-green { background: #e8f8ee; color: #1a9e54; }
.tag-gray { background: #f0f0f0; color: #888; }
.tag-red { background: #fdeaea; color: #d33; }
.tag-orange { background: #fdf2e3; color: #d08010; }
.sub { color: #999; font-size: 12px; }
.err-text { color: #d33; font-size: 12px; word-break: break-all; }
.warn-text { color: #b07010; font-size: 12px; word-break: break-all; }
.lk { color: #1d6fd1; cursor: pointer; margin-right: 10px; white-space: nowrap; }
.chk { font-size: 13px; display: flex; align-items: center; gap: 4px; }
.num { text-align: right; }
.empty { text-align: center; color: #999; padding: 24px; }
.pager { display: flex; gap: 12px; align-items: center; margin-top: 10px; }
.json-box { background: #f7f8fa; border: 1px solid #e5e7eb; border-radius: 6px; padding: 12px; max-height: 480px; overflow: auto; font-size: 12px; }
</style>
