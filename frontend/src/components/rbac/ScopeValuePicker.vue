<script setup>
/**
 * 数据范围「指定」模式的候选值选择器（PRD-28 §5.3）。
 * 维度候选由后端按 scopeType 返回（仓库/客户/供应商/业务员/老板/分类/品牌）。
 * 查询只在点「查询」时触发（全局 QueryBar 约定）。
 */
import { ref, watch } from 'vue'
import { userApi } from '../../api/rbac.js'

const props = defineProps({
  visible: Boolean,
  scopeType: { type: String, default: '' },
  scopeName: { type: String, default: '' },
  // 已选项 [{id, name}]
  selected: { type: Array, default: () => [] },
})
const emit = defineEmits(['close', 'confirm'])

const keyword = ref('')
const candidates = ref([])
const loading = ref(false)
// 选中项 id -> {id,name}（保留候选列表外已选项的名字，名字未知时显示 id）
const chosen = ref(new Map())

watch(() => props.visible, async (v) => {
  if (!v) return
  keyword.value = ''
  chosen.value = new Map((props.selected || []).map(x => [x.id, x]))
  await search()
})

async function search() {
  if (!props.scopeType) return
  loading.value = true
  try {
    candidates.value = await userApi.scopeValues(props.scopeType, keyword.value.trim())
    // 候选里出现的已选项补全名字
    for (const c of candidates.value) {
      if (chosen.value.has(c.id)) chosen.value.set(c.id, c)
    }
  } catch (e) {
    candidates.value = []
    alert('候选加载失败：' + (e.message || e))
  } finally {
    loading.value = false
  }
}

function toggle(item) {
  if (chosen.value.has(item.id)) chosen.value.delete(item.id)
  else chosen.value.set(item.id, item)
  // 触发响应式替换
  chosen.value = new Map(chosen.value)
}

function confirm() {
  emit('confirm', [...chosen.value.values()])
}
</script>

<template>
  <div v-if="visible" class="mask" @click.self="emit('close')">
    <div class="dialog picker-dialog">
      <div class="dialog-head">
        <b>选择{{ scopeName }}（指定可见范围）</b>
        <button class="link-btn" @click="emit('close')">×</button>
      </div>
      <div class="dialog-body">
        <div class="picker-search">
          <input v-model="keyword" placeholder="输入名称关键字" @keydown.enter="search" />
          <button class="btn" @click="search">查询</button>
          <span class="muted">已选 {{ chosen.size }} 项</span>
        </div>
        <div class="picker-list">
          <div v-if="loading" class="empty">加载中…</div>
          <div v-else-if="!candidates.length" class="empty">无候选数据</div>
          <label v-for="c in candidates" :key="c.id" class="picker-item">
            <input type="checkbox" :checked="chosen.has(c.id)" @change="toggle(c)" />
            <span>{{ c.name }}</span>
            <span class="muted">{{ c.id }}</span>
          </label>
        </div>
      </div>
      <div class="dialog-foot">
        <button class="btn" @click="emit('close')">取消</button>
        <button class="btn primary" @click="confirm">确定</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.picker-dialog { width: 560px; }
.picker-search { display: flex; gap: 8px; align-items: center; }
.picker-search input {
  flex: 1; padding: 5px 10px; border: 1px solid var(--line);
  border-radius: 6px; font-size: 13px; height: 30px;
}
.muted { color: #909399; font-size: 12px; }
.picker-list {
  max-height: 360px; overflow-y: auto;
  border: 1px solid var(--line-soft, #eef3f8); border-radius: 8px; margin-top: 8px;
}
.picker-item {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 12px; border-bottom: 1px solid #f1f5fa; font-size: 13px; cursor: pointer;
}
.picker-item:hover { background: #f6faff; }
.picker-item .muted { margin-left: auto; font-family: var(--font-mono); font-size: 11px; }
.empty { text-align: center; color: #909399; padding: 30px; font-size: 12px; }
.link-btn { border: 0; background: transparent; color: var(--primary); cursor: pointer; font-size: 14px; }
.dialog-body { padding: 14px 16px; }
.dialog-foot {
  padding: 10px 16px; border-top: 1px solid var(--line);
  display: flex; justify-content: flex-end; gap: 8px;
}
.dialog-foot .btn { height: 28px; padding: 0 14px; }
.mask {
  position: fixed; inset: 0; background: rgba(15, 35, 60, 0.28);
  display: grid; place-items: center; z-index: 220;
}
.dialog {
  width: 480px; background: #fff; border-radius: 8px;
  overflow: hidden; box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
}
.dialog-head {
  padding: 10px 16px; border-bottom: 1px solid var(--line);
  display: flex; justify-content: space-between; align-items: center; font-size: 14px;
}
</style>
