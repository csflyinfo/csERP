<script setup>
/**
 * 交账单管理（P3-3）
 *
 * 接口：
 *   POST /tms/settlement/page              列表
 *   GET  /tms/settlement/{id}              详情（含照片 + 签收明细）
 *   POST /tms/settlement/{id}/audit        审核通过（→ APPROVED）
 *   POST /tms/settlement/{id}/dispute      标记差异争议（→ DISPUTED）
 *
 * 状态：PENDING(待审核) / APPROVED(已审核) / DISPUTED(差异争议)
 * 业务：司机一天配送结束后提交交账 → 财务审核（核对金额 vs 系统应收，处理长款/短款差异）
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
const detail = ref({ photos: [], signRecords: [] })

// 审核对话框
const auditOpen = ref(false)
const auditId = ref('')
const auditNo = ref('')
const auditRemark = ref('')
const auditSubmitting = ref(false)

// 差异争议对话框
const disputeOpen = ref(false)
const disputeId = ref('')
const disputeNo = ref('')
const disputeReason = ref('')
const disputeSubmitting = ref(false)

const columns = [
  { key: 'c0', title: '交账单号' },
  { key: 'c1', title: '交账日期' },
  { key: 'c2', title: '司机' },
  { key: 'c3', title: '线路' },
  { key: 'c4', title: '签收/总门店', num: true },
  { key: 'c5', title: '应收金额', num: true },
  { key: 'c6', title: '实收现金', num: true },
  { key: 'c7', title: '线上收款', num: true },
  { key: 'c8', title: '应交回', num: true },
  { key: 'c9', title: '实际交回', num: true },
  { key: 'c10', title: '差异', num: true },
  { key: 'c11', title: '状态' },
  { key: 'c12', title: '操作' },
]

const statusOptions = [
  { value: 'PENDING', label: '待审核' },
  { value: 'APPROVED', label: '已审核' },
  { value: 'DISPUTED', label: '差异争议' },
]

const statusMap = {
  PENDING: { text: '待审核', cls: 'tag-orange' },
  APPROVED: { text: '已审核', cls: 'tag-green' },
  DISPUTED: { text: '差异争议', cls: 'tag-red' },
}

function fmtMoney(v) {
  const n = Number(v || 0)
  return n.toFixed(2)
}

function fmtDiff(v) {
  const n = Number(v || 0)
  if (n === 0) return '0.00'
  return (n > 0 ? '+' : '') + n.toFixed(2)
}

async function loadList() {
  loading.value = true
  feedback.value = ''
  try {
    const res = await post('/tms/settlement/page', {
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      filters: queryFilters.value,
    })
    tableRows.value = (res.data?.records || []).map(r => ({
      c0: r.settlementNo,
      c1: r.settleDate,
      c2: r.driverName,
      c3: r.routeLine || '-',
      c4: `${r.signedStores}/${r.totalStores}`,
      c5: fmtMoney(r.totalAmount),
      c6: fmtMoney(r.cashAmount),
      c7: fmtMoney(r.onlineAmount),
      c8: fmtMoney(r.submitAmount),
      c9: fmtMoney(r.actualSubmit),
      c10: fmtDiff(r.diffAmount),
      c11: statusMap[r.status]?.text || r.status,
      c12: '操作',
      _raw: r,
    }))
    total.value = res.data?.total || 0
  } catch (e) {
    feedback.value = '加载失败：' + (e.message || '')
  } finally {
    loading.value = false
  }
}

function onSearch(filters) {
  queryFilters.value = filters || {}
  pageNo.value = 1
  loadList()
}

function onPageChange(p) {
  pageNo.value = p
  loadList()
}

async function viewDetail(row) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = { photos: [], signRecords: [] }
  try {
    const res = await get(`/tms/settlement/${row._raw.settlementId}`)
    detail.value = res.data || {}
  } catch (e) {
    feedback.value = '加载详情失败：' + (e.message || '')
  } finally {
    detailLoading.value = false
  }
}

function openAudit(row) {
  auditId.value = row._raw.settlementId
  auditNo.value = row._raw.settlementNo
  auditRemark.value = ''
  auditOpen.value = true
}

async function submitAudit() {
  auditSubmitting.value = true
  feedback.value = ''
  try {
    await post(`/tms/settlement/${auditId.value}/audit`, {
      auditRemark: auditRemark.value,
    })
    feedback.value = `交账单 ${auditNo.value} 审核通过`
    auditOpen.value = false
    detailOpen.value = false
    loadList()
  } catch (e) {
    feedback.value = '审核失败：' + (e.message || '')
  } finally {
    auditSubmitting.value = false
  }
}

function openDispute(row) {
  disputeId.value = row._raw.settlementId
  disputeNo.value = row._raw.settlementNo
  disputeReason.value = ''
  disputeOpen.value = true
}

async function submitDispute() {
  if (!disputeReason.value.trim()) {
    feedback.value = '请填写差异原因'
    return
  }
  disputeSubmitting.value = true
  feedback.value = ''
  try {
    await post(`/tms/settlement/${disputeId.value}/dispute`, {
      diffReason: disputeReason.value,
    })
    feedback.value = `交账单 ${disputeNo.value} 已标记差异争议`
    disputeOpen.value = false
    detailOpen.value = false
    loadList()
  } catch (e) {
    feedback.value = '操作失败：' + (e.message || '')
  } finally {
    disputeSubmitting.value = false
  }
}

function photoTypeLabel(t) {
  const m = { CASH: '现金清点', ONLINE: '收款截图', POS: 'POS签购单', OTHER: '其他' }
  return m[t] || t || '其他'
}

onMounted(loadList)
</script>

<template>
  <div class="tms-page">
    <div class="page-head">
      <h2>交账单管理</h2>
      <div class="head-actions">
        <button class="btn" @click="loadList">刷新</button>
      </div>
    </div>

    <QueryBar @search="onSearch" @reset="() => { queryFilters = {}; loadList() }">
      <select v-model="queryFilters.status" class="sel">
        <option value="">全部状态</option>
        <option v-for="o in statusOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
      </select>
      <input v-model="queryFilters.driverName" class="inp" placeholder="司机姓名" />
      <input v-model="queryFilters.settlementNo" class="inp" placeholder="交账单号" />
      <input v-model="queryFilters.routeLine" class="inp" placeholder="线路" />
      <input v-model="queryFilters.settleDate" class="inp" type="date" />
    </QueryBar>

    <ProTable
      :columns="columns"
      :rows="tableRows"
      :loading="loading"
      :page-no="pageNo"
      :page-size="pageSize"
      :total="total"
      @page-change="onPageChange"
    >
      <template #c10="{ row }">
        <span :style="{ color: Number(row._raw.diffAmount) > 0 ? '#e6a23c' : Number(row._raw.diffAmount) < 0 ? '#f56c6c' : '#999' }">
          {{ fmtDiff(row._raw.diffAmount) }}
        </span>
      </template>
      <template #c11="{ row }">
        <span :class="['tag', statusMap[row._raw.status]?.cls || 'tag-gray']">{{ row.c11 }}</span>
      </template>
      <template #c12="{ row }">
        <button class="btn-link" @click="viewDetail(row)">详情</button>
        <button v-if="row._raw.status === 'PENDING'" class="btn-link" @click="openAudit(row)">审核</button>
        <button v-if="row._raw.status === 'PENDING'" class="btn-link" @click="openDispute(row)">争议</button>
      </template>
    </ProTable>

    <!-- 详情抽屉 -->
    <div v-if="detailOpen" class="drawer-mask" @click.self="detailOpen = false">
      <div class="drawer">
        <div class="drawer-head">
          <h3>交账单详情</h3>
          <button class="btn-link" @click="detailOpen = false">关闭</button>
        </div>
        <div v-if="detailLoading" class="drawer-body"><p>加载中...</p></div>
        <div v-else class="drawer-body">
          <!-- 基本信息 -->
          <div class="section">
            <h4>基本信息</h4>
            <div class="grid-2">
              <div><span class="lbl">交账单号：</span>{{ detail.settlementNo }}</div>
              <div><span class="lbl">调度单号：</span>{{ detail.dispatchNo || '-' }}</div>
              <div><span class="lbl">司机：</span>{{ detail.driverName }}</div>
              <div><span class="lbl">线路：</span>{{ detail.routeLine || '-' }}</div>
              <div><span class="lbl">交账日期：</span>{{ detail.settleDate }}</div>
              <div><span class="lbl">状态：</span>
                <span :class="['tag', statusMap[detail.status]?.cls || 'tag-gray']">{{ statusMap[detail.status]?.text || detail.status }}</span>
              </div>
              <div><span class="lbl">提交时间：</span>{{ detail.submittedAt || '-' }}</div>
              <div><span class="lbl">审核时间：</span>{{ detail.auditedAt || '-' }}</div>
            </div>
          </div>

          <!-- 金额汇总 -->
          <div class="section">
            <h4>金额汇总</h4>
            <div class="grid-2">
              <div><span class="lbl">签收门店：</span>{{ detail.signedStores }}/{{ detail.totalStores }}</div>
              <div><span class="lbl">应收总金额：</span>¥ {{ fmtMoney(detail.totalAmount) }}</div>
              <div><span class="lbl">实收现金：</span>¥ {{ fmtMoney(detail.cashAmount) }}</div>
              <div><span class="lbl">线上收款：</span>¥ {{ fmtMoney(detail.onlineAmount) }}</div>
              <div><span class="lbl">退货金额：</span>¥ {{ fmtMoney(detail.returnAmount) }}</div>
              <div><span class="lbl">退货件数：</span>{{ detail.returnQty || 0 }} 件</div>
              <div><span class="lbl">应交回现金：</span><b>¥ {{ fmtMoney(detail.submitAmount) }}</b></div>
              <div><span class="lbl">实际交回：</span><b>¥ {{ fmtMoney(detail.actualSubmit) }}</b></div>
              <div><span class="lbl">差异金额：</span>
                <b :style="{ color: Number(detail.diffAmount) > 0 ? '#e6a23c' : Number(detail.diffAmount) < 0 ? '#f56c6c' : '#999' }">
                  {{ fmtDiff(detail.diffAmount) }}
                </b>
              </div>
            </div>
            <div v-if="detail.diffReason"><span class="lbl">差异原因：</span>{{ detail.diffReason }}</div>
          </div>

          <!-- 签收明细 -->
          <div v-if="detail.signRecords && detail.signRecords.length" class="section">
            <h4>签收明细（{{ detail.signRecords.length }} 条）</h4>
            <table class="inner-table">
              <thead>
                <tr>
                  <th>发货单号</th>
                  <th>客户</th>
                  <th>类型</th>
                  <th>实收数量</th>
                  <th>拒收数量</th>
                  <th>收款金额</th>
                  <th>付款方式</th>
                  <th>签收人</th>
                  <th>签收时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="s in detail.signRecords" :key="s.signId">
                  <td>{{ s.sourceBillNo }}</td>
                  <td>{{ s.customerName }}</td>
                  <td>{{ s.billType === 'RETURN' ? '退货' : '发货' }}</td>
                  <td>{{ s.signedQty }}</td>
                  <td>{{ s.rejectQty || 0 }}</td>
                  <td>¥ {{ fmtMoney(s.collectAmount) }}</td>
                  <td>{{ s.payMethod || '-' }}</td>
                  <td>{{ s.customerSigner || '-' }}</td>
                  <td>{{ s.signTime || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- 结算照片 -->
          <div v-if="detail.photos && detail.photos.length" class="section">
            <h4>结算照片（{{ detail.photos.length }} 张）</h4>
            <div class="photo-grid">
              <div v-for="p in detail.photos" :key="p.photoId" class="photo-item">
                <img :src="p.photoUrl" :alt="photoTypeLabel(p.photoType)" />
                <span class="photo-type">{{ photoTypeLabel(p.photoType) }}</span>
              </div>
            </div>
          </div>

          <!-- 电子签名 -->
          <div v-if="detail.signatureImg" class="section">
            <h4>电子签名</h4>
            <div class="signature-box">
              <img :src="detail.signatureImg" alt="司机签名" style="max-width: 300px; max-height: 120px;" />
            </div>
          </div>

          <!-- 审核备注 -->
          <div v-if="detail.auditRemark" class="section">
            <h4>审核备注</h4>
            <p>{{ detail.auditRemark }}</p>
          </div>

          <!-- 操作按钮 -->
          <div v-if="detail.status === 'PENDING'" class="section">
            <button class="btn btn-primary" @click="openAudit({ _raw: detail })">审核通过</button>
            <button class="btn btn-warn" @click="openDispute({ _raw: detail })" style="margin-left: 8px;">标记差异争议</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 审核对话框 -->
    <div v-if="auditOpen" class="modal-mask" @click.self="auditOpen = false">
      <div class="modal">
        <h3>审核交账单</h3>
        <p>确认审核通过交账单 <b>{{ auditNo }}</b>？</p>
        <textarea v-model="auditRemark" class="textarea" placeholder="审核备注（选填）" rows="3"></textarea>
        <div class="modal-actions">
          <button class="btn" @click="auditOpen = false">取消</button>
          <button class="btn btn-primary" :disabled="auditSubmitting" @click="submitAudit">
            {{ auditSubmitting ? '处理中...' : '确认审核' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 差异争议对话框 -->
    <div v-if="disputeOpen" class="modal-mask" @click.self="disputeOpen = false">
      <div class="modal">
        <h3>标记差异争议</h3>
        <p>交账单 <b>{{ disputeNo }}</b></p>
        <textarea v-model="disputeReason" class="textarea" placeholder="请填写差异原因（必填）" rows="3"></textarea>
        <div class="modal-actions">
          <button class="btn" @click="disputeOpen = false">取消</button>
          <button class="btn btn-warn" :disabled="disputeSubmitting" @click="submitDispute">
            {{ disputeSubmitting ? '处理中...' : '确认标记' }}
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
.head-actions { display: flex; gap: 8px; }
.btn { padding: 6px 14px; border: 1px solid #ddd; border-radius: 4px; background: #fff; cursor: pointer; font-size: 13px; }
.btn-primary { background: #409eff; color: #fff; border-color: #409eff; }
.btn-warn { background: #e6a23c; color: #fff; border-color: #e6a23c; }
.btn-link { background: none; border: none; color: #409eff; cursor: pointer; font-size: 13px; padding: 2px 6px; }
.sel, .inp { padding: 6px 10px; border: 1px solid #ddd; border-radius: 4px; font-size: 13px; }

.drawer-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); z-index: 100; display: flex; justify-content: flex-end; }
.drawer { width: 600px; max-width: 90vw; background: #fff; height: 100vh; overflow-y: auto; }
.drawer-head { display: flex; justify-content: space-between; align-items: center; padding: 16px; border-bottom: 1px solid #eee; position: sticky; top: 0; background: #fff; z-index: 1; }
.drawer-body { padding: 16px; }
.section { margin-bottom: 20px; }
.section h4 { margin: 0 0 8px; font-size: 14px; color: #333; border-left: 3px solid #409eff; padding-left: 8px; }
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 16px; font-size: 13px; line-height: 1.8; }
.lbl { color: #999; }
.inner-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.inner-table th, .inner-table td { border: 1px solid #eee; padding: 6px 8px; text-align: left; }
.inner-table th { background: #f5f7fa; color: #666; }
.photo-grid { display: flex; flex-wrap: wrap; gap: 8px; }
.photo-item { position: relative; }
.photo-item img { width: 120px; height: 120px; object-fit: cover; border-radius: 4px; border: 1px solid #eee; }
.photo-type { display: block; text-align: center; font-size: 11px; color: #999; margin-top: 2px; }
.signature-box { padding: 8px; border: 1px solid #eee; border-radius: 4px; background: #fafafa; }

.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); z-index: 200; display: flex; align-items: center; justify-content: center; }
.modal { background: #fff; border-radius: 8px; padding: 20px; width: 420px; max-width: 90vw; }
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
