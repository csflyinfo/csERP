<script setup>
/**
 * 调度单列表 + 详情（V1.2 含发货单 + 退货单取货任务明细）
 *
 * 接口：
 *   POST /tms/dispatch/page    调度单列表
 *   POST /tms/dispatch/detail  调度单详情（明细 + 行程）
 *   POST /tms/dispatch/cancel  取消调度（回退发货单/退货单状态）
 */
import { ref, onMounted } from 'vue'
import QueryBar from '../../components/QueryBar.vue'
import ProTable from '../../components/ProTable.vue'
import { post } from '../../api/client.js'

const loading = ref(false)
const tableRows = ref([])
const pageNo = ref(1)
const pageSize = ref(100)
const total = ref(0)
const feedback = ref('')
const queryFilters = ref({})

// 详情抽屉
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref({ details: [], trips: [] })

const columns = [
  { key: 'c0', title: '调度单号' },
  { key: 'c1', title: '调度日期' },
  { key: 'c2', title: '线路' },
  { key: 'c3', title: '片区' },
  { key: 'c4', title: '司机' },
  { key: 'c5', title: '车牌' },
  { key: 'c6', title: '发货件数', num: true },
  { key: 'c7', title: '退货件数', num: true },
  { key: 'c8', title: '门店数', num: true },
  { key: 'c9', title: '应收金额', num: true },
  { key: 'c10', title: '状态' },
  { key: 'c11', title: '创建时间' },
  { key: 'c12', title: '操作' },
]

const statusOptions = [
  { value: 'ASSIGNED', label: '已分配' },
  { value: 'LOADED', label: '已装车' },
  { value: 'DEPARTED', label: '已发车' },
  { value: 'DELIVERING', label: '配送中' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CANCELLED', label: '已取消' },
]

const queryFields = [
  '调度单号',
  { label: '状态', type: 'select', options: statusOptions },
  '司机',
  { label: '线路', type: 'text' },
]

function show(msg) { feedback.value = msg; setTimeout(() => (feedback.value = ''), 2500) }

function statusBadgeClass(s) {
  if (s === '已完成') return 'ok'
  if (s === '已取消') return ''
  return 'wait'
}

async function loadRows() {
  loading.value = true
  try {
    const data = await post('/tms/dispatch/page', {
      pageNo: pageNo.value, pageSize: pageSize.value, filters: queryFilters.value,
    })
    tableRows.value = (data.records || []).map(r => {
      const st = r.statusText || r.status || ''
      return {
        c0: r.dispatchNo || '',
        c1: r.dispatchDate || '',
        c2: r.routeLine || '',
        c3: r.territory || '',
        c4: r.driverName || '',
        c5: r.vehiclePlate || '',
        c6: r.loadedQty ?? 0,
        c7: r.returnQty ?? 0,
        c8: r.storeCount ?? 0,
        c9: r.amount ?? 0,
        c10: st,
        c11: r.createTime || '',
        c12: (st === '已完成' || st === '已取消') ? '查看' : '查看 取消',
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
  const f = { ...filters }
  if (f['调度单号']) f.dispatchNo = f['调度单号']
  if (f['司机']) f.driverName = f['司机']
  if (f['线路']) f.routeLine = f['线路']
  if (f['状态']) f.status = f['状态']
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
  detail.value = { details: [], trips: [] }
  try {
    const d = await post('/tms/dispatch/detail', { dispatchId: row._raw.dispatchId })
    detail.value = d
  } catch (e) {
    show('详情加载失败：' + (e.message || '未知错误'))
  } finally { detailLoading.value = false }
}

async function cancelDispatch(row) {
  if (!confirm(`确认取消调度单【${row._raw.dispatchNo}】？\n\n取消后发货单/退货单状态回退至调度池。`)) return
  try {
    await post('/tms/dispatch/cancel', { dispatchId: row._raw.dispatchId })
    show('已取消调度，相关单据已回退至调度池')
    loadRows()
  } catch (e) { show('取消失败：' + (e.message || '未知错误')) }
}

function handleAction(action, row) {
  if (action === '查看') openDetail(row)
  else if (action === '取消') cancelDispatch(row)
}
function onRowAction(action, row) { handleAction(action, row) }

onMounted(() => { loadRows() })
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn" @click="loadRows">刷新</button>
    </div>
    <QueryBar :fields="queryFields" @query="onQuery" @reset="onReset" />
    <div v-if="loading" class="tips-inline"><span>正在加载...</span></div>
    <ProTable title="调度单列表" :columns="columns" :rows="tableRows"
              :page-no="pageNo" :page-size="pageSize" :total="total"
              @row-action="onRowAction" @page-change="handlePageChange" @page-size-change="handlePageSizeChange">
      <template #c10="{ row }">
        <span class="badge" :class="statusBadgeClass(row.c10)">{{ row.c10 }}</span>
      </template>
      <template #c12="{ row }">
        <button v-for="a in row.c12.split(' ')" :key="a" class="link link-btn"
                :class="{ 'danger-link': a === '取消' }"
                @click="handleAction(a, row)">{{ a }}</button>
      </template>
    </ProTable>

    <!-- 详情抽屉 -->
    <div v-if="detailOpen" class="drawer-overlay" @click.self="detailOpen = false">
      <div class="drawer-lite">
        <div class="modal-lite-box">
          <div class="modal-lite-head">
            <b>调度单详情 · {{ detail.dispatchNo || '' }}</b>
            <button class="link link-btn" @click="detailOpen = false">关闭</button>
          </div>
          <div class="modal-lite-body">
            <div v-if="detailLoading" class="tips-inline"><span>正在加载...</span></div>
            <template v-else>
              <!-- 调度单头部 -->
              <div class="grid4">
                <div class="field"><label>调度单号</label><input :value="detail.dispatchNo" readonly /></div>
                <div class="field"><label>调度日期</label><input :value="detail.dispatchDate" readonly /></div>
                <div class="field"><label>线路</label><input :value="detail.routeLine" readonly /></div>
                <div class="field"><label>片区</label><input :value="detail.territory" readonly /></div>
                <div class="field"><label>司机</label><input :value="detail.driverName" readonly /></div>
                <div class="field"><label>车牌</label><input :value="detail.vehiclePlate" readonly /></div>
                <div class="field"><label>车型</label><input :value="detail.vehicleType" readonly /></div>
                <div class="field"><label>载重</label><input :value="detail.loadCapacity" readonly /></div>
                <div class="field"><label>发货件数</label><input :value="detail.loadedQty" readonly /></div>
                <div class="field"><label>退货件数</label><input :value="detail.returnQty" readonly /></div>
                <div class="field"><label>门店数</label><input :value="detail.storeCount" readonly /></div>
                <div class="field"><label>应收金额</label><input :value="detail.amount" readonly /></div>
                <div class="field"><label>状态</label><input :value="detail.statusText" readonly /></div>
                <div class="field"><label>安排人</label><input :value="detail.arrangeUser" readonly /></div>
                <div class="field"><label>安排时间</label><input :value="detail.arrangeTime" readonly /></div>
                <div class="field"><label>创建时间</label><input :value="detail.createTime" readonly /></div>
              </div>
              <div v-if="detail.remark" class="field" style="margin-top:12px"><label>备注</label><input :value="detail.remark" readonly /></div>

              <!-- 配送明细 -->
              <div class="tablebox" style="margin-top:16px">
                <div class="toolbar"><b>配送明细（按顺序）</b></div>
                <div class="scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>顺序</th><th>类型</th><th>单据号</th><th>客户</th>
                        <th>地址</th><th class="num">件数</th><th>状态</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="d in detail.details" :key="d.detailId">
                        <td>{{ d.seqNo }}</td>
                        <td>
                          <span class="badge" :class="d.billType === 'RETURN' ? 'wait' : ''">{{ d.billTypeText }}</span>
                        </td>
                        <td>{{ d.sourceBillNo }}</td>
                        <td>{{ d.customerName }}</td>
                        <td>{{ d.customerAddress }}</td>
                        <td class="num">{{ d.qty }}</td>
                        <td>{{ d.status }}</td>
                      </tr>
                      <tr v-if="!detail.details.length"><td colspan="7" style="text-align:center;color:#98a2b3;padding:16px">暂无明细</td></tr>
                    </tbody>
                  </table>
                </div>
              </div>

              <!-- 配送行程 -->
              <div v-if="detail.trips && detail.trips.length" class="tablebox" style="margin-top:14px">
                <div class="toolbar"><b>配送行程</b></div>
                <div class="scroll">
                  <table>
                    <thead>
                      <tr><th>行程号</th><th>行程日期</th><th>司机</th><th>车牌</th><th class="num">门店数</th><th class="num">件数</th><th>状态</th></tr>
                    </thead>
                    <tbody>
                      <tr v-for="t in detail.trips" :key="t.tripId">
                        <td>{{ t.tripNo }}</td>
                        <td>{{ t.tripDate }}</td>
                        <td>{{ t.driverName }}</td>
                        <td>{{ t.vehiclePlate }}</td>
                        <td class="num">{{ t.totalStore }}</td>
                        <td class="num">{{ t.totalQty }}</td>
                        <td>{{ t.status }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
            </template>
          </div>
          <div class="modal-lite-foot">
            <button class="btn" @click="detailOpen = false">关闭</button>
          </div>
        </div>
      </div>
    </div>
  </div>
  <div v-if="feedback" class="toast-inline">{{ feedback }}</div>
</template>
