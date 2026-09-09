<script setup>
/**
 * 总账 M7——月度折旧计提向导
 * 预览（按卡片列本月折旧额、状态标签；工作量法填本期工作量）→ 执行：
 * 写折旧明细、更新卡片累计折旧/净值、按「费用科目+部门」汇总生成转字凭证草稿（ZJ{period} 幂等）。
 */
import { ref, onMounted } from 'vue'
import { post } from '../../api/client.js'

const periods = ref([])
const period = ref('')
const preview = ref(null)
const workloads = ref({})
const feedback = ref('')
const executing = ref(false)

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 8000)
}
function money(v) {
  if (v === null || v === undefined || v === '') return '—'
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function tagClass(tag) {
  if (tag === '正常' || tag === '提足到期') return 'tag-green'
  if (tag === '已提满' || tag === '当月新增不提') return 'tag-gray'
  return 'tag-orange'
}
function methodLabel(card) {
  if (card.depreciationMethod === '工作量') return `工作量法`
  return `${card.depreciationMethod} ${Math.round((card.lifeMonths || 0) / 12)}年`
}

async function loadPeriods() {
  periods.value = await post('/finance/gl/period/list', {})
  if (!period.value) {
    const cur = periods.value.find(p => p.status === '进行中')
    period.value = cur ? cur.period : (periods.value[periods.value.length - 1]?.period || '')
  }
}
async function loadPreview() {
  try {
    const wl = {}
    for (const [k, v] of Object.entries(workloads.value)) if (v !== '' && v !== null) wl[k] = Number(v)
    preview.value = await post('/finance/gl/asset/dep-preview', { period: period.value, workloads: wl })
  } catch (e) { preview.value = null; show('预览加载失败：' + (e?.message || e), 'err') }
}
async function execute() {
  if (!confirm(`确认为期间 ${period.value} 计提折旧？\n将生成折旧明细与「转」字凭证草稿（生成后不可重复计提）。`)) return
  executing.value = true
  try {
    const wl = {}
    for (const [k, v] of Object.entries(workloads.value)) if (v !== '' && v !== null) wl[k] = Number(v)
    const res = await post('/finance/gl/asset/dep-execute', { period: period.value, workloads: wl })
    show(`计提完成：${res.count} 张卡片，合计 ${money(res.total)} 元，凭证草稿已生成，请到凭证管理审核过账。`)
    workloads.value = {}
    await loadPreview()
  } catch (e) { show('计提失败：' + (e?.message || e), 'err') }
  finally { executing.value = false }
}

onMounted(async () => {
  try {
    await loadPeriods()
    await loadPreview()
  } catch (e) { show('初始化失败：' + (e?.message || e), 'err') }
})
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <span class="page-title">折旧计提</span>
      <label>会计期间
        <select v-model="period" @change="loadPreview">
          <option v-for="p in periods" :key="p.period" :value="p.period">{{ p.period }}（{{ p.status }}）</option>
        </select>
      </label>
      <button class="btn" @click="loadPreview">刷新预览</button>
      <button class="btn btn-primary" v-permission="'finance.gl.depreciation.audit'"
              :disabled="executing || !preview || preview.voucherExists"
              @click="execute">{{ executing ? '计提中…' : '执行本期折旧' }}</button>
    </div>
    <div v-if="feedback" :class="['feedback', feedback.level]">{{ feedback.msg }}</div>

    <div v-if="preview" class="banner">
      <template v-if="preview.voucherExists">
        <span class="tag tag-green">本期已计提</span>
        期间 {{ preview.period }} 的折旧凭证已生成（草稿/审核中），不能重复计提；如需重提请先作废原凭证。
      </template>
      <template v-else>
        <span class="tag tag-orange">待计提</span>
        本期应提折旧合计 <b>{{ money(preview.total) }}</b> 元（{{ preview.rows?.length || 0 }} 张使用中卡片）。
        工作量法卡片请先填写本期工作量再点「刷新预览」。
      </template>
    </div>

    <table class="tbl">
      <thead>
        <tr>
          <th>卡片编号</th><th>资产名称</th><th>部门</th><th>折旧方法</th><th>购入日期</th>
          <th class="num">原值</th><th class="num">已提折旧</th><th>状态</th>
          <th v-if="!preview?.voucherExists">本期工作量</th>
          <th class="num">本月折旧</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in preview?.rows || []" :key="r.cardId">
          <td>{{ r.cardNo }}</td>
          <td>{{ r.assetName }}</td>
          <td>{{ r.departmentName || '—' }}</td>
          <td>{{ methodLabel(r) }}</td>
          <td>{{ String(r.acquiredDate).slice(0, 10) }}</td>
          <td class="num">{{ money(r.originalValue) }}</td>
          <td class="num">{{ money(r.alreadyDepreciation || r.accumDepreciation) }}</td>
          <td><span :class="['tag', tagClass(r.tag)]">{{ r.tag }}</span></td>
          <td v-if="!preview?.voucherExists">
            <input v-if="r.depreciationMethod === '工作量'" class="wl-input" type="number" step="0.01" min="0"
                   :placeholder="r.workloadUnit || '工作量'"
                   v-model="workloads[r.cardNo]" @change="loadPreview">
            <span v-else class="muted">—</span>
          </td>
          <td class="num">{{ money(r.amount) }}</td>
        </tr>
        <tr v-if="!preview?.rows?.length"><td colspan="10" class="empty">暂无使用中的资产卡片</td></tr>
      </tbody>
      <tfoot v-if="preview?.rows?.length">
        <tr>
          <td colspan="9" class="num"><b>合计</b></td>
          <td class="num"><b>{{ money(preview.total) }}</b></td>
        </tr>
      </tfoot>
    </table>

    <div class="hint">
      规则：直线法 / 双倍余额递减法（末两年改直线）/ 年数总和法 / 工作量法；购入次月起提，末月提足至残值，提满自动停提。
      折旧凭证按「折旧费用科目 + 部门」汇总借方、贷 1602 累计折旧（挂部门辅助核算），凭证为草稿，审核过账后才入总账。
    </div>
  </div>
</template>

<style scoped>
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; background: #fff; margin-top: 10px; }
.tbl th, .tbl td { border: 1px solid #e4e7ed; padding: 6px 8px; text-align: left; }
.tbl th { background: #f5f7fa; font-weight: 600; }
.tfoot td { background: #fafafa; }
.num { text-align: right; font-variant-numeric: tabular-nums; }
.empty { text-align: center; color: #909399; padding: 24px; }
.muted { color: #bbb; }
.tag { padding: 1px 8px; border-radius: 10px; font-size: 12px; margin-right: 6px; }
.tag-green { background: #e7f7ec; color: #1a7f37; }
.tag-gray { background: #f0f0f0; color: #777; }
.tag-orange { background: #fff4e0; color: #b26a00; }
.banner { margin: 10px 0; padding: 10px 14px; background: #f8fafc; border: 1px solid #e4e7ed; border-radius: 4px; font-size: 13px; }
.feedback { margin: 8px 0; padding: 8px 12px; border-radius: 4px; font-size: 13px; }
.feedback.ok { background: #e7f7ec; color: #1a7f37; }
.feedback.err { background: #fdecea; color: #c0392b; }
.wl-input { width: 110px; padding: 4px 6px; border: 1px solid #d0d5dd; border-radius: 4px; }
.hint { font-size: 12px; color: #909399; line-height: 1.7; margin-top: 12px; }
</style>
