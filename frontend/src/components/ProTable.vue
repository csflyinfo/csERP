<script setup>
/**
 * 通用表格组件 —— 支持字段设置（可见/顺序/宽度/固定列）+ 拖拽表头 + 列宽 resize。
 *
 * 关键 prop：
 *   - columns  已经过 useColumnSettings 处理的 visibleColumns（含 fixed / stickyLeft / isLastFixed / width）
 *   - cell-style 由 composable 提供的 style 生成器：cellStyle(col, type)
 *   - dragging-key / on-*  拖拽表头回调（由 composable 提供，可选，不传则不启用拖拽）
 *   - on-start-resize      列宽拖拽起点（由 composable 提供，可选）
 *
 * 事件：field-setting / export / row-action / row-dblclick / page-change / page-size-change / sort-change
 */
defineProps({
  title: { type: String, default: '记录列表' },
  columns: { type: Array, required: true },
  rows: { type: Array, default: () => [] },
  pageNo: { type: Number, default: 1 },
  pageSize: { type: Number, default: 100 },
  total: { type: Number, default: 0 },
  sortField: { type: String, default: '' },
  sortOrder: { type: String, default: '' },
  // 字段设置能力（可选；提供后表格支持拖拽/resize/sticky 固定列）
  cellStyle: { type: Function, default: null },
  draggingKey: { type: String, default: '' },
  onHeaderDragStart: { type: Function, default: null },
  onHeaderDragOver: { type: Function, default: null },
  onHeaderDrop: { type: Function, default: null },
  onStartResize: { type: Function, default: null },
})

const emit = defineEmits(['field-setting', 'export', 'row-action', 'page-change', 'page-size-change', 'sort-change'])

// 生成 th/td 内联样式：如父组件传了 cellStyle 用之，否则回退到最简样式
function styleOf(col, type) {
  if (typeof arguments[0] === 'object' && arguments[0]) {
    // 走 props.cellStyle
  }
  return null
}
</script>

<template>
  <div class="tablebox">
    <div class="toolbar">
      <b>{{ title }}</b>
      <div class="spacer"></div>
      <button class="btn" @click="emit('field-setting')">字段设置</button>
      <button class="btn" @click="emit('export')">导出</button>
    </div>
    <div class="scroll">
      <table>
        <thead>
          <tr>
            <!-- 勾选列头部（提供了 slot 时显示） -->
            <th v-if="$slots['checkbox-header']" class="checkbox-th">
              <slot name="checkbox-header"></slot>
            </th>
            <th v-for="col in columns" :key="col.key"
                :class="[
                  'sortable-th',
                  {
                    'action-col': col.action || /操作/.test(col.title),
                    'th-fixed': col.fixed,
                    'th-fixed-last': col.isLastFixed,
                    'th-dragging': draggingKey === col.key,
                  },
                ]"
                :style="cellStyle ? cellStyle(col, 'th') : { width: (col.width || 100) + 'px' }"
                :draggable="onHeaderDragStart && !col.fixed && !col.action && !/操作/.test(col.title)"
                @dragstart="onHeaderDragStart && onHeaderDragStart($event, col)"
                @dragover="onHeaderDragOver && onHeaderDragOver($event)"
                @drop="onHeaderDrop && onHeaderDrop($event, col)"
                @click="emit('sort-change', col.key)">
              <span class="th-title">{{ col.title }}</span>
              <span v-if="sortField === col.key" class="th-sort">{{ sortOrder === 'desc' ? '↓' : '↑' }}</span>
              <span v-if="col.fixed" class="th-flag" title="已固定">📌</span>
              <span v-if="onStartResize && !col.action && !/操作/.test(col.title)"
                    class="th-resize" @mousedown.stop="onStartResize($event, col)"></span>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in rows" :key="index" @dblclick="emit('row-action', '查看', row)">
            <!-- 勾选列单元格（提供了 slot 时显示） -->
            <td v-if="$slots['checkbox-cell']" class="checkbox-td">
              <slot name="checkbox-cell" :row="row" :row-index="index"></slot>
            </td>
            <td v-for="col in columns" :key="col.key"
                :class="[
                  col.num ? 'num' : '',
                  {
                    'action-col': col.action || /操作/.test(col.title),
                    'td-fixed': col.fixed,
                    'td-fixed-last': col.isLastFixed,
                  },
                ]"
                :style="cellStyle ? cellStyle(col, 'td') : null">
              <slot :name="col.key" :row="row">{{ row[col.key] }}</slot>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="pager">
      <span>共 {{ total || rows.length }} 条</span>
      <select :value="pageSize" @change="emit('page-size-change', Number($event.target.value))">
        <option :value="100">100条/页</option>
        <option :value="500">500条/页</option>
        <option :value="1000">1000条/页</option>
        <option :value="10000">10000条/页</option>
      </select>
      <button class="btn" :disabled="pageNo <= 1" @click="emit('page-change', pageNo - 1)">上一页</button>
      <span>第 {{ pageNo }} / {{ Math.max(1, Math.ceil((total || rows.length) / pageSize)) }} 页</span>
      <button class="btn" :disabled="pageNo >= Math.ceil((total || rows.length) / pageSize)" @click="emit('page-change', pageNo + 1)">下一页</button>
    </div>
  </div>
</template>

<style scoped>
/* 勾选列 —— sticky 到 left:0，层级高于所有用户设置的固定列（thead=5 / tbody=3 / tfoot=3），避免被覆盖 */
.checkbox-th,
.checkbox-td {
  width: 40px;
  min-width: 40px;
  text-align: center;
  position: sticky;
  left: 0;
  background: #f7fbff;
}

thead th.checkbox-th {
  z-index: 5;         /* > 用户固定列 thead(4) > 普通 th sticky top(2) */
  top: 0;             /* thead sticky 也生效 */
}

tbody td.checkbox-td {
  z-index: 3;         /* > 用户固定列 tbody(1) > 普通 td(0) */
  background: #fff;
}

.checkbox-th input[type="checkbox"],
.checkbox-td input[type="checkbox"] {
  width: 16px;
  height: 16px;
  cursor: pointer;
}

/* 操作列固定在最右侧（老逻辑保留：/操作/ 匹配的列） */
.action-col {
  position: sticky;
  right: 0;
  background: #fff;
  z-index: 2;
  min-width: 120px;
}

th.action-col {
  background: #f7fbff;
  z-index: 3;
}

/* sticky 表头（如未走 cellStyle 的兜底 —— cellStyle 会自己覆盖） */
thead th { position: sticky; top: 0; z-index: 2; user-select: none; }

/* 拖拽表头 + resize handle */
.th-title { pointer-events: none; }
.th-sort { pointer-events: none; margin-left: 4px; color: var(--primary, #409eff); }
.th-flag { margin-left: 4px; font-size: 10px; }
.th-dragging { opacity: 0.4; }
.th-resize { position: absolute; right: 0; top: 0; bottom: 0; width: 4px; cursor: col-resize; background: transparent; }
.th-resize:hover { background: #409eff; }

/* 左侧固定列 —— sticky 的 left / z-index / background 由 cellStyle 内联注入 */
.th-fixed, .td-fixed { border-right: 1px solid #f0f0f0; }
.th-fixed-last, .td-fixed-last { box-shadow: 2px 0 6px -2px rgba(0, 0, 0, 0.12); }
.tablebox tbody tr:hover .td-fixed,
.tablebox tbody tr:hover .checkbox-td { background: #f5f7fa !important; }
</style>
