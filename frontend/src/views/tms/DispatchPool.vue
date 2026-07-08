<script setup>
/**
 * 配送任务池 + 创建调度单（V1.2 退货单取货任务融入）
 *
 * 数据来源：
 *   发货单：sales_receipt status=APPROVED 且 dispatch_status=UNDISPATCHED
 *   退货单：sales_return_apply return_type=DRIVER 且 logistics_status=已安排调度
 *
 * 接口：
 *   POST /tms/dispatch/pool          任务池（发货单 + 退货单 + 统计）
 *   POST /tms/dispatch/create        创建调度单（勾选发货单+退货单 → 生成 dispatch/detail/trip）
 *   POST /tms/return-dispatch/auto-match  指派发货单时按客户自动匹配退货单
 *   POST /base/master/employee/page  司机档案
 *   POST /base/master/route-line/page 线路档案
 */
import { ref, onMounted, computed } from 'vue'
import { post } from '../../api/client.js'

const loading = ref(false)
const feedback = ref('')
const activeTab = ref('RECEIPT') // RECEIPT 发货单 / RETURN 退货单

const receipts = ref([])
const returns = ref([])
const summary = ref({ receiptCount: 0, returnCount: 0, totalQty: 0, totalAmount: 0, storeCount: 0 })

const checkedReceipts = ref(new Set())
const checkedReturns = ref(new Set())

// 筛选
const fCustomer = ref('')
const fRouteLine = ref('')
const fTerritory = ref('')

// 司机/线路
const drivers = ref([])
const routeLines = ref([])

// 创建调度单抽屉
const createOpen = ref(false)
const creating = ref(false)
const createForm = ref({
  dispatchDate: new Date().toISOString().slice(0, 10),
  routeLine: '', driverId: '', driverName: '',
  vehiclePlate: '', vehicleType: '厢式', loadCapacity: 0,
  remark: '',
})
const autoMatchedReturns = ref([]) // 创建抽屉中自动匹配到的退货单

const receiptColumns = [
  { key: 'r0', title: '发货单号' },
  { key: 'r1', title: '客户' },
  { key: 'r2', title: '仓库' },
  { key: 'r3', title: '发货日期' },
  { key: 'r4', title: '件数', num: true },
  { key: 'r5', title: '金额', num: true },
  { key: 'r6', title: '线路' },
  { key: 'r7', title: '片区' },
  { key: 'r8', title: '地址' },
]
const returnColumns = [
  { key: 't0', title: '退货单号' },
  { key: 't1', title: '客户' },
  { key: 't2', title: '仓库' },
  { key: 't3', title: '退货数', num: true },
  { key: 't4', title: '退货原因' },
  { key: 't5', title: '安排时间' },
  { key: 't6', title: '线路' },
  { key: 't7', title: '片区' },
  { key: 't8', title: '地址' },
]

function show(msg) { feedback.value = msg; setTimeout(() => (feedback.value = ''), 2800) }

const receiptRows = computed(() => receipts.value.map(r => ({
  r0: r.receiptNo || '', r1: r.customerName || '', r2: r.warehouse || '',
  r3: r.receiptDate || '', r4: r.qty ?? 0, r5: r.finalAmount ?? 0,
  r6: r.routeLine || '', r7: r.territory || '', r8: r.addressDetail || '',
  _raw: r,
})))
const returnRows = computed(() => returns.value.map(r => ({
  t0: r.applyNo || '', t1: r.customerName || '', t2: r.warehouse || '',
  t3: r.returnQty ?? 0, t4: r.returnReason || '', t5: r.arrangeTime || '',
  t6: r.routeLine || '', t7: r.territory || '', t8: r.addressDetail || '',
  _raw: r,
})))

async function loadDrivers() {
  try {
    const data = await post('/base/master/employee/page', { pageNo: 1, pageSize: 10000, filters: {} })
    drivers.value = (data.records || []).filter(r => r.isDeliveryman === true || r.isDeliveryman === 'true')
  } catch (e) { drivers.value = [] }
}
async function loadRouteLines() {
  try {
    const data = await post('/base/master/route-line/page', { pageNo: 1, pageSize: 10000, filters: {} })
    routeLines.value = (data.records || []).filter(r => r.status === 'NORMAL' || r.status === '正常' || !r.status)
  } catch (e) { routeLines.value = [] }
}

async function loadPool() {
  loading.value = true
  try {
    const data = await post('/tms/dispatch/pool', {
      customer: fCustomer.value, routeLine: fRouteLine.value, territory: fTerritory.value,
    })
    receipts.value = data.receipts || []
    returns.value = data.returns || []
    summary.value = data.summary || summary.value
    checkedReceipts.value = new Set()
    checkedReturns.value = new Set()
  } catch (e) {
    receipts.value = []; returns.value = []
    show('加载失败：' + (e.message || '请检查后端服务'))
  } finally { loading.value = false }
}

function toggleReceipt(no) {
  const s = new Set(checkedReceipts.value)
  s.has(no) ? s.delete(no) : s.add(no)
  checkedReceipts.value = s
}
function toggleReturn(no) {
  const s = new Set(checkedReturns.value)
  s.has(no) ? s.delete(no) : s.add(no)
  checkedReturns.value = s
}
function toggleAllReceipts(e) {
  checkedReceipts.value = e.target.checked ? new Set(receipts.value.map(r => r.receiptNo)) : new Set()
}
function toggleAllReturns(e) {
  checkedReturns.value = e.target.checked ? new Set(returns.value.map(r => r.applyNo)) : new Set()
}

const selectedReceiptList = computed(() => receipts.value.filter(r => checkedReceipts.value.has(r.receiptNo)))
const selectedReturnList = computed(() => returns.value.filter(r => checkedReturns.value.has(r.applyNo)))
const selectedQty = computed(() => {
  const rq = selectedReceiptList.value.reduce((s, r) => s + (Number(r.qty) || 0), 0)
  const tq = selectedReturnList.value.reduce((s, r) => s + (Number(r.returnQty) || 0), 0)
  return rq + tq
})
const selectedAmount = computed(() => selectedReceiptList.value.reduce((s, r) => s + (Number(r.finalAmount) || 0), 0))
const selectedStoreCount = computed(() => {
  const codes = new Set()
  selectedReceiptList.value.forEach(r => codes.add(r.customerCode))
  selectedReturnList.value.forEach(r => codes.add(r.customerCode))
  return codes.size
})

function openCreate() {
  if (checkedReceipts.value.size === 0 && checkedReturns.value.size === 0) {
    show('请至少勾选一张发货单或退货单'); return
  }
  createForm.value = {
    dispatchDate: new Date().toISOString().slice(0, 10),
    routeLine: '', driverId: '', driverName: '',
    vehiclePlate: '', vehicleType: '厢式', loadCapacity: 0,
    remark: '',
  }
  autoMatchedReturns.value = []
  createOpen.value = true
  // 自动匹配：所选发货单客户下已安排调度的退货单（未勾选的）
  const customers = new Set(selectedReceiptList.value.map(r => r.customerCode).filter(Boolean))
  if (customers.size) {
    Promise.all([...customers].map(c => post('/tms/return-dispatch/auto-match', { customerCode: c }).catch(() => [])))
      .then(lists => {
        const matched = lists.flat()
        const checked = [...checkedReturns.value]
        autoMatchedReturns.value = matched.filter(m => !checked.includes(m.applyNo))
      })
  }
}
function onCreateDriverChange() {
  const d = drivers.value.find(x => x.employeeId === createForm.value.driverId)
  createForm.value.driverName = d ? d.employeeName : ''
}
async function submitCreate() {
  if (!createForm.value.driverId) { show('请选择司机'); return }
  creating.value = true
  try {
    await post('/tms/dispatch/create', {
      dispatchDate: createForm.value.dispatchDate,
      routeLine: createForm.value.routeLine,
      driverId: createForm.value.driverId,
      driverName: createForm.value.driverName,
      vehiclePlate: createForm.value.vehiclePlate,
      vehicleType: createForm.value.vehicleType,
      loadCapacity: createForm.value.loadCapacity,
      remark: createForm.value.remark,
      receiptNos: [...checkedReceipts.value],
      returnNos: [...checkedReturns.value],
    })
    show('调度单创建成功，已分配司机「' + createForm.value.driverName + '」')
    createOpen.value = false
    loadPool()
  } catch (e) {
    show('创建失败：' + (e.message || '未知错误'))
  } finally { creating.value = false }
}

onMounted(() => { loadDrivers(); loadRouteLines(); loadPool() })
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn" @click="loadPool">刷新</button>
      <button class="btn primary" :disabled="!checkedReceipts.size && !checkedReturns.size"
              @click="openCreate">创建调度单（已选 {{ checkedReceipts.size + checkedReturns.size }}）</button>
    </div>

    <!-- 统计卡片 -->
    <div class="tms-stat-row">
      <div class="tms-stat-card"><div class="num">{{ summary.receiptCount }}</div><div class="lbl">待调度发货单</div></div>
      <div class="tms-stat-card ok"><div class="num">{{ summary.totalQty }}</div><div class="lbl">待配送总件数</div></div>
      <div class="tms-stat-card warn"><div class="num">¥{{ Number(summary.totalAmount || 0).toLocaleString() }}</div><div class="lbl">待配送应收金额</div></div>
      <div class="tms-stat-card"><div class="num">{{ summary.storeCount }}</div><div class="lbl">覆盖门店数</div></div>
      <div class="tms-stat-card warn"><div class="num">{{ summary.returnCount }}</div><div class="lbl">待回收退货单（取货任务）</div></div>
    </div>

    <!-- 查询栏 -->
    <div class="query-inline">
      <div class="fi"><label>客户</label><input v-model="fCustomer" placeholder="客户编码/名称" @keydown.enter="loadPool" /></div>
      <div class="fi"><label>线路</label>
        <select v-model="fRouteLine" @keydown.enter="loadPool">
          <option value="">全部线路</option>
          <option v-for="r in routeLines" :key="r.routeLineCode" :value="r.routeLineName">{{ r.routeLineName }}</option>
        </select>
      </div>
      <div class="fi"><label>片区</label><input v-model="fTerritory" placeholder="片区" @keydown.enter="loadPool" /></div>
      <button class="btn primary" @click="loadPool">查询</button>
      <button class="btn" @click="fCustomer=''; fRouteLine=''; fTerritory=''; loadPool()">重置</button>
    </div>

    <div v-if="loading" class="tips-inline"><span>正在加载...</span></div>

    <!-- Tab 切换 -->
    <div class="tms-tabs">
      <span :class="{ on: activeTab === 'RECEIPT' }" @click="activeTab = 'RECEIPT'">
        待调度发货单（{{ receipts.length }}）
      </span>
      <span :class="{ on: activeTab === 'RETURN' }" @click="activeTab = 'RETURN'">
        待回收退货单（{{ returns.length }}）
      </span>
      <span class="tms-tab-tip">数据来源：销售发货单（出库审核）+ 销售退货单（安排调度）</span>
    </div>

    <!-- 发货单表 -->
    <div v-show="activeTab === 'RECEIPT'" class="tablebox">
      <div class="toolbar">
        <b>待调度发货单</b>
        <div class="spacer"></div>
        <span style="color:#5d7896">已勾选 {{ checkedReceipts.size }} 张</span>
      </div>
      <div class="scroll">
        <table>
          <thead>
            <tr>
              <th class="tms-ck"><input type="checkbox" :checked="receipts.length && checkedReceipts.size === receipts.length" @change="toggleAllReceipts" /></th>
              <th v-for="c in receiptColumns" :key="c.key" :class="c.num ? 'num' : ''">{{ c.title }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in receiptRows" :key="row.r0" :class="{ sel: checkedReceipts.has(row.r0) }" @dblclick="toggleReceipt(row.r0)">
              <td class="tms-ck"><input type="checkbox" :checked="checkedReceipts.has(row.r0)" @change="toggleReceipt(row.r0)" /></td>
              <td v-for="c in receiptColumns" :key="c.key" :class="c.num ? 'num' : ''">{{ row[c.key] }}</td>
            </tr>
            <tr v-if="!receiptRows.length"><td :colspan="receiptColumns.length + 1" style="text-align:center;color:#98a2b3;padding:24px">暂无待调度发货单</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 退货单表 -->
    <div v-show="activeTab === 'RETURN'" class="tablebox">
      <div class="toolbar">
        <b>待回收退货单（取货任务，不占车辆载重）</b>
        <div class="spacer"></div>
        <span style="color:#5d7896">已勾选 {{ checkedReturns.size }} 张</span>
      </div>
      <div class="scroll">
        <table>
          <thead>
            <tr>
              <th class="tms-ck"><input type="checkbox" :checked="returns.length && checkedReturns.size === returns.length" @change="toggleAllReturns" /></th>
              <th v-for="c in returnColumns" :key="c.key" :class="c.num ? 'num' : ''">{{ c.title }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in returnRows" :key="row.t0" :class="{ sel: checkedReturns.has(row.t0) }" @dblclick="toggleReturn(row.t0)">
              <td class="tms-ck"><input type="checkbox" :checked="checkedReturns.has(row.t0)" @change="toggleReturn(row.t0)" /></td>
              <td v-for="c in returnColumns" :key="c.key" :class="c.num ? 'num' : ''">{{ row[c.key] }}</td>
            </tr>
            <tr v-if="!returnRows.length"><td :colspan="returnColumns.length + 1" style="text-align:center;color:#98a2b3;padding:24px">暂无待回收退货单</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 创建调度单抽屉 -->
    <div v-if="createOpen" class="drawer-overlay" @click.self="createOpen = false">
      <div class="drawer-lite">
        <div class="modal-lite-box">
          <div class="modal-lite-head"><b>创建调度单</b><button class="link link-btn" @click="createOpen = false">关闭</button></div>
          <div class="modal-lite-body">
            <div class="grid4">
              <div class="field"><label>调度日期</label><input type="date" v-model="createForm.dispatchDate" /></div>
              <div class="field"><label>线路</label>
                <select v-model="createForm.routeLine">
                  <option value="">不指定</option>
                  <option v-for="r in routeLines" :key="r.routeLineCode" :value="r.routeLineName">{{ r.routeLineName }}</option>
                </select>
              </div>
              <div class="field"><label>司机</label>
                <select v-model="createForm.driverId" @change="onCreateDriverChange">
                  <option value="">请选择司机</option>
                  <option v-for="d in drivers" :key="d.employeeId" :value="d.employeeId">{{ d.employeeName }}（{{ d.mobile || '' }}）</option>
                </select>
              </div>
              <div class="field"><label>车牌号</label><input v-model="createForm.vehiclePlate" placeholder="如 浙A12345" /></div>
              <div class="field"><label>车型</label>
                <select v-model="createForm.vehicleType">
                  <option>厢式</option><option>平板</option><option>冷藏</option><option>面包</option>
                </select>
              </div>
              <div class="field"><label>载重(kg)</label><input type="number" v-model="createForm.loadCapacity" /></div>
            </div>

            <!-- 选中汇总 -->
            <div class="tms-summary-card">
              <div class="tms-summary-row">
                <span>发货单：<b>{{ selectedReceiptList.length }}</b> 张</span>
                <span>退货单：<b>{{ selectedReturnList.length }}</b> 张（取货任务）</span>
                <span>合计件数：<b>{{ selectedQty }}</b></span>
                <span>应收金额：<b>¥{{ Number(selectedAmount).toLocaleString() }}</b></span>
                <span>覆盖门店：<b>{{ selectedStoreCount }}</b> 家</span>
              </div>
              <div v-if="selectedReceiptList.length" class="tms-bill-list">
                <div v-for="r in selectedReceiptList" :key="r.receiptNo" class="tms-bill-tag">📦 {{ r.receiptNo }} · {{ r.customerName }} · {{ r.qty }}件</div>
              </div>
              <div v-if="selectedReturnList.length" class="tms-bill-list">
                <div v-for="r in selectedReturnList" :key="r.applyNo" class="tms-bill-tag ret">🔄 {{ r.applyNo }} · {{ r.customerName }} · {{ r.returnQty }}件</div>
              </div>
            </div>

            <div v-if="autoMatchedReturns.length" class="tips-inline">
              <span>⚠ 所选发货单客户另有 {{ autoMatchedReturns.length }} 张已安排调度退货单未勾选，建议一并加入：{{ autoMatchedReturns.map(x => x.applyNo).join('、') }}</span>
            </div>

            <div class="field" style="margin-top:12px"><label>备注</label><input v-model="createForm.remark" placeholder="可选" /></div>
            <div class="tips-inline"><span>⚠ 确认后生成调度单 + 配送行程，发货单→已调度、退货单物流状态→已调度，回写司机信息。</span></div>
          </div>
          <div class="modal-lite-foot">
            <button class="btn" @click="createOpen = false">取消</button>
            <button class="btn primary" :disabled="creating" @click="submitCreate">{{ creating ? '提交中...' : '确认创建' }}</button>
          </div>
        </div>
      </div>
    </div>
  </div>
  <div v-if="feedback" class="toast-inline">{{ feedback }}</div>
</template>

<style scoped>
.tms-stat-row { display:flex; gap:14px; margin-bottom:14px; }
.tms-stat-card { flex:1; background:#f8fafc; border:1px solid #e5eaf2; border-radius:8px; padding:14px 18px; }
.tms-stat-card .num { font-size:24px; font-weight:800; font-family:Consolas,monospace; color:#1677ff; }
.tms-stat-card .lbl { font-size:12px; color:#667085; margin-top:4px; }
.tms-stat-card.warn .num { color:#c2410c; }
.tms-stat-card.ok .num { color:#15803d; }
.tms-tabs { display:flex; gap:24px; height:42px; align-items:center; border-bottom:1px solid #e5eaf2; margin-bottom:12px; }
.tms-tabs span { height:42px; line-height:42px; font-size:15px; cursor:pointer; color:#667085; }
.tms-tabs .on { color:#1677ff; border-bottom:2px solid #1677ff; font-weight:700; }
.tms-tab-tip { margin-left:auto; color:#98a2b3; font-size:13px; font-weight:400; cursor:default; border:none !important; }
.tms-ck { width:40px; min-width:40px; text-align:center; position:sticky; left:0; background:#f7fbff; z-index:3; }
tbody tr.sel { background:#eef6ff; }
.tms-ck input[type=checkbox] { width:16px; height:16px; cursor:pointer; }
.tms-summary-card { background:#f8fafc; border:1px solid #e5eaf2; border-radius:8px; padding:12px 16px; margin-top:14px; }
.tms-summary-row { display:flex; gap:24px; flex-wrap:wrap; font-size:14px; color:#5d7896; }
.tms-summary-row b { color:#1677ff; }
.tms-bill-list { display:flex; flex-wrap:wrap; gap:6px; margin-top:8px; }
.tms-bill-tag { background:#eef6ff; color:#1677ff; border:1px solid #cfe0f5; border-radius:4px; padding:2px 8px; font-size:12px; }
.tms-bill-tag.ret { background:#fff7ed; color:#c2410c; border-color:#fed7aa; }
</style>
