<script setup>
/**
 * 司机退货单管理（P3 司机现场退货回收）
 *
 * 接口：
 *   POST /tms/driver-return/page    司机退货单列表
 *   GET  /tms/driver-return/{id}    司机退货单详情（含明细 + 照片 + 入库单）
 *
 * 状态：PENDING(待返仓) / WAREHOUSED(已入库)
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

// 详情抽屉
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref({ details: [], photos: [], inbounds: [] })

const columns = [
  { key: 'c0', title: '退货单号' },
  { key: 'c1', title: '退货申请号' },
  { key: 'c2', title: '退货日期' },
  { key: 'c3', title: '司机' },
  { key: 'c4', title: '客户' },
  { key: 'c5', title: '退货件数', num: true },
  { key: 'c6', title: '已签收', num: true },
  { key: 'c7', title: '退货原因' },
  { key: 'c8', title: '仓库' },
  { key: 'c9', title: '状态' },
  { key: 'c10', title: '操作' },
]

const statusOptions = [
  { value: 'PENDING', label: '待返仓' },
  { value: 'WAREHOUSED', label: '已入库' },
]

const statusMap = {
  PENDING: { text: '待返仓', cls: 'tag-orange' },
  WAREHOUSED: { text: '已入库', cls: 'tag-green' },
}

// QueryBar 为配置式组件：只认 :fields，且以中文 label 为 key 回传（详见 components/QueryBar.vue）
const queryFields = [
  '退货单号',
  { label: '状态', type: 'select', options: statusOptions },
  '司机',
  '客户',
  { label: '退货日期', type: 'dateRange', keyFrom: 'startDate', keyTo: 'endDate' },
]

async function loadList() {
  loading.value = true
  feedback.value = ''
  try {
    const data = await post('/tms/driver-return/page', {
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      filters: queryFilters.value,
    })
    tableRows.value = (data.records || []).map(r => ({
      c0: r.driverReturnNo,
      c1: r.returnApplyNo,
      c2: r.returnDate,
      c3: r.driverName,
      c4: r.customerName,
      c5: r.qty,
      c6: r.signedQty,
      c7: r.returnReason || '-',
      c8: r.warehouse || '-',
      c9: statusMap[r.status]?.text || r.status,
      c10: '查看详情',
      _raw: r,
    }))
    total.value = data.total || 0
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
    const res = await get(`/tms/driver-return/${row._raw.driverReturnId}`)
    detail.value = res || { details: [], photos: [], inbounds: [] }
  } catch (e) {
    feedback.value = '加载详情失败：' + (e.message || '')
  } finally {
    detailLoading.value = false
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
  if (filters['退货单号']) f.driverReturnNo = filters['退货单号']
  if (filters['状态']) f.status = filters['状态']
  if (filters['司机']) f.driverName = filters['司机']
  if (filters['客户']) f.customerName = filters['客户']
  if (filters.startDate) f.startDate = filters.startDate
  if (filters.endDate) f.endDate = filters.endDate
  queryFilters.value = f
  pageNo.value = 1
  loadList()
}

function onReset() {
  queryFilters.value = {}
  pageNo.value = 1
  loadList()
}

// 模板里不能直接用 window（不在 Vue 模板全局白名单内），需经方法透出
function openPhoto(url) {
  if (url) window.open(url, '_blank')
}

onMounted(loadList)
</script>

<template>
  <div class="tms-page">
    <div class="page-head">
      <h2>司机退货单管理</h2>
      <div class="head-actions">
        <button class="btn" @click="loadList">刷新</button>
      </div>
    </div>

    <QueryBar :fields="queryFields" :max-visible="queryFields.length" @query="onQuery" @reset="onReset" />

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
      <template #c9="{ row }">
        <span :class="['tag', statusMap[row._raw.status]?.cls || 'tag-gray']">{{ row.c9 }}</span>
      </template>
      <template #c10="{ row }">
        <button class="btn-link" @click="viewDetail(row)">查看详情</button>
      </template>
    </ProTable>

    <!-- 详情抽屉 -->
    <div v-if="detailOpen" class="drawer-mask" @click.self="detailOpen = false">
      <div class="drawer">
        <div class="drawer-head">
          <h3>司机退货单详情</h3>
          <button class="btn-link" @click="detailOpen = false">关闭</button>
        </div>
        <div v-if="detailLoading" class="drawer-loading">加载中...</div>
        <div v-else class="drawer-body">
          <!-- 基本信息 -->
          <div class="section">
            <h4>基本信息</h4>
            <div class="grid-2">
              <div><label>退货单号</label><span>{{ detail.driverReturnNo }}</span></div>
              <div><label>退货申请号</label><span>{{ detail.returnApplyNo }}</span></div>
              <div><label>司机</label><span>{{ detail.driverName }}</span></div>
              <div><label>客户</label><span>{{ detail.customerName }} ({{ detail.customerCode }})</span></div>
              <div><label>退货日期</label><span>{{ detail.returnDate }}</span></div>
              <div><label>退货件数</label><span>{{ detail.qty }}</span></div>
              <div><label>已签收</label><span>{{ detail.signedQty }}</span></div>
              <div><label>状态</label><span :class="['tag', statusMap[detail.status]?.cls]">{{ statusMap[detail.status]?.text }}</span></div>
              <div><label>退货原因</label><span>{{ detail.returnReason || '-' }}</span></div>
              <div><label>仓库</label><span>{{ detail.warehouse || '-' }}</span></div>
              <div><label>调度单</label><span>{{ detail.dispatchNo || '-' }}</span></div>
              <div><label>行程</label><span>{{ detail.tripNo || '-' }}</span></div>
            </div>
            <div v-if="detail.remark"><label style="margin-top:8px;display:block">备注</label><span>{{ detail.remark }}</span></div>
          </div>

          <!-- 退货商品明细 -->
          <div class="section">
            <h4>退货商品明细</h4>
            <table class="dt">
              <thead>
                <tr>
                  <th>商品编码</th>
                  <th>商品名称</th>
                  <th>规格</th>
                  <th>单位</th>
                  <th>数量</th>
                  <th>批次</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="d in detail.details" :key="d.detailId">
                  <td>{{ d.goodsCode }}</td>
                  <td>{{ d.goodsName }}</td>
                  <td>{{ d.spec }}</td>
                  <td>{{ d.unitName }}</td>
                  <td class="num">{{ d.qty }}</td>
                  <td>{{ d.batchNo || '-' }}</td>
                </tr>
                <tr v-if="!detail.details?.length">
                  <td colspan="6" class="empty">无明细</td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- 现场照片 -->
          <div class="section">
            <h4>现场照片 ({{ detail.photos?.length || 0 }})</h4>
            <div v-if="detail.photos?.length" class="photo-grid">
              <div v-for="p in detail.photos" :key="p.photoId" class="photo-item">
                <img :src="p.photoUrl" :alt="p.photoType" @click="openPhoto(p.photoUrl)" />
                <span>{{ p.photoType }}</span>
              </div>
            </div>
            <div v-else class="empty">无照片</div>
          </div>

          <!-- 关联入库单 -->
          <div class="section">
            <h4>关联入库单</h4>
            <table class="dt">
              <thead>
                <tr>
                  <th>入库单号</th>
                  <th>仓库</th>
                  <th>数量</th>
                  <th>金额</th>
                  <th>状态</th>
                  <th>入库日期</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="ib in detail.inbounds" :key="ib.inboundId">
                  <td>{{ ib.inboundNo }}</td>
                  <td>{{ ib.warehouse }}</td>
                  <td class="num">{{ ib.qty }}</td>
                  <td class="num">{{ ib.amount }}</td>
                  <td><span :class="['tag', ib.status === 'AUDITED' ? 'tag-green' : 'tag-orange']">{{ ib.status === 'AUDITED' ? '已审核' : '待审核' }}</span></td>
                  <td>{{ ib.billDate }}</td>
                </tr>
                <tr v-if="!detail.inbounds?.length">
                  <td colspan="6" class="empty">尚未生成入库单（待司机返仓交接确认）</td>
                </tr>
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
.btn-link { color: #2563eb; background: none; border: none; cursor: pointer; font-size: 13px; padding: 2px 4px; }
.btn-link:hover { text-decoration: underline; }
.feedback { padding: 8px 12px; background: #fef3c7; border-radius: 6px; margin-bottom: 8px; font-size: 13px; color: #92400e; }
.sel, .inp { padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; }
.tag { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.tag-orange { background: #fff7ed; color: #c2410c; }
.tag-green { background: #f0fdf4; color: #15803d; }
.tag-gray { background: #f3f4f6; color: #6b7280; }
.drawer-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); z-index: 100; display: flex; justify-content: flex-end; }
.drawer { width: 600px; background: #fff; height: 100%; overflow-y: auto; box-shadow: -2px 0 8px rgba(0,0,0,0.1); }
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
.photo-item { position: relative; }
.photo-item img { width: 100%; height: 120px; object-fit: cover; border-radius: 6px; cursor: pointer; border: 1px solid #e5e7eb; }
.photo-item span { display: block; text-align: center; font-size: 11px; color: #6b7280; margin-top: 2px; }
</style>
