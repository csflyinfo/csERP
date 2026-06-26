<script setup>
import { ref, onMounted } from 'vue'
import { post } from '../api/client.js'
import ProTable from '../components/ProTable.vue'

const loading = ref(false)
const tableRows = ref([])
const pageNo = ref(1)
const pageSize = ref(20)
const total = ref(0)
const feedback = ref('')
const summary = ref({ total: 0 })

const columns = [
  { key: 'c0', title: '待办事项' },
  { key: 'c1', title: '模块' },
  { key: 'c2', title: '单据号' },
  { key: 'c3', title: '优先级' },
  { key: 'c4', title: '状态' },
  { key: 'c5', title: '创建时间' },
  { key: 'c6', title: '操作' },
]

const moduleNames = {
  salesOrder: '销售订单',
  purchaseOrder: '采购订单',
  receiptVerify: '收款核销',
  paymentVerify: '付款核销',
  customerPrice: '客户价格调整',
}

function show(msg) {
  feedback.value = msg
  setTimeout(() => (feedback.value = ''), 2000)
}

async function loadRows() {
  loading.value = true
  try {
    const [data, sum] = await Promise.all([
      post('/system/todo/page', { pageNo: pageNo.value, pageSize: pageSize.value }),
      post('/system/todo/summary', {}),
    ])
    tableRows.value = (data.records || []).map(r => ({
      c0: r.title || '',
      c1: moduleNames[r.moduleCode] || r.moduleCode || '',
      c2: r.bizNo || '',
      c3: r.priority || '普通',
      c4: r.status || '待处理',
      c5: r.createdAt || '',
      c6: r.status === '待处理' ? '处理完成' : '',
      _todoId: r.todoId || '',
    }))
    total.value = data.total || tableRows.value.length
    summary.value = sum || { total: 0 }
  } catch (e) {
    show('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

async function handleAction(action, row) {
  if (/完成/.test(action)) {
    try {
      await post('/system/todo/done', { bizId: row._todoId })
      show('已标记为完成')
      loadRows()
    } catch (e) {
      show('操作失败')
    }
  }
}

function handlePageChange(n) { pageNo.value = n; loadRows() }
function handlePageSizeChange(s) { pageSize.value = s; pageNo.value = 1; loadRows() }

onMounted(loadRows)
</script>

<template>
  <div class="module-body">
    <div class="cards" style="grid-template-columns: repeat(4, 1fr); margin-bottom: 12px">
      <div class="card" style="text-align: center; padding: 16px">
        <div style="font-size: 12px; color: var(--muted)">总待办</div>
        <div style="font-size: 28px; font-weight: 900; color: var(--primary)">{{ summary.total }}</div>
      </div>
      <div class="card" style="text-align: center; padding: 16px">
        <div style="font-size: 12px; color: var(--muted)">销售订单</div>
        <div style="font-size: 28px; font-weight: 900; color: #fa8c16">{{ summary.salesOrder || 0 }}</div>
      </div>
      <div class="card" style="text-align: center; padding: 16px">
        <div style="font-size: 12px; color: var(--muted)">采购订单</div>
        <div style="font-size: 28px; font-weight: 900; color: #52c41a">{{ summary.purchaseOrder || 0 }}</div>
      </div>
      <div class="card" style="text-align: center; padding: 16px">
        <div style="font-size: 12px; color: var(--muted)">财务核销</div>
        <div style="font-size: 28px; font-weight: 900; color: #f5222d">{{ (summary.receiptVerify || 0) + (summary.paymentVerify || 0) }}</div>
      </div>
    </div>

    <div class="page-ops">
      <button class="btn" @click="loadRows">刷新</button>
    </div>
    <div v-if="loading" class="tips-inline"><span>正在加载...</span></div>
    <ProTable
      title="待办中心"
      :columns="columns"
      :rows="tableRows"
      :page-no="pageNo"
      :page-size="pageSize"
      :total="total"
      @row-action="handleAction"
      @page-change="handlePageChange"
      @page-size-change="handlePageSizeChange"
    >
      <template #c3="{ row }">
        <span class="badge" :class="row.c3 === '高' ? 'danger' : row.c3 === '低' ? 'ok' : 'wait'">{{ row.c3 }}</span>
      </template>
      <template #c4="{ row }">
        <span class="badge" :class="row.c4 === '待处理' ? 'wait' : 'ok'">{{ row.c4 }}</span>
      </template>
      <template #c6="{ row }">
        <button v-if="row.c6" class="link link-btn" @click="handleAction(row.c6, row)">{{ row.c6 }}</button>
      </template>
    </ProTable>
  </div>
  <div v-if="feedback" class="toast-inline">{{ feedback }}</div>
</template>
