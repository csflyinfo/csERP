<script setup>
defineProps({
  title: { type: String, default: '记录列表' },
  columns: { type: Array, required: true },
  rows: { type: Array, default: () => [] },
  pageNo: { type: Number, default: 1 },
  pageSize: { type: Number, default: 20 },
  total: { type: Number, default: 0 },
})

const emit = defineEmits(['field-setting', 'export', 'row-action', 'page-change', 'page-size-change'])
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
          <th v-for="col in columns" :key="col.key">{{ col.title }}</th>
        </tr>
        <tr v-for="(row, index) in rows" :key="index" @dblclick="emit('row-action', '查看', row)">
          <td v-for="col in columns" :key="col.key" :class="col.num ? 'num' : ''">
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
