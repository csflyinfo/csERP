<script setup>
import { computed } from 'vue'
import QueryBar from '../components/QueryBar.vue'
import ProTable from '../components/ProTable.vue'

const props = defineProps({
  current: { type: String, required: true },
  currentName: { type: String, required: true },
})

const emit = defineEmits(['create'])

const genericColumns = [
  { key: 'code', title: '编码' },
  { key: 'name', title: '名称' },
  { key: 'group', title: '类型/分组' },
  { key: 'status', title: '状态' },
  { key: 'action', title: '操作' },
]
const genericRows = computed(() => [{ code: `${props.current.toUpperCase()}001`, name: `${props.currentName}示例`, group: '默认', status: '正常', action: '编辑' }])
</script>

<template>
  <section>
    <QueryBar :fields="['关键字', '状态']" />
    <ProTable :title="currentName + '列表'" :columns="genericColumns" :rows="genericRows">
      <template #status="{ row }"><span class="badge ok">{{ row.status }}</span></template>
      <template #action="{ row }"><span class="link" @click="emit('create')">{{ row.action }}</span></template>
    </ProTable>
  </section>
</template>
