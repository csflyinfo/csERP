<script setup>
/**
 * WMS V1.5 入库作业（PRD-28）：
 *   wms-inbound      → 入库任务（采购/退货/拒收/调拨/其他）
 *   wms-putaway      → 上架任务
 * 按 route.meta.module 切换。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { get, post } from '../../api/client.js'

const route = useRoute()
const module = computed(() => route.meta?.module || 'wms-inbound')
const loading = ref(false)
const feedback = ref('')
const list = ref([])
const detail = ref(null)
const filters = ref({ status: '', inboundType: '', keyword: '' })
const showCreate = ref(false)
const createForm = ref({ inboundType: 'PURCHASE', purchaseOrderNo: '', warehouse: '总仓', details: [] })
const showReceive = ref(false)
const receiveForm = ref({ detailId: '', qty: 1, batchNo: '', productionDate: '', expiryDate: '' })
const putawayBin = ref({})

const inboundTypeText = { PURCHASE: '采购到货', RETURN: '销售退货', REJECT: '拒收', TRANSFER: '调拨入库', OTHER: '其他入库' }
const statusText = {
  PENDING: '待收货', RECEIVING: '收货中', RECHECK: '待复检', PUTAWAY: '待上架',
  PUTTING: '上架中', DONE: '已完成', EXCEPTION: '异常', CANCELLED: '已取消',
}

function show(msg) { feedback.value = msg; setTimeout(() => (feedback.value = ''), 3000) }

async function load() {
  loading.value = true
  try {
    if (module.value === 'wms-putaway') {
      list.value = (await post('/wms/inbound/putaway/page', { pageNo: 1, pageSize: 200, filters: { status: filters.value.status } })).records || []
    } else {
      list.value = (await post('/wms/inbound/page', { pageNo: 1, pageSize: 200, filters: { ...filters.value } })).records || []
    }
  } catch (e) { show('加载失败：' + (e?.message || e)) }
  finally { loading.value = false }
}

async function openDetail(row) {
  try { detail.value = await get(`/wms/inbound/detail?taskId=${encodeURIComponent(row.taskId)}`) }
  catch (e) { show('详情加载失败：' + (e?.message || e)) }
}

async function createFromPurchase() {
  if (!createForm.value.purchaseOrderNo) { show('请输入采购订单号'); return }
  try {
    await post('/wms/inbound/from-purchase', { purchaseOrderNo: createForm.value.purchaseOrderNo })
    showCreate.value = false
    createForm.value.purchaseOrderNo = ''
    show('已生成入库任务')
    load()
  } catch (e) { show(e?.message || '生成失败') }
}

async function finishReceive(row) {
  if (!confirm('确认结束收货？系统将按参数决定是否需要复检。')) return
  try {
    const r = await post('/wms/inbound/finish-receive', { taskId: row.taskId })
    show('已完成，下一步：' + (r.nextStatus || ''))
    load()
  } catch (e) { show(e?.message || '操作失败') }
}

async function recheck(row, passed) {
  try {
    await post('/wms/inbound/recheck', { taskId: row.taskId, passed })
    show(passed ? '复检通过，已生成上架任务' : '已登记为不合格，转异常处理')
    load()
  } catch (e) { show(e?.message || '操作失败') }
}

function openReceive(row) {
  receiveForm.value = { detailId: row.detailId, qty: row.expectedQty || 1, batchNo: row.batchNo || '', productionDate: '', expiryDate: '' }
  showReceive.value = true
}
async function submitReceive() {
  try {
    await post('/wms/inbound/receive', receiveForm.value)
    showReceive.value = false
    show('收货已登记')
    if (detail.value) await openDetail({ taskId: detail.value.taskId })
    load()
  } catch (e) { show(e?.message || '收货失败') }
}

async function claimPutaway(row) {
  try { await post('/wms/inbound/putaway/claim', { putawayId: row.putawayId }); show('已领取'); load() }
  catch (e) { show(e?.message || '领取失败') }
}
async function confirmPutaway(row) {
  const bin = putawayBin.value[row.putawayId] || row.recommendBin
  if (!bin) { show('请指定上架库位'); return }
  try {
    await post('/wms/inbound/putaway/confirm', { putawayId: row.putawayId, actualBin: bin })
    show('上架完成')
    load()
  } catch (e) { show(e?.message || '上架失败') }
}

onMounted(load)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn" @click="load">刷新</button>
      <input v-if="module==='wms-inbound'" v-model="filters.keyword" placeholder="任务号/来源单/供应商" />
      <select v-if="module==='wms-inbound'" v-model="filters.status">
        <option value="">全部状态</option>
        <option v-for="(t,k) in statusText" :key="k" :value="k">{{ t }}</option>
      </select>
      <select v-if="module==='wms-inbound'" v-model="filters.inboundType">
        <option value="">全部类型</option>
        <option v-for="(t,k) in inboundTypeText" :key="k" :value="k">{{ t }}</option>
      </select>
      <button v-if="module==='wms-inbound'" class="btn primary" @click="showCreate=true">从采购单生成</button>
      <button class="btn" @click="load">查询</button>
    </div>
    <div v-if="feedback" class="toast-inline">{{ feedback }}</div>

    <!-- 入库任务列表 -->
    <div v-if="module==='wms-inbound'" class="tablebox">
      <div class="scroll">
        <table class="data">
          <thead><tr>
            <th>任务号</th><th>类型</th><th>来源单</th><th>供应商/客户</th><th>仓库</th>
            <th class="num">应收</th><th class="num">已收</th><th class="num">已上架</th>
            <th>状态</th><th>收货人</th><th>创建时间</th><th>操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="r in list" :key="r.taskId">
              <td class="lnk" @click="openDetail(r)">{{ r.taskNo }}</td>
              <td>{{ inboundTypeText[r.inboundType] || r.inboundType }}</td>
              <td>{{ r.sourceOrderNo || '—' }}</td>
              <td>{{ r.supplierName || r.customerName || '—' }}</td>
              <td>{{ r.warehouse }}</td>
              <td class="num">{{ r.totalQty }}</td>
              <td class="num">{{ r.receivedQty }}</td>
              <td class="num">{{ r.putawayQty }}</td>
              <td><span class="tag" :class="r.status==='DONE'?'ok':(r.status==='EXCEPTION'?'err':'')">{{ statusText[r.status] || r.status }}</span></td>
              <td>{{ r.receiver || '—' }}</td>
              <td>{{ r.createdAt }}</td>
              <td>
                <button v-if="['PENDING','RECEIVING'].includes(r.status)" class="link" @click="openDetail(r)">收货</button>
                <button v-if="r.status==='RECEIVING'" class="link" @click="finishReceive(r)">结束收货</button>
                <button v-if="r.status==='RECHECK'" class="link ok" @click="recheck(r,true)">复检通过</button>
                <button v-if="r.status==='RECHECK'" class="link err" @click="recheck(r,false)">复检不通过</button>
                <button v-if="['PUTAWAY','PUTTING'].includes(r.status)" class="link" @click="openDetail(r)">查看上架</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 上架任务 -->
    <div v-if="module==='wms-putaway'" class="tablebox">
      <div class="scroll">
        <table class="data">
          <thead><tr>
            <th>上架单号</th><th>商品</th><th>批次</th><th class="num">数量</th>
            <th>推荐库区/温区</th><th>推荐库位</th><th>实际库位</th><th>负责人</th><th>状态</th><th>操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="r in list" :key="r.putawayId">
              <td>{{ r.putawayNo }}</td>
              <td>{{ r.goodsName }}<div class="sub">{{ r.goodsCode }}</div></td>
              <td>{{ r.batchNo || '—' }}</td>
              <td class="num">{{ r.qty }}</td>
              <td>
                <div v-if="r.recommendZoneCode">{{ r.recommendZoneName || r.recommendZoneCode }}
                  <span v-if="r.recommendStorageProp" class="storagetag">{{ r.recommendStorageProp }}</span>
                </div>
                <span v-else class="muted">—</span>
              </td>
              <td class="bin">{{ r.recommendBin || '—' }}</td>
              <td>
                <input v-if="r.status!=='DONE'" v-model="putawayBin[r.putawayId]" :placeholder="r.recommendBin || '扫/输库位'" style="width:120px" />
                <span v-else>{{ r.actualBin }}</span>
              </td>
              <td>{{ r.assignee || '—' }}</td>
              <td><span class="tag" :class="r.status==='DONE'?'ok':''">{{ {PENDING:'待上架',PUTTING:'上架中',DONE:'已完成',CANCELLED:'已取消'}[r.status] }}</span></td>
              <td>
                <button v-if="!r.assignee" class="link" @click="claimPutaway(r)">领取</button>
                <button v-if="r.status!=='DONE'" class="link ok" @click="confirmPutaway(r)">确认上架</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 创建弹窗 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate=false">
      <div class="modal">
        <h3>从采购订单生成入库任务</h3>
        <label>采购订单号 <input v-model="createForm.purchaseOrderNo" placeholder="CGDDxxxxxxxx" /></label>
        <div class="ops"><button class="btn" @click="showCreate=false">取消</button>
        <button class="btn primary" @click="createFromPurchase">生成</button></div>
      </div>
    </div>

    <!-- 收货弹窗 -->
    <div v-if="showReceive" class="modal-mask" @click.self="showReceive=false">
      <div class="modal">
        <h3>登记收货</h3>
        <label>本次实收 <input type="number" v-model.number="receiveForm.qty" min="0.001" step="0.001" /></label>
        <label>批次号 <input v-model="receiveForm.batchNo" placeholder="留空按生产日期自动生成" /></label>
        <label>生产日期 <input type="date" v-model="receiveForm.productionDate" /></label>
        <label>到期日期 <input type="date" v-model="receiveForm.expiryDate" /></label>
        <div class="ops"><button class="btn" @click="showReceive=false">取消</button>
        <button class="btn primary" @click="submitReceive">确认收货</button></div>
      </div>
    </div>

    <!-- 详情抽屉 -->
    <div v-if="detail" class="drawer-mask" @click.self="detail=null">
      <div class="drawer wide">
        <h3>入库任务 {{ detail.taskNo }}
          <span class="tag" :class="detail.status==='DONE'?'ok':''">{{ statusText[detail.status] || detail.status }}</span>
        </h3>
        <div class="kv">
          <div><b>类型：</b>{{ inboundTypeText[detail.inboundType] }}</div>
          <div><b>来源单：</b>{{ detail.sourceOrderNo || '—' }}</div>
          <div><b>供应商：</b>{{ detail.supplierName || '—' }}</div>
          <div><b>仓库：</b>{{ detail.warehouse }}</div>
          <div><b>收货人：</b>{{ detail.receiver || '—' }}</div>
          <div><b>复检人：</b>{{ detail.rechecker || '—' }}</div>
        </div>
        <table class="data">
          <thead><tr><th>商品</th><th class="num">应收</th><th class="num">已收</th><th class="num">已上架</th>
            <th>批次</th><th>生产日期</th><th>推荐库区/温区</th><th>推荐库位</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="d in detail.details" :key="d.detailId">
              <td>{{ d.goodsName }}<div class="sub">{{ d.goodsCode }}</div></td>
              <td class="num">{{ d.expectedQty }}</td>
              <td class="num">{{ d.receivedQty }}</td>
              <td class="num">{{ d.putawayQty }}</td>
              <td>{{ d.batchNo || '—' }}</td>
              <td>{{ d.productionDate || '—' }}</td>
              <td>
                <div v-if="d.recommendZoneCode">{{ d.recommendZoneName || d.recommendZoneCode }}
                  <span v-if="d.recommendStorageProp" class="storagetag">{{ d.recommendStorageProp }}</span>
                </div>
                <span v-else class="muted">—</span>
              </td>
              <td class="bin">{{ d.recommendBin || '—' }}</td>
              <td>{{ {PENDING:'待收',RECEIVED:'已收',PUTTING:'上架中',DONE:'完成'}[d.status] }}</td>
              <td>
                <button v-if="['PENDING','RECEIVED'].includes(d.status) && detail.status!=='DONE'" class="link" @click="openReceive(d)">收货</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div class="ops">
          <button v-if="detail.status==='RECEIVING'" class="btn primary" @click="finishReceive(detail)">结束收货</button>
          <button v-if="detail.status==='RECHECK'" class="btn primary" @click="recheck(detail,true)">复检通过</button>
          <button class="btn" @click="detail=null">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-ops { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.btn { height: 30px; padding: 0 14px; border: 1px solid #d9d9d9; background: #fff; border-radius: 4px; cursor: pointer; }
.btn.primary { background: #1677ff; color: #fff; border-color: #1677ff; }
input, select { height: 30px; padding: 0 8px; border: 1px solid #d9d9d9; border-radius: 4px; }
.tablebox { background: #fff; border: 1px solid #f0f0f0; border-radius: 6px; overflow: hidden; }
.scroll { overflow: auto; max-height: calc(100vh - 220px); }
table.data { width: 100%; border-collapse: collapse; font-size: 13px; }
table.data th, table.data td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; }
table.data th { background: #fafafa; font-weight: 600; color: #555; position: sticky; top: 0; }
.num { text-align: right; }
.bin { font-family: monospace; color: #1677ff; }
.sub { color: #aaa; font-size: 11px; }
.lnk { color: #1677ff; cursor: pointer; }
.link { border: none; background: none; color: #1677ff; cursor: pointer; padding: 0 6px; }
.link.err { color: #cf1322; }
.link.ok { color: #389e0d; }
.tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; background: #f0f0f0; }
.tag.ok { background: #f6ffed; color: #389e0d; }
.tag.err { background: #fff1f0; color: #cf1322; }
.toast-inline { background: #e6f4ff; border: 1px solid #91caff; padding: 6px 12px; border-radius: 4px; margin-bottom: 10px; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 8px; padding: 20px; min-width: 420px; }
.modal h3, .drawer h3 { margin: 0 0 16px; }
.modal label { display: block; margin-bottom: 10px; }
.modal label input { width: 100%; margin-top: 4px; }
.ops { display: flex; gap: 8px; justify-content: flex-end; margin-top: 14px; }
.drawer-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); z-index: 90; }
.drawer { position: absolute; right: 0; top: 0; bottom: 0; width: 720px; background: #fff; padding: 20px; overflow: auto; box-shadow: -4px 0 12px rgba(0,0,0,0.1); }
.drawer.wide { width: 900px; }
.kv { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 16px; margin-bottom: 14px; font-size: 13px; }
</style>
