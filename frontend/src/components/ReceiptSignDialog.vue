<script setup>
/**
 * 发货单确认签收弹窗。
 *
 * 逐行登记客户实际签收数量与拒收数量：
 *   · 签收数量 + 拒收数量 必须等于发货数量（改一个自动补另一个）
 *   · 拒收数量 > 0 的行必须填拒收原因
 *   · 有拒收商品时，确认签收后后端自动生成【拒收入库单】，由仓库审核入库
 *
 * 拒收入库单的批次号/生产日期/成本单价由后端从原出库单回溯，这里不需要填。
 *
 * 提交后后端还会做两件事（这里不用额外操作）：
 *   · 按【签收数量】汇总出签收金额 / 拒收金额 / 税额 / 不含税金额
 *     （发货金额是出库单审核时定死的，签收不改它）
 *   · 随即自动审核并按【签收金额（含税）】生成应收。应收天然只含签收部分，不需要事后冲减
 * 全部拒收时签收金额为 0，不生成应收也不自动审核，单据停在待审核。
 *
 * 弹窗里的金额只是按前端输入现算的预览，落库口径以后端为准（两边算法一致：价内税倒算）。
 */
import { ref, watch, computed } from 'vue'
import { post, get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 发货单 ID 或单号 */
  receiptId: { type: String, default: '' },
})
const emit = defineEmits(['close', 'saved'])

const loading = ref(false)
const head = ref({})
const lines = ref([])
const errorMsg = ref('')

/** 常用拒收原因，点选即填 */
const REASON_PRESETS = ['商品破损', '临期/过期', '规格不符', '数量不符', '客户临时取消', '包装破损']

const totalQty = computed(() => lines.value.reduce((s, r) => s + Number(r.qty || 0), 0))
const totalSignedQty = computed(() => lines.value.reduce((s, r) => s + Number(r.signedQty || 0), 0))
const totalRejectQty = computed(() => lines.value.reduce((s, r) => s + Number(r.rejectQty || 0), 0))
const hasReject = computed(() => totalRejectQty.value > 0)

/** '13%' / '0.13' / '13' → 0.13 */
function parseRate(v) {
  const s = String(v ?? '').trim()
  if (!s) return 0.13
  const isPct = s.endsWith('%')
  const n = Number(isPct ? s.slice(0, -1) : s)
  if (!Number.isFinite(n)) return 0.13
  return (isPct || n > 1) ? n / 100 : n
}
const round2 = v => Math.round((Number(v) || 0) * 100) / 100

/** 行签收金额 / 拒收金额（含税，= 数量 × 单价，与后端算法一致） */
const lineSignAmount = row => round2(Number(row.signedQty || 0) * Number(row.price || 0))
const lineRejectAmount = row => round2(Number(row.rejectQty || 0) * Number(row.price || 0))
/** 行税额：价内税倒算 —— 签收金额 × 税率 ÷ (1+税率) */
const lineTaxAmount = row => {
  const rate = parseRate(row.taxRate)
  return rate === 0 ? 0 : round2(lineSignAmount(row) * rate / (1 + rate))
}

// 发货金额取主单（出库审核时定死），其余三个按明细现算预览
const deliverAmount = computed(() => round2(head.value.deliverAmount || 0))
const totalSignAmount = computed(() => round2(lines.value.reduce((s, r) => s + lineSignAmount(r), 0)))
const totalRejectAmount = computed(() => round2(lines.value.reduce((s, r) => s + lineRejectAmount(r), 0)))
const totalTaxAmount = computed(() => round2(lines.value.reduce((s, r) => s + lineTaxAmount(r), 0)))
const totalUntaxedAmount = computed(() => round2(totalSignAmount.value - totalTaxAmount.value))

watch(() => [props.visible, props.receiptId], async ([v]) => {
  if (!v || !props.receiptId) return
  errorMsg.value = ''
  await loadReceipt(props.receiptId)
})

async function loadReceipt(id) {
  loading.value = true
  try {
    const data = await get(`/sales/receipt/detail?id=${encodeURIComponent(id)}`)
    head.value = data
    // 默认全部签收：签收数量 = 发货数量，拒收数量 = 0
    lines.value = (data.details || []).map(d => ({
      detailId: d.detailId,
      goodsCode: d.goodsCode,
      goodsName: d.goodsName,
      unitName: d.unitName || '',
      qty: Number(d.qty || 0),
      price: Number(d.price || 0),
      taxRate: d.taxRate || '',
      signedQty: Number(d.qty || 0),
      rejectQty: 0,
      rejectReason: '',
    }))
  } catch (e) {
    errorMsg.value = e.message || '加载发货单失败'
    head.value = {}
    lines.value = []
  } finally {
    loading.value = false
  }
}

function clamp(v, max) {
  const n = Number(v)
  if (!Number.isFinite(n) || n < 0) return 0
  return n > max ? max : n
}

/** 改拒收数量 → 签收数量自动补齐为 发货数量 − 拒收数量 */
function onRejectQtyInput(row, raw) {
  row.rejectQty = clamp(raw, row.qty)
  row.signedQty = Math.round((row.qty - row.rejectQty) * 10000) / 10000
  if (row.rejectQty === 0) row.rejectReason = ''
}
/** 改签收数量 → 拒收数量自动补齐 */
function onSignedQtyInput(row, raw) {
  row.signedQty = clamp(raw, row.qty)
  row.rejectQty = Math.round((row.qty - row.signedQty) * 10000) / 10000
  if (row.rejectQty === 0) row.rejectReason = ''
}
/** 整行拒收 */
function rejectAll(row) {
  onRejectQtyInput(row, row.qty)
}
/** 整行签收 */
function signAll(row) {
  onSignedQtyInput(row, row.qty)
}

function validate() {
  errorMsg.value = ''
  if (lines.value.length === 0) {
    errorMsg.value = '发货单没有明细，无法签收'
    return false
  }
  for (const row of lines.value) {
    const label = row.goodsName || row.goodsCode
    const sum = Number(row.signedQty || 0) + Number(row.rejectQty || 0)
    if (Math.abs(sum - Number(row.qty || 0)) > 1e-6) {
      errorMsg.value = `商品 ${label} 的签收数量 + 拒收数量应等于发货数量 ${row.qty}`
      return false
    }
    if (Number(row.rejectQty || 0) > 0 && !String(row.rejectReason || '').trim()) {
      errorMsg.value = `商品 ${label} 有拒收数量，必须填写拒收原因`
      return false
    }
  }
  return true
}

async function confirmSign() {
  if (!validate()) return
  const head0 = `确认签收发货单【${head.value.receiptNo}】？\n\n`
  let tip
  if (Number(totalSignedQty.value) <= 0) {
    // 全部拒收：签收金额为 0，后端不生成应收、单据停在待审核
    tip = head0 + `全部商品拒收（合计 ${totalRejectQty.value}），将自动生成【拒收入库单】。\n`
      + `签收金额为 0，不生成应收账款，发货单保持待审核。`
  } else if (hasReject.value) {
    tip = head0 + `拒收合计 ${totalRejectQty.value}，将自动生成【拒收入库单】，请到该模块审核入库。\n`
      + `签收金额 ${totalSignAmount.value}（拒收 ${totalRejectAmount.value}），`
      + `税额 ${totalTaxAmount.value}，不含税金额 ${totalUntaxedAmount.value}。\n`
      + `签收后自动审核，按签收金额生成应收（拒收部分不开票）。`
  } else {
    tip = head0 + `全部商品签收，无拒收。\n`
      + `签收金额 ${totalSignAmount.value}，税额 ${totalTaxAmount.value}，不含税金额 ${totalUntaxedAmount.value}。\n`
      + `签收后自动审核并生成应收账款。`
  }
  if (!confirm(tip)) return
  loading.value = true
  try {
    const result = await post('/sales/receipt/sign', {
      receiptId: head.value.receiptId,
      details: lines.value.map(r => ({
        detailId: r.detailId,
        signedQty: Number(r.signedQty || 0),
        rejectQty: Number(r.rejectQty || 0),
        rejectReason: String(r.rejectReason || '').trim(),
      })),
    })
    emit('saved', result)
    emit('close')
  } catch (e) {
    errorMsg.value = '签收失败：' + (e.message || '未知错误')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div v-if="visible" class="modal-lite" @click.self="emit('close')">
    <div class="modal-lite-box" style="width:min(1040px,97vw);max-height:90vh">
      <div class="modal-lite-head">
        <b>确认签收 {{ head.receiptNo || '' }}</b>
        <div class="actions">
          <button class="btn" @click="emit('close')">关闭</button>
          <button class="btn primary" @click="confirmSign" :disabled="loading">确认签收</button>
        </div>
      </div>
      <div class="modal-lite-body">
        <div v-if="errorMsg" class="err-banner">{{ errorMsg }}</div>

        <!-- 单据信息 -->
        <div style="display:grid;grid-template-columns:repeat(4,1fr);gap:8px 14px;margin-bottom:10px">
          <div class="fi"><label>发货单号</label><input readonly :value="head.receiptNo || ''" /></div>
          <div class="fi"><label>出库单号</label><input readonly :value="head.sourceOutboundNo || ''" /></div>
          <div class="fi"><label>销售订单号</label><input readonly :value="head.sourceOrderNo || ''" /></div>
          <div class="fi"><label>客户</label><input readonly :value="head.customerName || ''" /></div>
          <div class="fi"><label>仓库</label><input readonly :value="head.warehouse || ''" /></div>
          <div class="fi"><label>司机</label><input readonly :value="head.driver || ''" /></div>
          <div class="fi"><label>签收状态</label><input readonly :value="head.signStatusText || '待签收'" /></div>
        </div>

        <!-- 汇总 -->
        <div style="display:flex;gap:20px;padding:8px 12px;background:#f5f7fa;border-radius:4px;margin-bottom:10px;font-size:13px">
          <span>发货合计：<b style="color:#303133">{{ totalQty }}</b></span>
          <span>签收合计：<b style="color:#409eff">{{ totalSignedQty }}</b></span>
          <span>拒收合计：<b :style="{ color: hasReject ? '#f56c6c' : '#909399', fontSize: '15px' }">{{ totalRejectQty }}</b></span>
          <span v-if="hasReject" style="color:#e6a23c">签收后将自动生成拒收入库单</span>
          <span v-if="Number(totalSignedQty) > 0" style="color:#67c23a;margin-left:auto">
            签收后自动审核，应收按签收数量生成
          </span>
          <span v-else style="color:#f56c6c;margin-left:auto">全部拒收，签收金额为 0，不生成应收</span>
        </div>

        <!-- 金额汇总：发货金额取主单（出库审核时已定死），其余按当前输入现算预览 -->
        <div style="display:flex;flex-wrap:wrap;gap:18px;padding:8px 12px;background:#fdf6ec;border:1px solid #faecd8;border-radius:4px;margin-bottom:10px;font-size:13px">
          <span>发货金额：<b style="color:#303133">{{ deliverAmount }}</b></span>
          <span>签收金额：<b style="color:#409eff">{{ totalSignAmount }}</b></span>
          <span>拒收金额：<b :style="{ color: hasReject ? '#f56c6c' : '#909399' }">{{ totalRejectAmount }}</b></span>
          <span>税额：<b style="color:#303133">{{ totalTaxAmount }}</b></span>
          <span>不含税金额：<b style="color:#303133">{{ totalUntaxedAmount }}</b></span>
          <span style="color:#909399;margin-left:auto">税额按价内倒算：签收金额 × 税率 ÷ (1+税率)</span>
        </div>

        <div v-if="loading && lines.length === 0" class="empty-detail">加载中...</div>
        <div v-else-if="lines.length === 0" class="empty-detail">暂无明细</div>
        <div v-else class="detail-scroll" style="max-height:46vh">
          <table>
            <thead>
              <tr>
                <th style="width:36px">#</th>
                <th style="min-width:110px">商品编号</th>
                <th style="min-width:150px">商品名称</th>
                <th style="width:52px">单位</th>
                <th style="width:80px">发货数量</th>
                <th style="width:96px">签收数量</th>
                <th style="width:96px">拒收数量</th>
                <th style="width:84px">签收金额</th>
                <th style="width:84px">拒收金额</th>
                <th style="min-width:180px">拒收原因</th>
                <th style="width:92px">快捷</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in lines" :key="row.detailId || index"
                  :class="{ 'reject-row': Number(row.rejectQty || 0) > 0 }">
                <td>{{ index + 1 }}</td>
                <td>{{ row.goodsCode }}</td>
                <td>{{ row.goodsName }}</td>
                <td>{{ row.unitName || '-' }}</td>
                <td style="text-align:right;font-weight:700">{{ row.qty }}</td>
                <td>
                  <input type="text" inputmode="decimal" :value="row.signedQty"
                         @input="onSignedQtyInput(row, $event.target.value)"
                         style="width:100%;height:24px;text-align:right" />
                </td>
                <td>
                  <input type="text" inputmode="decimal" :value="row.rejectQty"
                         @input="onRejectQtyInput(row, $event.target.value)"
                         style="width:100%;height:24px;text-align:right" />
                </td>
                <!-- 金额只读，随数量联动，不可直接编辑 -->
                <td style="text-align:right;color:#409eff">{{ lineSignAmount(row) }}</td>
                <td style="text-align:right" :style="{ color: Number(row.rejectQty || 0) > 0 ? '#f56c6c' : '#909399' }">
                  {{ lineRejectAmount(row) }}
                </td>
                <td>
                  <input type="text" v-model="row.rejectReason"
                         :disabled="Number(row.rejectQty || 0) <= 0"
                         :list="`reject-reasons-${index}`"
                         :placeholder="Number(row.rejectQty || 0) > 0 ? '必填' : '无拒收'"
                         style="width:100%;height:24px" />
                  <datalist :id="`reject-reasons-${index}`">
                    <option v-for="r in REASON_PRESETS" :key="r" :value="r"></option>
                  </datalist>
                </td>
                <td>
                  <button class="link link-btn" @click="signAll(row)">全签</button>
                  <button class="link link-btn" style="margin-left:6px" @click="rejectAll(row)">全拒</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.fi { display: flex; flex-direction: column; gap: 2px; }
.fi label { font-size: 12px; color: #606266; font-weight: 600; }
.fi input { height: 28px; padding: 0 6px; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px; }
table { width: 100%; border-collapse: collapse; font-size: 12px; }
th { background: #f5f7fa; padding: 5px 6px; text-align: left; font-weight: 600; border-bottom: 1px solid #e5e7eb; }
td { padding: 3px 6px; border-bottom: 1px solid #f0f0f0; }
.detail-scroll { overflow: auto; }
.empty-detail { padding: 40px; text-align: center; color: #909399; font-size: 13px; background: #fafbfc; border: 1px dashed #e5e7eb; border-radius: 8px; }
.err-banner { padding: 8px 12px; background: #fef0f0; border: 1px solid #fde2e2; border-radius: 6px; color: #f56c6c; font-size: 12px; margin-bottom: 10px; }
.reject-row { background: #fff8f8; }
</style>
