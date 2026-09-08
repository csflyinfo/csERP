<script setup>
/**
 * 总账 M7/M8——固定资产卡片（资产台账）
 * M7：列表 + 建卡抽屉（可选入账凭证草稿）+ 停用/启用。
 * M8：信息变更（原值禁改，留痕）、拆分（比例和=1、末张吃尾差、不生凭证）、
 *     合并（同类同部门同方法同费用科目、不生凭证）、清理（1606 过渡、损益 5301/5711，
 *     生成 QL 转字凭证草稿）；资产台账滚动表（期末=期初+增-减，附 GL 1601/1602 对照）。
 */
import { ref, computed, onMounted } from 'vue'
import { post } from '../../api/client.js'

const cards = ref([])
const categories = ref([])
const departments = ref([])
const leafAccounts = ref([])
const periods = ref([])
const period = ref('')
const statusFilter = ref('使用中')
const feedback = ref('')
const showForm = ref(false)
const saving = ref(false)
const busy = ref(false)
const dialog = ref(null)   // change|split|merge|dispose
const current = ref(null)  // current card for dialogs

// 台账滚动表
const ledger = ref(null)
// 拆分/合并/清理/变更表单
const changeForm = ref({})
const splitParts = ref([{ assetName: '', ratio: 0.6 }, { assetName: '', ratio: 0.4 }])
const mergeForm = ref({ cardIds: [], assetName: '' })
const disposeForm = ref({ incomeAmount: 0, expenseAmount: 0, cashAccount: '', remark: '' })
const disposePreview = ref(null)

const emptyForm = () => ({
  assetName: '', categoryCode: '04', departmentCode: '', departmentName: '',
  acquiredDate: new Date().toISOString().slice(0, 10),
  originalValue: '', salvageRate: '', depreciationMethod: '',
  lifeYears: 3, lifeMonths: '', totalWorkload: '', workloadUnit: '工时',
  expenseAccount: '', createVoucher: false, creditAccount: '', remark: ''
})
const form = ref(emptyForm())

const catMap = computed(() => Object.fromEntries(categories.value.map(c => [c.categoryCode, c])))
const deptMap = computed(() => Object.fromEntries(departments.value.map(d => [d.code, d])))
const activeCards = computed(() => cards.value.filter(c => c.status === '使用中'))
const cashAccounts = computed(() => leafAccounts.value.filter(a => a.isCash))

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 8000)
}
function money(v) {
  if (v === null || v === undefined || v === '') return '—'
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function methodLabel(card) {
  if (card.depreciationMethod === '工作量') return `工作量法（${card.totalWorkload}${card.workloadUnit || ''}）`
  return `${card.depreciationMethod}（${Math.round((card.lifeMonths || 0) / 12)}年）`
}
function statusClass(s) {
  return s === '使用中' ? 'tag-green' : s === '已停用' ? 'tag-gray' : 'tag-red'
}

async function load() {
  try {
    cards.value = await post('/finance/gl/asset/cards', { status: statusFilter.value || '' })
  } catch (e) { show('卡片加载失败：' + (e?.message || e), 'err') }
}
async function loadLedger() {
  if (!period.value) return
  try {
    ledger.value = await post('/finance/gl/asset/ledger', { period: period.value })
  } catch (e) { show('台账滚动表加载失败：' + (e?.message || e), 'err') }
}
async function loadPeriods() {
  periods.value = await post('/finance/gl/period/list', {})
  const cur = periods.value.find(p => p.status === '进行中')
  period.value = cur ? cur.period : (periods.value[periods.value.length - 1]?.period || '')
}

function onCategoryChange() {
  const c = catMap.value[form.value.categoryCode]
  if (!c) return
  form.value.depreciationMethod = c.defaultMethod
  form.value.lifeMonths = c.defaultLifeMonths
  form.value.lifeYears = Math.round(c.defaultLifeMonths / 12)
  form.value.expenseAccount = c.expenseAccount
  if (form.value.salvageRate === '') form.value.salvageRate = c.salvageRate
}
function openForm() {
  form.value = emptyForm()
  onCategoryChange()
  showForm.value = true
}
async function save() {
  saving.value = true
  try {
    const f = form.value
    const payload = {
      assetName: f.assetName, categoryCode: f.categoryCode,
      departmentCode: f.departmentCode,
      departmentName: f.departmentName || deptMap.value[f.departmentCode]?.name || '',
      acquiredDate: f.acquiredDate, originalValue: f.originalValue,
      salvageRate: f.salvageRate === '' ? null : f.salvageRate,
      depreciationMethod: f.depreciationMethod,
      expenseAccount: f.expenseAccount, remark: f.remark,
      createVoucher: f.createVoucher, creditAccount: f.createVoucher ? f.creditAccount : ''
    }
    if (f.depreciationMethod === '工作量') {
      payload.totalWorkload = f.totalWorkload
      payload.workloadUnit = f.workloadUnit
    } else {
      payload.lifeMonths = Number(f.lifeYears) * 12
    }
    const res = await post('/finance/gl/asset/card-save', payload)
    show(`建卡成功：${res.cardNo}` + (res.voucherId ? '，入账凭证草稿已生成（请到凭证管理审核过账）' : ''))
    showForm.value = false
    await load()
  } catch (e) { show('建卡失败：' + (e?.message || e), 'err') }
  finally { saving.value = false }
}

async function toggle(card) {
  const next = card.status === '使用中' ? '已停用' : '使用中'
  if (!confirm(`确定将卡片 ${card.cardNo}「${card.assetName}」置为「${next}」？\n停用后不再计提折旧。`)) return
  try {
    await post('/finance/gl/asset/card-toggle', { cardId: card.cardId, status: next })
    await load()
  } catch (e) { show('操作失败：' + (e?.message || e), 'err') }
}

// ---------- 变更 ----------
function openChange(card) {
  current.value = card
  changeForm.value = {
    departmentCode: card.departmentCode || '',
    expenseAccount: card.expenseAccount || '',
    depreciationMethod: card.depreciationMethod || '直线',
    lifeYears: Math.round((card.lifeMonths || 36) / 12),
    totalWorkload: card.totalWorkload || '',
    workloadUnit: card.workloadUnit || '',
    reason: '',
  }
  dialog.value = 'change'
}
async function submitChange() {
  busy.value = true
  try {
    const f = changeForm.value
    const body = { cardId: current.value.cardId, reason: f.reason || '信息变更' }
    if (f.departmentCode !== (current.value.departmentCode || '')) {
      body.departmentCode = f.departmentCode
      body.departmentName = deptMap.value[f.departmentCode]?.name || ''
    }
    if (f.expenseAccount !== current.value.expenseAccount) body.expenseAccount = f.expenseAccount
    if (f.depreciationMethod !== current.value.depreciationMethod
        || Number(f.lifeYears) * 12 !== current.value.lifeMonths
        || String(f.totalWorkload) !== String(current.value.totalWorkload || '')) {
      body.depreciationMethod = f.depreciationMethod
      if (f.depreciationMethod === '工作量') {
        body.totalWorkload = f.totalWorkload
        body.workloadUnit = f.workloadUnit
      } else {
        body.lifeMonths = Number(f.lifeYears) * 12
      }
    }
    const res = await post('/finance/gl/asset/change', body)
    show(`变更已保存（${res.changeNo}），原值/残值不可变更`)
    dialog.value = null
    await load()
  } catch (e) { show('变更失败：' + (e?.message || e), 'err') }
  finally { busy.value = false }
}

// ---------- 拆分 ----------
function openSplit(card) {
  current.value = card
  splitParts.value = [{ assetName: '', ratio: 0.6 }, { assetName: '', ratio: 0.4 }]
  dialog.value = 'split'
}
const splitSum = () => splitParts.value.reduce((s, p) => s + Number(p.ratio || 0), 0)
async function submitSplit() {
  busy.value = true
  try {
    const res = await post('/finance/gl/asset/split', {
      cardId: current.value.cardId,
      parts: splitParts.value.map(p => ({ assetName: p.assetName, ratio: Number(p.ratio) })),
    })
    show(`拆分完成：${res.newCards.map(c => c.cardNo).join('、')}（末张吃尾差），原卡置「已拆分」`)
    dialog.value = null
    await load()
  } catch (e) { show('拆分失败：' + (e?.message || e), 'err') }
  finally { busy.value = false }
}

// ---------- 合并 ----------
function openMerge() {
  mergeForm.value = { cardIds: [], assetName: '' }
  dialog.value = 'merge'
}
async function submitMerge() {
  busy.value = true
  try {
    const res = await post('/finance/gl/asset/merge', {
      cardIds: mergeForm.value.cardIds,
      assetName: mergeForm.value.assetName,
    })
    show(`合并完成：新卡 ${res.cardNo}（原值 ${money(res.originalValue)}），原卡置「已合并」`)
    dialog.value = null
    await load()
  } catch (e) { show('合并失败：' + (e?.message || e), 'err') }
  finally { busy.value = false }
}

// ---------- 清理 ----------
function openDispose(card) {
  current.value = card
  disposeForm.value = { incomeAmount: 0, expenseAmount: 0, cashAccount: '', remark: '' }
  disposePreview.value = null
  dialog.value = 'dispose'
}
async function previewDispose() {
  try {
    disposePreview.value = await post('/finance/gl/asset/disposal-preview', {
      cardId: current.value.cardId,
      incomeAmount: Number(disposeForm.value.incomeAmount || 0),
      expenseAmount: Number(disposeForm.value.expenseAmount || 0),
      cashAccount: disposeForm.value.cashAccount,
    })
  } catch (e) { disposePreview.value = null; show('预览失败：' + (e?.message || e), 'err') }
}
async function submitDispose() {
  busy.value = true
  try {
    const res = await post('/finance/gl/asset/disposal-execute', {
      cardId: current.value.cardId,
      incomeAmount: Number(disposeForm.value.incomeAmount || 0),
      expenseAmount: Number(disposeForm.value.expenseAmount || 0),
      cashAccount: disposeForm.value.cashAccount,
      remark: disposeForm.value.remark,
    })
    show(`清理完成（${res.disposalNo}）：${res.resultType} ${money(res.gainLoss)}，凭证草稿已生成，请到凭证管理审核过账`)
    dialog.value = null
    await load()
    await loadLedger()
  } catch (e) { show('清理失败：' + (e?.message || e), 'err') }
  finally { busy.value = false }
}

function closeDialog() { dialog.value = null }

onMounted(async () => {
  try {
    categories.value = await post('/finance/gl/asset/categories', {})
    const aux = await post('/finance/gl/voucher/aux-options')
    departments.value = aux.departments || []
    leafAccounts.value = (await post('/finance/gl/account/leaf-options', {})) || []
    await loadPeriods()
  } catch (e) { show('基础资料加载失败：' + (e?.message || e), 'err') }
  await load()
  await loadLedger()
})
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <span class="page-title">资产卡片</span>
      <label>状态
        <select v-model="statusFilter" @change="load">
          <option value="">全部</option>
          <option value="使用中">使用中</option>
          <option value="已停用">已停用</option>
          <option value="已清理">已清理</option>
          <option value="已拆分">已拆分</option>
          <option value="已合并">已合并</option>
        </select>
      </label>
      <label>期间
        <select v-model="period" @change="loadLedger">
          <option v-for="p in periods" :key="p.period" :value="p.period">{{ p.period }}（{{ p.status }}）</option>
        </select>
      </label>
      <button class="btn" @click="load(); loadLedger()">刷新</button>
      <button class="btn" @click="openMerge">合并卡片</button>
      <button class="btn btn-primary" @click="openForm">＋ 新建卡片</button>
    </div>
    <div v-if="feedback" :class="['feedback', feedback.level]">{{ feedback.msg }}</div>

    <!-- 台账滚动表 -->
    <div v-if="ledger" class="ledger-box">
      <div class="ledger-title">资产台账滚动表（{{ ledger.period }}）
        <span :class="['tag', ledger.origBalanced && ledger.accumBalanced ? 'tag-green' : 'tag-red']">
          {{ ledger.origBalanced && ledger.accumBalanced ? '✓ 期末=期初+增−减' : '✕ 滚动不平' }}
        </span>
        <span class="hint-inline">在用卡片 {{ ledger.activeCount }} 张，本期清理 {{ ledger.disposedCount }} 张；GL 列为总账 1601/1602 口径（账外卡片不进 GL，差异属预期）</span>
      </div>
      <table class="tbl">
        <thead>
          <tr>
            <th>项目</th><th class="num">台账期初</th><th class="num">本期增加</th><th class="num">本期减少</th>
            <th class="num">台账期末</th><th class="num">GL期初</th><th class="num">GL增(借/贷)</th>
            <th class="num">GL减(贷/借)</th><th class="num">GL期末</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in ledger.rows" :key="r.item">
            <td>{{ r.item }}</td>
            <td class="num">{{ money(r.beginAmount) }}</td>
            <td class="num">{{ money(r.addAmount) }}</td>
            <td class="num">{{ money(r.reduceAmount) }}</td>
            <td class="num"><b>{{ money(r.endAmount) }}</b></td>
            <td class="num gl-col">{{ money(r.glBeginAmount) }}</td>
            <td class="num gl-col">{{ money(r.glAddAmount) }}</td>
            <td class="num gl-col">{{ money(r.glReduceAmount) }}</td>
            <td class="num gl-col">{{ money(r.glEndAmount) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <table class="tbl">
      <thead>
        <tr>
          <th>卡片编号</th><th>资产名称</th><th>类别</th><th>部门</th><th>购入日期</th>
          <th class="num">原值</th><th class="num">残值</th><th>折旧方法</th>
          <th class="num">累计折旧</th><th class="num">净值</th><th>最后折旧期</th><th>状态</th><th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="c in cards" :key="c.cardId">
          <td>{{ c.cardNo }}</td>
          <td>{{ c.assetName }}</td>
          <td>{{ c.categoryName }}</td>
          <td>{{ c.departmentName || '—' }}</td>
          <td>{{ String(c.acquiredDate).slice(0, 10) }}</td>
          <td class="num">{{ money(c.originalValue) }}</td>
          <td class="num">{{ money(c.salvageValue) }}</td>
          <td>{{ methodLabel(c) }}</td>
          <td class="num">{{ money(c.accumDepreciation) }}</td>
          <td class="num">{{ money(c.netValue) }}</td>
          <td>{{ c.lastDepPeriod || '—' }}</td>
          <td><span :class="['tag', statusClass(c.status)]">{{ c.status }}</span></td>
          <td class="ops">
            <template v-if="c.status === '使用中' || c.status === '已停用'">
              <button class="btn-link" @click="toggle(c)">{{ c.status === '使用中' ? '停用' : '启用' }}</button>
              <button v-if="c.status === '使用中'" class="btn-link" @click="openChange(c)">变更</button>
              <button v-if="c.status === '使用中'" class="btn-link" @click="openSplit(c)">拆分</button>
              <button class="btn-link" @click="openDispose(c)">清理</button>
            </template>
          </td>
        </tr>
        <tr v-if="!cards.length"><td colspan="13" class="empty">暂无资产卡片，点击「新建卡片」建档</td></tr>
      </tbody>
    </table>

    <!-- 建卡抽屉 -->
    <div v-if="showForm" class="drawer-mask" @click.self="showForm = false">
      <div class="drawer">
        <div class="drawer-title">新建资产卡片</div>
        <div class="form-grid">
          <label class="span2">资产名称 *
            <input v-model="form.assetName" placeholder="如：联想 ThinkPad X1 笔记本">
          </label>
          <label>资产类别 *
            <select v-model="form.categoryCode" @change="onCategoryChange">
              <option v-for="c in categories" :key="c.categoryCode" :value="c.categoryCode">
                {{ c.categoryName }}（{{ c.defaultMethod }}）
              </option>
            </select>
          </label>
          <label>使用部门
            <select v-model="form.departmentCode">
              <option value="">（不挂部门）</option>
              <option v-for="d in departments" :key="d.code" :value="d.code">{{ d.name }}</option>
            </select>
          </label>
          <label>购入日期 *
            <input type="date" v-model="form.acquiredDate">
          </label>
          <label>原值（元）*
            <input type="number" step="0.01" min="0" v-model="form.originalValue">
          </label>
          <label>残值率
            <input type="number" step="0.01" min="0" max="0.99" v-model="form.salvageRate"
                   :placeholder="catMap[form.categoryCode]?.salvageRate">
          </label>
          <label>折旧方法 *
            <select v-model="form.depreciationMethod">
              <option value="直线">直线法</option>
              <option value="双倍余额">双倍余额递减法（末两年改直线）</option>
              <option value="年数总和">年数总和法</option>
              <option value="工作量">工作量法</option>
            </select>
          </label>
          <label v-if="form.depreciationMethod !== '工作量'">折旧年限
            <select v-model="form.lifeYears">
              <option v-for="y in [1,2,3,4,5,10,20]" :key="y" :value="y">{{ y }} 年</option>
            </select>
          </label>
          <template v-else>
            <label>总工作量 *
              <input type="number" step="0.01" min="0" v-model="form.totalWorkload">
            </label>
            <label>工作量单位
              <input v-model="form.workloadUnit" placeholder="工时 / 公里">
            </label>
          </template>
          <label class="span2">折旧费用科目 *
            <select v-model="form.expenseAccount">
              <option v-for="a in leafAccounts.filter(x => x.balanceDirection === '借')"
                      :key="a.accountCode" :value="a.accountCode">
                {{ a.accountCode }} {{ a.accountName }}
              </option>
            </select>
          </label>
          <label class="span2 check">
            <input type="checkbox" v-model="form.createVoucher">
            同时生成入账凭证草稿（借 1601 固定资产 / 贷对方科目）
          </label>
          <label v-if="form.createVoucher" class="span2">对方科目（贷方，须无辅助核算）*
            <select v-model="form.creditAccount">
              <option value="">请选择</option>
              <option v-for="a in leafAccounts" :key="a.accountCode" :value="a.accountCode">
                {{ a.accountCode }} {{ a.accountName }}
              </option>
            </select>
          </label>
          <label class="span2">备注
            <input v-model="form.remark">
          </label>
        </div>
        <div class="drawer-ops">
          <button class="btn" @click="showForm = false">取消</button>
          <button class="btn btn-primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存建卡' }}</button>
        </div>
        <div class="hint">规则：当月购入次月起提折旧；末月提足至残值；提满自动停提。原值一经建卡不可变更（变更仅支持部门/费用科目/折旧参数）。</div>
      </div>
    </div>

    <!-- 变更对话框 -->
    <div v-if="dialog === 'change'" class="drawer-mask" @click.self="closeDialog">
      <div class="drawer drawer-sm">
        <div class="drawer-title">资产变更：{{ current.cardNo }} {{ current.assetName }}</div>
        <div class="form-grid">
          <label>使用部门
            <select v-model="changeForm.departmentCode">
              <option value="">（不挂部门）</option>
              <option v-for="d in departments" :key="d.code" :value="d.code">{{ d.name }}</option>
            </select>
          </label>
          <label>折旧费用科目
            <select v-model="changeForm.expenseAccount">
              <option v-for="a in leafAccounts.filter(x => x.balanceDirection === '借')"
                      :key="a.accountCode" :value="a.accountCode">
                {{ a.accountCode }} {{ a.accountName }}
              </option>
            </select>
          </label>
          <label>折旧方法
            <select v-model="changeForm.depreciationMethod">
              <option value="直线">直线法</option>
              <option value="双倍余额">双倍余额递减法</option>
              <option value="年数总和">年数总和法</option>
              <option value="工作量">工作量法</option>
            </select>
          </label>
          <label v-if="changeForm.depreciationMethod !== '工作量'">折旧年限
            <select v-model="changeForm.lifeYears">
              <option v-for="y in [1,2,3,4,5,10,20]" :key="y" :value="y">{{ y }} 年</option>
            </select>
          </label>
          <template v-else>
            <label>总工作量
              <input type="number" step="0.01" v-model="changeForm.totalWorkload">
            </label>
            <label>工作量单位
              <input v-model="changeForm.workloadUnit">
            </label>
          </template>
          <label class="span2">变更原因
            <input v-model="changeForm.reason" placeholder="如：部门调整 / 会计估计变更">
          </label>
        </div>
        <div class="hint">原值、残值不允许变更；折旧参数变更采用未来适用法（已提折旧不追溯）；变更全程留痕。</div>
        <div class="drawer-ops">
          <button class="btn" @click="closeDialog">取消</button>
          <button class="btn btn-primary" :disabled="busy" @click="submitChange">{{ busy ? '保存中…' : '保存变更' }}</button>
        </div>
      </div>
    </div>

    <!-- 拆分对话框 -->
    <div v-if="dialog === 'split'" class="drawer-mask" @click.self="closeDialog">
      <div class="drawer drawer-sm">
        <div class="drawer-title">拆分卡片：{{ current.cardNo }} {{ current.assetName }}</div>
        <div class="hint">原值/累计折旧/残值/工作量按比例拆分，比例之和必须 = 1，最后一张自动吃尾差；不生成凭证，原卡置「已拆分」。</div>
        <table class="tbl">
          <thead><tr><th>新资产名称</th><th style="width:140px">比例</th><th></th></tr></thead>
          <tbody>
            <tr v-for="(p, i) in splitParts" :key="i">
              <td><input v-model="p.assetName" placeholder="拆分后资产名称"></td>
              <td><input type="number" step="0.01" min="0" max="1" v-model="p.ratio"></td>
              <td><button v-if="splitParts.length > 2" class="btn-link" @click="splitParts.splice(i, 1)">删除</button></td>
            </tr>
          </tbody>
        </table>
        <button class="btn" @click="splitParts.push({ assetName: '', ratio: '' })">＋ 增加一行</button>
        <div :class="['feedback', Math.abs(splitSum() - 1) < 0.0001 ? 'ok' : 'err']">
          比例合计：{{ splitSum().toFixed(4) }}（须等于 1）
        </div>
        <div class="drawer-ops">
          <button class="btn" @click="closeDialog">取消</button>
          <button class="btn btn-primary" :disabled="busy" @click="submitSplit">{{ busy ? '处理中…' : '确认拆分' }}</button>
        </div>
      </div>
    </div>

    <!-- 合并对话框 -->
    <div v-if="dialog === 'merge'" class="drawer-mask" @click.self="closeDialog">
      <div class="drawer drawer-sm">
        <div class="drawer-title">合并卡片</div>
        <div class="hint">仅限同类别、同部门、同折旧方法、同费用科目的使用中卡片；原值/累计折旧/残值直接相加，不生成凭证，原卡置「已合并」。</div>
        <label class="span2">合并后资产名称 *
          <input v-model="mergeForm.assetName" placeholder="如：三号车间合并生产线">
        </label>
        <table class="tbl">
          <thead><tr><th style="width:40px"></th><th>卡片</th><th>类别</th><th>部门</th><th>方法</th><th class="num">原值</th><th class="num">累计折旧</th></tr></thead>
          <tbody>
            <tr v-for="c in activeCards" :key="c.cardId">
              <td><input type="checkbox" :value="c.cardId" v-model="mergeForm.cardIds"></td>
              <td>{{ c.cardNo }} {{ c.assetName }}</td>
              <td>{{ c.categoryName }}</td>
              <td>{{ c.departmentName || '—' }}</td>
              <td>{{ methodLabel(c) }}</td>
              <td class="num">{{ money(c.originalValue) }}</td>
              <td class="num">{{ money(c.accumDepreciation) }}</td>
            </tr>
          </tbody>
        </table>
        <div class="drawer-ops">
          <button class="btn" @click="closeDialog">取消</button>
          <button class="btn btn-primary" :disabled="busy" @click="submitMerge">{{ busy ? '处理中…' : '确认合并' }}</button>
        </div>
      </div>
    </div>

    <!-- 清理对话框 -->
    <div v-if="dialog === 'dispose'" class="drawer-mask" @click.self="closeDialog">
      <div class="drawer drawer-sm">
        <div class="drawer-title">资产清理：{{ current.cardNo }} {{ current.assetName }}</div>
        <div class="form-grid">
          <label>清理收入（元）
            <input type="number" step="0.01" min="0" v-model="disposeForm.incomeAmount" @change="disposePreview = null">
          </label>
          <label>清理费用（元）
            <input type="number" step="0.01" min="0" v-model="disposeForm.expenseAmount" @change="disposePreview = null">
          </label>
          <label class="span2">收支对方科目（现金类，有收支时必填）
            <select v-model="disposeForm.cashAccount" @change="disposePreview = null">
              <option value="">（无收支，不选）</option>
              <option v-for="a in cashAccounts" :key="a.accountCode" :value="a.accountCode">
                {{ a.accountCode }} {{ a.accountName }}
              </option>
            </select>
          </label>
          <label class="span2">备注
            <input v-model="disposeForm.remark" placeholder="如：报废处置 / 出售">
          </label>
        </div>
        <button class="btn" @click="previewDispose">预览损益</button>
        <div v-if="disposePreview" class="preview-box">
          <div>原值 {{ money(disposePreview.originalValue) }} − 累计折旧 {{ money(disposePreview.accumDepreciation) }}
            = 净值 <b>{{ money(disposePreview.netValue) }}</b></div>
          <div>损益 = 收入 {{ money(disposePreview.incomeAmount) }} − 费用 {{ money(disposePreview.expenseAmount) }}
            − 净值 {{ money(disposePreview.netValue) }} =
            <span :class="['tag', disposePreview.resultType === '损失' ? 'tag-red' : disposePreview.resultType === '收益' ? 'tag-green' : 'tag-gray']">
              {{ disposePreview.resultType }} {{ money(disposePreview.gainLoss) }}
            </span>
          </div>
          <div v-if="disposePreview.depWarn" class="feedback err" style="margin:6px 0">
            该卡片本期尚未计提折旧（当月减少当月照提），建议先到「折旧计提」处理本期折旧再清理。
          </div>
          <div class="hint">凭证：借 1602 累计折旧 / 借 1606 净值 贷 1601 原值；收支过 1606；净损益结 5301 营业外收入或 5711 营业外支出。</div>
        </div>
        <div class="drawer-ops">
          <button class="btn" @click="closeDialog">取消</button>
          <button class="btn btn-primary" :disabled="busy" @click="submitDispose">{{ busy ? '处理中…' : '确认清理并生成凭证' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; background: #fff; margin-bottom: 14px; }
.tbl th, .tbl td { border: 1px solid #e4e7ed; padding: 6px 8px; text-align: left; }
.tbl th { background: #f5f7fa; font-weight: 600; }
.tbl input { width: 100%; padding: 4px 6px; border: 1px solid #d0d5dd; border-radius: 4px; }
.num { text-align: right; font-variant-numeric: tabular-nums; }
.gl-col { color: #6b7280; }
.empty { text-align: center; color: #909399; padding: 24px; }
.ops .btn-link + .btn-link { margin-left: 8px; }
.tag { padding: 1px 8px; border-radius: 10px; font-size: 12px; white-space: nowrap; }
.tag-green { background: #e7f7ec; color: #1a7f37; }
.tag-red { background: #fdecea; color: #c0392b; }
.tag-gray { background: #f0f0f0; color: #777; }
.btn-link { border: none; background: none; color: #2563eb; cursor: pointer; padding: 0; font-size: 13px; }
.feedback { margin: 8px 0; padding: 8px 12px; border-radius: 4px; font-size: 13px; }
.feedback.ok { background: #e7f7ec; color: #1a7f37; }
.feedback.err { background: #fdecea; color: #c0392b; }
.ledger-box { background: #fafcff; border: 1px solid #e0e7f0; border-radius: 6px; padding: 10px 12px; margin: 10px 0; }
.ledger-title { font-weight: 700; font-size: 14px; margin-bottom: 8px; }
.hint-inline { font-weight: 400; font-size: 12px; color: #909399; margin-left: 8px; }
.drawer-mask { position: fixed; inset: 0; background: rgba(0,0,0,.35); z-index: 100; display: flex; justify-content: flex-end; }
.drawer { width: 640px; max-width: 92vw; background: #fff; height: 100%; overflow-y: auto; padding: 20px 24px; box-shadow: -4px 0 16px rgba(0,0,0,.12); }
.drawer-sm { width: 720px; }
.drawer-title { font-size: 16px; font-weight: 700; margin-bottom: 16px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; }
.form-grid label { display: flex; flex-direction: column; font-size: 13px; color: #444; gap: 4px; }
.form-grid .span2 { grid-column: span 2; }
.form-grid .span2 input { padding: 6px 8px; border: 1px solid #d0d5dd; border-radius: 4px; font-size: 13px; }
.form-grid input, .form-grid select { padding: 6px 8px; border: 1px solid #d0d5dd; border-radius: 4px; font-size: 13px; }
.form-grid .check { flex-direction: row; align-items: center; gap: 8px; }
.drawer-ops { margin: 20px 0 8px; display: flex; gap: 12px; justify-content: flex-end; }
.hint { font-size: 12px; color: #909399; line-height: 1.6; margin: 8px 0; }
.preview-box { background: #f8fafc; border: 1px dashed #cbd5e1; border-radius: 4px; padding: 10px 12px; font-size: 13px; line-height: 2; margin: 10px 0; }
</style>
