<script setup>
/**
 * 客户账户（PRD-35 M1）：每客户一条，应收余额 + 预收余额；
 * 点余额弹出往来流水查询（应收/预收两本账）；数据修复按真值重算缓存。
 */
import { onMounted, ref } from 'vue'
import { post } from '../../api/client.js'
import CustomerAccountFlowDrawer from '../../components/CustomerAccountFlowDrawer.vue'

const loading = ref(false)
const records = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const feedback = ref(null)

// 查询条件（仅点「查询」生效，下拉/输入不自动触发，遵守全局查询约定）
const filters = ref({
  keyword: '',
  channelType: '',
  salesman: '',
  hideZero: false,
})
const channelOptions = ref([])

const flowDrawer = ref(null)
const repairing = ref(false)

async function loadOptions() {
  try {
    const res = await post('/base/customer/page', { pageNo: 1, pageSize: 500, filters: {} })
    channelOptions.value = [...new Set((res.records || [])
      .map(c => c.channelType)
      .filter(Boolean))]
  } catch (e) {
    channelOptions.value = []
  }
}

async function load() {
  loading.value = true
  try {
    const res = await post('/finance/customer-account/page', {
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      keyword: filters.value.keyword,
      channelType: filters.value.channelType,
      salesman: filters.value.salesman,
      hideZero: filters.value.hideZero,
    })
    records.value = res.records || []
    total.value = res.total || 0
  } catch (e) {
    feedback.value = { level: 'error', msg: e?.message || '查询失败' }
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

function openFlow(row, accountType) {
  flowDrawer.value.open(row, accountType)
}

async function repairAll() {
  if (!window.confirm('确认对全部客户执行数据修复？将按应收单/核销记录重算余额、补缺流水、重排余额链，不删除业务数据。')) return
  repairing.value = true
  feedback.value = null
  try {
    const res = await post('/finance/customer-account/repair', {})
    const mis = res.mismatches || []
    feedback.value = {
      level: mis.length ? 'warn' : 'ok',
      msg: `修复完成：账户 ${res.accounts} 个，补形成流水 ${res.missingForming} 条、`
        + `结算流水 ${res.missingSettle} 条，重排余额链 ${res.chainsRebuilt} 条`
        + (mis.length ? `；差异 ${mis.length} 项（已按真值修正）` : ''),
    }
    load()
  } catch (e) {
    feedback.value = { level: 'error', msg: e?.message || '修复失败' }
  } finally {
    repairing.value = false
  }
}

function fmt(v) {
  return (Number(v || 0)).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function termText(row) {
  // 客户主档账期为中文字面值（现结/月结…），截单日/付款日有值时拼「截X付Y」
  const t = row.accountPeriodType || ''
  const cut = row.cutoffDay ? `截${row.cutoffDay}` : ''
  const pay = row.paymentDay ? `付${row.paymentDay}` : ''
  const tail = cut || pay ? `（${cut}${pay}）` : ''
  return t ? `${t}${tail}` : ''
}

onMounted(() => {
  loadOptions()
  load()
})
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn primary" @click="doQuery">查询</button>
      <button class="btn" @click="repairAll" v-permission="'fin.customer_account.repair'"
              :disabled="repairing">{{ repairing ? '修复中…' : '数据修复' }}</button>
      <div class="spacer"></div>
    </div>

    <div class="query-panel">
      <div class="query-row">
        <label>渠道
          <select v-model="filters.channelType" class="sel">
            <option value="">全部</option>
            <option v-for="c in channelOptions" :key="c" :value="c">{{ c }}</option>
          </select>
        </label>
        <label>业务员
          <input v-model="filters.salesman" class="inp" placeholder="业务员姓名" />
        </label>
        <label>关键字
          <input v-model="filters.keyword" class="inp kw" placeholder="请输入客户编号、店名、助记"
                 @keyup.enter="doQuery" />
        </label>
        <label class="check-line">
          <input type="checkbox" v-model="filters.hideZero" /> 本页不体现余额为 0
        </label>
      </div>
    </div>

    <div v-if="feedback" class="toast-inline" :class="feedback.level">{{ feedback.msg }}</div>

    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th style="width:60px">序号</th>
            <th style="width:140px">客户编号</th>
            <th>门店</th>
            <th style="width:140px">应收余额</th>
            <th style="width:140px">预收余额</th>
            <th style="width:100px">业务员</th>
            <th style="width:120px">渠道</th>
            <th style="width:120px">线路</th>
            <th style="width:170px">账期</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="9" class="empty">加载中…</td></tr>
          <tr v-else-if="!records.length"><td colspan="9" class="empty">暂无数据</td></tr>
          <tr v-for="(r, i) in records" :key="r.customerCode">
            <td>{{ (pageNo - 1) * pageSize + i + 1 }}</td>
            <td>{{ r.customerCode }}</td>
            <td>{{ r.customerName }}</td>
            <td>
              <a class="link-num" @click="openFlow(r, 'AR')">{{ fmt(r.arBalance) }}</a>
            </td>
            <td>
              <a class="link-num" @click="openFlow(r, 'ADVANCE')">{{ fmt(r.advanceBalance) }}</a>
            </td>
            <td>{{ r.salesman }}</td>
            <td>{{ r.channelType }}</td>
            <td>{{ r.routeLine }}</td>
            <td>{{ termText(r) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pager">
      <button class="btn sm" :disabled="pageNo <= 1" @click="changePage(-1)">上一页</button>
      <span class="page-no">{{ pageNo }}</span>
      <button class="btn sm" :disabled="pageNo * pageSize >= total" @click="changePage(1)">下一页</button>
      <span class="total-text">共 {{ total }} 条</span>
    </div>

    <CustomerAccountFlowDrawer ref="flowDrawer" @reload="load" />
  </div>
</template>

<style scoped>
.query-panel {
  background: var(--panel-bg, #fff);
  border: 1px solid var(--border-color, #e4e7ed);
  border-radius: 4px;
  padding: 12px 16px;
  margin-bottom: 10px;
}
.query-row {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  align-items: center;
}
.query-row label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #606266;
  white-space: nowrap;
}
.sel, .inp {
  height: 28px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 0 8px;
  font-size: 13px;
  min-width: 140px;
}
.kw { min-width: 220px; }
.check-line { cursor: pointer; }
.table-wrap {
  background: var(--panel-bg, #fff);
  border: 1px solid var(--border-color, #e4e7ed);
  border-radius: 4px;
  overflow: auto;
}
table.data {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
table.data th, table.data td {
  border-bottom: 1px solid #ebeef5;
  padding: 8px 10px;
  text-align: left;
  white-space: nowrap;
}
table.data th {
  background: #f5f7fa;
  color: #303133;
  font-weight: 600;
  text-align: center;
}
table.data td:nth-child(1), table.data td:nth-child(4), table.data td:nth-child(5) {
  text-align: right;
}
table.data th:nth-child(1), table.data th:nth-child(4), table.data th:nth-child(5) {
  text-align: right;
}
.empty { text-align: center; color: #909399; padding: 30px 0 !important; }
.link-num {
  color: #1f6fdd;
  cursor: pointer;
  text-decoration: underline;
  font-variant-numeric: tabular-nums;
}
.pager {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}
.total-text { color: #909399; font-size: 12px; margin-left: 8px; }
.toast-inline.warn { color: #b88230; }
.toast-inline.ok { color: #2f8f46; }
.toast-inline.error { color: #c45656; }
</style>
