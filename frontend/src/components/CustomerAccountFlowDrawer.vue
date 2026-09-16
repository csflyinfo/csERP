<script setup>
/**
 * 客户往来流水查询（PRD-35 M1）：应收/预收两本账。
 * 从客户账户列表点余额打开；条件仅点「查询」生效；展示此前余额与滚存账户金额。
 */
import { ref } from 'vue'
import { post } from '../api/client.js'

const visible = ref(false)
const loading = ref(false)
const customer = ref({ customerCode: '', customerName: '' })
const accountType = ref('AR')
const records = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const priorBalance = ref(0)
const currentBalance = ref(0)
const feedback = ref(null)

function defaultRange() {
  const now = new Date()
  const end = fmtDt(now)
  const begin = new Date(now.getFullYear(), now.getMonth() - 3, now.getDate())
  return { beginTime: fmtDt(begin), endTime: end }
}
const filters = ref({ ...defaultRange(), settleStatus: '' })

function fmtDt(d) {
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
function dayStart(d) { return fmtDt(new Date(d.getFullYear(), d.getMonth(), d.getDate())) }
function dayEnd(d) { return fmtDt(new Date(d.getFullYear(), d.getMonth(), d.getDate(), 23, 59, 59)) }

function quickRange(kind) {
  const now = new Date()
  const dow = (now.getDay() + 6) % 7 // 周一=0
  if (kind === 'yesterday') {
    const y = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1)
    filters.value.beginTime = dayStart(y)
    filters.value.endTime = dayEnd(y)
  } else if (kind === 'thisWeek') {
    const mon = new Date(now.getFullYear(), now.getMonth(), now.getDate() - dow)
    filters.value.beginTime = dayStart(mon)
    filters.value.endTime = dayEnd(now)
  } else if (kind === 'lastWeek') {
    const lastMon = new Date(now.getFullYear(), now.getMonth(), now.getDate() - dow - 7)
    const lastSun = new Date(now.getFullYear(), now.getMonth(), now.getDate() - dow - 1)
    filters.value.beginTime = dayStart(lastMon)
    filters.value.endTime = dayEnd(lastSun)
  } else if (kind === 'thisMonth') {
    filters.value.beginTime = dayStart(new Date(now.getFullYear(), now.getMonth(), 1))
    filters.value.endTime = dayEnd(now)
  } else if (kind === 'lastMonth') {
    const first = new Date(now.getFullYear(), now.getMonth() - 1, 1)
    const last = new Date(now.getFullYear(), now.getMonth(), 0)
    filters.value.beginTime = dayStart(first)
    filters.value.endTime = dayEnd(last)
  }
  doQuery()
}

function open(row, type) {
  customer.value = row
  accountType.value = type || 'AR'
  filters.value = { ...defaultRange(), settleStatus: '' }
  visible.value = true
  pageNo.value = 1
  load()
}

function switchTab(t) {
  if (accountType.value === t) return
  accountType.value = t
  filters.value.settleStatus = ''
  pageNo.value = 1
  load()
}

async function load() {
  loading.value = true
  feedback.value = null
  try {
    const res = await post('/finance/customer-account/flow/page', {
      customerCode: customer.value.customerCode,
      accountType: accountType.value,
      beginTime: filters.value.beginTime,
      endTime: filters.value.endTime,
      settleStatus: filters.value.settleStatus,
      pageNo: pageNo.value,
      pageSize: pageSize.value,
    })
    records.value = res.records || []
    total.value = res.total || 0
    priorBalance.value = res.priorBalance || 0
    currentBalance.value = res.currentBalance || 0
  } catch (e) {
    feedback.value = e?.message || '查询失败'
  } finally {
    loading.value = false
  }
}

function doQuery() {
  pageNo.value = 1
  load()
}

function changePage(delta) {
  const next = pageNo.value + delta
  if (next < 1 || (next - 1) * pageSize.value >= total.value) return
  pageNo.value = next
  load()
}

function close() {
  visible.value = false
}

function fmt(v) {
  return (Number(v || 0)).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function fmtTime(v) {
  return v ? String(v).replace('T', ' ').substring(0, 19) : ''
}
function fmtDate(v) {
  return v ? String(v).substring(0, 10) : ''
}

defineExpose({ open })
</script>

<template>
  <div v-if="visible" class="flow-mask" @click.self="close">
    <div class="flow-modal">
      <div class="flow-h">
        往来流水查询 [{{ customer.customerName }}]
        <span class="x" @click="close">×</span>
      </div>

      <div class="flow-b">
        <div class="tabs">
          <button class="tab" :class="{ on: accountType === 'AR' }" @click="switchTab('AR')">应收</button>
          <button class="tab" :class="{ on: accountType === 'ADVANCE' }" @click="switchTab('ADVANCE')">预收</button>
        </div>

        <div class="cond">
          <label>开始时间
            <input type="datetime-local" step="1" v-model="filters.beginTime" class="dt" />
          </label>
          <label>结束时间
            <input type="datetime-local" step="1" v-model="filters.endTime" class="dt" />
          </label>
          <label v-if="accountType === 'AR'">结算标志
            <select v-model="filters.settleStatus" class="sel">
              <option value="">全部</option>
              <option value="未结算">未结算</option>
              <option value="部分结算">部分结算</option>
              <option value="已结算">已结算</option>
            </select>
          </label>
          <button class="btn sm primary" @click="doQuery">查询</button>
          <span class="quick">
            <a @click="quickRange('yesterday')">昨天</a>
            <a @click="quickRange('lastWeek')">上周</a>
            <a @click="quickRange('thisWeek')">本周</a>
            <a @click="quickRange('lastMonth')">上月</a>
            <a @click="quickRange('thisMonth')">本月</a>
          </span>
        </div>

        <div class="balance-bar">
          <span>此前余额：<b :class="{ neg: priorBalance < 0 }">{{ fmt(priorBalance) }}</b></span>
          <span class="sep">|</span>
          <span>当前{{ accountType === 'AR' ? '应收' : '预收' }}余额：
            <b class="money" :class="{ neg: currentBalance < 0 }">{{ fmt(currentBalance) }}</b>
          </span>
          <span class="sep">|</span>
          <span>共 {{ total }} 条流水</span>
        </div>
        <div v-if="feedback" class="toast-inline error">{{ feedback }}</div>

        <div class="table-scroll">
          <table class="flow-table">
            <thead>
              <tr>
                <th style="width:50px">序号</th>
                <th style="width:150px">操作时间</th>
                <th style="width:100px">记账日期</th>
                <th style="width:100px">业务单据</th>
                <th style="width:110px">增加金额</th>
                <th style="width:110px">减少金额</th>
                <th style="width:110px">账户金额</th>
                <th style="width:80px">结算标志</th>
                <th>摘要</th>
                <th style="width:160px">要货单据号</th>
                <th style="width:160px">出库/退货单据号</th>
                <th style="width:150px">结算单据号</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loading"><td colspan="12" class="empty">加载中…</td></tr>
              <tr v-else-if="!records.length"><td colspan="12" class="empty">暂无流水</td></tr>
              <tr v-for="(r, i) in records" :key="r.flowId" :class="{ redrow: r.isRed === 'Y' }">
                <td class="c">{{ (pageNo - 1) * pageSize + i + 1 }}</td>
                <td>{{ fmtTime(r.occurredAt) }}</td>
                <td>{{ fmtDate(r.postDate) }}</td>
                <td>{{ r.bizLabel }}</td>
                <td class="num" :class="{ redtext: Number(r.increaseAmount) < 0 }">{{ fmt(r.increaseAmount) }}</td>
                <td class="num">{{ Number(r.decreaseAmount) ? fmt(r.decreaseAmount) : '' }}</td>
                <td class="num strong">{{ fmt(r.balanceAfter) }}</td>
                <td class="c">
                  <span v-if="r.settleStatus" class="settle" :class="r.settleStatus">{{ r.settleStatus }}</span>
                </td>
                <td class="summary">{{ r.summary }}</td>
                <td class="bill">{{ r.orderNo }}</td>
                <td class="bill">{{ r.deliveryNo }}</td>
                <td class="bill">{{ r.writeoffNo || r.receiptNo }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="pager">
          <button class="btn sm" :disabled="pageNo <= 1" @click="changePage(-1)">上一页</button>
          <span class="page-no">{{ pageNo }}</span>
          <button class="btn sm" :disabled="pageNo * pageSize >= total" @click="changePage(1)">下一页</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.flow-mask {
  position: fixed; inset: 0;
  background: rgba(0,0,0,.45);
  z-index: 2000;
  display: flex; align-items: center; justify-content: center;
}
.flow-modal {
  width: 92vw; max-width: 1500px; height: 86vh;
  background: #fff; border-radius: 6px;
  display: flex; flex-direction: column;
  box-shadow: 0 8px 30px rgba(0,0,0,.2);
}
.flow-h {
  padding: 12px 18px; font-size: 15px; font-weight: 600;
  border-bottom: 1px solid #ebeef5;
  display: flex; justify-content: space-between; align-items: center;
}
.flow-h .x { cursor: pointer; font-size: 20px; color: #909399; }
.flow-b { padding: 12px 18px 16px; display: flex; flex-direction: column; flex: 1; min-height: 0; }
.tabs { display: flex; gap: 4px; margin-bottom: 10px; }
.tab {
  border: 1px solid #dcdfe6; background: #f5f7fa; padding: 5px 18px;
  border-radius: 4px 4px 0 0; cursor: pointer; font-size: 13px;
}
.tab.on { background: #1f6fdd; color: #fff; border-color: #1f6fdd; }
.cond { display: flex; flex-wrap: wrap; gap: 14px; align-items: center; margin-bottom: 10px; }
.cond label { display: flex; align-items: center; gap: 6px; font-size: 13px; color: #606266; }
.dt, .sel { height: 28px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 6px; font-size: 13px; }
.dt { width: 190px; }
.quick { display: flex; gap: 10px; margin-left: 6px; }
.quick a { color: #1f6fdd; cursor: pointer; font-size: 13px; }
.balance-bar { font-size: 13px; color: #606266; margin-bottom: 8px; display: flex; gap: 10px; align-items: center; }
.balance-bar b { font-variant-numeric: tabular-nums; }
.balance-bar .money { color: #1f6fdd; }
.sep { color: #c0c4cc; }
.neg, .redtext { color: #c45656 !important; }
.table-scroll { flex: 1; overflow: auto; border: 1px solid #ebeef5; border-radius: 4px; }
.flow-table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.flow-table th, .flow-table td {
  border-bottom: 1px solid #f0f2f5; padding: 7px 8px; white-space: nowrap;
}
.flow-table th { background: #f5f7fa; font-weight: 600; text-align: center; position: sticky; top: 0; z-index: 1; }
.flow-table td.num, .flow-table td.c { text-align: right; }
.flow-table td.c { text-align: center; }
.flow-table .strong { font-weight: 600; color: #1f6fdd; font-variant-numeric: tabular-nums; }
.flow-table .summary { white-space: normal; min-width: 160px; }
.flow-table .bill { color: #1f6fdd; }
.flow-table .empty { text-align: center; color: #909399; padding: 36px 0; }
.redrow td { background: #fff7f6; }
.settle { padding: 1px 7px; border-radius: 3px; font-size: 12px; }
.settle.未结算 { background: #f4f4f5; color: #909399; }
.settle.部分结算 { background: #fdf6ec; color: #b88230; }
.settle.已结算 { background: #f0f9eb; color: #2f8f46; }
.pager { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.toast-inline.error { color: #c45656; margin-bottom: 8px; }
</style>
