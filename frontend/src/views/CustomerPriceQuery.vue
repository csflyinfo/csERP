<script setup>
/**
 * 客户价格查询 —— 客户指定商品的当前专属价格（按单位拆行）
 *
 * 对照【价格组商品查询】开发：
 *   · 展示客户 × 商品 × 单位类型 的当前价与标价
 *   · 生效中的价格可停用（单条 / 批量），已停用的不可再停
 *   · 支持导出当前查询结果
 *   · 查询条件：客户、商品、状态、单位类型
 */
import { ref, computed, onMounted } from 'vue'
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

// 勾选的价格记录 id（base_customer_price_item.id）
const selectedIds = ref(new Set())

// 一客户一商品一单位恒定一条记录（后端 uk_cust_goods_unit 唯一约束保证）。
// 不展示调价单号/生效方式/价格有效期 —— 那是来源单据属性，追溯请用【客户商品变价查询】。
const columns = [
  { key: 'c0', title: '客户编号' },
  { key: 'c1', title: '客户名称' },
  { key: 'c2', title: '商品编号' },
  { key: 'c3', title: '商品名称' },
  { key: 'c4', title: '规格' },
  { key: 'c5', title: '条码' },
  { key: 'c6', title: '单位' },
  { key: 'c7', title: '单位类型' },
  { key: 'c8', title: '商品分类' },
  { key: 'c9', title: '品牌' },
  { key: 'c10', title: '标价', num: true },
  { key: 'c11', title: '现价', num: true },
  { key: 'c12', title: '状态' },
  { key: 'c13', title: '操作' },
]

// 状态与单位类型给下拉，客户/商品给文本框
const queryFields = [
  '客户',
  '商品',
  { label: '状态', type: 'select', options: ['生效中', '已停用'] },
  { label: '单位类型', type: 'select', options: ['小单位', '中单位', '大单位'] },
]
const FILTER_KEY_MAP = {
  '客户': 'customer',
  '商品': 'goods',
  '状态': 'status',
  '单位类型': 'unitType',
}

const api = moduleApis.customerPriceQuery

function show(msg) {
  feedback.value = msg
  setTimeout(() => (feedback.value = ''), 2500)
}

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
      c0: r.customerCode || '',
      c1: r.customerName || '',
      c2: r.goodsCode || '',
      c3: r.goodsName || '',
      c4: r.spec || '',
      c5: r.barcode || '',
      c6: r.unitName || '',
      c7: r.unitLevelText || '',
      c8: r.categoryName || '',
      c9: r.brandName || '',
      c10: r.standardPrice == null ? '' : Number(r.standardPrice).toFixed(2),
      c11: r.price == null ? '' : Number(r.price).toFixed(2),
      c12: r.statusText || '',
      c13: r.isActive ? '停用' : '',
      _id: r.id || '',
      _active: !!r.isActive,
      _raw: r,
    }))
    total.value = data.total || tableRows.value.length
    // 重新加载后清空勾选，避免选中已不在当前结果里的记录
    selectedIds.value = new Set()
  } catch (e) {
    tableRows.value = []
    total.value = 0
    show('加载失败：' + (e.message || '请检查后端服务'))
  } finally {
    loading.value = false
  }
}

// ==================== 停用 ====================

/** 只有生效中的记录可被勾选停用 */
const selectableRows = computed(() => tableRows.value.filter(r => r._active && r._id))

const allSelected = computed(() =>
  selectableRows.value.length > 0
  && selectableRows.value.every(r => selectedIds.value.has(r._id))
)

function toggleSelectAll(checked) {
  selectableRows.value.forEach(r => {
    if (checked) selectedIds.value.add(r._id)
    else selectedIds.value.delete(r._id)
  })
}

function toggleRow(rowIndex, checked) {
  const row = tableRows.value[rowIndex]
  if (!row || !row._active || !row._id) return
  if (checked) selectedIds.value.add(row._id)
  else selectedIds.value.delete(row._id)
}

async function stopPrices(ids, label) {
  if (!ids.length) return show('请选择要停用的价格')
  if (!confirm(`确认停用${label}？\n\n停用后该客户该单位的专属价失效，销售将回退到价格组价格。`)) return
  try {
    const res = await post(api.stop, { priceIds: ids, reason: '客户价格查询页手动停用' })
    show(`已停用 ${res?.stoppedCount ?? ids.length} 条`)
    loadRows()
  } catch (e) {
    show('停用失败：' + (e.message || '未知错误'))
  }
}

function stopOne(row) {
  if (!row._active) return
  stopPrices([row._id], `【${row.c2} ${row.c3} - ${row.c7}】的价格`)
}

function stopSelected() {
  stopPrices(Array.from(selectedIds.value), `所选 ${selectedIds.value.size} 条价格`)
}

// ==================== 查询 / 分页 / 导出 ====================

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

/** 导出当前查询结果（不含「操作」列） */
function exportRows() {
  if (tableRows.value.length === 0) return show('没有可导出的记录')
  const cols = columns.filter(c => !/操作/.test(c.title))
  const titles = cols.map(c => c.title)
  const data = tableRows.value.map(row => {
    const obj = {}
    cols.forEach(c => { obj[c.title] = row[c.key] })
    return obj
  })
  const ws = XLSX.utils.json_to_sheet(data, { header: titles })
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '客户价格查询')
  const fileName = `客户价格查询_${new Date().toISOString().slice(0, 10).replace(/-/g, '')}.xlsx`
  XLSX.writeFile(wb, fileName)
  show(`已导出 ${data.length} 条到 ${fileName}`)
}

onMounted(loadRows)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn danger" :disabled="selectedIds.size === 0" @click="stopSelected">
        批量停用<span v-if="selectedIds.size">({{ selectedIds.size }})</span>
      </button>
      <button class="btn" @click="loadRows">刷新</button>
      <button class="btn" @click="exportRows">导出</button>
    </div>
    <QueryBar :fields="queryFields" @query="onQuery" @reset="onReset" />
    <div v-if="loading" class="tips-inline"><span>正在加载...</span></div>
    <ProTable
      title="客户价格查询"
      :columns="columns"
      :rows="tableRows"
      :page-no="pageNo"
      :page-size="pageSize"
      :total="total"
      @page-change="handlePageChange"
      @page-size-change="handlePageSizeChange"
    >
      <template #checkbox-header>
        <input type="checkbox" :checked="allSelected" @change="toggleSelectAll($event.target.checked)" />
      </template>
      <template #checkbox-cell="{ rowIndex }">
        <input
          type="checkbox"
          :checked="selectedIds.has(tableRows[rowIndex]?._id)"
          :disabled="!tableRows[rowIndex]?._active"
          @change="toggleRow(rowIndex, $event.target.checked)" />
      </template>
      <template #c12="{ row }">
        <span class="badge" :class="row.c12 === '生效中' ? 'ok' : 'wait'">{{ row.c12 }}</span>
      </template>
      <template #c13="{ row }">
        <button v-if="row._active" class="link link-btn danger-link" @click="stopOne(row)">停用</button>
        <span v-else class="muted">—</span>
      </template>
    </ProTable>
  </div>
  <div v-if="feedback" class="toast-inline">{{ feedback }}</div>
</template>

<style scoped>
.muted { color: #c0c4cc; }
</style>
