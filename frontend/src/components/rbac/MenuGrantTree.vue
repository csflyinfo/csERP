<script setup>
/**
 * 角色授权用菜单树（PRD-28 §10.2）：菜单勾选 + 页面功能点懒加载勾选。
 * 数据源 /system/menu/grant-tree（已排除 admin_only/STOPPED/空目录）。
 * - 勾选子节点时由父组件级联勾选祖先目录（保证菜单层级完整）；
 * - 功能点在点开「功能点」面板时按 menuId 懒加载。
 */
import { ref } from 'vue'
import { permApi } from '../../api/rbac.js'

defineOptions({ name: 'MenuGrantTree' })

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  menuChecked: { type: Set, default: () => new Set() },
  funcChecked: { type: Set, default: () => new Set() },
  disabled: { type: Boolean, default: false },
  depth: { type: Number, default: 0 },
})
const emit = defineEmits(['toggle-menu', 'toggle-func'])

// 功能点面板状态：menuId -> {open, loading, funcs}
const panels = ref({})

async function togglePanel(node) {
  const cur = panels.value[node.menuId]
  if (cur?.open) {
    panels.value[node.menuId] = { ...cur, open: false }
    return
  }
  if (!cur) {
    panels.value[node.menuId] = { open: true, loading: true, funcs: [] }
    try {
      panels.value[node.menuId].funcs = await permApi.funcsByMenu(node.menuId)
    } catch (e) {
      alert('功能点加载失败：' + (e.message || e))
    } finally {
      panels.value[node.menuId].loading = false
    }
  } else {
    panels.value[node.menuId] = { ...cur, open: true }
  }
}
</script>

<template>
  <ul class="grant-tree" :class="{ root: depth === 0 }">
    <li v-for="n in nodes" :key="n.menuId">
      <div class="node-line" :style="{ paddingLeft: depth * 18 + 'px' }">
        <span
          v-if="n.children?.length"
          class="twisty"
          :title="n.menuType === 'PAGE' ? '' : ''"
        ></span>
        <span v-else class="twisty placeholder"></span>
        <input
          type="checkbox"
          :checked="menuChecked.has(n.menuId)"
          :disabled="disabled"
          @change="emit('toggle-menu', n, $event.target.checked)"
        />
        <span class="node-name" :class="{ dir: n.menuType === 'DIR' }">{{ n.name }}</span>
        <span class="type-tag" :class="n.menuType === 'DIR' ? 'tag-dir' : 'tag-page'">
          {{ n.menuType === 'DIR' ? '目录' : '页面' }}
        </span>
        <span class="muted code">{{ n.code }}</span>
        <button
          v-if="n.menuType === 'PAGE'"
          type="button"
          class="btn func-btn"
          :disabled="disabled"
          @click="togglePanel(n)"
        >
          功能点
        </button>
      </div>

      <!-- 该页面下的功能点勾选 -->
      <div
        v-if="n.menuType === 'PAGE' && panels[n.menuId]?.open"
        class="func-panel"
        :style="{ marginLeft: depth * 18 + 36 + 'px' }"
      >
        <div v-if="panels[n.menuId].loading" class="muted">加载中…</div>
        <div v-else-if="!panels[n.menuId].funcs.length" class="muted">该页面无注册功能点</div>
        <label v-for="f in panels[n.menuId].funcs" :key="f.funcId" class="func-item">
          <input
            type="checkbox"
            :checked="funcChecked.has(f.funcCode)"
            :disabled="disabled || !menuChecked.has(n.menuId)"
            @change="emit('toggle-func', f.funcCode, $event.target.checked)"
          />
          <span :title="f.funcCode">{{ f.funcName || f.funcCode }}</span>
          <span class="muted">{{ f.funcCode }}</span>
        </label>
      </div>

      <MenuGrantTree
        v-if="n.children?.length"
        :nodes="n.children"
        :menu-checked="menuChecked"
        :func-checked="funcChecked"
        :disabled="disabled"
        :depth="depth + 1"
        @toggle-menu="(node, checked) => emit('toggle-menu', node, checked)"
        @toggle-func="(code, checked) => emit('toggle-func', code, checked)"
      />
    </li>
  </ul>
</template>

<style scoped>
.grant-tree { list-style: none; margin: 0; padding: 0; }
.node-line {
  display: flex; align-items: center; gap: 8px;
  height: 30px; border-radius: 6px; padding-right: 8px;
}
.node-line:hover { background: #f6faff; }
.twisty { width: 14px; display: inline-block; color: #94a3b8; }
.twisty.placeholder { visibility: hidden; }
.node-name { font-size: 13px; color: #1f2d3d; }
.node-name.dir { font-weight: 700; color: #12385f; }
.type-tag { font-size: 10px; border-radius: 4px; padding: 0 5px; line-height: 16px; }
.tag-dir { background: #eef2ff; color: #4f46e5; border: 1px solid #c7d2fe; }
.tag-page { background: #f0f9eb; color: #5b8c2d; border: 1px solid #c2e7a8; }
.muted { color: #94a3b8; font-size: 11px; }
.code { font-family: var(--font-mono); }
.func-btn { height: 22px; padding: 0 8px; font-size: 11px; margin-left: auto; }
.func-panel {
  display: flex; flex-wrap: wrap; gap: 4px 14px;
  padding: 4px 0 6px;
}
.func-item {
  display: inline-flex; align-items: center; gap: 5px;
  font-size: 12px; color: #475569;
  border: 1px solid var(--line-soft, #eef3f8); border-radius: 6px;
  padding: 2px 8px; background: #fbfdff;
}
.func-item .muted { font-family: var(--font-mono); }
</style>
