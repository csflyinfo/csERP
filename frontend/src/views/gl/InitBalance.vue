<script setup>
/**
 * 总账 M1——总账初始化
 * 流程：录期初余额（主科目行 / 辅助核算明细行）→ 试算平衡 →（可选）一键引入业务期初
 *       → 选择启用期间启用总账；启用后期初锁定，页面转为只读 + 期间状态表。
 */
import { ref, computed, onMounted } from 'vue'
import { post } from '../../api/client.js'

const status = ref({ initialized: false, enabled: false, startPeriod: '', currentPeriod: '' })
const rows = ref([])
const periods = ref([])
const loading = ref(false)
const feedback = ref('')
const dirty = ref(new Set())

const AUX_DIMS = [
  { v: 'customer', f: 'auxCustomer', l: '客户' },
  { v: 'supplier', f: 'auxSupplier', l: '供应商' },
  { v: 'department', f: 'auxDepartment', l: '部门' },
  { v: 'employee', f: 'auxEmployee', l: '员工' },
  { v: 'goods', f: 'auxGoods', l: '商品' },
  { v: 'project', f: 'auxProject', l: '项目' },
  { v: 'area', f: 'auxArea', l: '片区' },
]

function auxDimsOf(row) {
  return (row.auxDimensions || '').split(',').filter(Boolean)
    .map(v => AUX_DIMS.find(d => d.v === v)).filter(Boolean)
}
function auxText(dims) {
  if (!dims) return ''
  return dims.split(',').map(v => AUX_DIMS.find(d => d.v === v)?.l || v).join('、')
}

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 3500)
}

async function loadAll() {
  loading.value = true
  try {
    status.value = await post('/finance/gl/init/status')
    rows.value = (await post('/finance/gl/init/balance-list')) || []
    periods.value = (await post('/finance/gl/init/period-list')) || []
    dirty.value = new Set()
  } catch (e) { show('加载失败：' + (e?.message || e), 'err') }
  finally { loading.value = false }
}

function markDirty(code) { dirty.value.add(code) }

async function saveBalances() {
  const payload = []
  for (const code of dirty.value) {
    const r = rows.value.find(x => x.accountCode === code)
    if (!r) continue
    payload.push({
      accountCode: r.accountCode,
      openDebit: r.openDebit || 0, openCredit: r.openCredit || 0,
      openQty: r.openQty || 0, ytdDebit: r.ytdDebit || 0, ytdCredit: r.ytdCredit || 0,
    })
  }
  if (!payload.length) return show('没有需要保存的改动', 'err')
  try {
    await post('/finance/gl/init/balance-save', { rows: payload })
    show(`已保存 ${payload.length} 个科目的期初`)
    await loadAll()
  } catch (e) { show('保存失败：' + (e?.message || e), 'err') }
}

// ===== 辅助核算明细弹窗 =====
const auxOpen = ref(false)
const auxAccount = ref(null)
const auxRows = ref([])
const auxDims = ref([])

async function openAux(row) {
  auxAccount.value = row
  auxDims.value = auxDimsOf(row)
  try {
    auxRows.value = (await post('/finance/gl/init/aux-balance-list', { accountCode: row.accountCode })) || []
    if (!auxRows.value.length) addAuxRow()
    auxOpen.value = true
  } catch (e) { show('辅助明细加载失败：' + (e?.message || e), 'err') }
}
function addAuxRow() {
  const blank = { openDebit: 0, openCredit: 0, openQty: 0, ytdDebit: 0, ytdCredit: 0, _deleted: false }
  for (const d of auxDims.value) blank[d.f] = ''
  auxRows.value.push(blank)
}
async function saveAux() {
  const payload = []
  for (const r of auxRows.value) {
    const item = { accountCode: auxAccount.value.accountCode,
      openDebit: r._deleted ? 0 : (r.openDebit || 0), openCredit: r._deleted ? 0 : (r.openCredit || 0),
      openQty: r._deleted ? 0 : (r.openQty || 0), ytdDebit: r._deleted ? 0 : (r.ytdDebit || 0),
      ytdCredit: r._deleted ? 0 : (r.ytdCredit || 0) }
    for (const d of auxDims.value) item[d.f] = r[d.f] || ''
    payload.push(item)
  }
  try {
    await post('/finance/gl/init/balance-save', { rows: payload })
    show('辅助明细已保存')
    auxOpen.value = false
    await loadAll()
  } catch (e) { show('保存失败：' + (e?.message || e), 'err') }
}

// ===== 试算平衡 =====
const trialOpen = ref(false)
const trial = ref(null)
async function openTrial() {
  try {
    trial.value = await post('/finance/gl/init/trial-balance')
    trialOpen.value = true
  } catch (e) { show('试算失败：' + (e?.message || e), 'err') }
}
const fmt = v => (Number(v || 0)).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

// ===== 一键引入业务期初 =====
async function importBusiness() {
  if (!confirm('将从应收/应付/库存业务数据引入期初：\n  应收账款 1122（按客户）\n  应付账款 2202（按供应商）\n  库存商品 1405（按商品，含数量成本）\n同键重复引入以业务数据覆盖，确认继续？')) return
  try {
    const r = await post('/finance/gl/init/business-import')
    show(`引入完成：应收 ${r.arCount} 户/${fmt(r.arTotal)} 元，应付 ${r.apCount} 户/${fmt(r.apTotal)} 元，库存 ${r.goodsCount} 种/${fmt(r.goodsTotal)} 元`)
    await loadAll()
  } catch (e) { show('引入失败：' + (e?.message || e), 'err') }
}

// ===== 启用总账 =====
const enablePeriod = ref('')
async function enableGl() {
  const p = (enablePeriod.value || '').replace('-', '')
  if (!/^\d{6}$/.test(p)) return show('请选择启用期间', 'err')
  const t = await post('/finance/gl/init/trial-balance')
  if (!t.balanced) {
    show(`试算不平衡，不能启用：借方 ${fmt(t.totalDebit)}，贷方 ${fmt(t.totalCredit)}，差额 ${fmt(t.diff)}`, 'err')
    trial.value = t
    trialOpen.value = true
    return
  }
  if (!confirm(`确认启用总账？启用期间 ${p}。\n启用后期初余额锁定、会计期间正式开账，不能再修改期初。`)) return
  try {
    await post('/finance/gl/init/enable', { startPeriod: p })
    show('总账已启用！')
    await loadAll()
  } catch (e) { show('启用失败：' + (e?.message || e), 'err') }
}

const periodStatusClass = computed(() => s => ({
  未开始: 'muted', 进行中: 'ok', 已结账: 'done', 已冻结: 'frozen',
}[s] || 'muted'))

onMounted(async () => {
  const now = new Date()
  enablePeriod.value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
  await loadAll()
})
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <b>总账初始化</b>
      <span class="state-tag" :class="status.initialized ? 'on' : 'off'">
        {{ status.initialized ? `已启用 · 启用期间 ${status.startPeriod} · 当前期间 ${status.currentPeriod}` : '未启用（期初录入中）' }}
      </span>
      <div class="spacer"></div>
      <button class="btn" @click="loadAll">刷新</button>
      <template v-if="!status.initialized">
        <button class="btn" @click="openTrial">试算平衡</button>
        <button class="btn" @click="importBusiness">一键引入业务期初</button>
        <button class="btn" :disabled="!dirty.size" @click="saveBalances">保存期初（{{ dirty.size }}）</button>
      </template>
    </div>
    <div v-if="feedback" class="toast-inline" :class="feedback.level">{{ feedback.msg }}</div>

    <!-- 未启用：期初录入表 -->
    <div v-if="!status.initialized" class="card-box">
      <table class="data">
        <thead>
          <tr>
            <th style="width:180px">科目编码</th><th>科目名称</th><th style="width:50px">方向</th>
            <th style="width:150px">辅助核算</th>
            <th class="num" style="width:130px">期初借方</th><th class="num" style="width:130px">期初贷方</th>
            <th class="num" style="width:100px">数量</th>
            <th class="num" style="width:120px">本年累计借</th><th class="num" style="width:120px">本年累计贷</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.accountCode" :class="{ dirty: dirty.has(r.accountCode) }">
            <td><b class="code">{{ r.accountCode }}</b></td>
            <td>{{ r.accountName }}</td>
            <td>{{ r.balanceDirection }}</td>
            <td>
              <a v-if="r.auxDimensions" class="link" @click="openAux(r)">辅助明细（{{ auxText(r.auxDimensions) }}）</a>
              <span v-else class="muted">—</span>
            </td>
            <td class="num"><input class="num-input" type="number" step="0.01" v-model.number="r.openDebit"
              @input="markDirty(r.accountCode)" :disabled="!!r.auxDimensions" /></td>
            <td class="num"><input class="num-input" type="number" step="0.01" v-model.number="r.openCredit"
              @input="markDirty(r.accountCode)" :disabled="!!r.auxDimensions" /></td>
            <td class="num"><input class="num-input" type="number" step="0.0001" v-model.number="r.openQty"
              @input="markDirty(r.accountCode)" :disabled="!!r.auxDimensions || !r.isQty" /></td>
            <td class="num"><input class="num-input" type="number" step="0.01" v-model.number="r.ytdDebit"
              @input="markDirty(r.accountCode)" :disabled="!!r.auxDimensions" /></td>
            <td class="num"><input class="num-input" type="number" step="0.01" v-model.number="r.ytdCredit"
              @input="markDirty(r.accountCode)" :disabled="!!r.auxDimensions" /></td>
          </tr>
        </tbody>
      </table>
      <div class="init-bar">
        <span class="muted">说明：带辅助核算的科目请点「辅助明细」按期初对象逐户录入；主科目行与辅助明细不能同时有余额。金额单位：元。</span>
        <div class="spacer"></div>
        <label class="enable-label">启用期间
          <input type="month" v-model="enablePeriod" />
        </label>
        <button class="btn primary" @click="enableGl">启用总账</button>
      </div>
    </div>

    <!-- 已启用：期间状态表 -->
    <div v-else class="card-box">
      <div class="done-banner">✓ 总账已于 {{ status.startPeriod }} 启用，期初余额锁定。如需调整请通过凭证处理；期间结账在「期末处理」中进行。</div>
      <table class="data period-table">
        <thead><tr><th>期间</th><th>起止日期</th><th>状态</th><th>结账人</th><th>结账时间</th></tr></thead>
        <tbody>
          <tr v-for="p in periods" :key="p.period">
            <td><b class="code">{{ p.period }}</b></td>
            <td class="muted">{{ p.startDate }} ~ {{ p.endDate }}</td>
            <td><span class="ptag" :class="periodStatusClass(p.status)">{{ p.status }}</span></td>
            <td class="muted">{{ p.settleName || '—' }}</td>
            <td class="muted">{{ p.settleTime || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 辅助明细弹窗 -->
    <div v-if="auxOpen" class="modal-mask" @click.self="auxOpen=false">
      <div class="modal w900">
        <div class="modal-h">
          辅助核算期初 — {{ auxAccount?.accountCode }} {{ auxAccount?.accountName }}（{{ auxText(auxAccount?.auxDimensions) }}）
          <span class="x" @click="auxOpen=false">×</span>
        </div>
        <div class="modal-b">
          <table class="data aux-table">
            <thead>
              <tr>
                <th v-for="d in auxDims" :key="d.v">{{ d.l }}</th>
                <th class="num">期初借方</th><th class="num">期初贷方</th><th class="num">数量</th>
                <th class="num">年累借</th><th class="num">年累贷</th><th style="width:50px"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(r, i) in auxRows" :key="i" :class="{ deleted: r._deleted }">
                <td v-for="d in auxDims" :key="d.v">
                  <input v-model="r[d.f]" :placeholder="d.l + '编码/名称'" :disabled="r._deleted" />
                </td>
                <td class="num"><input type="number" step="0.01" v-model.number="r.openDebit" :disabled="r._deleted" /></td>
                <td class="num"><input type="number" step="0.01" v-model.number="r.openCredit" :disabled="r._deleted" /></td>
                <td class="num"><input type="number" step="0.0001" v-model.number="r.openQty" :disabled="r._deleted || !auxAccount?.isQty" /></td>
                <td class="num"><input type="number" step="0.01" v-model.number="r.ytdDebit" :disabled="r._deleted" /></td>
                <td class="num"><input type="number" step="0.01" v-model.number="r.ytdCredit" :disabled="r._deleted" /></td>
                <td><a class="danger" @click="r._deleted = !r._deleted">{{ r._deleted ? '撤销' : '删' }}</a></td>
              </tr>
            </tbody>
          </table>
          <button class="btn" style="margin-top:10px" @click="addAuxRow">＋ 增加一行</button>
        </div>
        <div class="modal-f">
          <button class="btn" @click="auxOpen=false">取消</button>
          <button class="btn primary" @click="saveAux">保存辅助明细</button>
        </div>
      </div>
    </div>

    <!-- 试算平衡弹窗 -->
    <div v-if="trialOpen" class="modal-mask" @click.self="trialOpen=false">
      <div class="modal w720">
        <div class="modal-h">试算平衡表<span class="x" @click="trialOpen=false">×</span></div>
        <div class="modal-b">
          <div class="trial-sum" :class="trial?.balanced ? 'ok' : 'bad'">
            借方合计 <b>{{ fmt(trial?.totalDebit) }}</b> ｜ 贷方合计 <b>{{ fmt(trial?.totalCredit) }}</b>
            ｜ 差额 <b>{{ fmt(trial?.diff) }}</b>
            <span class="verdict">{{ trial?.balanced ? '✓ 试算平衡' : '✗ 不平衡' }}</span>
          </div>
          <table class="data">
            <thead><tr><th>科目</th><th>名称</th><th>方向</th><th class="num">期初借</th><th class="num">期初贷</th></tr></thead>
            <tbody>
              <tr v-for="d in trial?.details || []" :key="d.accountCode">
                <td><b class="code">{{ d.accountCode }}</b></td>
                <td>{{ d.accountName }}</td><td>{{ d.balanceDirection }}</td>
                <td class="num">{{ fmt(d.openDebit) }}</td><td class="num">{{ fmt(d.openCredit) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="modal-f"><button class="btn primary" @click="trialOpen=false">关闭</button></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.spacer { flex: 1; }
.state-tag { font-size: 12px; padding: 3px 12px; border-radius: 12px; margin-left: 10px; }
.state-tag.on { background: #f6ffed; color: #389e0d; border: 1px solid #b7eb8f; }
.state-tag.off { background: #fff7e6; color: #d46b08; border: 1px solid #ffd591; }
.card-box { background: #fff; border: 1px solid #f0f0f0; border-radius: 8px; overflow: auto; margin-top: 10px; }
.data { width: 100%; border-collapse: collapse; font-size: 13px; }
.data th, .data td { padding: 6px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; }
.data th { background: #fafafa; font-weight: 600; color: #555; position: sticky; top: 0; }
.num { text-align: right; }
.code { font-family: monospace; color: #1677ff; }
.muted { color: #999; font-size: 12px; }
.link { color: #1677ff; cursor: pointer; font-size: 12px; }
tr.dirty { background: #fffbe6; }
.num-input { width: 110px; padding: 4px 8px; border: 1px solid #d9d9d9; border-radius: 5px; text-align: right; font-size: 12px; }
.num-input:disabled { background: #f5f5f5; }
.init-bar { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-top: 1px solid #f0f0f0; background: #fafafa; }
.enable-label { font-size: 13px; color: #555; display: flex; align-items: center; gap: 6px; }
.enable-label input { padding: 5px 8px; border: 1px solid #d9d9d9; border-radius: 6px; }
.done-banner { padding: 12px 16px; background: #f6ffed; color: #389e0d; font-size: 13px; border-bottom: 1px solid #d9f7be; }
.period-table td { padding: 8px 12px; }
.ptag { display: inline-block; padding: 1px 10px; border-radius: 10px; font-size: 12px; }
.ptag.ok { background: #e6f4ff; color: #1677ff; }
.ptag.done { background: #f6ffed; color: #389e0d; }
.ptag.muted { background: #f5f5f5; color: #999; }
.ptag.frozen { background: #fff1f0; color: #cf1322; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 10px; width: 560px; max-width: 94vw; max-height: 90vh; overflow: auto; }
.modal.w720 { width: 720px; }
.modal.w900 { width: 900px; }
.modal-h { padding: 14px 18px; font-weight: 700; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; position: sticky; top: 0; background: #fff; }
.modal-h .x { cursor: pointer; color: #999; }
.modal-b { padding: 16px 18px; }
.modal-f { padding: 12px 18px; border-top: 1px solid #f0f0f0; text-align: right; }
.modal-f .btn { margin-left: 8px; }
.aux-table input { width: 100%; padding: 4px 8px; border: 1px solid #d9d9d9; border-radius: 5px; font-size: 12px; box-sizing: border-box; }
.aux-table .num input { text-align: right; }
.aux-table tr.deleted { opacity: .4; }
.aux-table .danger { color: #cf1322; cursor: pointer; font-size: 12px; }
.trial-sum { padding: 12px 16px; border-radius: 8px; margin-bottom: 14px; font-size: 14px; display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.trial-sum.ok { background: #f6ffed; border: 1px solid #b7eb8f; color: #389e0d; }
.trial-sum.bad { background: #fff1f0; border: 1px solid #ffa39e; color: #cf1322; }
.trial-sum .verdict { margin-left: auto; font-weight: 700; font-size: 15px; }
.toast-inline { padding: 8px 12px; border-radius: 6px; margin-bottom: 10px; font-size: 13px; background: #f6ffed; border: 1px solid #b7eb8f; color: #389e0d; }
.toast-inline.err { background: #fff1f0; border-color: #ffa39e; color: #cf1322; }
</style>
