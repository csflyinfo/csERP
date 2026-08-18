<script setup>
/**
 * 门店定位修正审核（P4-1）
 *
 * 接口：
 *   POST /tms/store-location/page          修正申请列表
 *   GET  /tms/store-location/{id}          申请详情（含门头照）
 *   POST /tms/store-location/{id}/approve  批准修正（更新客户定位）
 *   POST /tms/store-location/{id}/reject   驳回修正
 *
 * 状态：PENDING(待审核) / APPROVED(已批准) / REJECTED(已驳回)
 */
import { ref, onMounted } from 'vue'
import QueryBar from '../../components/QueryBar.vue'
import ProTable from '../../components/ProTable.vue'
import { post, get } from '../../api/client.js'

const loading = ref(false)
const tableRows = ref([])
const pageNo = ref(1)
const pageSize = ref(20)
const total = ref(0)
const feedback = ref('')
const queryFilters = ref({})

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref({})

const rejectOpen = ref(false)
const rejectId = ref('')
const rejectNo = ref('')
const rejectReason = ref('')
const rejectSubmitting = ref(false)

const columns = [
  { key: 'c0', title: '客户编码' },
  { key: 'c1', title: '客户名称' },
  { key: 'c2', title: '原定位' },
  { key: 'c3', title: '新定位' },
  { key: 'c4', title: '提交司机' },
  { key: 'c5', title: '提交时间' },
  { key: 'c6', title: '状态' },
  { key: 'c7', title: '操作' },
]

const statusOptions = [
  { value: 'PENDING', label: '待审核' },
  { value: 'APPROVED', label: '已批准' },
  { value: 'REJECTED', label: '已驳回' },
]

const statusMap = {
  PENDING: { text: '待审核', cls: 'tag-orange' },
  APPROVED: { text: '已批准', cls: 'tag-green' },
  REJECTED: { text: '已驳回', cls: 'tag-red' },
}

function fmtCoord(lat, lng) {
  if (!lat && !lng) return '-'
  return `${lat}, ${lng}`
}

// QueryBar 为配置式组件：只认 :fields，且以中文 label 为 key 回传（详见 components/QueryBar.vue）
const queryFields = [
  { label: '状态', type: 'select', options: statusOptions },
  '客户',
  '提交司机',
]

async function loadList() {
  loading.value = true
  feedback.value = ''
  try {
    const data = await post('/tms/store-location/page', {
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      filters: queryFilters.value,
    })
    tableRows.value = (data.records || []).map(r => ({
      c0: r.customerCode || '-',
      c1: r.customerName,
      c2: fmtCoord(r.oldLat, r.oldLng),
      c3: fmtCoord(r.newLat, r.newLng),
      c4: r.driverName || '-',
      c5: (r.createdAt || '').substring(0, 16),
      c6: statusMap[r.status]?.text || r.status,
      c7: '操作',
      _raw: r,
    }))
    total.value = data.total || 0
  } catch (e) {
    feedback.value = '加载失败：' + (e.message || '')
  } finally {
    loading.value = false
  }
}

// QueryBar 以中文 label 为 key 回传，这里映射为后端 filters 字段名
function onQuery(filters) {
  const f = {}
  if (filters['状态']) f.status = filters['状态']
  if (filters['客户']) f.customerName = filters['客户']
  if (filters['提交司机']) f.driverName = filters['提交司机']
  queryFilters.value = f
  pageNo.value = 1
  loadList()
}

function onReset() {
  queryFilters.value = {}
  pageNo.value = 1
  loadList()
}

function onPageChange(p) {
  pageNo.value = p
  loadList()
}

function onPageSizeChange(s) {
  pageSize.value = s
  pageNo.value = 1
  loadList()
}

async function viewDetail(row) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = {}
  try {
    const res = await get(`/tms/store-location/${row._raw.logId}`)
    detail.value = res || {}
  } catch (e) {
    feedback.value = '加载详情失败：' + (e.message || '')
  } finally {
    detailLoading.value = false
  }
}

async function approve(row) {
  if (!confirm(`确认批准 ${row._raw.customerName} 的定位修正？\n将更新客户坐标为 (${row._raw.newLat}, ${row._raw.newLng})`)) return
  feedback.value = ''
  try {
    await post(`/tms/store-location/${row._raw.logId}/approve`, {})
    feedback.value = `已批准：${row._raw.customerName} 的定位修正`
    loadList()
  } catch (e) {
    feedback.value = '操作失败：' + (e.message || '')
  }
}

function openReject(row) {
  rejectId.value = row._raw.logId
  rejectNo.value = row._raw.customerName
  rejectReason.value = ''
  rejectOpen.value = true
}

async function submitReject() {
  if (!rejectReason.value.trim()) {
    feedback.value = '请填写驳回原因'
    return
  }
  rejectSubmitting.value = true
  feedback.value = ''
  try {
    await post(`/tms/store-location/${rejectId.value}/reject`, { reviewRemark: rejectReason.value })
    feedback.value = `已驳回：${rejectNo.value} 的定位修正`
    rejectOpen.value = false
    detailOpen.value = false
    loadList()
  } catch (e) {
    feedback.value = '操作失败：' + (e.message || '')
  } finally {
    rejectSubmitting.value = false
  }
}

onMounted(loadList)
</script>

<template>
  <div class="tms-page">
    <div class="page-head">
      <h2>门店定位修正审核</h2>
      <button class="btn" @click="loadList">刷新</button>
    </div>

    <QueryBar :fields="queryFields" @query="onQuery" @reset="onReset" />

    <ProTable
      :columns="columns"
      :rows="tableRows"
      :loading="loading"
      :page-no="pageNo"
      :page-size="pageSize"
      :total="total"
      @page-change="onPageChange"
      @page-size-change="onPageSizeChange"
    >
      <template #c6="{ row }">
        <span :class="['tag', statusMap[row._raw.status]?.cls || 'tag-gray']">{{ row.c6 }}</span>
      </template>
      <template #c7="{ row }">
        <button class="btn-link" @click="viewDetail(row)">详情</button>
        <button v-if="row._raw.status === 'PENDING'" class="btn-link" @click="approve(row)">批准</button>
        <button v-if="row._raw.status === 'PENDING'" class="btn-link" @click="openReject(row)">驳回</button>
      </template>
    </ProTable>

    <!-- 详情抽屉 -->
    <div v-if="detailOpen" class="drawer-mask" @click.self="detailOpen = false">
      <div class="drawer">
        <div class="drawer-head">
          <h3>定位修正详情</h3>
          <button class="btn-link" @click="detailOpen = false">关闭</button>
        </div>
        <div v-if="detailLoading" class="drawer-body"><p>加载中...</p></div>
        <div v-else class="drawer-body">
          <div class="section">
            <h4>客户信息</h4>
            <div class="grid-2">
              <div><span class="lbl">客户编码：</span>{{ detail.customerCode }}</div>
              <div><span class="lbl">客户名称：</span>{{ detail.customerName }}</div>
              <div><span class="lbl">提交司机：</span>{{ detail.driverName || '-' }}</div>
              <div><span class="lbl">提交时间：</span>{{ (detail.createdAt || '').substring(0, 19) }}</div>
              <div><span class="lbl">状态：</span>
                <span :class="['tag', statusMap[detail.status]?.cls || 'tag-gray']">{{ statusMap[detail.status]?.text || detail.status }}</span>
              </div>
            </div>
          </div>

          <div class="section">
            <h4>定位对比</h4>
            <div class="loc-compare">
              <div class="loc-box old">
                <div class="loc-title">原定位</div>
                <div class="loc-val">{{ fmtCoord(detail.oldLat, detail.oldLng) }}</div>
              </div>
              <div class="loc-arrow">→</div>
              <div class="loc-box new">
                <div class="loc-title">新定位</div>
                <div class="loc-val">{{ fmtCoord(detail.newLat, detail.newLng) }}</div>
              </div>
            </div>
          </div>

          <div v-if="detail.storePhotoUrl" class="section">
            <h4>门头照</h4>
            <img :src="detail.storePhotoUrl" alt="门头照" style="max-width: 100%; border-radius: 6px; border: 1px solid #eee;" />
          </div>

          <div v-if="detail.reviewRemark" class="section">
            <h4>审核备注</h4>
            <p>{{ detail.reviewRemark }}</p>
            <p v-if="detail.reviewerName"><span class="lbl">审核人：</span>{{ detail.reviewerName }} · {{ (detail.reviewedAt || '').substring(0, 16) }}</p>
          </div>

          <div v-if="detail.status === 'PENDING'" class="section">
            <button class="btn btn-primary" @click="approve({ _raw: detail })">批准修正</button>
            <button class="btn btn-warn" @click="openReject({ _raw: detail })" style="margin-left: 8px;">驳回</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 驳回对话框 -->
    <div v-if="rejectOpen" class="modal-mask" @click.self="rejectOpen = false">
      <div class="modal">
        <h3>驳回定位修正</h3>
        <p>客户：<b>{{ rejectNo }}</b></p>
        <textarea v-model="rejectReason" class="textarea" placeholder="请填写驳回原因（必填）" rows="3"></textarea>
        <div class="modal-actions">
          <button class="btn" @click="rejectOpen = false">取消</button>
          <button class="btn btn-warn" :disabled="rejectSubmitting" @click="submitReject">
            {{ rejectSubmitting ? '处理中...' : '确认驳回' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="feedback" class="feedback">{{ feedback }}</div>
  </div>
</template>

<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.page-head h2 { margin: 0; font-size: 18px; }
.btn { padding: 6px 14px; border: 1px solid #ddd; border-radius: 4px; background: #fff; cursor: pointer; font-size: 13px; }
.btn-primary { background: #409eff; color: #fff; border-color: #409eff; }
.btn-warn { background: #e6a23c; color: #fff; border-color: #e6a23c; }
.btn-link { background: none; border: none; color: #409eff; cursor: pointer; font-size: 13px; padding: 2px 6px; }
.sel, .inp { padding: 6px 10px; border: 1px solid #ddd; border-radius: 4px; font-size: 13px; }

.drawer-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); z-index: 100; display: flex; justify-content: flex-end; }
.drawer { width: 560px; max-width: 90vw; background: #fff; height: 100vh; overflow-y: auto; }
.drawer-head { display: flex; justify-content: space-between; align-items: center; padding: 16px; border-bottom: 1px solid #eee; position: sticky; top: 0; background: #fff; z-index: 1; }
.drawer-body { padding: 16px; }
.section { margin-bottom: 20px; }
.section h4 { margin: 0 0 8px; font-size: 14px; color: #333; border-left: 3px solid #409eff; padding-left: 8px; }
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 16px; font-size: 13px; line-height: 1.8; }
.lbl { color: #999; }
.loc-compare { display: flex; align-items: center; gap: 16px; }
.loc-box { flex: 1; padding: 12px; border-radius: 6px; text-align: center; }
.loc-box.old { background: #f5f5f5; }
.loc-box.new { background: #e8f4ff; }
.loc-title { font-size: 12px; color: #999; margin-bottom: 4px; }
.loc-val { font-size: 14px; font-weight: 600; color: #333; }
.loc-arrow { font-size: 20px; color: #409eff; }

.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); z-index: 200; display: flex; align-items: center; justify-content: center; }
.modal { background: #fff; border-radius: 8px; padding: 20px; width: 400px; max-width: 90vw; }
.modal h3 { margin: 0 0 12px; font-size: 16px; }
.modal p { font-size: 13px; color: #666; margin: 0 0 12px; }
.textarea { width: 100%; padding: 8px; border: 1px solid #ddd; border-radius: 4px; font-size: 13px; resize: vertical; box-sizing: border-box; }
.modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }

.feedback { position: fixed; bottom: 20px; left: 50%; transform: translateX(-50%); background: #333; color: #fff; padding: 8px 20px; border-radius: 4px; font-size: 13px; z-index: 300; }
.tag { padding: 2px 8px; border-radius: 10px; font-size: 11px; }
.tag-orange { background: #fdf6ec; color: #e6a23c; }
.tag-green { background: #f0f9eb; color: #67c23a; }
.tag-red { background: #fef0f0; color: #f56c6c; }
.tag-gray { background: #f4f4f5; color: #909399; }
</style>
