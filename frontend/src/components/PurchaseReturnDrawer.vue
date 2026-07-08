<script setup>
/**
 * 采购退货单抽屉 —— 只读查看 + 审核 / 反审核。
 *
 * 退货单由退货出库审核后自动生成（status = PENDING），本抽屉用于：
 *   1. 查看来源申请单 / 出库单、税额计算、成本金额
 *   2. 审核 → 后端写入负向 fin_ap 冲减应付账款
 *   3. 反审核 → 删除负向 fin_ap（仅当未发生付款）
 *
 * 应付冲减说明：审核时按 goods_amount（**含税**商品金额）写入一条 ap_amount 为负数的 fin_ap，
 * source_bill 存退货单号。原采购收货单的应付记录保持不变，
 * 供应商应付余额 = SUM(所有 fin_ap.ap_amount)。
 *
 * 金额口径：单价为含税单价 → goods_amount 为含税金额；
 *   税额 = 含税金额 × 税率 ÷ (1 + 税率)（价内税倒算）
 *   不含税金额 = 含税金额 − 税额
 */
import { ref, computed, watch } from 'vue'
import { post, get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 退货单 ID 或单号 */
  returnId: { type: String, default: '' },
})
const emit = defineEmits(['close', 'save'])

const head = ref({})
const detailList = ref([])
const errors = ref({})
const loading = ref(false)

const isApproved = computed(() => head.value.status === 'APPROVED')

const totalQty = computed(() =>
  detailList.value.reduce((s, r) => s + Number(r.qty || 0), 0)
)

/**
 * 不含税金额 = 含税商品金额 − 税额。
 * <p>后端 detail 已返回 untaxedAmount，这里兜底现算（兼容缓存的老响应）。
 */
const untaxedAmount = computed(() => {
  if (head.value.untaxedAmount != null) return Number(head.value.untaxedAmount).toFixed(2)
  return (Number(head.value.goodsAmount || 0) - Number(head.value.taxAmount || 0)).toFixed(2)
})

watch(() => [props.visible, props.returnId], async ([val]) => {
  if (!val || !props.returnId) return
  errors.value = {}
  await loadReturn(props.returnId)
})

async function loadReturn(id) {
  loading.value = true
  try {
    const data = await get(`/purchase/return/detail?id=${encodeURIComponent(id)}`)
    head.value = data
    detailList.value = (data.details || []).map(d => ({
      goodsCode: d.goodsCode,
      goodsName: d.goodsName,
      unitName: d.unitName,
      qty: Number(d.qty || 0),
      price: Number(d.price || 0),
      amount: Number(d.amount || 0),
      taxRate: d.taxRate || '',
      taxAmount: Number(d.taxAmount || 0),
      costPrice: Number(d.costPrice || 0),
      costAmount: Number(d.costAmount || 0),
    }))
  } catch (e) {
    errors.value.header = e.message || '加载退货单失败'
    head.value = {}
    detailList.value = []
  } finally {
    loading.value = false
  }
}

/** 审核 → 写负向 fin_ap 冲减应付（按含税商品金额） */
async function auditReturn() {
  const amount = Number(head.value.goodsAmount || 0).toFixed(2)
  if (!confirm(`确认审核采购退货单【${head.value.returnNo}】？\n\n审核后将写入一条金额为 -¥${amount}（含税商品金额）的负向应付记录，冲减供应商【${head.value.supplierName}】的应付账款。`)) return
  try {
    const result = await post('/purchase/return/audit', { bizId: head.value.returnId })
    emit('save', result)
    await loadReturn(head.value.returnId)
  } catch (e) {
    errors.value.header = '审核失败：' + (e.message || '未知错误')
  }
}

/** 反审核 → 删除负向 fin_ap */
async function reverseAudit() {
  if (!confirm(`确认反审核采购退货单【${head.value.returnNo}】？\n\n反审核后将撤销该退货单的应付冲减记录。若已发生付款核销，反审核会被拒绝。`)) return
  try {
    const result = await post('/purchase/return/reverse-audit', { bizId: head.value.returnId })
    emit('save', result)
    await loadReturn(head.value.returnId)
  } catch (e) {
    errors.value.header = '反审核失败：' + (e.message || '未知错误')
  }
}

function closeDrawer() { emit('close') }
</script>

<template>
  <div v-show="visible" class="return-drawer-mask">
    <div class="return-drawer-box">
      <div class="return-drawer-head">
        <b>采购退货单 {{ head.returnNo || '' }}</b>
        <span v-if="isApproved" class="badge ok">已审核</span>
        <span v-else class="badge wait">待审核</span>
        <div style="flex:1"></div>
        <div class="actions">
          <button class="btn" @click="closeDrawer">关闭</button>
          <button v-if="!isApproved" class="btn primary" @click="auditReturn">审核（冲减应付）</button>
          <button v-else class="btn" @click="reverseAudit">反审核</button>
        </div>
      </div>

      <div class="return-drawer-body">
        <div v-if="errors.header" class="err-banner">{{ errors.header }}</div>

        <!-- 头部 -->
        <div class="card" style="padding:12px">
          <div style="font-weight:900;margin-bottom:10px;color:var(--primary)">单据信息</div>
          <div class="grid4">
            <div class="field">
              <label>退货单号</label>
              <input readonly :value="head.returnNo || ''" />
            </div>
            <div class="field">
              <label>来源退货申请</label>
              <input readonly :value="head.sourceApplyNo || ''" />
            </div>
            <div class="field">
              <label>来源退货出库单</label>
              <input readonly :value="head.sourceOutboundNo || ''" />
            </div>
            <div class="field">
              <label>供应商</label>
              <input readonly :value="head.supplierName || ''" />
            </div>
            <div class="field">
              <label>仓库</label>
              <input readonly :value="head.warehouse || ''" />
            </div>
            <div class="field">
              <label>退货日期</label>
              <input readonly :value="String(head.returnDate || '').slice(0, 10)" />
            </div>
            <div class="field">
              <label>应付冲减状态</label>
              <input readonly :value="head.apStatus || ''" />
            </div>
            <div class="field">
              <label>审核信息</label>
              <input readonly :value="head.auditUser ? `${head.auditUser} ${String(head.auditTime || '').slice(0, 19)}` : '未审核'" />
            </div>
          </div>
        </div>

        <!-- 金额汇总 -->
        <div class="card" style="padding:12px">
          <div style="font-weight:900;margin-bottom:10px;color:var(--primary)">金额汇总</div>
          <div class="grid4">
            <div class="field">
              <label>商品金额（含税）</label>
              <input readonly class="highlight" :value="'¥ ' + Number(head.goodsAmount || 0).toFixed(2)" />
            </div>
            <div class="field">
              <label>税额</label>
              <input readonly :value="'¥ ' + Number(head.taxAmount || 0).toFixed(2)" />
            </div>
            <div class="field">
              <label>不含税金额</label>
              <input readonly :value="'¥ ' + untaxedAmount" />
            </div>
            <div class="field">
              <label>成本金额</label>
              <input readonly :value="'¥ ' + Number(head.costAmount || 0).toFixed(2)" />
            </div>
          </div>
          <div class="amount-tip">
            应付结算按<b>商品金额（含税）</b>进行；税额按价内税倒算（含税金额 × 税率 ÷ (1+税率)），不含税金额 = 含税金额 − 税额
          </div>
        </div>

        <!-- 明细 -->
        <div class="card detail-card">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
            <div style="font-weight:900;color:var(--primary)">退货明细（按商品聚合）</div>
            <div style="font-size:12px;color:#5d7896">成本单价取退货出库时的当前库存成本单价</div>
          </div>
          <div v-if="loading" class="empty-detail">加载中...</div>
          <div v-else-if="detailList.length === 0" class="empty-detail">暂无退货明细</div>
          <div v-else class="detail-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:40px">#</th>
                  <th style="min-width:120px">商品编号</th>
                  <th style="min-width:160px">商品名称</th>
                  <th style="width:60px">单位</th>
                  <th style="width:90px">退货数量</th>
                  <th style="width:90px">单价</th>
                  <th style="width:90px">金额(含税)</th>
                  <th style="width:70px">税率</th>
                  <th style="width:90px">税额</th>
                  <th style="width:100px">成本单价</th>
                  <th style="width:90px">成本金额</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in detailList" :key="index">
                  <td>{{ index + 1 }}</td>
                  <td>{{ row.goodsCode }}</td>
                  <td>{{ row.goodsName }}</td>
                  <td>{{ row.unitName }}</td>
                  <td style="text-align:right">{{ row.qty }}</td>
                  <td style="text-align:right">{{ Number(row.price).toFixed(4) }}</td>
                  <td style="text-align:right;font-weight:700">{{ Number(row.amount).toFixed(2) }}</td>
                  <td style="text-align:right">{{ row.taxRate }}</td>
                  <td style="text-align:right">{{ Number(row.taxAmount).toFixed(2) }}</td>
                  <td style="text-align:right">{{ Number(row.costPrice).toFixed(6) }}</td>
                  <td style="text-align:right;font-weight:700">{{ Number(row.costAmount).toFixed(2) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="summary">
          <span>合计退货数量：<b>{{ totalQty }}</b></span>
          <span>商品金额（含税）：<b style="color:var(--danger)">¥ {{ Number(head.goodsAmount || 0).toFixed(2) }}</b></span>
          <span>税额：<b>¥ {{ Number(head.taxAmount || 0).toFixed(2) }}</b></span>
          <span>不含税金额：<b>¥ {{ untaxedAmount }}</b></span>
          <span>行数：<b>{{ detailList.length }}</b></span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.return-drawer-mask {
  position: fixed;
  top: 48px; right: 0; bottom: 0; left: 299px;
  z-index: 900;
  display: flex;
  pointer-events: none;
  animation: fadeIn 0.2s ease;
}
.return-drawer-box {
  flex: 1;
  background: #fff;
  display: flex; flex-direction: column;
  min-width: 0;
  border-left: 1px solid var(--line);
  box-shadow: -6px 0 24px rgba(15, 46, 88, 0.12);
  pointer-events: auto;
  animation: slideIn 0.25s ease;
}
.return-drawer-head {
  display: flex; align-items: center; gap: 10px;
  height: 46px; padding: 0 16px;
  border-bottom: 1px solid var(--line-soft);
}
.return-drawer-head b { font-size: 15px; }
.return-drawer-body {
  flex: 1; overflow: auto;
  padding: 12px 16px;
  display: flex; flex-direction: column; gap: 12px;
  background: #f5f7fa;
}
.detail-card { flex: 1; display: flex; flex-direction: column; min-height: 220px; padding: 12px; }
.detail-scroll { flex: 1; overflow: auto; min-height: 0; }
.empty-detail { padding: 40px; text-align: center; color: #909399; font-size: 13px; background: #fafbfc; border: 1px dashed #e5e7eb; border-radius: 8px; }
.err-banner { padding: 8px 12px; background: #fef0f0; border: 1px solid #fde2e2; border-radius: 6px; color: var(--danger); font-size: 12px; }
.field input.highlight { font-weight: 900; color: var(--danger); }
/* 金额口径说明 */
.amount-tip {
  margin-top: 8px; padding: 6px 10px;
  background: #f4f4f5; border-left: 3px solid #909399; border-radius: 3px;
  font-size: 12px; color: #606266;
}
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes slideIn { from { transform: translateX(60px); opacity: 0.6 } to { transform: translateX(0); opacity: 1 } }
@media (max-width: 900px) {
  .return-drawer-mask { left: 0; }
}
</style>