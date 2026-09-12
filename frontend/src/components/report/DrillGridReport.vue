<template>
  <div class="drill-card">
    <div class="drill-scroll">
      <table class="drill-table">
        <thead>
          <tr>
            <th v-for="col in columns" :key="col.key"
                :style="widthStyle(col)"
                :class="{ num: col.num, sortable: col.sortable, sensitive: col.sensitive }"
                @click="col.sortable && onSort(col)">
              {{ col.title }}
              <span v-if="col.sortable" class="sort-mark">{{ sortMark(col.key) }}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          <template v-for="(item, idx) in treeRows" :key="idx">
            <!-- 组小计行 -->
            <tr v-if="item.kind === 'group'" class="subtotal-row">
              <td v-for="col in columns" :key="col.key"
                  :class="cellClass(col)"
                  :style="indentStyle(col, item)">
                <template v-if="col.key === item.key">
                  <span class="subtotal-label">{{ item.value || '（空）' }}</span>
                  <span class="subtotal-tag">小计</span>
                </template>
                <a v-else-if="col.num && col.drill && item.sums[col.key] !== undefined"
                   class="drill-num"
                   @click="$emit('drill', { group: true, dims: item.dims, col })">{{ format(col, item.sums[col.key]) }}</a>
                <!-- 比率列不可加：组小计 = 组毛利额 ÷ 组净销售额 -->
                <span v-else-if="col.rate && nonEmpty(item.sums.grossProfit) && Number(item.sums.netAmount) !== 0">{{ fmtPercent(item.sums.grossProfit / item.sums.netAmount) }}</span>
                <span v-else-if="col.num && item.sums[col.key] !== undefined">{{ format(col, item.sums[col.key]) }}</span>
              </td>
            </tr>
            <!-- 叶子行（rowClassFn：台账期初灰行/红冲行、勾稽差异行等页面级行样式） -->
            <tr v-else :class="['leaf-row', rowClass(item.row)]">
              <td v-for="col in columns" :key="col.key"
                  :class="cellClass(col)"
                  :style="col.key === groupKeys[0] ? indentStyle(col, { level: item.level }) : null">
                <a v-if="col.link" class="link-num" @click="$emit('link', { row: item.row, col })">{{ display(col, item.row) }}</a>
                <a v-else-if="col.num && col.drill && nonEmpty(item.row[col.key])"
                   class="drill-num"
                   @click="$emit('drill', { group: false, row: item.row, col })">{{ format(col, item.row[col.key]) }}</a>
                <span v-else>{{ display(col, item.row) }}</span>
              </td>
            </tr>
          </template>
          <tr v-if="!loading && (!rows || rows.length === 0)">
            <td :colspan="columns.length" class="empty-cell">
              当前条件下无数据，可放宽日期或清空条件；数据量大时建议使用异步导出
            </td>
          </tr>
        </tbody>
        <tfoot v-if="summary && hasSummary">
          <tr class="grand-row">
            <td v-for="(col, i) in columns" :key="col.key" :class="cellClass(col)">
              <template v-if="i === 0">合计</template>
              <template v-else-if="col.num && nonEmpty(summary[col.key])">{{ format(col, summary[col.key]) }}</template>
            </td>
          </tr>
        </tfoot>
      </table>
    </div>

    <div v-if="paged" class="drill-pager">
      <span class="pager-total">共 {{ total }} 条</span>
      <button class="btn-mini" :disabled="pageNo <= 1" @click="$emit('page-change', pageNo - 1)">上一页</button>
      <span>第 {{ pageNo }} / {{ totalPages }} 页</span>
      <button class="btn-mini" :disabled="pageNo >= totalPages" @click="$emit('page-change', pageNo + 1)">下一页</button>
      <select :value="pageSize" @change="$emit('size-change', Number($event.target.value))">
        <option :value="100">100 条/页</option>
        <option :value="500">500 条/页</option>
        <option :value="1000">1000 条/页</option>
      </select>
    </div>
    <div v-if="loading" class="drill-loading">查询中…</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { buildTreeRows, fmtNum, fmtPercent } from './report-table.js'

/**
 * 报表钻取表格：明细报表（无分组键）与汇总报表（1~3 级分组）共用。
 * 组小计/合计一律由前端对叶子行实时汇总（汇总报表服务端只返回叶子行，
 * 避免深分页 ROLLUP 口径问题）；可钻数字蓝色下划线，事件交给页面拼目标报表条件。
 */
const props = defineProps({
  columns: { type: Array, required: true },
  rows: { type: Array, default: () => [] },
  groupKeys: { type: Array, default: () => [] },
  summary: { type: Object, default: () => ({}) },
  loading: { type: Boolean, default: false },
  paged: { type: Boolean, default: false },
  total: { type: Number, default: 0 },
  pageNo: { type: Number, default: 1 },
  pageSize: { type: Number, default: 100 },
  sortField: { type: String, default: '' },
  sortOrder: { type: String, default: '' },
  /** 叶子行自定义样式类（如期初行/红冲行/差异行），返回类名或空串 */
  rowClassFn: { type: Function, default: null },
})
const emit = defineEmits(['page-change', 'size-change', 'sort', 'drill', 'link'])

// 比率列（毛利率/回款率等）不可加，不参与组小计累加；小计由分子/分母实时派生。
// noSum 为其他不可加计数指标（如去重客户数），同样不累加。
const numKeys = computed(() =>
  props.columns.filter(c => c.num && !c.rate && !c.noSum).map(c => c.key))
function rowClass(row) {
  return props.rowClassFn ? props.rowClassFn(row) || '' : ''
}
const treeRows = computed(() => buildTreeRows(props.rows, props.groupKeys, numKeys.value))
const totalPages = computed(() => Math.max(1, Math.ceil((props.total || 0) / props.pageSize)))
const hasSummary = computed(() =>
  props.columns.some(c => c.num && props.summary[c.key] !== undefined && props.summary[c.key] !== null))

function widthStyle(col) {
  return col.width ? { width: typeof col.width === 'number' ? `${col.width}px` : col.width, minWidth: '90px' } : { minWidth: '100px' }
}
function cellClass(col) {
  // sensitive：价格/金额等敏感列，有权限时橘色显示（Q4：按角色露出，露出即橘色提示保密口径）
  return { num: col.num, sensitive: !!col.sensitive }
}
function indentStyle(col, item) {
  const pad = 10 + item.level * 18
  return { paddingLeft: `${pad}px` }
}
function nonEmpty(v) {
  return v !== null && v !== undefined && v !== ''
}
function display(col, row) {
  const v = row[col.key]
  if (col.bool) return v === true || v === 'true' ? '是' : ''
  return col.num ? format(col, v) : (v ?? '')
}
function format(col, v) {
  if (!nonEmpty(v)) return ''
  if (col.percent) return fmtPercent(v)
  return fmtNum(v, col.money ? 'money' : 'qty')
}
function sortMark(key) {
  if (props.sortField !== key) return '↕'
  return props.sortOrder === 'asc' ? '▲' : '▼'
}
function onSort(col) {
  if (props.sortField === col.key && props.sortOrder === 'desc') {
    emit('sort', { field: col.key, order: 'asc' })
  } else {
    emit('sort', { field: col.key, order: 'desc' })
  }
}
</script>

<style scoped>
.drill-card {
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, .06);
  position: relative;
}
.drill-scroll {
  overflow: auto;
  max-height: calc(100vh - 250px);
}
.drill-table {
  border-collapse: collapse;
  width: max-content;
  min-width: 100%;
  font-size: 12.5px;
}
.drill-table th,
.drill-table td {
  border: 1px solid #ebeef5;
  padding: 6px 10px;
  white-space: nowrap;
  color: #303133;
}
.drill-table thead th {
  background: #f5f7fa;
  font-weight: 600;
  position: sticky;
  top: 0;
  z-index: 2;
}
.drill-table th.sortable {
  cursor: pointer;
  user-select: none;
}
.sort-mark {
  color: #c0c4cc;
  font-size: 10px;
  margin-left: 2px;
}
.num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.drill-table th.sensitive {
  color: #e6a23c;
}
.drill-table td.sensitive,
.drill-table td.sensitive .drill-num {
  color: #e6a23c;
}
.leaf-row:hover td {
  background: #f5f9ff;
}
/* 台账期初虚拟行：灰底、不可钻 */
.opening-row td {
  background: #f4f4f5;
  color: #909399;
}
.opening-row:hover td {
  background: #eeeeef;
}
/* 红冲/作废回库行：负数红字 */
.reversal-row td,
.reversal-row td .drill-num {
  color: #f56c6c;
}
/* 进销存对账差异行 */
.diff-row td {
  color: #e6a23c;
}
.subtotal-row td {
  background: #fafafa;
  font-weight: 600;
}
.subtotal-tag {
  color: #909399;
  font-size: 11px;
  margin-left: 6px;
  font-weight: 400;
}
.grand-row td {
  background: #ecf3ff;
  font-weight: 700;
  position: sticky;
  bottom: 0;
}
.drill-num,
.link-num {
  color: #409eff;
  text-decoration: underline;
  cursor: pointer;
}
.empty-cell {
  text-align: center;
  color: #909399;
  padding: 30px 0;
}
.drill-pager {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-top: 1px solid #ebeef5;
  font-size: 12.5px;
  color: #606266;
}
.pager-total {
  margin-right: auto;
}
.btn-mini {
  border: 1px solid #dcdfe6;
  background: #fff;
  border-radius: 4px;
  padding: 3px 10px;
  cursor: pointer;
  font-size: 12px;
}
.btn-mini:disabled {
  color: #c0c4cc;
  cursor: not-allowed;
}
.drill-pager select {
  height: 26px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
}
.drill-loading {
  position: absolute;
  inset: 0;
  background: rgba(255, 255, 255, .55);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #409eff;
  font-size: 14px;
}
</style>
