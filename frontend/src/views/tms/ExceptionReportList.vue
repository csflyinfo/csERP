<script setup>
/**
 * 异常上报处理（P3-4 配套 ERP 端）
 *
 * 接口：
 *   POST /tms/exception/page                列表（紧急优先、未处理优先）
 *   GET  /tms/exception/{id}                详情（含现场照片）
 *   POST /tms/exception/{id}/handle         接手处理（PENDING -> HANDLING）
 *   POST /tms/exception/{id}/close          关闭（-> CLOSED，处理结果必填）
 *
 * 状态：PENDING(待处理) / HANDLING(处理中) / CLOSED(已关闭)
 * 严重程度：URGENT(紧急) / NORMAL(一般)，由服务端按 TMS_EXCEPTION_URGENT_TYPES 强制判定
 * 业务：司机现场上报（车辆故障/事故/货损等）-> 调度员接手 -> 线下处置 -> 填写结论关闭
 */
import { ref, onMounted } from 'vue'
import QueryBar from '../../components/QueryBar.vue'
import ProTable from '../../components/ProTable.vue'
import { post, get } from '../../api/client.js'

const loading = ref(false)
const tableRows = ref([])
const pageNo = ref(1)
const pageSize = ref(100)
const total = ref(0)
const feedback = ref('')
const queryFilters = ref({})

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref({ photos: [] })

const closeOpen = ref(false)
const closeSubmitting = ref(false)
const closeReportId = ref('')
const closeReportNo = ref('')
const closeResult = ref('')

const columns = [
  { key: 'c0', title: '上报单号', width: 160 },
  { key: 'c1', title: '严重程度', width: 90 },
  { key: 'c2', title: '异常类型', width: 110 },
  { key: 'c3', title: '标题', width: 180 },
  { key: 'c4', title: '司机', width: 90 },
  { key: 'c5', title: '车牌', width: 100 },
  { key: 'c6', title: '客户', width: 160 },
  { key: 'c7', title: '上报时间', width: 150 },
  { key: 'c8', title: '处理人', width: 90 },
  { key: 'c9', title: '状态', width: 90 },
  { key: 'c10', title: '操作', width: 150 },
]

const typeOptions = [
  { value: 'VEHICLE_FAULT', label: '车辆故障' },
  { value: 'TRAFFIC_ACCIDENT', label: '交通事故' },
  { value: 'GOODS_DAMAGE', label: '货物破损' },
  { value: 'STORE_CLOSED', label: '门店关门' },
  { value: 'WEATHER', label: '天气阻断' },
  { value: 'ROAD_BLOCKED', label: '道路管控' },
  { value: 'OTHER', label: '其他异常' },
]

const statusOptions = [
  { value: 'PENDING', label: '待处理' },
  { value: 'HANDLING', label: '处理中' },
  { value: 'CLOSED', label: '已关闭' },
]

const statusMap = {
  PENDING: { text: '待处理', cls: 'wait' },
  HANDLING: { text: '处理中', cls: 'wait' },
  CLOSED: { text: '已关闭', cls: 'ok' },
}

const queryFields = [
  '上报单号',
  { label: '状态', type: 'select', options: statusOptions },
  { label: '异常类型', type: 'select', options: typeOptions },
  { label: '严重程度', type: 'select', options: [{ value: 'URGENT', label: '紧急' }, { value: 'NORMAL', label: '一般' }] },
  '司机',
  '车牌',
  '客户',
  { label: '上报日期', type: 'dateRange', keyFrom: 'beginDate', keyTo: 'endDate' },
]

function show(msg) { feedback.value = msg; setTimeout(() => (feedback.value = ''), 2500) }

function statusText(s) { return statusMap[s]?.text || s || '' }

async function loadRows() {
  loading.value = true
  try {
    const data = await post('/tms/exception/page', {
      pageNo: pageNo.value, pageSize: pageSize.value, filters: queryFilters.value,
    })
    tableRows.value = (data.records || []).map(r => ({
      c0: r.reportNo || '',
      c1: r.severity === 'URGENT' ? '紧急' : '一般',
      c2: r.exceptionTypeName || r.exceptionType || '',
      c3: r.title || '',
      c4: r.driverName || '',
      c5: r.vehicleNo || '',
      c6: r.customerName || '',
      c7: r.reportedAt || r.createTime || '',
      c8: r.handler || '',
      c9: statusText(r.status),
      c10: r.status === 'PENDING' ? '详情 接手 关闭' : (r.status === 'HANDLING' ? '详情 关闭' : '详情'),
      _raw: r,
    }))
    total.value = data.total || tableRows.value.length
  } catch (e) {
    tableRows.value = []; total.value = 0
    show('加载失败：' + (e.message || '请检查后端服务'))
  } finally { loading.value = false }
}

function onQuery(filters) {
  const f = {}
  if (filters['上报单号']) f.reportNo = filters['上报单号']
  if (filters['状态']) f.status = filters['状态']
  if (filters['异常类型']) f.exceptionType = filters['异常类型']
  if (filters['严重程度']) f.severity = filters['严重程度']
  if (filters['司机']) f.driverName = filters['司机']
  if (filters['车牌']) f.vehicleNo = filters['车牌']
  if (filters['客户']) f.customerName = filters['客户']
  if (filters.beginDate) f.beginDate = filters.beginDate
  if (filters.endDate) f.endDate = filters.endDate
  queryFilters.value = f
  pageNo.value = 1
  loadRows()
}
function onReset() { queryFilters.value = {}; pageNo.value = 1; loadRows() }
function handlePageChange(n) { pageNo.value = n; loadRows() }
function handlePageSizeChange(s) { pageSize.value = s; pageNo.value = 1; loadRows() }

async function openDetail(row) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = { photos: [] }
  try {
    detail.value = await get('/tms/exception/' + row._raw.reportId)
  } catch (e) {
    show('详情加载失败：' + (e.message || '未知错误'))
  } finally { detailLoading.value = false }
}

async function handleReport(row) {
  if (!confirm('确认接手处理【' + row._raw.reportNo + '】？\n\n接手后状态变为「处理中」，并记录你为处理人。')) return
  try {
    await post('/tms/exception/' + row._raw.reportId + '/handle', {})
    show('已接手，状态变更为处理中')
    loadRows()
  } catch (e) { show('接手失败：' + (e.message || '未知错误')) }
}

function openClose(row) {
  closeReportId.value = row._raw.reportId
  closeReportNo.value = row._raw.reportNo
  closeResult.value = row._raw.handleResult || ''
  closeOpen.value = true
}

async function submitClose() {
  if (!closeResult.value.trim()) { show('请填写处理结果'); return }
  closeSubmitting.value = true
  try {
    await post('/tms/exception/' + closeReportId.value + '/close', { handleResult: closeResult.value.trim() })
    closeOpen.value = false
    detailOpen.value = false
    show('异常已关闭')
    loadRows()
  } catch (e) {
    show('关闭失败：' + (e.message || '未知错误'))
  } finally { closeSubmitting.value = false }
}

function handleAction(action, row) {
  if (action === '详情') openDetail(row)
  else if (action === '接手') handleReport(row)
  else if (action === '关闭') openClose(row)
}
function onRowAction(action, row) { if (action === '查看') openDetail(row) }

onMounted(() => { loadRows() })
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn" @click="loadRows">刷新</button>
    </div>
    <QueryBar :fields="queryFields" :max-visible="queryFields.length" @query="onQuery" @reset="onReset" />
    <div v-if="loading" class="tips-inline"><span>正在加载...</span></div>
    <ProTable title="异常上报列表" :columns="columns" :rows="tableRows"
              :page-no="pageNo" :page-size="pageSize" :total="total"
              @row-action="onRowAction" @page-change="handlePageChange" @page-size-change="handlePageSizeChange">
      <template #c1="{ row }">
        <span class="badge" :class="row._raw.severity === 'URGENT' ? 'urgent' : ''">{{ row.c1 }}</span>
      </template>
      <template #c9="{ row }">
        <span class="badge" :class="statusMap[row._raw.status]?.cls || ''">{{ row.c9 }}</span>
      </template>
      <template #c10="{ row }">
        <button v-for="a in row.c10.split(' ')" :key="a" class="link link-btn"
                :class="{ 'danger-link': a === '关闭' }"
                @click="handleAction(a, row)">{{ a }}</button>
      </template>
    </ProTable>

    <div v-if="detailOpen" class="modal-lite" @click.self="detailOpen = false">
      <div class="modal-lite-box" style="width:min(920px,96vw)">
        <div class="modal-lite-head">
          <b>异常上报详情 · {{ detail.reportNo || '' }}</b>
          <button class="link link-btn" @click="detailOpen = false">关闭</button>
        </div>
        <div class="modal-lite-body">
          <div v-if="detailLoading" class="tips-inline"><span>正在加载...</span></div>
          <template v-else>
            <div class="grid4">
              <div class="field"><label>上报单号</label><input :value="detail.reportNo" readonly /></div>
              <div class="field"><label>异常类型</label><input :value="detail.exceptionTypeName" readonly /></div>
              <div class="field"><label>严重程度</label><input :value="detail.severity === 'URGENT' ? '紧急' : '一般'" readonly /></div>
              <div class="field"><label>状态</label><input :value="statusText(detail.status)" readonly /></div>
              <div class="field"><label>司机</label><input :value="detail.driverName" readonly /></div>
              <div class="field"><label>车牌</label><input :value="detail.vehicleNo" readonly /></div>
              <div class="field"><label>上报时间</label><input :value="detail.reportedAt || detail.createTime" readonly /></div>
              <div class="field"><label>入库时间</label><input :value="detail.createTime" readonly /></div>
              <div class="field"><label>客户</label><input :value="detail.customerName" readonly /></div>
              <div class="field"><label>客户编码</label><input :value="detail.customerCode" readonly /></div>
              <div class="field"><label>关联发货单</label><input :value="detail.receiptNo" readonly /></div>
              <div class="field"><label>调度单</label><input :value="detail.dispatchNo" readonly /></div>
              <div class="field"><label>行程号</label><input :value="detail.tripNo" readonly /></div>
              <div class="field"><label>处理人</label><input :value="detail.handler" readonly /></div>
              <div class="field"><label>接手时间</label><input :value="detail.handledAt" readonly /></div>
              <div class="field"><label>关闭时间</label><input :value="detail.closedAt" readonly /></div>
            </div>

            <div class="field" style="margin-top:12px"><label>异常标题</label><input :value="detail.title" readonly /></div>
            <div class="field" style="margin-top:10px">
              <label>异常描述</label>
              <textarea :value="detail.description" readonly style="height:72px;width:100%"></textarea>
            </div>
            <div v-if="detail.handleResult" class="field" style="margin-top:10px">
              <label>处理结果</label>
              <textarea :value="detail.handleResult" readonly style="height:60px;width:100%"></textarea>
            </div>
            <div v-if="detail.remark" class="field" style="margin-top:10px"><label>备注</label><input :value="detail.remark" readonly /></div>

            <div class="tablebox" style="margin-top:16px">
              <div class="toolbar"><b>上报位置</b></div>
              <div style="padding:10px">
                <div v-if="detail.longitude || detail.latitude" class="grid4">
                  <div class="field"><label>经度</label><input :value="detail.longitude" readonly /></div>
                  <div class="field"><label>纬度</label><input :value="detail.latitude" readonly /></div>
                  <div class="field"><label>定位精度(米)</label><input :value="detail.accuracy" readonly /></div>
                  <div class="field"><label>地址</label><input :value="detail.locationAddress" readonly /></div>
                </div>
                <div v-else class="muted-tip">司机上报时未获取到定位（可能在地下车库或信号盲区）</div>
              </div>
            </div>

            <div class="tablebox" style="margin-top:14px">
              <div class="toolbar"><b>现场照片（{{ detail.photos?.length || 0 }}）</b></div>
              <div style="padding:10px">
                <div v-if="detail.photos?.length" class="photo-grid">
                  <a v-for="(url, i) in detail.photos" :key="i" :href="url" target="_blank" class="photo-item">
                    <img :src="url" :alt="'现场照片' + (i + 1)" />
                  </a>
                </div>
                <div v-else class="muted-tip">无现场照片</div>
              </div>
            </div>
          </template>
        </div>
        <div class="modal-lite-foot">
          <button v-if="detail.status && detail.status !== 'CLOSED'" class="btn primary"
                  @click="openClose({ _raw: detail })">填写结论并关闭</button>
          <button class="btn" @click="detailOpen = false">关闭</button>
        </div>
      </div>
    </div>

    <div v-if="closeOpen" class="modal-lite" @click.self="closeOpen = false">
      <div class="modal-lite-box" style="width:min(560px,92vw)">
        <div class="modal-lite-head">
          <b>关闭异常 · {{ closeReportNo }}</b>
          <button class="link link-btn" @click="closeOpen = false">关闭</button>
        </div>
        <div class="modal-lite-body">
          <div class="tips-inline"><span>处理结论会回写到司机端，请写清处置方式与后续安排。</span></div>
          <div class="field">
            <label>处理结果（必填）</label>
            <textarea v-model="closeResult" placeholder="例：已联系维修厂现场更换轮胎，货物转由 B 车配送，18:30 完成签收"
                      style="height:110px;width:100%"></textarea>
          </div>
        </div>
        <div class="modal-lite-foot">
          <button class="btn" @click="closeOpen = false">取消</button>
          <button class="btn primary" :disabled="closeSubmitting" @click="submitClose">
            {{ closeSubmitting ? '提交中...' : '确认关闭' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="feedback" class="toast-inline">{{ feedback }}</div>
  </div>
</template>

<style scoped>
.badge.urgent { background: #fee2e2; color: #b91c1c; }
.muted-tip { color: #98a2b3; font-size: 12px; }
.photo-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.photo-item img { width: 100%; height: 120px; object-fit: cover; border-radius: 8px; border: 1px solid #d7e5f6; }
</style>
