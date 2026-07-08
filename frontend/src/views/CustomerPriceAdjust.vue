<script setup>
/**
 * 客户价格调整单 —— 列表页
 *
 * 交互约定：
 *   · 未审核记录显示「编辑 审核 作废」，编辑跳转编辑页
 *   · 已审核/已作废记录只显示「查看」
 *   · 双击任意记录弹出只读详情窗口
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import QueryBar from '../components/QueryBar.vue'
import ProTable from '../components/ProTable.vue'
import CustomerPriceViewDialog from '../components/CustomerPriceViewDialog.vue'
import { post } from '../api/client.js'
import { moduleApis } from '../module-api.js'

const router = useRouter()
const loading = ref(false)
const tableRows = ref([])
const pageNo = ref(1)
const pageSize = ref(100)
const total = ref(0)
const feedback = ref('')
const queryFilters = ref({})

// 详情查看窗口
const viewOpen = ref(false)
const viewAdjustId = ref('')

const columns = [
  { key: 'c0', title: '调整单号' },
  { key: 'c1', title: '客户' },
  { key: 'c2', title: '单据日期' },
  { key: 'c3', title: '生效方式' },
  { key: 'c4', title: '价格有效期' },
  { key: 'c5', title: '商品数', num: true },
  { key: 'c6', title: '制单人' },
  { key: 'c7', title: '制单时间' },
  { key: 'c8', title: '审核人' },
  { key: 'c9', title: '审核时间' },
  { key: 'c10', title: '状态' },
  { key: 'c11', title: '操作' },
]

const api = moduleApis.customerPrice

function show(msg) {
  feedback.value = msg
  setTimeout(() => (feedback.value = ''), 2500)
}

async function loadRows() {
  loading.value = true
  try {
    const data = await post(api.page, { pageNo: pageNo.value, pageSize: pageSize.value, filters: queryFilters.value })
    tableRows.value = (data.records || []).map(r => {
      const statusText = r.statusText || r.status || '待审核'
      const pending = statusText === '待审核'
      return {
        c0: r.adjustNo || r.adjustId || '',
        c1: r.customer || '',
        c2: r.billDateText || r.billDate || '',
        c3: r.effectiveModeText || r.effectiveMode || '',
        c4: r.validRangeText || '长期有效',
        c5: r.detailCount ?? 0,
        c6: r.creatorNameText || '',
        c7: r.createTimeText || '',
        c8: r.auditorNameText || '',
        c9: r.auditTimeText || '',
        c10: statusText,
        // 待审核可编辑/审核/作废；已审核、已作废只能查看
        c11: pending ? '编辑 审核 作废' : '查看',
        _raw: r,
      }
    })
    total.value = data.total || tableRows.value.length
  } catch (e) {
    // 加载失败保持空表；不让页面白屏
    tableRows.value = []
    total.value = 0
    show('加载失败：' + (e.message || '请检查后端服务'))
  } finally {
    loading.value = false
  }
}

function rowKeyOf(row) { return row._raw?.adjustId || row.c0 }

function goNew() { router.push('/customer-price/new') }
function goEdit(row) { router.push(`/customer-price/edit/${encodeURIComponent(rowKeyOf(row))}`) }

function openView(row) {
  viewAdjustId.value = rowKeyOf(row)
  viewOpen.value = true
}

async function handleAction(action, row) {
  if (action === '查看') { openView(row); return }
  if (action === '编辑') { goEdit(row); return }
  if (/审核/.test(action)) {
    if (!confirm(`确认审核【${row.c0}】？\n\n审核后价格立即生效，该客户该商品的历史有效价将自动停用。`)) return
    try {
      await post(api.audit, { bizId: rowKeyOf(row) })
      show('审核成功')
      loadRows()
    } catch (e) {
      show('审核失败：' + (e.message || '未知错误'))
    }
  } else if (/作废/.test(action)) {
    if (!confirm(`确认作废【${row.c0}】？`)) return
    try {
      await post(api.cancel, { bizId: rowKeyOf(row) })
      show('作废成功')
      loadRows()
    } catch (e) {
      show('作废失败：' + (e.message || '未知错误'))
    }
  } else if (/新建/.test(action)) {
    goNew()
  } else {
    show(action)
  }
}

/**
 * ProTable 双击行时统一发 row-action('查看')，
 * 这里拦下来弹详情窗口，而不是走编辑页。
 */
function onRowAction(action, row) {
  handleAction(action, row)
}

function onQuery(filters) {
  queryFilters.value = filters || {}
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

onMounted(() => { loadRows() })
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn primary" @click="goNew">新建调整单</button>
      <button class="btn" @click="loadRows">刷新</button>
      <button class="btn" @click="handleAction('导入价格')">导入价格</button>
    </div>
    <QueryBar :fields="['调整单号', '客户', '生效方式', '状态']" @query="onQuery" @reset="onReset" />
    <div v-if="loading" class="tips-inline"><span>正在加载...</span></div>
    <ProTable
      title="客户价格调整单"
      :columns="columns"
      :rows="tableRows"
      :page-no="pageNo"
      :page-size="pageSize"
      :total="total"
      @row-action="onRowAction"
      @page-change="handlePageChange"
      @page-size-change="handlePageSizeChange"
    >
      <template #c10="{ row }">
        <span class="badge" :class="row.c10 === '已审核' ? 'ok' : 'wait'">{{ row.c10 }}</span>
      </template>
      <template #c11="{ row }">
        <button v-for="a in row.c11.split(' ')" :key="a" class="link link-btn" @click="handleAction(a, row)">{{ a }}</button>
      </template>
    </ProTable>

    <!-- 双击行弹出的只读详情窗口 -->
    <CustomerPriceViewDialog
      :visible="viewOpen"
      :adjust-id="viewAdjustId"
      @close="viewOpen = false" />
  </div>
  <div v-if="feedback" class="toast-inline">{{ feedback }}</div>
</template>
