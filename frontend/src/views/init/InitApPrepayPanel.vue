<script setup>
/**
 * 已上线供应商期初预付补录（PRD-36 M4，设计 §7.2/AC-24）。
 *
 * 与应付初始化的差异：不设建账锁定标志、可多批过账；守卫是当日未封账 + 总账当月未月结；
 * 每批只生 QCYF 预付期初流水（不动资金、不造单），未被核销/退款/费用划转占用时可整批反建账。
 * 暂存行维护复用 OpeningInitPanel（line_kind=PREPAY_SUPPLEMENT 由后端隔离）。
 */
import { ref, watch } from 'vue'
import { post } from '../../api/client.js'
import OpeningInitPanel from './OpeningInitPanel.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 供应商下拉选项（由父页面统一加载传入）：[{value,label}] */
  supplierOptions: { type: Array, default: () => [] },
})
const emit = defineEmits(['close'])

const perm = { view: 'fin.init_ap.view', edit: 'fin.init_ap.edit', delete: 'fin.init_ap.delete', import: 'fin.init_ap.import' }

const status = ref({})
const panelRef = ref(null)
const feedback = ref('')
const acting = ref(false)
const reverseOpen = ref(false)
const reversePostNo = ref('')
const reverseReason = ref('')

const columns = [
  { f: 'supplierCode', t: '供应商编码', w: '140px' },
  { f: 'supplierName', t: '供应商名称' },
  { f: 'prepayAmount', t: '期初预付金额', num: true, w: '140px' },
  { f: 'remark', t: '备注' },
  { f: 'generatedPrepayFlowId', t: '预付流水号', w: '170px' },
]

const formDef = [
  { f: 'supplierCode', l: '供应商编码', required: true, type: 'select', options: props.supplierOptions, placeholder: '选择供应商' },
  { f: 'supplierName', l: '供应商名称（留空取档案）', type: 'text', placeholder: '可不填，过账时按编码回写' },
  { f: 'prepayAmount', l: '期初预付金额', required: true, type: 'number', step: '0.01',
    hint: '必须 >0；只登预付账户余额，不动资金、不造应付/付款单' },
  { f: 'remark', l: '备注', type: 'textarea' },
]

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 4000)
}

async function loadStatus() {
  try {
    status.value = await post('/init/ap/prepay-supplement/status')
  } catch (e) {
    show('状态加载失败：' + (e?.message || e), 'err')
  }
}

watch(() => props.visible, v => {
  if (v) {
    feedback.value = ''
    loadStatus()
  }
}, { immediate: true })

async function doPost() {
  if (!confirm(`确认将暂存的预付补录行过账？\n过账日=今天（${status.value.today}），将生成 QCYF 开头的预付期初流水（可多批），不产生资金变动与单据。`)) return
  acting.value = true
  try {
    const r = await post('/init/ap/prepay-supplement/post')
    show(`补录过账成功，批号 ${r.postNo}，共 ${r.lineCount} 行，合计 ￥${fmt(r.totalAmount)}`)
    await loadStatus()
    panelRef.value?.load()
  } catch (e) {
    show('过账失败：' + (e?.message || e), 'err')
  } finally {
    acting.value = false
  }
}

function openReverse(batch) {
  reversePostNo.value = batch?.postNo || (status.value.batches || []).find(b => b.status === 'POSTED')?.postNo || ''
  reverseReason.value = ''
  reverseOpen.value = true
}

async function doReverse() {
  if (!reversePostNo.value) { show('没有可反建账的批号', 'err'); return }
  if (!reverseReason.value.trim()) { show('请填写反建账原因', 'err'); return }
  acting.value = true
  try {
    await post('/init/ap/prepay-supplement/reverse', { postNo: reversePostNo.value, reason: reverseReason.value.trim() })
    reverseOpen.value = false
    show(`批号 ${reversePostNo.value} 已整批反建账，预付流水已删除`)
    await loadStatus()
    panelRef.value?.load()
  } catch (e) {
    show('反建账失败：' + (e?.message || e), 'err')
  } finally {
    acting.value = false
  }
}

function fmt(v) {
  return (Number(v || 0)).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function fmtDateTime(v) {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(0, 16)
}
</script>

<template>
  <div v-if="visible" class="modal-mask" @click.self="emit('close')">
    <div class="modal w960">
      <div class="modal-h">
        供应商期初预付补录（上线后通道）
        <span class="x" @click="emit('close')">×</span>
      </div>
      <div class="modal-b">
        <div v-if="feedback" class="toast-inline" :class="feedback.level">{{ feedback.msg }}</div>

        <div v-if="status.todayClosed" class="banner warn">
          🔒 今日（{{ status.today }}）已做业务日结封账，不能过账或反建账；请改天或先反封账。
        </div>
        <div v-else-if="status.glBlockReason" class="banner warn">🔒 {{ status.glBlockReason }}。</div>
        <div v-else class="banner info">
          补录规则：过账日=审核当日；只登记供应商账户预付余额（QCYF 流水），不动资金、不造单据；
          可分多批过账，未被核销/退款/费用划转占用的批次可整批反建账。
        </div>

        <!-- 暂存合计 + 操作 -->
        <div class="summary-bar">
          <span>有效行 <b>{{ status.lineCount || 0 }}</b></span>
          <span class="sep">|</span>
          <span>错误行 <b :class="{ red: status.errorCount > 0 }">{{ status.errorCount || 0 }}</b></span>
          <span class="sep">|</span>
          <span>预付合计 <b class="money">￥{{ fmt(status.totalPrepayAmount) }}</b></span>
          <div class="spacer"></div>
          <button class="btn sm" @click="loadStatus">刷新状态</button>
          <button class="btn sm primary" v-permission="'fin.init_ap.post'"
                  :disabled="!status.canPost || acting" @click="doPost">补录过账</button>
        </div>

        <OpeningInitPanel
          ref="panelRef"
          api-base="/init/ap/prepay-supplement"
          :perm="perm"
          preset-key="initApPrepay"
          entity-label="预付补录"
          :columns="columns"
          :form-def="formDef"
          :locked="false"
          @changed="loadStatus"
        />

        <!-- 负应付提示（2202 借方已是预付性质，不要重复补录） -->
        <div v-if="(status.negativeAp || []).length" class="banner warn neg-tip">
          ⚠️ 以下供应商应付单汇总为<b>借方（负应付）</b>，资产负债表已重分类为预付，
          如该余额就是漏录的预付，<b>不要再在此补录</b>，避免两边重复挂账：
          <span v-for="s in status.negativeAp" :key="s.supplier" class="neg-item">
            {{ s.supplier }}（￥{{ fmt(s.unpaidAmount) }}）
          </span>
        </div>

        <!-- 批号历史 -->
        <div class="section-title">补录批号（近 20 批）</div>
        <div class="table-wrap">
          <table class="data">
            <thead>
              <tr>
                <th>批号</th><th class="num">行数</th><th class="num">预付合计</th>
                <th>状态</th><th>过账时间</th><th>过账人</th><th>反建账信息</th><th style="width:90px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!(status.batches || []).length">
                <td colspan="8" class="muted center">尚无补录批号</td>
              </tr>
              <tr v-for="b in status.batches" :key="b.postNo">
                <td>{{ b.postNo }}</td>
                <td class="num">{{ b.lineCount }}</td>
                <td class="num">{{ fmt(b.totalAmount) }}</td>
                <td>
                  <span class="batch-tag" :class="b.status === 'POSTED' ? 'on' : 'rev'">
                    {{ b.status === 'POSTED' ? '生效中' : '已反建账' }}
                  </span>
                </td>
                <td>{{ fmtDateTime(b.postedAt) }}</td>
                <td>{{ b.postedByName || '—' }}</td>
                <td class="muted small">
                  <template v-if="b.status !== 'POSTED'">
                    {{ fmtDateTime(b.reversedAt) }} {{ b.reversedByName || '' }}：{{ b.reverseReason || '—' }}
                  </template>
                  <template v-else>—</template>
                </td>
                <td>
                  <a v-if="b.status === 'POSTED'" class="link danger" v-permission="'fin.init_ap.reverse'"
                     @click="openReverse(b)">反建账</a>
                  <span v-else class="muted">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 各供应商期初预付累计（初始化批 + 补录批） -->
        <details class="opening-box">
          <summary>各供应商期初预付累计（QCYF 流水，含初始化批号与补录批号，共 {{ (status.supplierOpening || []).length }}{{ ' 户' }}）</summary>
          <div class="table-wrap">
            <table class="data">
              <thead><tr><th>供应商编码</th><th>供应商名称</th><th class="num">期初预付累计</th><th class="num">流水笔数</th></tr></thead>
              <tbody>
                <tr v-for="s in status.supplierOpening" :key="s.supplierCode">
                  <td>{{ s.supplierCode }}</td>
                  <td>{{ s.supplierName }}</td>
                  <td class="num">{{ fmt(s.openingAmount) }}</td>
                  <td class="num">{{ s.batchLineCount }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </details>
      </div>

      <!-- 反建账弹窗 -->
      <div v-if="reverseOpen" class="modal-mask inner" @click.self="reverseOpen = false">
        <div class="modal r520">
          <div class="modal-h">整批反建账 — 预付补录<span class="x" @click="reverseOpen = false">×</span></div>
          <div class="modal-b">
            <p class="tip">
              反建账将物理删除批号 <b>{{ reversePostNo }}</b> 的全部 QCYF 预付流水并重算账户链；
              已被预付核销、退款或厂家费用划转占用时会被拒绝。过账日已封账或过账当月已月结时也不能反建账。
            </p>
            <label class="reason-label">
              反建账批号
              <select v-model="reversePostNo">
                <option v-for="b in (status.batches || []).filter(x => x.status === 'POSTED')" :key="b.postNo" :value="b.postNo">
                  {{ b.postNo }}（{{ b.lineCount }} 行 / ￥{{ fmt(b.totalAmount) }}）
                </option>
              </select>
            </label>
            <label class="reason-label">反建账原因（必填，留痕）
              <textarea v-model="reverseReason" rows="3" placeholder="如：补录金额录错，重新导入"></textarea>
            </label>
          </div>
          <div class="modal-f">
            <button class="btn" @click="reverseOpen = false">取消</button>
            <button class="btn danger" :disabled="acting" @click="doReverse">确认反建账</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal-mask.inner { position: absolute; z-index: 110; background: rgba(0,0,0,.35); }
.modal { background: #fff; border-radius: 10px; width: 560px; max-width: 96vw; }
.modal.w960 { width: 1080px; max-height: 92vh; display: flex; flex-direction: column; position: relative; }
.modal.r520 { width: 520px; }
.modal-h { padding: 14px 18px; font-weight: 700; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; flex-shrink: 0; }
.modal-h .x { cursor: pointer; color: #999; }
.modal-b { padding: 16px 18px; overflow: auto; display: flex; flex-direction: column; gap: 10px; }
.spacer { flex: 1; }
.summary-bar { display: flex; align-items: center; gap: 10px; background: #fff; border: 1px solid #f0f0f0;
  border-radius: 8px; padding: 9px 14px; font-size: 13px; }
.money { color: #cf1322; font-size: 15px; }
.red { color: #cf1322; }
.sep { color: #e0e0e0; }
.banner { padding: 9px 14px; border-radius: 8px; font-size: 13px; }
.banner.warn { background: #fffbe6; border: 1px solid #ffe58f; color: #ad6800; }
.banner.info { background: #e6f4ff; border: 1px solid #91caff; color: #0958d9; }
.neg-tip { display: flex; flex-wrap: wrap; gap: 6px 14px; }
.neg-item { white-space: nowrap; }
.btn.sm { padding: 4px 12px; font-size: 13px; }
.btn.danger { background: #cf1322; color: #fff; border-color: #cf1322; }
.section-title { font-weight: 600; font-size: 13px; color: #555; margin-top: 4px; }
.table-wrap { background: #fff; border: 1px solid #f0f0f0; border-radius: 8px; overflow: auto; }
.data { width: 100%; border-collapse: collapse; font-size: 13px; }
.data th, .data td { padding: 6px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; white-space: nowrap; }
.data th { background: #fafafa; font-weight: 600; color: #555; }
.data td.num, .data th.num { text-align: right; }
.muted { color: #999; }
.small { font-size: 12px; }
.center { text-align: center; padding: 18px 0; }
.batch-tag { display: inline-block; padding: 1px 10px; border-radius: 10px; font-size: 12px; }
.batch-tag.on { background: #f6ffed; color: #389e0d; border: 1px solid #b7eb8f; }
.batch-tag.rev { background: #f5f5f5; color: #999; border: 1px solid #d9d9d9; }
.link { color: #1677ff; cursor: pointer; font-size: 12px; }
.link.danger { color: #cf1322; }
.opening-box { border: 1px solid #f0f0f0; border-radius: 8px; padding: 8px 12px; font-size: 13px; }
.opening-box summary { cursor: pointer; color: #555; font-weight: 600; }
.opening-box .table-wrap { border: none; margin-top: 8px; }
.toast-inline { padding: 8px 12px; border-radius: 6px; font-size: 13px; background: #f6ffed; border: 1px solid #b7eb8f; color: #389e0d; }
.toast-inline.err { background: #fff1f0; border-color: #ffa39e; color: #cf1322; }
.tip { font-size: 12px; color: #999; margin: 0 0 12px; }
.reason-label { display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: #555; margin-bottom: 12px; }
.reason-label select, .reason-label textarea { padding: 7px 10px; border: 1px solid #d9d9d9; border-radius: 6px; font-family: inherit; font-size: 13px; }
.reason-label textarea { resize: vertical; }
.modal-f { padding: 12px 18px; border-top: 1px solid #f0f0f0; text-align: right; }
.modal-f .btn { margin-left: 8px; }
</style>
