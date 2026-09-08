<script setup>
/**
 * 操作日志「改前/改后」对比视图（PRD-31）——纯展示组件，被时间线节点展开区与管理端详情抽屉复用。
 *
 * <p>后端 afterValue（diff 结构）：
 * <pre>
 * { main: [ {field,label,old,new} ... ],
 *   lines: [ {op:'ADD'|'MODIFY'|'REMOVE', lineKey, lineLabel, changes:[{field,label,old,new}]} ... ] }
 * </pre>
 * beforeValue 是改前字段快照（snake_case key → value），仅在详情抽屉里作为原始数据折叠展示。
 * 敏感值脱敏由后端按角色完成（非管理员成本/采购价等返回 ***），前端不再二次处理。
 */
import { computed } from 'vue'

const props = defineProps({
  after: { type: [Object, null], default: null },   // diff 结构
  before: { type: [Object, null], default: null },  // 改前快照（可空）
  content: { type: String, default: '' },           // operation_content 摘要
  detail: { type: String, default: '' },            // 旧 detail 文本
})

const mainChanges = computed(() => Array.isArray(props.after?.main) ? props.after.main : [])
const lineChanges = computed(() => Array.isArray(props.after?.lines) ? props.after.lines : [])

const OP_META = {
  ADD: { text: '新增行', cls: 'op-add' },
  MODIFY: { text: '修改行', cls: 'op-modify' },
  REMOVE: { text: '删除行', cls: 'op-remove' },
}
function opMeta(op) { return OP_META[op] || { text: op || '', cls: '' } }

const snapshotEntries = computed(() => {
  if (!props.before || typeof props.before !== 'object') return []
  return Object.entries(props.before)
    .filter(([, v]) => v !== null && v !== undefined && v !== '')
    .map(([k, v]) => ({ key: k, value: typeof v === 'object' ? JSON.stringify(v) : String(v) }))
})

const hasAnyDiff = computed(() => mainChanges.value.length > 0 || lineChanges.value.length > 0)
const summary = computed(() => props.content || props.detail || '')
</script>

<template>
  <div class="old-diff">
    <!-- 摘要文本 -->
    <div v-if="summary" class="old-summary">{{ summary }}</div>

    <!-- 主表字段级对比 -->
    <div v-if="mainChanges.length" class="old-block">
      <div class="old-block-title">主表字段</div>
      <table class="old-table">
        <thead><tr><th>字段</th><th>改前</th><th>改后</th></tr></thead>
        <tbody>
          <tr v-for="c in mainChanges" :key="c.field">
            <td class="t-label">{{ c.label || c.field }}</td>
            <td class="t-old">{{ c.old === '' || c.old == null ? '—' : c.old }}</td>
            <td class="t-new">{{ c.new === '' || c.new == null ? '—' : c.new }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 明细行级对比 -->
    <div v-if="lineChanges.length" class="old-block">
      <div class="old-block-title">明细行</div>
      <div v-for="(lc, i) in lineChanges" :key="lc.lineKey || i" class="old-line">
        <div class="old-line-head">
          <span class="op-badge" :class="opMeta(lc.op).cls">{{ opMeta(lc.op).text }}</span>
          <span class="old-line-label">{{ lc.lineLabel || lc.lineKey }}</span>
        </div>
        <table v-if="lc.changes && lc.changes.length" class="old-table">
          <thead><tr><th>字段</th><th>改前</th><th>改后</th></tr></thead>
          <tbody>
            <tr v-for="c in lc.changes" :key="c.field">
              <td class="t-label">{{ c.label || c.field }}</td>
              <td class="t-old">{{ c.old === '' || c.old == null ? '—' : c.old }}</td>
              <td class="t-new">{{ c.new === '' || c.new == null ? '—' : c.new }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 无 diff 时仅展示摘要（兜底，正常不出现空块） -->
    <div v-if="!hasAnyDiff && !summary" class="old-empty">无改前改后数据</div>

    <!-- 改前原始快照（折叠） -->
    <details v-if="snapshotEntries.length" class="old-snapshot">
      <summary>改前快照（原始字段）</summary>
      <table class="old-table">
        <tbody>
          <tr v-for="e in snapshotEntries" :key="e.key">
            <td class="t-label t-raw-key">{{ e.key }}</td>
            <td class="t-old">{{ e.value }}</td>
          </tr>
        </tbody>
      </table>
    </details>
  </div>
</template>

<style scoped>
.old-diff { font-size: 12px; }
.old-summary {
  background: #f4f7fb; border: 1px solid var(--line-soft, #e4e7ed);
  border-radius: 4px; padding: 6px 10px; color: #4a5568; line-height: 1.6;
  word-break: break-all; margin-bottom: 8px;
}
.old-block { margin-bottom: 10px; }
.old-block-title {
  font-weight: 700; color: #5d7896; font-size: 12px; margin-bottom: 4px;
}
.old-table {
  width: 100%; border-collapse: collapse; border: 1px solid var(--line-soft, #e4e7ed);
  border-radius: 4px; overflow: hidden;
}
.old-table th {
  background: #f0f2f5; color: #606266; font-weight: 600; text-align: left;
  padding: 5px 8px; border-bottom: 1px solid var(--line, #ebeef5); white-space: nowrap;
}
.old-table td { padding: 4px 8px; border-bottom: 1px solid var(--line-soft, #f0f2f5); vertical-align: top; }
.old-table tr:last-child td { border-bottom: none; }
.t-label { color: #909399; white-space: nowrap; width: 34%; }
.t-old { color: #b56b6b; word-break: break-all; }
.t-new { color: #3d8b5f; font-weight: 600; word-break: break-all; }
.t-raw-key { font-family: monospace; font-size: 11px; color: #a0a4aa; }
.old-line {
  border: 1px solid var(--line-soft, #e4e7ed); border-radius: 4px;
  padding: 6px 8px; margin-bottom: 6px; background: #fafbfc;
}
.old-line-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.old-line-label { font-weight: 600; color: #303133; }
.op-badge {
  display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; font-weight: 700;
}
.op-add { background: #e6f7ec; color: #2f9e58; }
.op-modify { background: #fdf3e0; color: #c8841c; }
.op-remove { background: #fde8e8; color: #d04848; }
.old-empty { color: #909399; padding: 12px; text-align: center; }
.old-snapshot { margin-top: 6px; }
.old-snapshot summary { cursor: pointer; color: #909399; font-size: 11px; user-select: none; }
.old-snapshot[open] summary { margin-bottom: 6px; }
</style>
