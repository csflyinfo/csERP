<script setup>
/**
 * 客户拒收单管理（P3-2）
 *
 * 接口：
 *   POST /tms/customer-reject/page              列表
 *   GET  /tms/customer-reject/{id}              详情
 *   POST /tms/customer-reject/{id}/receive      仓库收货（生成拒收入库单 JSRK）
 *   POST /tms/customer-reject/{id}/complete     完结（JSRK 审核后置为 COMPLETED）
 *
 * 状态：PENDING(待返仓) / RECEIVED(已收货) / COMPLETED(已完成)
 * 业务：客户拒收 → 货物随车返仓 → 仓库收货生成 JSRK → 审核后库存增加 + 撤销应收
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
const detail = ref({ details: [], photos: [] })

// 收货对话框
const receiveOpen = ref(false)
const receiveSubmitting = ref(false)
const receiveItems = ref([])
const receiveRejectId = ref('')

const columns = [
  { key: 'c0', title: '拒收单号' },
  { key: 'c1', title: '发货单号' },
  { key: 'c2', title: '客户' },
  { key: 'c3', title: '司机' },
  { key: 'c4', title: '拒收原因' },
  { key: 'c5', title: '拒收数量', num: true },
  { key: 'c6', title: '拒收金额', num: true },
  { key: 'c7', title: '关联入库单' },
  { key: 'c8', title: '状态' },
  { key: 'c9', title: '操作' },
]

const statusOptions = [
  { value: 'PENDING', label: '待返仓' },
  { value: 'RECEIVED', label: '已收货' },
  { value: 'COMPLETED', label: '已完成' },
]

const statusMap = {
  PENDING: { text: '待返仓', cls: 'tag-red' },
  RECEIVED: { text: '已收货', cls: 'tag-orange' },
  COMPLETED: { text: '已完成', cls: 'tag-green' },
}

const reasonMap = {
  CUSTOMER_REJECT: '客户拒收',
  GOODS_DAMAGED: '货物破损',
  SPEC_MISMATCH: '规格不符',
  QTY_MISMATCH: '数量不符',
  OTHER: '其他',
}

const reasonLabel = (code) => reasonMap[code] || code

// QueryBar 为配置式组件：只认 :fields，且以中文 label 为 key 回传（详见 components/QueryBar.vue）
const queryFields = [
  '拒收单号',
  { label: '状态', type: 'select', options: statusOptions },
  '司机',
  '客户',
]

async function loadList() {
  loading.value = true
  feedback.value = ''
  try {
    const data = await post('/tms/customer-reject/page', {
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      filters: queryFilters.value,
    })
    tableRows.value = (data.records || []).map(r => ({
      c0: r.rejectNo,
      c1: r.receiptNo,
      c2: r.customerName,
      c3: r.driverName,
      c4: reasonLabel(r.rejectReason),
      c5: r.totalQty,
      c6: r.totalAmount,
      c7: r.rejectInboundNo || '-',
      c8: statusMap[r.status]?.text || r.status,
      c9: '操作',
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
    const res = await get(`/tms/customer-reject/${row._raw.rejectId}`)
    detail.value = res || { details: [], photos: [] }
  } catch (e) {
    feedback.value = '加载详情失败：' + (e.message || '')
  } finally {
    detailLoading.value = false
  }
}

function openReceive(row) {
  // 如果详情未拉取，先按 row 拉取一次
  if (!detail.value || detail.value.rejectId !== row._raw.rejectId) {
    viewDetail(row).then(() => {
      receiveRejectId.value = row._raw.rejectId
      receiveItems.value = (detail.value.details || []).map(d => ({
        detailId: d.detailId,
        goodsName: d.goodsName,
        spec: d.spec,
        unitName: d.unitName,
        rejectQty: d.rejectQty,
        actualReceiveQty: d.rejectQty,
      }))
      receiveOpen.value = true
    })
  } else {
    receiveRejectId.value = row._raw.rejectId
    receiveItems.value = (detail.value.details || []).map(d => ({
      detailId: d.detailId,
      goodsName: d.goodsName,
      spec: d.spec,
      unitName: d.unitName,
      rejectQty: d.rejectQty,
      actualReceiveQty: d.rejectQty,
    }))
    receiveOpen.value = true
  }
}

async function submitReceive() {
  receiveSubmitting.value = true
  feedback.value = ''
  try {
    const res = await post(`/tms/customer-reject/${receiveRejectId.value}/receive`, {
      items: receiveItems.value.map(it => ({ detailId: it.detailId, actualReceiveQty: it.actualReceiveQty })),
    })
    const inboundNo = res?.rejectInboundNo
    feedback.value = `仓库收货成功，已生成拒收入库单：${inboundNo || '无'}`
    receiveOpen.value = false
    detailOpen.value = false
    loadList()
  } catch (e) {
    feedback.value = '收货失败：' + (e.message || '')
  } finally {
    receiveSubmitting.value = false
  }
}

async function complete(row) {
  if (!confirm(`确认完结拒收单 ${row._raw.rejectNo}？需先在「拒收入库单」审核关联的 JSRK`)) return
  feedback.value = ''
  try {
    await post(`/tms/customer-reject/${row._raw.rejectId}/complete`)
    feedback.value = '已完结'
    loadList()
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
  if (filters['拒收单号']) f.rejectNo = filters['拒收单号']
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
      <h2>客户拒收单管理</h2>
      <div class="head-actions">
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
        <button v-if="row._raw.status === 'PENDING'" class="btn-link" @click="openReceive(row)">收货</button>
        <button v-if="row._raw.status === 'RECEIVED'" class="btn-link" @click="complete(row)">完结</button>
      </template>
    </ProTable>

    <!-- 详情抽屉 -->
    <div v-if="detailOpen" class="drawer-mask" @click.self="detailOpen = false">
      <div class="drawer">
        <div class="drawer-head">
          <h3>客户拒收单详情</h3>
          <button class="btn-link" @click="detailOpen = false">关闭</button>
        </div>
        <div v-if="detailLoading" class="drawer-loading">加载中...</div>
        <div v-else class="drawer-body">
          <div class="section">
            <h4>基本信息</h4>
            <div class="grid-2">
              <div><label>拒收单号</label><span>{{ detail.rejectNo }}</span></div>
              <div><label>发货单号</label><span>{{ detail.receiptNo }}</span></div>
              <div><label>司机</label><span>{{ detail.driverName }}</span></div>
              <div><label>客户</label><span>{{ detail.customerName }} ({{ detail.customerCode }})</span></div>
              <div><label>客户地址</label><span>{{ detail.customerAddress || '-' }}</span></div>
              <div><label>拒收原因</label><span>{{ reasonLabel(detail.rejectReason) }}</span></div>
              <div><label>原因说明</label><span>{{ detail.reasonDetail || '-' }}</span></div>
              <div><label>拒收数量</label><span>{{ detail.totalQty }}</span></div>
              <div><label>拒收金额</label><span>¥ {{ detail.totalAmount }}</span></div>
              <div><label>关联拒收入库单</label><span>{{ detail.rejectInboundNo || '-' }}</span></div>
              <div><label>状态</label><span :class="['tag', statusMap[detail.status]?.cls]">{{ statusMap[detail.status]?.text }}</span></div>
              <div><label>调度单</label><span>{{ detail.dispatchNo || '-' }}</span></div>
              <div><label>行程</label><span>{{ detail.tripNo || '-' }}</span></div>
              <div><label>司机返仓时间</label><span>{{ detail.returnedAt || '-' }}</span></div>
              <div><label>仓库收货时间</label><span>{{ detail.receivedAt || '-' }}</span></div>
              <div><label>收货人</label><span>{{ detail.receiver || '-' }}</span></div>
            </div>
            <div v-if="detail.remark"><label style="margin-top:8px;display:block">备注</label><span>{{ detail.remark }}</span></div>
          </div>

          <div class="section">
            <h4>拒收商品明细</h4>
            <table class="dt">
              <thead>
                <tr>
                  <th>商品编码</th>
                  <th>商品名称</th>
                  <th>规格</th>
                  <th>单位</th>
                  <th>拒收数量</th>
                  <th>实收数量</th>
                  <th>差异</th>
                  <th>单价</th>
                  <th>金额</th>
                  <th>批次</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="d in detail.details" :key="d.detailId">
                  <td>{{ d.goodsCode }}</td>
                  <td>{{ d.goodsName }}</td>
                  <td>{{ d.spec }}</td>
                  <td>{{ d.unitName }}</td>
                  <td class="num">{{ d.rejectQty }}</td>
                  <td class="num">{{ d.actualReceiveQty }}</td>
                  <td class="num">{{ d.diffQty }}</td>
                  <td class="num">{{ d.price }}</td>
                  <td class="num">{{ d.amount }}</td>
                  <td>{{ d.batchNo || '-' }}</td>
                </tr>
                <tr v-if="!detail.details?.length">
                  <td colspan="10" class="empty">无明细</td>
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

    <!-- 收货对话框 -->
    <div v-if="receiveOpen" class="drawer-mask" @click.self="receiveOpen = false">
      <div class="drawer">
        <div class="drawer-head">
          <h3>仓库收货（生成拒收入库单）</h3>
          <button class="btn-link" @click="receiveOpen = false">关闭</button>
        </div>
        <div class="drawer-body">
          <div class="feedback" v-if="feedback">{{ feedback }}</div>
          <div class="alert">⚠️ 收货后系统会自动：<br>1. 把拒收数量回写到发货单明细<br>2. 生成拒收入库单 JSRK（待审核后增加库存）<br>3. 撤销对应应收（按拒收金额冲减）</div>
          <table class="dt">
            <thead>
              <tr>
                <th>商品名称</th>
                <th>规格</th>
                <th>拒收数量</th>
                <th>实收数量</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(it, idx) in receiveItems" :key="idx">
                <td>{{ it.goodsName }}</td>
                <td>{{ it.spec }}</td>
                <td class="num">{{ it.rejectQty }}</td>
                <td><input v-model.number="it.actualReceiveQty" type="number" class="inp-num" /></td>
              </tr>
              <tr v-if="!receiveItems.length"><td colspan="4" class="empty">无明细</td></tr>
            </tbody>
          </table>
          <div style="margin-top:16px;text-align:right">
            <button class="btn" @click="receiveOpen = false">取消</button>
            <button class="btn-primary" :disabled="receiveSubmitting" @click="submitReceive">{{ receiveSubmitting ? '提交中...' : '确认收货' }}</button>
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
.btn-primary { padding: 6px 14px; border: none; background: #dc2626; color: #fff; border-radius: 6px; cursor: pointer; font-size: 13px; margin-left: 8px; }
.btn-primary:disabled { background: #9ca3af; cursor: not-allowed; }
.btn-link { color: #2563eb; background: none; border: none; cursor: pointer; font-size: 13px; padding: 2px 4px; margin-right: 6px; }
.btn-link:hover { text-decoration: underline; }
.feedback { padding: 8px 12px; background: #fef3c7; border-radius: 6px; margin-bottom: 8px; font-size: 13px; color: #92400e; }
.alert { padding: 10px 12px; background: #fee2e2; border-radius: 6px; margin-bottom: 12px; font-size: 12px; color: #991b1b; line-height: 1.6; }
.sel, .inp { padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; }
.inp-num { width: 80px; padding: 4px 8px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 13px; }
.tag { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.tag-orange { background: #fff7ed; color: #c2410c; }
.tag-red { background: #fee2e2; color: #b91c1c; }
.tag-green { background: #f0fdf4; color: #15803d; }
.tag-gray { background: #f3f4f6; color: #6b7280; }
.drawer-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); z-index: 100; display: flex; justify-content: flex-end; }
.drawer { width: 720px; background: #fff; height: 100%; overflow-y: auto; box-shadow: -2px 0 8px rgba(0,0,0,0.1); }
.drawer-head { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; border-bottom: 1px solid #e5e7eb; position: sticky; top: 0; background: #fff; z-index: 1; }
.drawer-head h3 { margin: 0; font-size: 16px; }
.drawer-loading { padding: 40px; text-align: center; color: #6b7280; }
.drawer-body { padding: 20px; }
.section { margin-bottom: 24px; }
.section h4 { margin: 0 0 12px; font-size: 14px; color: #374151; border-left: 3px solid #dc2626; padding-left: 8px; }
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
