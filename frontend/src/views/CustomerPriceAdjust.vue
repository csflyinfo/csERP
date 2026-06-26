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

const columns = [
  { key: 'c0', title: '调整单号' },
  { key: 'c1', title: '客户' },
  { key: 'c2', title: '单据日期' },
  { key: 'c3', title: '生效方式' },
  { key: 'c4', title: '有效期' },
  { key: 'c5', title: '商品数', num: true },
  { key: 'c6', title: '制单信息' },
  { key: 'c7', title: '审核信息' },
  { key: 'c8', title: '状态' },
  { key: 'c9', title: '操作' },
]

const api = moduleApis.customerPrice

function show(msg) {
  feedback.value = msg
  setTimeout(() => (feedback.value = ''), 2000)
}

async function loadRows() {
  loading.value = true
  try {
    const data = await post(api.page, { pageNo: pageNo.value, pageSize: pageSize.value, filters: {} })
    tableRows.value = (data.records || []).map(r => ({
      c0: r.adjustNo || r.adjustId || '',
      c1: r.customer || '',
      c2: r.billDate || '',
      c3: r.effectiveMode || '',
      c4: r.validRange || '',
      c5: r.detailCount || '0',
      c6: r.creatorInfo || '',
      c7: r.auditInfo || '',
      c8: r.status || '待审核',
      c9: '查看 审核 作废',
    }))
    total.value = data.total || tableRows.value.length
  } catch (e) {
    show('加载失败：' + (e.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

async function handleAction(action, row) {
  if (/审核/.test(action)) {
    try {
      await post(api.audit, { bizId: row.c0 })
      show('审核成功')
      loadRows()
    } catch (e) {
      show('审核失败：' + (e.message || '未知错误'))
    }
  } else if (/作废/.test(action)) {
    try {
      await post(api.cancel, { bizId: row.c0 })
      show('作废成功')
      loadRows()
    } catch (e) {
      show('作废失败：' + (e.message || '未知错误'))
    }
  } else if (/新建/.test(action)) {
    show('请使用客户价格调整专用页面新建')
  } else {
    show(action)
  }
}

function handlePageChange(n) { pageNo.value = n; loadRows() }
function handlePageSizeChange(s) { pageSize.value = s; pageNo.value = 1; loadRows() }

onMounted(loadRows)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn primary" @click="handleAction('新建调整单')">新建调整单</button>
      <button class="btn" @click="loadRows">刷新</button>
      <button class="btn" @click="handleAction('导入价格')">导入价格</button>
    </div>
    <QueryBar :fields="['调整单号', '客户', '生效方式', '状态']" @query="loadRows" @reset="loadRows" />
    <div v-if="loading" class="tips-inline"><span>正在加载...</span></div>
    <ProTable
      title="客户价格调整单"
      :columns="columns"
      :rows="tableRows"
      :page-no="pageNo"
      :page-size="pageSize"
      :total="total"
      @row-action="handleAction"
      @page-change="handlePageChange"
      @page-size-change="handlePageSizeChange"
    >
      <template #c8="{ row }">
        <span class="badge" :class="row.c8 === '已审核' ? 'ok' : 'wait'">{{ row.c8 }}</span>
      </template>
      <template #c9="{ row }">
        <button v-for="a in row.c9.split(' ')" :key="a" class="link link-btn" @click="handleAction(a, row)">{{ a }}</button>
      </template>
    </ProTable>
  </div>
  <div v-if="feedback" class="toast-inline">{{ feedback }}</div>
</template>
