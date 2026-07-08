<script setup>
/**
 * 业务单据详情抽屉（只读）—— 双击列表行触发。
 *
 * <p>展示结构：
 *   · 顶部：单据头字段（按 {@code headerFields} 分组网格显示）
 *   · 下部：明细表格（列自动从明细第一行的 key 推导，或用 {@code detailColumns} 覆盖）
 *
 * <p>数据源约定：后端 detail 端点返回的 {@code data} 已经过 camelize，是驼峰对象；
 *   {@code details} 字段是明细数组。
 */
import { computed } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '单据详情' },
  data: { type: Object, default: () => ({}) },     // 后端 detail 返回的头部对象（含 details 数组）
  /** 头部字段展示配置，形如 [{key,label,format?}]；不提供则自动展示所有非 details 字段 */
  headerFields: { type: Array, default: null },
  /** 明细列展示配置，形如 [{key,label,align?,format?}]；不提供则按明细第一行 keys 自动推导 */
  detailColumns: { type: Array, default: null },
})
const emit = defineEmits(['close'])

const details = computed(() => Array.isArray(props.data?.details) ? props.data.details : [])

// 自动头部字段：排除 details / 大对象 / null
const autoHeaderFields = computed(() => {
  if (!props.data) return []
  const excluded = new Set(['details'])
  return Object.entries(props.data)
    .filter(([k, v]) => !excluded.has(k) && v !== null && typeof v !== 'object')
    .map(([k]) => ({ key: k, label: humanize(k) }))
})
const headerCols = computed(() => props.headerFields || autoHeaderFields.value)

// 自动明细列：取第一行 keys
const autoDetailColumns = computed(() => {
  if (details.value.length === 0) return []
  const first = details.value[0]
  const excluded = new Set(['detailId', 'receiptId', 'inboundId', 'outboundId', 'orderId'])
  return Object.keys(first)
    .filter(k => !excluded.has(k))
    .map(k => ({ key: k, label: humanize(k) }))
})
const cols = computed(() => props.detailColumns || autoDetailColumns.value)

// 简单的 camelCase → 中文标签的字典（未命中时用 camelCase 显示）
const LABEL_MAP = {
  orderNo: '单据号', orderId: '单据ID', receiptNo: '单据号', inboundNo: '单据号', outboundNo: '单据号',
  billDate: '单据日期', receiptDate: '收货日期',
  supplierCode: '供应商编号', supplierName: '供应商', customerCode: '客户编号', customerName: '客户',
  customer: '客户', warehouse: '仓库', buyer: '采购员', salesman: '业务员',
  amount: '金额', paidAmount: '已付金额', unpaidAmount: '未付金额',
  inboundAmount: '已入库金额', outboundAmount: '已出库金额',
  goodsAmount: '商品金额', taxAmount: '税额', expenseAmount: '费用', finalAmount: '最终金额',
  // 销售发货单 V52 金额口径：发货金额出库审核时定死，其余三个签收后由明细汇总
  deliverAmount: '发货金额', signAmount: '签收金额', rejectAmount: '拒收金额', untaxedAmount: '不含税金额',
  signedQty: '签收数量', rejectQty: '拒收数量', rejectReason: '拒收原因',
  inboundStatus: '入库状态', outboundStatus: '出库状态', paymentStatus: '付款状态',
  arStatus: '应收生成', apStatus: '应付生成', receiveStatus: '收款状态', payStatus: '付款状态',
  status: '状态', statusText: '状态',
  creatorName: '创建人', createTime: '创建时间', createdAt: '创建时间',
  auditUser: '审核人', auditTime: '审核时间', remark: '备注',
  sourceOrderNo: '来源订单', sourceInboundNo: '来源入库单', sourceOutboundNo: '来源出库单',
  sourceOrder: '来源订单', sourceBill: '来源单据', sourceInbound: '来源入库单',
  goodsCode: '商品编号', goodsName: '商品名称', spec: '规格', unitName: '单位',
  qty: '数量', expectedQty: '应入数量', receivedQty: '实收数量',
  price: '单价', taxRate: '税率', costPrice: '成本单价', costAmount: '成本金额',
  batchNo: '批次号', productionDate: '生产日期', expiryDate: '到期日期',
  beforeCost: '入库前成本', afterCost: '入库后成本',
  unitLevel: '单位级别', convertQty: '换算率', baseQty: '基本单位数量',
  lineType: '行类型', salesAttribute: '销售属性',
  priceGroupCode: '价格组', expectedDeliveryDate: '预计送达',
  paidAmount2: '已收金额', unpaidAmount2: '未收金额',
}
function humanize(key) {
  return LABEL_MAP[key] || key
}

function fmt(v) {
  if (v == null) return ''
  if (typeof v === 'number') return v
  return String(v)
}

function closeDrawer() { emit('close') }
</script>

<template>
  <div v-show="visible" class="bd-drawer-mask" @click.self="closeDrawer">
    <div class="bd-drawer-box">
      <div class="bd-drawer-head">
        <b>{{ title }}</b>
        <span v-if="data && (data.orderNo || data.receiptNo || data.inboundNo || data.outboundNo)"
              style="color:#5d7896;font-size:13px;margin-left:8px">
          {{ data.orderNo || data.receiptNo || data.inboundNo || data.outboundNo }}
        </span>
        <div style="flex:1"></div>
        <button class="btn" @click="closeDrawer">关闭</button>
      </div>

      <div class="bd-drawer-body">
        <!-- 头部信息 -->
        <div class="card">
          <div class="section-title">单据信息</div>
          <div class="header-grid">
            <div v-for="col in headerCols" :key="col.key" class="header-cell">
              <label>{{ col.label }}</label>
              <span>{{ col.format ? col.format(data[col.key]) : fmt(data[col.key]) }}</span>
            </div>
          </div>
        </div>

        <!-- 明细 -->
        <div class="card detail-card">
          <div class="section-title">
            明细
            <span style="font-weight:normal;color:#909399;font-size:12px;margin-left:6px">
              共 {{ details.length }} 行
            </span>
          </div>
          <div v-if="details.length === 0" class="empty">
            无明细数据
          </div>
          <div v-else class="detail-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:40px">#</th>
                  <th v-for="c in cols" :key="c.key" :style="c.align === 'right' ? { textAlign: 'right' } : null">
                    {{ c.label }}
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in details" :key="index">
                  <td>{{ index + 1 }}</td>
                  <td v-for="c in cols" :key="c.key" :style="c.align === 'right' ? { textAlign: 'right' } : null">
                    {{ c.format ? c.format(row[c.key]) : fmt(row[c.key]) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bd-drawer-mask {
  position: fixed;
  top: 48px;
  right: 0; bottom: 0;
  left: 299px;
  z-index: 900;
  display: flex;
  pointer-events: auto;
  background: rgba(15, 46, 88, 0.08);
  animation: fadeIn 0.2s ease;
}
.bd-drawer-box {
  flex: 1;
  background: #fff;
  display: flex; flex-direction: column;
  min-width: 0;
  border-left: 1px solid var(--line);
  box-shadow: -6px 0 24px rgba(15, 46, 88, 0.12);
  animation: slideIn 0.25s ease;
}
.bd-drawer-head {
  display: flex; align-items: center; gap: 10px;
  height: 46px; padding: 0 16px;
  border-bottom: 1px solid var(--line-soft);
}
.bd-drawer-head b { font-size: 15px; }
.bd-drawer-body {
  flex: 1; overflow: auto;
  padding: 12px 16px;
  display: flex; flex-direction: column; gap: 12px;
  background: #f5f7fa;
}
.card {
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: 6px;
  padding: 12px 14px;
}
.section-title {
  font-weight: 800;
  color: var(--primary);
  font-size: 13px;
  margin-bottom: 10px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--line-soft);
}
/* 头部字段网格：4 列，label 60px + 值填充剩余 */
.header-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px 20px;
}
.header-cell {
  display: flex;
  align-items: center;
  min-width: 0;
  font-size: 12px;
  gap: 8px;
}
.header-cell label {
  width: 88px;
  color: #909399;
  text-align: right;
  flex-shrink: 0;
}
.header-cell span {
  color: #303133;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 明细网格 */
.detail-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 220px;
}
.detail-scroll {
  flex: 1;
  overflow: auto;
  min-height: 0;
  border: 1px solid var(--line-soft);
  border-radius: 4px;
}
.detail-scroll table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.detail-scroll th {
  position: sticky;
  top: 0;
  background: #f0f2f5;
  color: #303133;
  font-weight: 700;
  padding: 8px 10px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  white-space: nowrap;
}
.detail-scroll td {
  padding: 6px 10px;
  border-bottom: 1px solid var(--line-soft);
  color: #303133;
  vertical-align: middle;
}
.detail-scroll tr:hover td {
  background: #eff6ff;
}
.empty {
  padding: 40px;
  text-align: center;
  color: #909399;
  font-size: 13px;
  background: #fafbfc;
  border: 1px dashed var(--line);
  border-radius: 6px;
}
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes slideIn { from { transform: translateX(60px); opacity: 0.6 } to { transform: translateX(0); opacity: 1 } }
@media (max-width: 900px) {
  .bd-drawer-mask { left: 0; }
  .header-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
