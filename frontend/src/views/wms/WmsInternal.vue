<script setup>
/**
 * WMS V1.5 库内作业统一页（PRD-28），按 route.meta.module 切换：
 *   wms-replenish / wms-move / wms-adjust / wms-damage /
 *   wms-assembly / wms-expiry / wms-stocktake / wms-freeze
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { get, post } from '../../api/client.js'

const route = useRoute()
const module = computed(() => route.meta?.module || 'wms-replenish')
const loading = ref(false)
const feedback = ref('')
const list = ref([])
const filters = ref({ status: '', keyword: '' })
const showForm = ref(false)
const form = ref({})
const stocktakeBins = ref([])
const stocktakeTaskId = ref('')

function show(msg) { feedback.value = msg; setTimeout(() => (feedback.value = ''), 3000) }
function emptyForm() {
  return {
    move: { fromBin: '', toBin: '', goodsCode: '', goodsName: '', batchNo: '', qty: 1, moveType: 'ACTIVE', remark: '' },
    adjust: { binCode: '', goodsCode: '', goodsName: '', batchNo: '', bookQty: 0, actualQty: 0, reason: 'COUNT_DIFF', remark: '' },
    damage: { binCode: '', goodsCode: '', goodsName: '', batchNo: '', qty: 1, reason: 'BREAKAGE', responsibility: '', imageUrl: '', remark: '' },
    freeze: { binCode: '', goodsCode: '', batchNo: '', qty: 0, reason: 'QUALITY', remark: '' },
    assembly: { taskType: 'ASSEMBLY', finishedGoodsCode: '', finishedGoodsName: '', qty: 1, stationBin: '', remark: '', components: [] },
    stocktake: { countType: 'DYNAMIC', scopeText: '', warehouse: '总仓', binCodes: [], remark: '' },
  }
}

const endpoints = {
  'wms-replenish': '/wms/internal/replenish',
  'wms-move': '/wms/internal/move',
  'wms-adjust': '/wms/internal/adjust',
  'wms-damage': '/wms/internal/damage',
  'wms-assembly': '/wms/internal/assembly',
  'wms-expiry': '/wms/internal/expiry',
  'wms-stocktake': '/wms/internal/stocktake',
  'wms-freeze': '/wms/internal/freeze',
}

async function load() {
  loading.value = true
  try {
    const ep = endpoints[module.value]
    if (module.value === 'wms-expiry') {
      list.value = (await post(ep + '/page', { pageNo: 1, pageSize: 200, filters: { ...filters.value } })).records || []
    } else {
      list.value = (await post(ep + '/page', { pageNo: 1, pageSize: 200, filters: { ...filters.value } })).records || []
    }
  } catch (e) { show('加载失败：' + (e?.message || e)) }
  finally { loading.value = false }
}

function openCreate() {
  const key = module.value.replace('wms-', '')
  form.value = structuredClone(emptyForm()[key] || {})
  showForm.value = true
}

async function submit() {
  try {
    const ep = endpoints[module.value]
    if (module.value === 'wms-replenish') {
      await post(ep + '/generate', { warehouse: '总仓' })
      show('已扫描生成补货任务')
    } else if (module.value === 'wms-move') {
      await post(ep + '/create', form.value); show('移库单已创建')
    } else if (module.value === 'wms-adjust') {
      await post(ep + '/create', form.value); show('调整单已创建，待审批')
    } else if (module.value === 'wms-damage') {
      await post(ep + '/create', form.value); show('报损单已创建，待审批')
    } else if (module.value === 'wms-freeze') {
      await post(ep + '/create', form.value); show('冻结成功')
    } else if (module.value === 'wms-assembly') {
      await post(ep + '/create', form.value); show('组装任务已创建')
    } else if (module.value === 'wms-stocktake') {
      await post(ep + '/create', form.value); show('盘点单已创建')
    }
    showForm.value = false
    load()
  } catch (e) { show(e?.message || '操作失败') }
}

async function rowAction(action, row) {
  const ep = endpoints[module.value]
  try {
    if (module.value === 'wms-replenish') {
      if (action === 'claim') await post(ep + '/claim', { taskId: row.taskId })
      else if (action === 'complete') await post(ep + '/complete', { taskId: row.taskId })
      show('操作成功')
    } else if (module.value === 'wms-move') {
      await post(ep + '/complete', { taskId: row.taskId }); show('移库完成')
    } else if (module.value === 'wms-adjust') {
      await post(ep + '/approve', { adjustId: row.adjustId, approved: action === 'approve' });
      show(action === 'approve' ? '已审批通过' : '已驳回')
    } else if (module.value === 'wms-damage') {
      await post(ep + '/approve', { damageId: row.damageId, approved: action === 'approve' });
      show(action === 'approve' ? '已审批报损' : '已驳回')
    } else if (module.value === 'wms-assembly') {
      await post(ep + '/complete', { taskId: row.taskId }); show('组装/拆卸完成')
    } else if (module.value === 'wms-expiry') {
      const status = action === 'promotion' ? 'PROMOTION' : action === 'return' ? 'RETURN' : action === 'loss' ? 'LOSS' : 'DONE'
      await post(ep + '/handle', { alertId: row.alertId, handleStatus: status, handleRemark: 'PC处理' })
      show('已登记处理结果')
    } else if (module.value === 'wms-freeze') {
      if (action === 'unfreeze') await post(ep + '/unfreeze', { freezeId: row.freezeId })
      show('已解冻')
    }
    load()
  } catch (e) { show(e?.message || '操作失败') }
}

async function refreshExpiry() {
  try {
    const r = await post('/wms/internal/expiry/refresh', { warehouse: '总仓' })
    show(`已刷新，新预警 ${r.refreshed} 条`)
    load()
  } catch (e) { show(e?.message || '刷新失败') }
}

async function openStocktakeBins(row) {
  stocktakeTaskId.value = row.taskId
  stocktakeBins.value = await get(`/wms/internal/stocktake/bins?taskId=${encodeURIComponent(row.taskId)}`)
}
async function countBinSubmit(b) {
  try {
    await post('/wms/internal/stocktake/count', { id: b.id, realQty: Number(b.realQty || 0) })
    show('已提交')
    await openStocktakeBins({ taskId: stocktakeTaskId.value })
  } catch (e) { show(e?.message || '提交失败') }
}
async function finishStocktake() {
  if (!confirm('确认完成盘点？有差异的行将自动生成调整单。')) return
  try {
    const r = await post('/wms/internal/stocktake/finish', { taskId: stocktakeTaskId.value })
    show(`盘点完成，生成 ${r.adjustCount} 条调整单`)
    stocktakeBins.value = []
    load()
  } catch (e) { show(e?.message || '操作失败') }
}

onMounted(load)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn" @click="load">刷新</button>
      <input v-model="filters.keyword" placeholder="关键字" />
      <select v-model="filters.status">
        <option value="">全部状态</option>
      </select>
      <button v-if="module==='wms-replenish'" class="btn primary" @click="submit">扫描生成主动补货</button>
      <button v-if="module==='wms-expiry'" class="btn primary" @click="refreshExpiry">刷新效期预警</button>
      <button v-if="['wms-move','wms-adjust','wms-damage','wms-freeze','wms-assembly','wms-stocktake'].includes(module)" class="btn primary" @click="openCreate">新建</button>
      <button class="btn" @click="load">查询</button>
    </div>
    <div v-if="feedback" class="toast-inline">{{ feedback }}</div>

    <!-- 补货 -->
    <div v-if="module==='wms-replenish'" class="tablebox">
      <table class="data">
        <thead><tr><th>补货单号</th><th>商品</th><th>源库位</th><th>目标库位</th><th class="num">数量</th><th>触发类型</th><th>负责人</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="r in list" :key="r.taskId">
            <td>{{ r.taskNo }}</td>
            <td>{{ r.goodsName || r.goodsCode }}<div class="sub">{{ r.batchNo || '' }}</div></td>
            <td class="bin">{{ r.fromBin }}</td><td class="bin">{{ r.toBin }}</td>
            <td class="num">{{ r.qty }}</td>
            <td>{{ {PASSIVE:'被动',ACTIVE:'主动',MANUAL:'手动',URGENT:'加急'}[r.triggerType] }}</td>
            <td>{{ r.assignee || '—' }}</td>
            <td><span class="tag">{{ {PENDING:'待处理',PICKING:'拣货中',DONE:'完成',CANCELLED:'取消'}[r.status] }}</span></td>
            <td>
              <button v-if="!r.assignee" class="link" @click="rowAction('claim',r)">领取</button>
              <button v-if="r.status!=='DONE'" class="link ok" @click="rowAction('complete',r)">完成补货</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 移库 -->
    <div v-if="module==='wms-move'" class="tablebox">
      <table class="data">
        <thead><tr><th>移库单号</th><th>商品</th><th>源库位</th><th>目标库位</th><th class="num">数量</th><th>类型</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="r in list" :key="r.taskId">
            <td>{{ r.taskNo }}</td><td>{{ r.goodsName || r.goodsCode }}</td>
            <td class="bin">{{ r.fromBin }}</td><td class="bin">{{ r.toBin }}</td>
            <td class="num">{{ r.qty }}</td>
            <td>{{ {ACTIVE:'主动整理',PASSIVE:'被动',PALLET:'整托'}[r.moveType] }}</td>
            <td><span class="tag">{{ {PENDING:'待移',MOVING:'移库中',DONE:'完成',EXCEPTION:'异常'}[r.status] }}</span></td>
            <td><button v-if="r.status!=='DONE'" class="link ok" @click="rowAction('complete',r)">确认移库</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 调整 -->
    <div v-if="module==='wms-adjust'" class="tablebox">
      <table class="data">
        <thead><tr><th>调整单号</th><th>商品</th><th>库位</th><th class="num">账面</th><th class="num">实盘</th><th class="num">差异</th><th>类型</th><th>原因</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="r in list" :key="r.adjustId">
            <td>{{ r.adjustNo }}</td><td>{{ r.goodsName || r.goodsCode }}</td>
            <td class="bin">{{ r.binCode }}</td>
            <td class="num">{{ r.bookQty }}</td><td class="num">{{ r.actualQty }}</td>
            <td class="num" :class="r.adjustType==='LOSS'?'neg':'pos'">{{ r.adjustQty > 0 ? '+' : '' }}{{ r.adjustQty }}</td>
            <td>{{ r.adjustType==='GAIN'?'盘盈':'盘亏' }}</td>
            <td>{{ {COUNT_DIFF:'盘点差异',DAMAGE:'货损',HISTORY:'历史漏记',OTHER:'其他'}[r.reason] }}</td>
            <td><span class="tag" :class="r.status==='APPROVED'?'ok':(r.status==='REJECTED'?'err':'')">{{ {PENDING:'待审批',APPROVED:'已审批',REJECTED:'驳回'}[r.status] }}</span></td>
            <td>
              <button v-if="r.status==='PENDING'" class="link ok" @click="rowAction('approve',r)">通过</button>
              <button v-if="r.status==='PENDING'" class="link err" @click="rowAction('reject',r)">驳回</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 报损 -->
    <div v-if="module==='wms-damage'" class="tablebox">
      <table class="data">
        <thead><tr><th>报损单号</th><th>商品</th><th>库位</th><th class="num">数量</th><th class="num">成本损失</th><th>原因</th><th>责任方</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="r in list" :key="r.damageId">
            <td>{{ r.damageNo }}</td><td>{{ r.goodsName || r.goodsCode }}</td>
            <td class="bin">{{ r.binCode }}</td>
            <td class="num">{{ r.qty }}</td><td class="num">¥{{ r.costAmount }}</td>
            <td>{{ {BREAKAGE:'破损',EXPIRY:'过期',QUALITY:'质量',OTHER:'其他'}[r.reason] }}</td>
            <td>{{ r.responsibility || '—' }}</td>
            <td><span class="tag" :class="r.status==='APPROVED'?'ok':(r.status==='REJECTED'?'err':'')">{{ {PENDING:'待审批',APPROVED:'已报损',REJECTED:'驳回'}[r.status] }}</span></td>
            <td>
              <button v-if="r.status==='PENDING'" class="link ok" @click="rowAction('approve',r)">通过</button>
              <button v-if="r.status==='PENDING'" class="link err" @click="rowAction('reject',r)">驳回</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 冻结 -->
    <div v-if="module==='wms-freeze'" class="tablebox">
      <table class="data">
        <thead><tr><th>冻结ID</th><th>库位</th><th>商品</th><th>批次</th><th class="num">数量</th><th>原因</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="r in list" :key="r.freezeId">
            <td>{{ r.freezeId }}</td><td class="bin">{{ r.binCode }}</td>
            <td>{{ r.goodsCode || '整库位' }}</td><td>{{ r.batchNo || '—' }}</td>
            <td class="num">{{ r.qty || '全部' }}</td>
            <td>{{ {COUNT:'盘点',QUALITY:'质量',DAMAGE:'破损',EXPIRY:'临期',OTHER:'其他'}[r.reason] }}</td>
            <td><span class="tag" :class="r.status==='FROZEN'?'err':'ok'">{{ r.status==='FROZEN'?'冻结中':'已解冻' }}</span></td>
            <td><button v-if="r.status==='FROZEN'" class="link ok" @click="rowAction('unfreeze',r)">解冻</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 组装拆卸 -->
    <div v-if="module==='wms-assembly'" class="tablebox">
      <table class="data">
        <thead><tr><th>任务单号</th><th>类型</th><th>成品</th><th class="num">数量</th><th>工位</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="r in list" :key="r.taskId">
            <td>{{ r.taskNo }}</td>
            <td>{{ r.taskType==='ASSEMBLY'?'组装':'拆卸' }}</td>
            <td>{{ r.finishedGoodsName || r.finishedGoodsCode }}</td>
            <td class="num">{{ r.qty }}</td><td class="bin">{{ r.stationBin || '—' }}</td>
            <td><span class="tag">{{ {PENDING:'待处理',PICKING:'拣料中',ASSEMBLING:'组装中',DONE:'完成',EXCEPTION:'异常'}[r.status] }}</span></td>
            <td><button v-if="r.status!=='DONE'" class="link ok" @click="rowAction('complete',r)">完成</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 效期预警 -->
    <div v-if="module==='wms-expiry'" class="tablebox">
      <table class="data">
        <thead><tr><th>商品</th><th>批次</th><th>库位</th><th class="num">数量</th><th>生产日期</th><th>到期日期</th><th class="num">剩余天数</th><th>等级</th><th>处理</th></tr></thead>
        <tbody>
          <tr v-for="r in list" :key="r.alertId">
            <td>{{ r.goodsName || r.goodsCode }}</td><td>{{ r.batchNo || '—' }}</td>
            <td class="bin">{{ r.binCode }}</td><td class="num">{{ r.qty }}</td>
            <td>{{ r.productionDate || '—' }}</td><td>{{ r.expiryDate }}</td>
            <td class="num" :class="r.alertLevel==='EXPIRED'?'neg':(r.alertLevel==='CRITICAL'?'warn':'')">{{ r.daysToExpiry }}</td>
            <td><span class="tag" :class="r.alertLevel==='EXPIRED'?'err':(r.alertLevel==='CRITICAL'?'warn':'')">{{ {NORMAL:'正常',WARNING:'临期',CRITICAL:'紧急',EXPIRED:'已过期'}[r.alertLevel] }}</span></td>
            <td>
              <button v-if="r.handleStatus==='PENDING'" class="link" @click="rowAction('promotion',r)">促销</button>
              <button v-if="r.handleStatus==='PENDING'" class="link" @click="rowAction('return',r)">退货</button>
              <button v-if="r.handleStatus==='PENDING'" class="link err" @click="rowAction('loss',r)">报损</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 盘点 -->
    <div v-if="module==='wms-stocktake'" class="tablebox">
      <table class="data">
        <thead><tr><th>盘点单号</th><th>类型</th><th>模式</th><th class="num">总行数</th><th class="num">已盘</th><th class="num">差异数</th><th>冻结</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="r in list" :key="r.taskId">
            <td>{{ r.taskNo }}</td>
            <td>{{ {DYNAMIC:'动盘',FULL:'全盘',SPECIFIED:'指定',SAMPLE:'抽盘',CYCLE:'循环盘'}[r.countType] }}</td>
            <td>{{ r.countMode==='BLIND'?'暗盘':'明盘' }}</td>
            <td class="num">{{ r.totalBins }}</td><td class="num">{{ r.countedBins }}</td><td class="num">{{ r.diffCount }}</td>
            <td>{{ r.freezeFlag==='Y' ? '是' : '否' }}</td>
            <td><span class="tag" :class="r.status==='APPROVED'?'ok':''">{{ {PENDING:'待盘',COUNTING:'盘点中',RECOUNT:'复盘',PENDING_APPROVAL:'待审批',APPROVED:'已完成',CANCELLED:'取消'}[r.status] }}</span></td>
            <td>
              <button v-if="r.status==='COUNTING'" class="link" @click="openStocktakeBins(r)">录入盘点</button>
              <button v-if="r.status==='COUNTING'" class="link ok" @click="finishStocktake">完成盘点</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 新建弹窗 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm=false">
      <div class="modal">
        <h3>新建</h3>
        <template v-if="module==='wms-move'">
          <label>源库位 <input v-model="form.fromBin" /></label>
          <label>目标库位 <input v-model="form.toBin" /></label>
          <label>商品编码 <input v-model="form.goodsCode" /></label>
          <label>批次 <input v-model="form.batchNo" /></label>
          <label>数量 <input type="number" v-model.number="form.qty" /></label>
        </template>
        <template v-if="module==='wms-adjust'">
          <label>库位 <input v-model="form.binCode" /></label>
          <label>商品编码 <input v-model="form.goodsCode" /></label>
          <label>批次 <input v-model="form.batchNo" /></label>
          <label>账面数量 <input type="number" v-model.number="form.bookQty" /></label>
          <label>实盘数量 <input type="number" v-model.number="form.actualQty" /></label>
        </template>
        <template v-if="module==='wms-damage'">
          <label>库位 <input v-model="form.binCode" /></label>
          <label>商品编码 <input v-model="form.goodsCode" /></label>
          <label>批次 <input v-model="form.batchNo" /></label>
          <label>数量 <input type="number" v-model.number="form.qty" /></label>
          <label>责任方 <input v-model="form.responsibility" /></label>
          <label>照片URL <input v-model="form.imageUrl" placeholder="必填（参数要求）" /></label>
        </template>
        <template v-if="module==='wms-freeze'">
          <label>库位 <input v-model="form.binCode" /></label>
          <label>商品编码（留空=整库位）<input v-model="form.goodsCode" /></label>
          <label>批次 <input v-model="form.batchNo" /></label>
          <label>数量（0=整库位）<input type="number" v-model.number="form.qty" /></label>
        </template>
        <template v-if="module==='wms-assembly'">
          <label>类型 <select v-model="form.taskType"><option value="ASSEMBLY">组装</option><option value="DISASSEMBLY">拆卸</option></select></label>
          <label>成品编码 <input v-model="form.finishedGoodsCode" /></label>
          <label>数量 <input type="number" v-model.number="form.qty" /></label>
          <label>工位库位 <input v-model="form.stationBin" /></label>
        </template>
        <template v-if="module==='wms-stocktake'">
          <label>类型 <select v-model="form.countType">
            <option value="DYNAMIC">动盘</option><option value="FULL">全盘</option>
            <option value="SAMPLE">抽盘</option><option value="CYCLE">循环盘</option>
          </select></label>
          <label>范围描述 <input v-model="form.scopeText" /></label>
        </template>
        <div class="ops">
          <button class="btn" @click="showForm=false">取消</button>
          <button class="btn primary" @click="submit">提交</button>
        </div>
      </div>
    </div>

    <!-- 盘点录入抽屉 -->
    <div v-if="stocktakeBins.length" class="drawer-mask" @click.self="stocktakeBins=[]">
      <div class="drawer">
        <h3>盘点录入（{{ stocktakeTaskId }}）</h3>
        <table class="data">
          <thead><tr><th>库位</th><th>商品</th><th>批次</th><th class="num">账面</th><th class="num">实盘</th><th class="num">差异</th><th></th></tr></thead>
          <tbody>
            <tr v-for="b in stocktakeBins" :key="b.id">
              <td class="bin">{{ b.binCode }}</td><td>{{ b.goodsName }}</td><td>{{ b.batchNo || '—' }}</td>
              <td class="num">{{ b.bookQty }}</td>
              <td><input type="number" v-model.number="b.realQty" style="width:90px" /></td>
              <td class="num" :class="b.realQty - b.bookQty > 0 ? 'pos' : (b.realQty - b.bookQty < 0 ? 'neg' : '')">{{ b.realQty != null ? (b.realQty - b.bookQty) : '—' }}</td>
              <td><button class="link ok" @click="countBinSubmit(b)">提交</button></td>
            </tr>
          </tbody>
        </table>
        <div class="ops">
          <button class="btn primary" @click="finishStocktake">完成盘点</button>
          <button class="btn" @click="stocktakeBins=[]">关闭</button>
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
.tablebox { background: #fff; border: 1px solid #f0f0f0; border-radius: 6px; overflow: auto; max-height: calc(100vh - 200px); }
table.data { width: 100%; border-collapse: collapse; font-size: 13px; }
table.data th, table.data td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; white-space: nowrap; }
table.data th { background: #fafafa; font-weight: 600; color: #555; }
.num { text-align: right; }
.pos { color: #389e0d; }
.neg { color: #cf1322; }
.warn { color: #d46b08; }
.bin { font-family: monospace; color: #1677ff; }
.sub { color: #aaa; font-size: 11px; }
.link { border: none; background: none; color: #1677ff; cursor: pointer; padding: 0 6px; }
.link.err { color: #cf1322; }
.link.ok { color: #389e0d; }
.tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; background: #f0f0f0; }
.tag.ok { background: #f6ffed; color: #389e0d; }
.tag.err { background: #fff1f0; color: #cf1322; }
.tag.warn { background: #fff7e6; color: #d46b08; }
.toast-inline { background: #e6f4ff; border: 1px solid #91caff; padding: 6px 12px; border-radius: 4px; margin-bottom: 10px; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 8px; padding: 20px; min-width: 420px; max-height: 85vh; overflow: auto; }
.modal h3, .drawer h3 { margin: 0 0 16px; }
.modal label { display: block; margin-bottom: 10px; }
.modal label input, .modal label select { width: 100%; margin-top: 4px; }
.ops { display: flex; gap: 8px; justify-content: flex-end; margin-top: 14px; }
.drawer-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); z-index: 90; }
.drawer { position: absolute; right: 0; top: 0; bottom: 0; width: 860px; background: #fff; padding: 20px; overflow: auto; box-shadow: -4px 0 12px rgba(0,0,0,0.1); }
</style>
