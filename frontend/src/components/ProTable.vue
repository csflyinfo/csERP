<script setup>
defineProps({
  title: { type: String, default: '记录列表' },
  columns: { type: Array, required: true },
  rows: { type: Array, default: () => [] },
})

const emit = defineEmits(['field-setting', 'export', 'row-action'])
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
  </div>
</template>
