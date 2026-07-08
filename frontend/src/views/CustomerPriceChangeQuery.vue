<script setup>
/**
 * 客户商品变价查询 —— 客户商品历史调价记录（只读）
 *
 * 数据来自 base_customer_price_change_log：调整单审核时每个单位写一条。
 * 本页只做查询，不提供停用等任何操作（停用请到【客户价格查询】）。
 */
import { ref, onMounted } from 'vue'
import * as XLSX from 'xlsx'
import QueryBar from '../components/QueryBar.vue'
import ProTable from '../components/ProTable.vue'
import { post } from '../api/client.js'
import { moduleApis } from '../module-api.js'

const loading = ref(false)
const tableRows = ref([])
const pageNo = ref(1)
const pageSize = ref(100)
const total = ref(0)
const feedback = ref('')
const queryFilters = ref({})

// 需求指定的 15 个展示字段
const columns = [
  { key: 'c0', title: '调价单号' },
  { key: 'c1', title: '日期' },
  { key: 'c2', title: '客户编号' },
  { key: 'c3', title: '客户名称' },
  { key: 'c4', title: '商品编号' },
  { key: 'c5', title: '商品名称' },
  { key: 'c6', title: '单位' },
  { key: 'c7', title: '单位类型' },
  { key: 'c8', title: '商品分类' },
  { key: 'c9', title: '品牌' },
  { key: 'c10', title: '变价前', num: true },
  { key: 'c11', title: '变价后', num: true },
  { key: 'c12', title: '生效方式' },
  { key: 'c13', title: '价格有效期' },
  { key: 'c14', title: '备注' },
]

// 查询条件与后端 filters 字段的映射（QueryBar 用中文标签做 key）
const queryFields = ['客户', '商品', '调价单号', '单位类型']
const FILTER_KEY_MAP = {
  '客户': 'customer',
  '商品': 'goods',
  '调价单号': 'adjustNo',
  '单位类型': 'unitType',
}

const api = moduleApis.customerPriceChangeLog

function show(msg) {
  feedback.value = msg
  setTimeout(() => (feedback.value = ''), 2500)
}

/** QueryBar 传回的是以中文标签为 key 的对象，转成后端认的英文 key */
function toBackendFilters(raw) {
  const out = {}
  Object.entries(raw || {}).forEach(([label, value]) => {
    const key = FILTER_KEY_MAP[label] || label
    if (value !== undefined && value !== null && String(value).trim() !== '') out[key] = value
  })
  return out
}

async function loadRows() {
  loading.value = true
  try {
    const data = await post(api.page, {
      pageNo: pageNo.value, pageSize: pageSize.value, filters: queryFilters.value,
    })
    tableRows.value = (data.records || []).map(r => ({
      c0: r.adjustNo || '',
      c1: r.billDateText || r.createdAtText || '',
      c2: r.customerCode || '',
      c3: r.customerName || '',
      c4: r.goodsCode || '',
      c5: r.goodsName || '',
      c6: r.unitName || '',
      c7: r.unitLevelText || '',
      c8: r.categoryName || '',
      c9: r.brandName || '',
      // 首次设价时后端返回「首次设价」而不是数字
      c10: r.oldPriceText || '首次设价',
      c11: r.newPrice == null ? '' : Number(r.newPrice).toFixed(2),
      c12: r.effectiveModeText || '',
      c13: r.validRangeText || '长期有效',
      c14: r.remark || '',
      _raw: r,
    }))
    total.value = data.total || tableRows.value.length
  } catch (e) {
    tableRows.value = []
    total.value = 0
    show('加载失败：' + (e.message || '请检查后端服务'))
  } finally {
    loading.value = false
  }
}

function onQuery(filters) {
  queryFilters.value = toBackendFilters(filters)
  pageNo.value = 1
  loadRows()
}
function onReset() {
  queryFilters.value = {}
  pageNo.value = 1
  loadRows()
}

function handlePageChange(n) { pageNo.value = n; loadRows() }
function handlePageSizeChange(s) { pageSize.value = s; pageNo.value = 1; loadRows() }

/** 导出当前查询结果（按列表列顺序），单页导出即所见即所得 */
function exportRows() {
  if (tableRows.value.length === 0) return show('没有可导出的记录')
  const titles = columns.map(c => c.title)
  const data = tableRows.value.map(row => {
    const obj = {}
    columns.forEach(c => { obj[c.title] = row[c.key] })
    return obj
  })
  const ws = XLSX.utils.json_to_sheet(data, { header: titles })
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '客户商品变价查询')
  const fileName = `客户商品变价查询_${new Date().toISOString().slice(0, 10).replace(/-/g, '')}.xlsx`
  XLSX.writeFile(wb, fileName)
  show(`已导出 ${data.length} 条到 ${fileName}`)
}

onMounted(loadRows)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn" @click="loadRows">刷新</button>
      <button class="btn" @click="exportRows">导出</button>
    </div>
    <QueryBar :fields="queryFields" @query="onQuery" @reset="onReset" />
    <div v-if="loading" class="tips-inline"><span>正在加载...</span></div>
    <ProTable
      title="客户商品变价查询"
      :columns="columns"
      :rows="tableRows"
      :page-no="pageNo"
      :page-size="pageSize"
      :total="total"
      @page-change="handlePageChange"
      @page-size-change="handlePageSizeChange"
    >
      <!-- 变价前后对比着色：涨价红、降价绿 -->
      <template #c10="{ row }">
        <span :class="{ 'first-set': row.c10 === '首次设价' }">{{ row.c10 }}</span>
      </template>
      <template #c11="{ row }">
        <b>{{ row.c11 }}</b>
      </template>
    </ProTable>
  </div>
  <div v-if="feedback" class="toast-inline">{{ feedback }}</div>
</template>

<style scoped>
.first-set { color: #909399; }
</style>
