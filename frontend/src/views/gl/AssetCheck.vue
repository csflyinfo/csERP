<script setup>
/**
 * 总账 M8——固定资产盘点
 * 按期间对在用卡片逐张盘点（相符/盘亏/盘盈备注），保存盘点单。
 * 口径：盘亏/盘盈只留痕（不改卡片状态、不生凭证）；资产清理由会计在「资产卡片」页另行发起。
 */
import { ref, onMounted } from 'vue'
import { post } from '../../api/client.js'

const periods = ref([])
const period = ref('')
const cards = ref([])
const rows = ref([])
const history = ref([])
const detail = ref(null)
const remark = ref('')
const feedback = ref('')
const saving = ref(false)

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 8000)
}
function money(v) {
  if (v === null || v === undefined || v === '') return '—'
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

async function loadPeriods() {
  periods.value = await post('/finance/gl/period/list', {})
  const cur = periods.value.find(p => p.status === '进行中')
  period.value = cur ? cur.period : (periods.value[periods.value.length - 1]?.period || '')
}
async function loadCards() {
  const using = await post('/finance/gl/asset/cards', { status: '使用中' })
  const stopped = await post('/finance/gl/asset/cards', { status: '已停用' })
  cards.value = [...using, ...stopped]
  rows.value = cards.value.map(c => ({ cardId: c.cardId, checkResult: '相符', remark: '' }))
}
async function loadHistory() {
  history.value = await post('/finance/gl/asset/check-list', {})
}
function resultOf(cardId) {
  return rows.value.find(r => r.cardId === cardId) || { checkResult: '相符', remark: '' }
}
const lossCount = () => rows.value.filter(r => r.checkResult === '盘亏').length

async function save() {
  if (!confirm(`确认保存 ${period.value} 盘点单？盘亏 ${lossCount()} 项（只留痕，不自动清理）。`)) return
  saving.value = true
  try {
    const res = await post('/finance/gl/asset/check-save', {
      period: period.value,
      rows: rows.value.map(r => ({ cardId: r.cardId, checkResult: r.checkResult, remark: r.remark })),
      remark: remark.value,
    })
    show(`盘点单 ${res.checkNo} 已保存：共 ${res.totalCount} 项，盘亏 ${res.lossCount}，盘盈 ${res.profitCount}`)
    remark.value = ''
    await loadHistory()
  } catch (e) { show('保存失败：' + (e?.message || e), 'err') }
  finally { saving.value = false }
}
async function viewDetail(checkId) {
  try { detail.value = await post('/finance/gl/asset/check-detail', { checkId }) }
  catch (e) { show('查询失败：' + (e?.message || e), 'err') }
}

onMounted(async () => {
  try {
    await loadPeriods()
    await loadCards()
    await loadHistory()
  } catch (e) { show('初始化失败：' + (e?.message || e), 'err') }
})
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <span class="page-title">资产盘点</span>
      <label>会计期间
        <select v-model="period">
          <option v-for="p in periods" :key="p.period" :value="p.period">{{ p.period }}（{{ p.status }}）</option>
        </select>
      </label>
      <button class="btn" @click="loadCards(); loadHistory()">刷新</button>
      <button class="btn btn-primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存盘点单' }}</button>
    </div>
    <div v-if="feedback" :class="['feedback', feedback.level]">{{ feedback.msg }}</div>

    <div class="hint">
      盘点口径：逐项选择「相符 / 盘亏」并可填备注；<b>盘亏只登记留痕，不自动修改卡片状态、不生成凭证</b>——
      确认损失后请到「资产卡片」对盘亏资产发起清理（走 1606 / 5711 损益凭证）。
    </div>

    <table class="tbl">
      <thead>
        <tr>
          <th>卡片编号</th><th>资产名称</th><th>部门</th><th>购入日期</th>
          <th class="num">原值</th><th class="num">净值</th><th>账面状态</th>
          <th style="width:130px">盘点结果</th><th>备注</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="c in cards" :key="c.cardId">
          <td>{{ c.cardNo }}</td>
          <td>{{ c.assetName }}</td>
          <td>{{ c.departmentName || '—' }}</td>
          <td>{{ String(c.acquiredDate).slice(0, 10) }}</td>
          <td class="num">{{ money(c.originalValue) }}</td>
          <td class="num">{{ money(c.netValue) }}</td>
          <td>{{ c.status }}</td>
          <td>
            <select v-model="resultOf(c.cardId).checkResult">
              <option value="相符">相符</option>
              <option value="盘亏">盘亏</option>
            </select>
          </td>
          <td><input v-model="resultOf(c.cardId).remark" placeholder="盘亏原因等"></td>
        </tr>
        <tr v-if="!cards.length"><td colspan="9" class="empty">本期无在用/停用卡片</td></tr>
      </tbody>
    </table>

    <div class="page-title" style="margin-top:20px">历史盘点单</div>
    <table class="tbl">
      <thead>
        <tr><th>盘点单号</th><th>期间</th><th>部门</th><th class="num">盘点数</th>
          <th class="num">盘亏</th><th class="num">盘盈</th><th>状态</th><th>制单人</th><th></th></tr>
      </thead>
      <tbody>
        <tr v-for="h in history" :key="h.checkId">
          <td>{{ h.checkNo }}</td>
          <td>{{ h.period }}</td>
          <td>{{ h.departmentName || '全部' }}</td>
          <td class="num">{{ h.totalCount }}</td>
          <td class="num" :class="h.lossCount > 0 ? 'loss' : ''">{{ h.lossCount }}</td>
          <td class="num">{{ h.profitCount }}</td>
          <td>{{ h.status }}</td>
          <td>{{ h.makerName }}</td>
          <td><button class="btn-link" @click="viewDetail(h.checkId)">查看明细</button></td>
        </tr>
        <tr v-if="!history.length"><td colspan="9" class="empty">暂无盘点单</td></tr>
      </tbody>
    </table>

    <div v-if="detail" class="drawer-mask" @click.self="detail = null">
      <div class="drawer drawer-sm">
        <div class="drawer-title">盘点单 {{ detail.checkNo }}（{{ detail.period }}）</div>
        <table class="tbl">
          <thead><tr><th>卡片</th><th>资产名称</th><th>账面状态</th><th>结果</th><th>备注</th></tr></thead>
          <tbody>
            <tr v-for="(d, i) in detail.details" :key="i">
              <td>{{ d.cardNo }}</td>
              <td>{{ d.assetName }}</td>
              <td>{{ d.bookStatus }}</td>
              <td><span :class="['tag', d.checkResult === '盘亏' ? 'tag-red' : 'tag-green']">{{ d.checkResult }}</span></td>
              <td>{{ d.remark || '—' }}</td>
            </tr>
          </tbody>
        </table>
        <div class="drawer-ops"><button class="btn" @click="detail = null">关闭</button></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; background: #fff; margin-top: 10px; }
.tbl th, .tbl td { border: 1px solid #e4e7ed; padding: 6px 8px; text-align: left; }
.tbl th { background: #f5f7fa; font-weight: 600; }
.tbl input, .tbl select { width: 100%; padding: 4px 6px; border: 1px solid #d0d5dd; border-radius: 4px; }
.num { text-align: right; font-variant-numeric: tabular-nums; }
.loss { color: #c0392b; font-weight: 700; }
.empty { text-align: center; color: #909399; padding: 24px; }
.tag { padding: 1px 8px; border-radius: 10px; font-size: 12px; }
.tag-green { background: #e7f7ec; color: #1a7f37; }
.tag-red { background: #fdecea; color: #c0392b; }
.btn-link { border: none; background: none; color: #2563eb; cursor: pointer; padding: 0; font-size: 13px; }
.feedback { margin: 8px 0; padding: 8px 12px; border-radius: 4px; font-size: 13px; }
.feedback.ok { background: #e7f7ec; color: #1a7f37; }
.feedback.err { background: #fdecea; color: #c0392b; }
.hint { font-size: 12px; color: #909399; line-height: 1.7; margin-top: 10px; }
.drawer-mask { position: fixed; inset: 0; background: rgba(0,0,0,.35); z-index: 100; display: flex; justify-content: flex-end; }
.drawer { width: 640px; max-width: 92vw; background: #fff; height: 100%; overflow-y: auto; padding: 20px 24px; box-shadow: -4px 0 16px rgba(0,0,0,.12); }
.drawer-sm { width: 720px; }
.drawer-title { font-size: 16px; font-weight: 700; margin-bottom: 16px; }
.drawer-ops { margin: 20px 0 8px; display: flex; gap: 12px; justify-content: flex-end; }
</style>
