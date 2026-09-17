<script setup>
/**
 * 供应商账户（PRD-36 M1）：每供应商一条，应付余额 + 预付余额 + 费用余额；
 * 点余额弹出往来流水查询（应付/预付/费用三本账）；数据修复按真值重算缓存。
 */
import { onMounted, ref } from 'vue'
import { post } from '../../api/client.js'
import SupplierAccountFlowDrawer from '../../components/SupplierAccountFlowDrawer.vue'

const loading = ref(false)
const records = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const feedback = ref(null)

// 查询条件（仅点「查询」生效，输入不自动触发，遵守全局查询约定）
const filters = ref({
  keyword: '',
  hideZero: false,
})

const flowDrawer = ref(null)
const repairing = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await post('/finance/supplier-account/page', {
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      keyword: filters.value.keyword,
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
  if (!window.confirm('确认对全部供应商执行数据修复？将按应付单/核销记录重算余额、补缺流水、重排余额链，不删除业务数据。')) return
  repairing.value = true
  feedback.value = null
  try {
    const res = await post('/finance/supplier-account/repair', {})
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

onMounted(load)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn primary" @click="doQuery">查询</button>
      <button class="btn" @click="repairAll" v-permission="'fin.supplier_account.repair'"
              :disabled="repairing">{{ repairing ? '修复中…' : '数据修复' }}</button>
      <div class="spacer"></div>
    </div>

    <div class="query-panel">
      <div class="query-row">
        <label>关键字
          <input v-model="filters.keyword" class="inp kw" placeholder="请输入供应商编号、名称、联系人"
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
            <th style="width:140px">供应商编号</th>
            <th>供应商名称</th>
            <th style="width:140px">应付余额</th>
            <th style="width:140px">预付余额</th>
            <th style="width:140px">费用余额</th>
            <th style="width:110px">默认采购员</th>
            <th style="width:110px">结算方式</th>
            <th style="width:90px">账期(天)</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="9" class="empty">加载中…</td></tr>
          <tr v-else-if="!records.length"><td colspan="9" class="empty">暂无数据</td></tr>
          <tr v-for="(r, i) in records" :key="r.supplierCode">
            <td>{{ (pageNo - 1) * pageSize + i + 1 }}</td>
            <td>{{ r.supplierCode }}</td>
            <td>{{ r.supplierName }}</td>
            <td>
              <a class="link-num" @click="openFlow(r, 'AP')">{{ fmt(r.apBalance) }}</a>
            </td>
            <td>
              <a class="link-num" @click="openFlow(r, 'PREPAY')">{{ fmt(r.prepayBalance) }}</a>
            </td>
            <td>
              <a class="link-num" @click="openFlow(r, 'EXPENSE')">{{ fmt(r.expenseBalance) }}</a>
            </td>
            <td>{{ r.defaultBuyer }}</td>
            <td>{{ r.settlementMethod }}</td>
            <td>{{ r.accountPeriodDays }}</td>
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

    <SupplierAccountFlowDrawer ref="flowDrawer" />
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
.kw { min-width: 240px; }
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
table.data td:nth-child(1), table.data td:nth-child(4), table.data td:nth-child(5), table.data td:nth-child(6) {
  text-align: right;
}
table.data th:nth-child(1), table.data th:nth-child(4), table.data th:nth-child(5), table.data th:nth-child(6) {
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
