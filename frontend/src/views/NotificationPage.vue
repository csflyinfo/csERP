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

const columns = [
  { key: 'c0', title: '标题' },
  { key: 'c1', title: '内容' },
  { key: 'c2', title: '类型' },
  { key: 'c3', title: '模块' },
  { key: 'c4', title: '状态' },
  { key: 'c5', title: '时间' },
  { key: 'c6', title: '操作' },
]

function show(msg) {
  feedback.value = msg
  setTimeout(() => (feedback.value = ''), 2000)
}

async function loadRows() {
  loading.value = true
  try {
    const data = await post('/system/notification/page', { pageNo: pageNo.value, pageSize: pageSize.value })
    tableRows.value = (data.records || []).map(r => ({
      c0: r.title || '',
      c1: r.content || '',
      c2: r.notifyType || 'SYSTEM',
      c3: r.moduleCode || '',
      c4: r.status || '未读',
      c5: r.createdAt || '',
      c6: r.status === '未读' ? '标记已读' : '',
      _notifyId: r.notifyId || '',
    }))
    total.value = data.total || tableRows.value.length
  } catch (e) {
    show('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

async function handleAction(action, row) {
  if (/已读/.test(action)) {
    try {
      await post('/system/notification/read', { bizId: row._notifyId })
      show('已标记为已读')
      loadRows()
    } catch (e) {
      show('操作失败')
    }
  }
}

async function markAllRead() {
  try {
    await post('/system/notification/read', {})
    show('全部标记为已读')
    loadRows()
  } catch (e) {
    show('操作失败')
  }
}

function handlePageChange(n) { pageNo.value = n; loadRows() }
function handlePageSizeChange(s) { pageSize.value = s; pageNo.value = 1; loadRows() }

onMounted(loadRows)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn primary" @click="markAllRead">全部已读</button>
      <button class="btn" @click="loadRows">刷新</button>
    </div>
    <div v-if="loading" class="tips-inline"><span>正在加载...</span></div>
    <ProTable
      title="消息通知"
      :columns="columns"
      :rows="tableRows"
      :page-no="pageNo"
      :page-size="pageSize"
      :total="total"
      @row-action="handleAction"
      @page-change="handlePageChange"
      @page-size-change="handlePageSizeChange"
    >
      <template #c4="{ row }">
        <span class="badge" :class="row.c4 === '未读' ? 'wait' : 'ok'">{{ row.c4 }}</span>
      </template>
      <template #c6="{ row }">
        <button v-if="row.c6" class="link link-btn" @click="handleAction(row.c6, row)">{{ row.c6 }}</button>
      </template>
    </ProTable>
  </div>
  <div v-if="feedback" class="toast-inline">{{ feedback }}</div>
</template>
