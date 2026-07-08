<script setup>
/**
 * 销售退货单调度管理（V1.2 退货调度闭环 · ERP 端）
 *
 * 物流状态机：未安排 ──[安排调度]──> 已安排调度 ──[指派司机]──> 已调度 ──[APP退货签收]──> 司机已回收
 *
 * 接口：
 *   POST /tms/return-dispatch/page          列表（司机回收型退货单）
 *   POST /tms/return-dispatch/arrange       安排调度（未安排 → 已安排调度）
 *   POST /tms/return-dispatch/cancel-arrange 取消安排（已安排调度 → 未安排）
 *   POST /tms/return-dispatch/assign        指派司机（已安排调度 → 已调度）
 *   POST /tms/return-dispatch/auto-match    按客户自动匹配已安排调度退货单
 *   POST /base/master/employee/page         司机档案（前端过滤 is_deliveryman）
 */
import { ref, onMounted, computed } from 'vue'
import QueryBar from '../../components/QueryBar.vue'
import ProTable from '../../components/ProTable.vue'
import { post } from '../../api/client.js'

const loading = ref(false)
const tableRows = ref([])
const pageNo = ref(1)
const pageSize = ref(100)
const total = ref(0)
const feedback = ref('')
const queryFilters = ref({ logisticsStatus: 'ALL' })

// 司机档案
const drivers = ref([])

// 安排调度抽屉
const arrangeOpen = ref(false)
const arrangeForm = ref({ applyNo: '', customerName: '', remark: '' })

// 指派司机抽屉
const assignOpen = ref(false)
const assignForm = ref({ applyNo: '', customerName: '', returnQty: 0, driverId: '', driverName: '', dispatchId: '', tripId: '' })
const matchedReturns = ref([])

// 查看抽屉
const viewOpen = ref(false)
const viewRow = ref({})

const columns = [
  { key: 'c0', title: '退货单号' },
  { key: 'c1', title: '客户' },
  { key: 'c2', title: '仓库' },
  { key: 'c3', title: '退货日期' },
  { key: 'c4', title: '退货数', num: true },
  { key: 'c5', title: '签收数', num: true },
  { key: 'c6', title: '退货原因' },
  { key: 'c7', title: '物流状态' },
  { key: 'c8', title: '司机' },
  { key: 'c9', title: '安排时间' },
  { key: 'c10', title: '操作' },
]

const statusOptions = [
  { value: 'ALL', label: '全部' },
  { value: '未安排', label: '未安排' },
  { value: '已安排调度', label: '已安排调度' },
  { value: '已调度', label: '已调度' },
  { value: '司机已回收', label: '司机已回收' },
]

const queryFields = [
  { label: '物流状态', type: 'select', options: statusOptions },
  '退货单号',
  '客户',
  { label: '线路', type: 'text' },
]

function show(msg) { feedback.value = msg; setTimeout(() => (feedback.value = ''), 2500) }

function statusBadgeClass(s) {
  if (s === '司机已回收') return 'ok'
  if (s === '已调度') return 'ok'
  if (s === '已安排调度') return 'wait'
  return ''
}

async function loadDrivers() {
  try {
    const data = await post('/base/master/employee/page', { pageNo: 1, pageSize: 10000, filters: {} })
    drivers.value = (data.records || []).filter(r => r.isDeliveryman === true || r.isDeliveryman === 'true')
  } catch (e) { drivers.value = [] }
}

async function loadRows() {
  loading.value = true
  try {
    const data = await post('/tms/return-dispatch/page', {
      pageNo: pageNo.value, pageSize: pageSize.value, filters: queryFilters.value,
    })
    tableRows.value = (data.records || []).map(r => {
      const ls = r.logisticsStatus || ''
      let ops = '查看'
      if (ls === '未安排' && (r.billStatus === 'APPROVED' || r.billStatusText === '已审核')) ops = '安排调度 查看'
      else if (ls === '已安排调度') ops = '取消安排 指派司机 查看'
      return {
        c0: r.applyNo || '',
        c1: r.customerName || '',
        c2: r.warehouse || '',
        c3: r.billDate || '',
        c4: r.returnQty ?? 0,
        c5: r.signedQty ?? 0,
        c6: r.returnReason || '',
        c7: ls,
        c8: r.driverName || '',
        c9: r.arrangeTime || '',
        c10: ops,
        _raw: r,
      }
    })
    total.value = data.total || tableRows.value.length
  } catch (e) {
    tableRows.value = []; total.value = 0
    show('加载失败：' + (e.message || '请检查后端服务'))
  } finally { loading.value = false }
}

function onQuery(filters) {
  // QueryBar 用「物流状态」字段名生成 key 为「物流状态」，转成 logisticsStatus
  const f = { ...filters }
  if (f['物流状态']) f.logisticsStatus = f['物流状态']
  if (f['退货单号']) f.applyNo = f['退货单号']
  if (f['线路']) f.routeLine = f['线路']
  queryFilters.value = f
  pageNo.value = 1
  loadRows()
}
function onReset() { queryFilters.value = { logisticsStatus: 'ALL' }; pageNo.value = 1; loadRows() }
function handlePageChange(n) { pageNo.value = n; loadRows() }
function handlePageSizeChange(s) { pageSize.value = s; pageNo.value = 1; loadRows() }

function openArrange(row) {
  arrangeForm.value = { applyNo: row._raw.applyNo, customerName: row._raw.customerName, remark: '' }
  arrangeOpen.value = true
}
async function submitArrange() {
  try {
    await post('/tms/return-dispatch/arrange', { applyNo: arrangeForm.value.applyNo, remark: arrangeForm.value.remark })
    show('已安排调度，进入调度池')
    arrangeOpen.value = false
    loadRows()
  } catch (e) { show('安排调度失败：' + (e.message || '未知错误')) }
}

async function cancelArrange(row) {
  if (!confirm(`确认取消【${row._raw.applyNo}】的安排调度？\n\n取消后物流状态回到「未安排」。`)) return
  try {
    await post('/tms/return-dispatch/cancel-arrange', { applyNo: row._raw.applyNo })
    show('已取消安排调度')
    loadRows()
  } catch (e) { show('取消失败：' + (e.message || '未知错误')) }
}

function openAssign(row) {
  assignForm.value = {
    applyNo: row._raw.applyNo,
    customerName: row._raw.customerName,
    returnQty: row._raw.returnQty || 0,
    driverId: '', driverName: '',
    dispatchId: row._raw.dispatchId || '',
    tripId: row._raw.tripId || '',
  }
  matchedReturns.value = []
  assignOpen.value = true
  // 自动匹配同客户已安排调度退货单
  post('/tms/return-dispatch/auto-match', { customerCode: row._raw.customerCode })
    .then(list => { matchedReturns.value = list || [] })
    .catch(() => { matchedReturns.value = [] })
}
function onDriverChange() {
  const d = drivers.value.find(x => x.employeeId === assignForm.value.driverId)
  assignForm.value.driverName = d ? d.employeeName : ''
}
async function submitAssign() {
  if (!assignForm.value.driverId) { show('请选择司机'); return }
  try {
    await post('/tms/return-dispatch/assign', {
      applyNo: assignForm.value.applyNo,
      driverId: assignForm.value.driverId,
      driverName: assignForm.value.driverName,
      dispatchId: assignForm.value.dispatchId,
      tripId: assignForm.value.tripId,
    })
    show('已指派司机，物流状态 → 已调度')
    assignOpen.value = false
    loadRows()
  } catch (e) { show('指派失败：' + (e.message || '未知错误')) }
}

function openView(row) { viewRow.value = row._raw; viewOpen.value = true }

function handleAction(action, row) {
  if (action === '安排调度') openArrange(row)
  else if (action === '取消安排') cancelArrange(row)
  else if (action === '指派司机') openAssign(row)
  else if (action === '查看') openView(row)
}
function onRowAction(action, row) { handleAction(action, row) }

onMounted(() => { loadDrivers(); loadRows() })
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn" @click="loadRows">刷新</button>
    </div>
    <QueryBar :fields="queryFields" :defaults="{ '物流状态': 'ALL' }" @query="onQuery" @reset="onReset" />
    <div v-if="loading" class="tips-inline"><span>正在加载...</span></div>
    <div class="tips-inline">
      <span>⚠ 物流状态流转：未安排 → 已安排调度 → 已调度 → 司机已回收。仅「司机回收」型退货单参与调度。</span>
    </div>
    <ProTable title="退货单调度管理" :columns="columns" :rows="tableRows"
              :page-no="pageNo" :page-size="pageSize" :total="total"
              @row-action="onRowAction" @page-change="handlePageChange" @page-size-change="handlePageSizeChange">
      <template #c4="{ row }"><span class="num">{{ row.c4 }}</span></template>
      <template #c5="{ row }"><span class="num">{{ row.c5 }}</span></template>
      <template #c7="{ row }">
        <span class="badge" :class="statusBadgeClass(row.c7)">{{ row.c7 || '未安排' }}</span>
      </template>
      <template #c10="{ row }">
        <button v-for="a in row.c10.split(' ')" :key="a" class="link link-btn"
                :class="{ 'danger-link': a === '取消安排' }"
                @click="handleAction(a, row)">{{ a }}</button>
      </template>
    </ProTable>

    <!-- 安排调度抽屉 -->
    <div v-if="arrangeOpen" class="drawer-overlay" @click.self="arrangeOpen = false">
      <div class="drawer-lite">
        <div class="modal-lite-box">
          <div class="modal-lite-head"><b>安排调度</b><button class="link link-btn" @click="arrangeOpen = false">关闭</button></div>
          <div class="modal-lite-body">
            <div class="grid4">
              <div class="field"><label>退货单号</label><input :value="arrangeForm.applyNo" readonly /></div>
              <div class="field"><label>客户</label><input :value="arrangeForm.customerName" readonly /></div>
            </div>
            <div class="field" style="margin-top:12px"><label>安排备注</label>
              <textarea v-model="arrangeForm.remark" rows="3" placeholder="可选，记录安排调度说明"></textarea>
            </div>
            <div class="tips-inline"><span>⚠ 确认后物流状态：未安排 → 已安排调度，该退货单进入调度池作为取货任务。</span></div>
          </div>
          <div class="modal-lite-foot">
            <button class="btn" @click="arrangeOpen = false">取消</button>
            <button class="btn primary" @click="submitArrange">确认安排调度</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 指派司机抽屉 -->
    <div v-if="assignOpen" class="drawer-overlay" @click.self="assignOpen = false">
      <div class="drawer-lite">
        <div class="modal-lite-box">
          <div class="modal-lite-head"><b>指派司机</b><button class="link link-btn" @click="assignOpen = false">关闭</button></div>
          <div class="modal-lite-body">
            <div class="grid4">
              <div class="field"><label>退货单号</label><input :value="assignForm.applyNo" readonly /></div>
              <div class="field"><label>客户</label><input :value="assignForm.customerName" readonly /></div>
              <div class="field"><label>退货数量</label><input :value="assignForm.returnQty" readonly /></div>
              <div class="field"><label>选择司机</label>
                <select v-model="assignForm.driverId" @change="onDriverChange">
                  <option value="">请选择司机</option>
                  <option v-for="d in drivers" :key="d.employeeId" :value="d.employeeId">{{ d.employeeName }}（{{ d.mobile || '' }}）</option>
                </select>
              </div>
            </div>
            <div v-if="matchedReturns.length" class="tips-inline" style="margin-top:12px">
              <span>⚠ 该客户另有 {{ matchedReturns.length }} 张已安排调度退货单，建议一并指派同一司机：{{ matchedReturns.map(x => x.applyNo).join('、') }}</span>
            </div>
            <div class="tips-inline"><span>⚠ 确认后物流状态：已安排调度 → 已调度，回写司机信息。</span></div>
          </div>
          <div class="modal-lite-foot">
            <button class="btn" @click="assignOpen = false">取消</button>
            <button class="btn primary" @click="submitAssign">确认指派</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 查看抽屉 -->
    <div v-if="viewOpen" class="drawer-overlay" @click.self="viewOpen = false">
      <div class="drawer-lite">
        <div class="modal-lite-box">
          <div class="modal-lite-head"><b>退货单详情</b><button class="link link-btn" @click="viewOpen = false">关闭</button></div>
          <div class="modal-lite-body">
            <div class="grid4">
              <div class="field"><label>退货单号</label><input :value="viewRow.applyNo" readonly /></div>
              <div class="field"><label>客户</label><input :value="viewRow.customerName" readonly /></div>
              <div class="field"><label>仓库</label><input :value="viewRow.warehouse" readonly /></div>
              <div class="field"><label>退货日期</label><input :value="viewRow.billDate" readonly /></div>
              <div class="field"><label>退货数量</label><input :value="viewRow.returnQty" readonly /></div>
              <div class="field"><label>签收数量</label><input :value="viewRow.signedQty" readonly /></div>
              <div class="field"><label>物流状态</label><input :value="viewRow.logisticsStatus" readonly /></div>
              <div class="field"><label>司机</label><input :value="viewRow.driverName" readonly /></div>
              <div class="field"><label>线路</label><input :value="viewRow.routeLine" readonly /></div>
              <div class="field"><label>片区</label><input :value="viewRow.territory" readonly /></div>
              <div class="field"><label>安排时间</label><input :value="viewRow.arrangeTime" readonly /></div>
              <div class="field"><label>调度单号</label><input :value="viewRow.dispatchId" readonly /></div>
            </div>
            <div class="field" style="margin-top:12px"><label>退货原因</label><input :value="viewRow.returnReason" readonly /></div>
            <div class="field" style="margin-top:12px"><label>安排备注</label><input :value="viewRow.arrangeRemark" readonly /></div>
          </div>
          <div class="modal-lite-foot">
            <button class="btn" @click="viewOpen = false">关闭</button>
          </div>
        </div>
      </div>
    </div>
  </div>
  <div v-if="feedback" class="toast-inline">{{ feedback }}</div>
</template>
