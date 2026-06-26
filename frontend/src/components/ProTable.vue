<script setup>
defineProps({
  title: { type: String, default: '记录列表' },
  columns: { type: Array, required: true },
  rows: { type: Array, default: () => [] },
  pageNo: { type: Number, default: 1 },
  pageSize: { type: Number, default: 20 },
  total: { type: Number, default: 0 },
  sortField: { type: String, default: '' },
  sortOrder: { type: String, default: '' },
})

const emit = defineEmits(['field-setting', 'export', 'row-action', 'page-change', 'page-size-change', 'sort-change'])
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
        <tr>
          <!-- 勾选列头部（提供了slot时显示） -->
          <th v-if="$slots['checkbox-header']" class="checkbox-th">
            <slot name="checkbox-header"></slot>
          </th>
          <th v-for="col in columns" :key="col.key" :class="['sortable-th', { 'action-col': /操作/.test(col.title) }]" @click="emit('sort-change', col.key)">
            {{ col.title }}
            <span v-if="sortField === col.key">{{ sortOrder === 'desc' ? '↓' : '↑' }}</span>
          </th>
        </tr>
        <tr v-for="(row, index) in rows" :key="index" @dblclick="emit('row-action', '查看', row)">
          <!-- 勾选列单元格（提供了slot时显示） -->
          <td v-if="$slots['checkbox-cell']" class="checkbox-td">
            <slot name="checkbox-cell" :row="row" :row-index="index"></slot>
          </td>
          <td v-for="col in columns" :key="col.key" :class="[col.num ? 'num' : '', { 'action-col': /操作/.test(col.title) }]">
            <slot :name="col.key" :row="row">{{ row[col.key] }}</slot>
          </td>
        </tr>
      </table>
    </div>
    <div class="pager">
      <span>共 {{ total || rows.length }} 条</span>
      <select :value="pageSize" @change="emit('page-size-change', Number($event.target.value))">
        <option :value="10">10条/页</option>
        <option :value="20">20条/页</option>
        <option :value="50">50条/页</option>
      </select>
      <button class="btn" :disabled="pageNo <= 1" @click="emit('page-change', pageNo - 1)">上一页</button>
      <span>第 {{ pageNo }} / {{ Math.max(1, Math.ceil((total || rows.length) / pageSize)) }} 页</span>
      <button class="btn" :disabled="pageNo >= Math.ceil((total || rows.length) / pageSize)" @click="emit('page-change', pageNo + 1)">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.checkbox-th,
.checkbox-td {
  width: 40px;
  min-width: 40px;
  text-align: center;
  position: sticky;
  left: 0;
  background: #f7fbff;
  z-index: 2;
}

.checkbox-td {
  background: #fff;
}

.checkbox-th input[type="checkbox"],
.checkbox-td input[type="checkbox"] {
  width: 16px;
  height: 16px;
  cursor: pointer;
}

/* 操作列固定在最右侧 */
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
</style>
