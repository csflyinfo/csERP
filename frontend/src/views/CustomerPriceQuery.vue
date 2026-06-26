<script setup>
import { ref, onMounted } from 'vue'
import QueryBar from '../components/QueryBar.vue'
import ProTable from '../components/ProTable.vue'
import { post } from '../api/client.js'
import { moduleApis } from '../module-api.js'

const loading = ref(false)
const tableRows = ref([])
const pageNo = ref(1)
const pageSize = ref(20)
const total = ref(0)
const feedback = ref('')
const selectedPriceIds = ref(new Set())

const columns = [
  { key: 'c0', title: '调整单号' },
  { key: 'c1', title: '客户' },
  { key: 'c2', title: '商品编码' },
  { key: 'c3', title: '商品名称' },
  { key: 'c4', title: '单位' },
  { key: 'c5', title: '规格' },
  { key: 'c6', title: '原价', num: true },
  { key: 'c7', title: '现价', num: true },
  { key: 'c8', title: '最新进价', num: true },
  { key: 'c9', title: '成本价', num: true },
  { key: 'c10', title: '生效状态' },
  { key: 'c11', title: '操作' },
]

const api = moduleApis.customerPriceQuery

function show(msg) {
  feedback.value = msg
  setTimeout(() => (feedback.value = ''), 2000)
}

async function loadRows() {
  loading.value = true
  try {
    const data = await post(api.page, { pageNo: pageNo.value, pageSize: pageSize.value, filters: {} })
    tableRows.value = (data.records || []).map((r, idx) => ({
      _index: idx,
      c0: r.adjustNo || '',
      c1: r.customer || '',
      c2: r.goodsCode || '',
      c3: r.goodsName || '',
      c4: r.baseUnit || '',
      c5: r.spec || '',
      c6: r.originalPrice || '0.00',
      c7: r.currentPrice || '0.00',
      c8: r.latestPurchasePrice || '0.00',
      c9: r.costPrice || '0.00',
      c10: r.effectiveStatus || '',
      c11: '停用',
      _priceId: r.priceId || '',
    }))
    total.value = data.total || tableRows.value.length
  } catch (e) {
    show('加载失败：' + (e.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

async function handleAction(action, row) {
  if (/停用/.test(action)) {
    const ids = row ? [row._priceId] : Array.from(selectedPriceIds.value)
    if (ids.length === 0 || !ids[0]) {
      show('请选择要停用的价格')
      return
    }
    try {
      await post(api.stop, { priceIds: ids, reason: '页面手动停用' })
      show('停用成功')
      loadRows()
    } catch (e) {
      show('停用失败：' + (e.message || '未知错误'))
    }
  } else {
    show(action)
  }
}

function toggleSelection(index, checked) {
  const row = tableRows.value[index]
  if (!row) return
  if (checked) selectedPriceIds.value.add(row._priceId)
  else selectedPriceIds.value.delete(row._priceId)
}

function handlePageChange(n) { pageNo.value = n; loadRows() }
function handlePageSizeChange(s) { pageSize.value = s; pageNo.value = 1; loadRows() }

onMounted(loadRows)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn danger" @click="handleAction('停用价格')">停用所选价格</button>
      <button class="btn" @click="loadRows">刷新</button>
      <button class="btn" @click="handleAction('导出')">导出</button>
    </div>
    <QueryBar :fields="['客户', '商品', '生效状态', '单号']" @query="loadRows" @reset="loadRows" />
    <div v-if="loading" class="tips-inline"><span>正在加载...</span></div>
    <ProTable
      title="客户价格查询"
      :columns="columns"
      :rows="tableRows"
      :page-no="pageNo"
      :page-size="pageSize"
      :total="total"
      @row-action="handleAction"
      @page-change="handlePageChange"
      @page-size-change="handlePageSizeChange"
    >
      <template #checkbox-header>
        <input type="checkbox" @change="$event.target.checked ? tableRows.forEach((r,i)=>selectedPriceIds.add(r._priceId)) : selectedPriceIds.clear()" />
      </template>
      <template #checkbox-cell="{ rowIndex }">
        <input type="checkbox" :checked="selectedPriceIds.has(tableRows[rowIndex]?._priceId)" @change="toggleSelection(rowIndex, $event.target.checked)" />
      </template>
      <template #c10="{ row }">
        <span class="badge" :class="row.c10 === '生效中' ? 'ok' : 'wait'">{{ row.c10 }}</span>
      </template>
      <template #c11="{ row }">
        <button class="link link-btn" @click="handleAction('停用', row)">停用</button>
      </template>
    </ProTable>
  </div>
  <div v-if="feedback" class="toast-inline">{{ feedback }}</div>
</template>
