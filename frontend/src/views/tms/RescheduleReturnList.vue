<script setup>
/**
 * 改派返仓单管理（P3-2）
 *
 * 接口：
 *   POST /tms/reschedule-return/page              列表
 *   GET  /tms/reschedule-return/{id}              详情
 *   POST /tms/reschedule-return/{id}/check        仓库验收（→ CHECKED，发货单回调度池）
 *   POST /tms/reschedule-return/{id}/redispatch   重新纳入调度（→ REDISPATCHED）
 *   POST /tms/reschedule-return/pool              返仓改派池（待重新派送的发货单）
 *
 * 状态：PENDING(待返仓) / CHECKED(已验收) / REDISPATCHED(已重新派送)
 * 业务：客户不在/地址错误 → 货物随车返仓 → 仓库验收 → 发货单回调度池重新派送
 *      不反审核出库单，不生成入库单，库存不变
 */
import { ref, onMounted, computed } from 'vue'
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

// 详情抽屉
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref({ details: [], photos: [] })

// 验收对话框
const checkOpen = ref(false)
const checkSubmitting = ref(false)
const checkItems = ref([])
const checkReturnId = ref('')

// 改派池抽屉
const poolOpen = ref(false)
const poolLoading = ref(false)
const poolRows = ref([])

const columns = [
  { key: 'c0', title: '改派返仓单号' },
  { key: 'c1', title: '发货单号' },
  { key: 'c2', title: '客户' },
  { key: 'c3', title: '司机' },
  { key: 'c4', title: '原因' },
  { key: 'c5', title: '改派次数', num: true },
  { key: 'c6', title: '改送日期' },
  { key: 'c7', title: '数量', num: true },
  { key: 'c8', title: '状态' },
  { key: 'c9', title: '操作' },
]

const statusOptions = [
  { value: 'PENDING', label: '待返仓' },
  { value: 'CHECKED', label: '已验收' },
  { value: 'REDISPATCHED', label: '已重新派送' },
]

const statusMap = {
  PENDING: { text: '待返仓', cls: 'tag-orange' },
  CHECKED: { text: '已验收', cls: 'tag-blue' },
  REDISPATCHED: { text: '已重新派送', cls: 'tag-green' },
}

// QueryBar 为配置式组件：只认 :fields，且以中文 label 为 key 回传（详见 components/QueryBar.vue）
const queryFields = [
  '改派返仓单号',
  { label: '状态', type: 'select', options: statusOptions },
  '司机',
  '客户',
]

const reasonMap = {
  CUSTOMER_ABSENT: '客户不在',
  ADDRESS_ERROR: '地址错误',
  UNREACHABLE: '联系不上',
  CUSTOMER_REQUEST: '客户要求改期',
  OTHER: '其他',
}

const reasonLabel = (code) => reasonMap[code] || code

async function loadList() {
  loading.value = true
  feedback.value = ''
  try {
    const res = await post('/tms/reschedule-return/page', {
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      filters: queryFilters.value,
    })
    tableRows.value = (res.records || []).map(r => ({
      c0: r.returnNo,
      c1: r.receiptNo,
      c2: r.customerName,
      c3: r.driverName,
      c4: reasonLabel(r.reason),
      c5: r.rescheduleCount,
      c6: r.rescheduleDate,
      c7: r.totalQty,
      c8: statusMap[r.status]?.text || r.status,
      c9: '操作',
      _raw: r,
    }))
    total.value = res.total || 0
  } catch (e) {
    feedback.value = '加载失败：' + (e.message || '')
  } finally {
    loading.value = false
  }
}

async function viewDetail(row) {
  detailOpen.value = true
  detailLoading.value = true
  feedback.value = ''
  try {
    const res = await get(`/tms/reschedule-return/${row._raw.returnId}`)
    detail.value = res || { details: [], photos: [] }
  } catch (e) {
    feedback.value = '加载详情失败：' + (e.message || '')
  } finally {
    detailLoading.value = false
  }
}

function openCheck(row) {
  checkReturnId.value = row._raw.returnId
  // 从详情拉取明细作为验收行
  checkItems.value = (detail.value.details && detail.value.details.length ? detail.value.details : [])
    .map(d => ({ detailId: d.detailId, goodsName: d.goodsName, spec: d.spec, unitName: d.unitName, plannedQty: d.plannedQty, actualReturnQty: d.plannedQty }))
  checkOpen.value = true
}

async function submitCheck() {
  checkSubmitting.value = true
  feedback.value = ''
  try {
    await post(`/tms/reschedule-return/${checkReturnId.value}/check`, {
      items: checkItems.value.map(it => ({ detailId: it.detailId, actualReturnQty: it.actualReturnQty, plannedQty: it.plannedQty })),
    })
    feedback.value = '验收成功，发货单已回调度池'
    checkOpen.value = false
    detailOpen.value = false
    loadList()
  } catch (e) {
    feedback.value = '验收失败：' + (e.message || '')
  } finally {
    checkSubmitting.value = false
  }
}

async function redispatch(row) {
  if (!confirm(`确认将发货单 ${row._raw.receiptNo} 重新纳入调度？`)) return
  feedback.value = ''
  try {
    await post(`/tms/reschedule-return/${row._raw.returnId}/redispatch`)
    feedback.value = '已重新纳入调度池'
    loadList()
  } catch (e) {
    feedback.value = '操作失败：' + (e.message || '')
  }
}

async function openPool() {
  poolOpen.value = true
  poolLoading.value = true
  feedback.value = ''
  try {
    const res = await post('/tms/reschedule-return/pool', { pageNo: 1, pageSize: 100, filters: {} })
    poolRows.value = res.records || []
  } catch (e) {
    feedback.value = '加载改派池失败：' + (e.message || '')
  } finally {
    poolLoading.value = false
  }
}

async function poolRedispatch(row) {
  if (!confirm(`确认将 ${row.receiptNo} 重新派送？`)) return
  feedback.value = ''
  try {
    await post(`/tms/reschedule-return/${row.returnId}/redispatch`)
    feedback.value = '已重新纳入调度池'
    openPool()
  } catch (e) {
    feedback.value = '操作失败：' + (e.message || '')
  }
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

// QueryBar 以中文 label 为 key 回传，这里映射为后端 filters 字段名
function onQuery(filters) {
  const f = {}
  if (filters['改派返仓单号']) f.returnNo = filters['改派返仓单号']
  if (filters['状态']) f.status = filters['状态']
  if (filters['司机']) f.driverName = filters['司机']
  if (filters['客户']) f.customerName = filters['客户']
  queryFilters.value = f
  pageNo.value = 1
  loadList()
}

function onReset() {
  queryFilters.value = {}
  pageNo.value = 1
  loadList()
}

onMounted(loadList)
</script>

<template>
  <div class="tms-page">
    <div class="page-head">
      <h2>改派返仓单管理</h2>
      <div class="head-actions">
        <button class="btn" @click="openPool">🔄 返仓改派池</button>
        <button class="btn" @click="loadList">刷新</button>
      </div>
    </div>

    <QueryBar :fields="queryFields" @query="onQuery" @reset="onReset" />

    <div v-if="feedback" class="feedback">{{ feedback }}</div>

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
      <template #c8="{ row }">
        <span :class="['tag', statusMap[row._raw.status]?.cls || 'tag-gray']">{{ row.c8 }}</span>
      </template>
      <template #c9="{ row }">
        <button class="btn-link" @click="viewDetail(row)">详情</button>
        <button v-if="row._raw.status === 'PENDING'" class="btn-link" @click="openCheck(row)">验收</button>
        <button v-if="row._raw.status === 'CHECKED'" class="btn-link" @click="redispatch(row)">重新派送</button>
      </template>
    </ProTable>

    <!-- 详情抽屉 -->
    <div v-if="detailOpen" class="drawer-mask" @click.self="detailOpen = false">
      <div class="drawer">
        <div class="drawer-head">
          <h3>改派返仓单详情</h3>
          <button class="btn-link" @click="detailOpen = false">关闭</button>
        </div>
        <div v-if="detailLoading" class="drawer-loading">加载中...</div>
        <div v-else class="drawer-body">
          <div class="section">
            <h4>基本信息</h4>
            <div class="grid-2">
              <div><label>改派返仓单号</label><span>{{ detail.returnNo }}</span></div>
              <div><label>发货单号</label><span>{{ detail.receiptNo }}</span></div>
              <div><label>司机</label><span>{{ detail.driverName }}</span></div>
              <div><label>客户</label><span>{{ detail.customerName }} ({{ detail.customerCode }})</span></div>
              <div><label>客户地址</label><span>{{ detail.customerAddress || '-' }}</span></div>
              <div><label>改派原因</label><span>{{ reasonLabel(detail.reason) }}</span></div>
              <div><label>原因说明</label><span>{{ detail.reasonDetail || '-' }}</span></div>
              <div><label>改派次数</label><span>第 {{ detail.rescheduleCount }} 次</span></div>
              <div><label>期望改送日期</label><span>{{ detail.rescheduleDate || '-' }}</span></div>
              <div><label>合计数量</label><span>{{ detail.totalQty }}</span></div>
              <div><label>状态</label><span :class="['tag', statusMap[detail.status]?.cls]">{{ statusMap[detail.status]?.text }}</span></div>
              <div><label>调度单</label><span>{{ detail.dispatchNo || '-' }}</span></div>
              <div><label>行程</label><span>{{ detail.tripNo || '-' }}</span></div>
              <div><label>司机返仓时间</label><span>{{ detail.returnedAt || '-' }}</span></div>
              <div><label>仓库验收时间</label><span>{{ detail.checkedAt || '-' }}</span></div>
              <div><label>验收人</label><span>{{ detail.checker || '-' }}</span></div>
            </div>
            <div v-if="detail.remark"><label style="margin-top:8px;display:block">备注</label><span>{{ detail.remark }}</span></div>
          </div>

          <div class="section">
            <h4>商品明细</h4>
            <table class="dt">
              <thead>
                <tr>
                  <th>商品编码</th>
                  <th>商品名称</th>
                  <th>规格</th>
                  <th>单位</th>
                  <th>计划数量</th>
                  <th>实收数量</th>
                  <th>差异</th>
                  <th>批次</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="d in detail.details" :key="d.detailId">
                  <td>{{ d.goodsCode }}</td>
                  <td>{{ d.goodsName }}</td>
                  <td>{{ d.spec }}</td>
                  <td>{{ d.unitName }}</td>
                  <td class="num">{{ d.plannedQty }}</td>
                  <td class="num">{{ d.actualReturnQty }}</td>
                  <td class="num">{{ d.diffQty }}</td>
                  <td>{{ d.batchNo || '-' }}</td>
                </tr>
                <tr v-if="!detail.details?.length">
                  <td colspan="8" class="empty">无明细</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="section">
            <h4>现场照片 ({{ detail.photos?.length || 0 }})</h4>
            <div v-if="detail.photos?.length" class="photo-grid">
              <div v-for="p in detail.photos" :key="p.photoId" class="photo-item">
                <img :src="p.photoUrl" :alt="p.photoType" />
              </div>
            </div>
            <div v-else class="empty">无照片</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 验收对话框 -->
    <div v-if="checkOpen" class="drawer-mask" @click.self="checkOpen = false">
      <div class="drawer">
        <div class="drawer-head">
          <h3>仓库验收</h3>
          <button class="btn-link" @click="checkOpen = false">关闭</button>
        </div>
        <div class="drawer-body">
          <div class="feedback" v-if="feedback">{{ feedback }}</div>
          <table class="dt">
            <thead>
              <tr>
                <th>商品名称</th>
                <th>规格</th>
                <th>计划数量</th>
                <th>实收数量</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(it, idx) in checkItems" :key="idx">
                <td>{{ it.goodsName }}</td>
                <td>{{ it.spec }}</td>
                <td class="num">{{ it.plannedQty }}</td>
                <td><input v-model.number="it.actualReturnQty" type="number" class="inp-num" /></td>
              </tr>
              <tr v-if="!checkItems.length"><td colspan="4" class="empty">无明细</td></tr>
            </tbody>
          </table>
          <div style="margin-top:16px;text-align:right">
            <button class="btn" @click="checkOpen = false">取消</button>
            <button class="btn-primary" :disabled="checkSubmitting" @click="submitCheck">{{ checkSubmitting ? '提交中...' : '确认验收' }}</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 改派池抽屉 -->
    <div v-if="poolOpen" class="drawer-mask" @click.self="poolOpen = false">
      <div class="drawer">
        <div class="drawer-head">
          <h3>返仓改派池（待重新派送）</h3>
          <button class="btn-link" @click="poolOpen = false">关闭</button>
        </div>
        <div class="drawer-body">
          <div v-if="poolLoading" class="drawer-loading">加载中...</div>
          <div v-else>
            <table class="dt">
              <thead>
                <tr>
                  <th>改派返仓单号</th>
                  <th>发货单号</th>
                  <th>客户</th>
                  <th>合计数量</th>
                  <th>改送日期</th>
                  <th>改派次数</th>
                  <th>验收时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in poolRows" :key="r.returnId">
                  <td>{{ r.returnNo }}</td>
                  <td>{{ r.receiptNo }}</td>
                  <td>{{ r.customerName }}</td>
                  <td class="num">{{ r.totalQty }}</td>
                  <td>{{ r.rescheduleDate }}</td>
                  <td class="num">第 {{ r.rescheduleCount }} 次</td>
                  <td>{{ r.checkedAt }}</td>
                  <td><button class="btn-link" @click="poolRedispatch(r)">重新派送</button></td>
                </tr>
                <tr v-if="!poolRows.length"><td colspan="8" class="empty">暂无待重新派送的发货单</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tms-page { padding: 16px; }
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.page-head h2 { margin: 0; font-size: 18px; }
.head-actions { display: flex; gap: 8px; }
.btn { padding: 6px 14px; border: 1px solid #d1d5db; background: #fff; border-radius: 6px; cursor: pointer; font-size: 13px; }
.btn:hover { background: #f9fafb; }
.btn-primary { padding: 6px 14px; border: none; background: #2563eb; color: #fff; border-radius: 6px; cursor: pointer; font-size: 13px; margin-left: 8px; }
.btn-primary:disabled { background: #9ca3af; cursor: not-allowed; }
.btn-link { color: #2563eb; background: none; border: none; cursor: pointer; font-size: 13px; padding: 2px 4px; margin-right: 6px; }
.btn-link:hover { text-decoration: underline; }
.feedback { padding: 8px 12px; background: #fef3c7; border-radius: 6px; margin-bottom: 8px; font-size: 13px; color: #92400e; }
.sel, .inp { padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; }
.inp-num { width: 80px; padding: 4px 8px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 13px; }
.tag { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.tag-orange { background: #fff7ed; color: #c2410c; }
.tag-blue { background: #dbeafe; color: #1d4ed8; }
.tag-green { background: #f0fdf4; color: #15803d; }
.tag-gray { background: #f3f4f6; color: #6b7280; }
.drawer-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); z-index: 100; display: flex; justify-content: flex-end; }
.drawer { width: 640px; background: #fff; height: 100%; overflow-y: auto; box-shadow: -2px 0 8px rgba(0,0,0,0.1); }
.drawer-head { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; border-bottom: 1px solid #e5e7eb; position: sticky; top: 0; background: #fff; z-index: 1; }
.drawer-head h3 { margin: 0; font-size: 16px; }
.drawer-loading { padding: 40px; text-align: center; color: #6b7280; }
.drawer-body { padding: 20px; }
.section { margin-bottom: 24px; }
.section h4 { margin: 0 0 12px; font-size: 14px; color: #374151; border-left: 3px solid #2563eb; padding-left: 8px; }
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 16px; }
.grid-2 div { display: flex; flex-direction: column; }
.grid-2 label { font-size: 11px; color: #6b7280; margin-bottom: 2px; }
.grid-2 span { font-size: 13px; color: #111827; }
.dt { width: 100%; border-collapse: collapse; font-size: 12px; }
.dt th { background: #f9fafb; padding: 8px; text-align: left; border-bottom: 1px solid #e5e7eb; font-weight: 600; color: #374151; }
.dt td { padding: 8px; border-bottom: 1px solid #f3f4f6; color: #111827; }
.dt td.num { text-align: right; font-variant-numeric: tabular-nums; }
.dt .empty { text-align: center; color: #9ca3af; padding: 16px; }
.photo-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.photo-item img { width: 100%; height: 120px; object-fit: cover; border-radius: 6px; border: 1px solid #e5e7eb; }
</style>
