<script setup>
/**
 * 模块菜单管理（PRD-28 §10.3，MENU-001~012）。
 * 仅 SYS_ADMIN 可进入（路由守卫 + 后端每个写接口双重硬校验）：
 *  - 改名：PUT /system/menu-manage/{id}/name（置 name_customized，重启抗同步）
 *  - 换上级：PUT /system/menu-manage/{id}/parent（层级≤3、目录/页面层位、防环、空目录二次确认）
 *  - 排序：PUT /system/menu-manage/sort（同一上级整组重排）
 *  - 恢复默认：单节点 /reset、整树 /reset-all（整树二次确认）
 */
import { ref, computed, onMounted } from 'vue'
import { permApi, menuManageApi } from '../../api/rbac.js'

const APP_TABS = [
  { value: 'ERP', label: 'ERP 端' },
  { value: 'WMS_PDA', label: '仓储 PDA' },
  { value: 'DRIVER', label: '司机端' },
]
const TYPE_LABELS = { DIR: '目录', PAGE: '页面', GROUP: '分组' }

const appTab = ref('ERP')
const tree = ref([])
const loading = ref(false)
const selectedId = ref('')
const collapsed = ref(new Set())

async function loadTree(appType = appTab.value) {
  loading.value = true
  try {
    tree.value = await permApi.menuTree(appType)
  } catch (e) {
    tree.value = []
    alert('菜单树加载失败：' + (e.message || e))
  } finally {
    loading.value = false
  }
}

async function switchTab(t) {
  appTab.value = t
  selectedId.value = ''
  editForm.name = ''
  await loadTree(t)
}

// ================= 树展开/拍平 =================
function isExpanded(id) { return !collapsed.value.has(id) }
function toggleCollapse(id) {
  const next = new Set(collapsed.value)
  next.has(id) ? next.delete(id) : next.add(id)
  collapsed.value = next
}

const flatRows = computed(() => {
  const out = []
  const walk = (nodes, depth) => {
    for (const n of nodes) {
      out.push({ node: n, depth })
      if (n.children?.length && isExpanded(n.menuId)) walk(n.children, depth + 1)
    }
  }
  walk(tree.value, 0)
  return out
})

function findNode(id, nodes = tree.value) {
  for (const n of nodes) {
    if (n.menuId === id) return n
    if (n.children?.length) {
      const hit = findNode(id, n.children)
      if (hit) return hit
    }
  }
  return null
}
function siblingsOf(node) {
  if (!node.parentId) return tree.value
  return findNode(node.parentId)?.children || []
}

// ================= 选中 / 编辑表单 =================
const editName = ref('')
const editParentId = ref('')
const savingName = ref(false)
const savingMove = ref(false)

const selected = computed(() => (selectedId.value ? findNode(selectedId.value) : null))
const rowIndexInSiblings = (node) => siblingsOf(node).findIndex(x => x.menuId === node.menuId)

function selectNode(node) {
  selectedId.value = node.menuId
  editName.value = node.name || ''
  editParentId.value = node.parentId || ''
}

/** 换上级候选：根 + 所有目录；排除自身与子孙（防环前端兜底）；深层目录标注层级。 */
const parentOptions = computed(() => {
  const node = selected.value
  if (!node) return []
  const descendantIds = new Set()
  const collect = (n) => {
    descendantIds.add(n.menuId)
    ;(n.children || []).forEach(collect)
  }
  collect(node)
  const opts = [{ menuId: '', label: '一级菜单（根）', depth: 0, disabled: false }]
  const walk = (nodes, depth) => {
    for (const n of nodes) {
      if (n.menuType !== 'DIR') continue
      const isSelf = descendantIds.has(n.menuId)
      opts.push({
        menuId: n.menuId,
        label: '　'.repeat(depth) + n.name,
        depth,
        disabled: isSelf || n.status === 'STOPPED',
      })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  }
  walk(tree.value, 0)
  return opts
})

// ================= 改名 =================
async function saveName() {
  const node = selected.value
  if (!node) return
  const name = editName.value.trim()
  if (!name) { alert('菜单名称不能为空'); return }
  if (name === node.name) return
  savingName.value = true
  try {
    await menuManageApi.rename(node.menuId, name)
    await loadTree()
    const fresh = findNode(node.menuId)
    if (fresh) selectNode(fresh)
  } catch (e) {
    alert('改名失败：' + (e.message || e))
  } finally {
    savingName.value = false
  }
}

// ================= 换上级（含空目录二次确认） =================
async function saveMove(confirmAgain = false) {
  const node = selected.value
  if (!node) return
  if (node.adminOnly) { alert('系统内置专属菜单不允许移动'); editParentId.value = node.parentId || ''; return }
  if (node.status === 'STOPPED') { alert('已停用（代码已删除）的菜单不允许移动'); editParentId.value = node.parentId || ''; return }
  const newParentId = editParentId.value || null
  if ((node.parentId || null) === newParentId) return
  savingMove.value = true
  try {
    const r = await menuManageApi.move(node.menuId, newParentId, confirmAgain)
    if (r?.needConfirm) {
      savingMove.value = false
      if (window.confirm(r.warning + '\n\n点击「确定」继续移动，「取消」放弃。')) {
        await saveMove(true)
        return
      }
      editParentId.value = node.parentId || ''
      return
    }
    await loadTree()
    const fresh = findNode(node.menuId)
    if (fresh) selectNode(fresh)
    if (r?.warning) alert(r.warning)
  } catch (e) {
    alert('移动失败：' + (e.message || e))
    editParentId.value = node.parentId || ''
  } finally {
    savingMove.value = false
  }
}

// ================= 同层排序 =================
async function moveSibling(node, delta) {
  const siblings = siblingsOf(node)
  const idx = siblings.findIndex(x => x.menuId === node.menuId)
  const target = idx + delta
  if (idx < 0 || target < 0 || target >= siblings.length) return
  const reordered = [...siblings]
  ;[reordered[idx], reordered[target]] = [reordered[target], reordered[idx]]
  // 乐观更新（失败回滚由整树重载兜底）
  const applyLocal = (list) => { list.splice(0, list.length, ...reordered) }
  if (node.parentId) applyLocal(findNode(node.parentId).children)
  else applyLocal(tree.value)
  try {
    // 同一上级整组 10 步进重排，保证相对顺序确定
    const items = reordered.map((n, i) => ({ menuId: n.menuId, sortOrder: (i + 1) * 10 }))
    await menuManageApi.sort(items)
    await loadTree()
    const fresh = findNode(node.menuId)
    if (fresh) selectedId.value = fresh.menuId
  } catch (e) {
    alert('排序失败：' + (e.message || e))
    await loadTree()
  }
}

// ================= 恢复默认 =================
const resetting = ref(false)
async function resetOne() {
  const node = selected.value
  if (!node) return
  if (!window.confirm(`确认把菜单「${node.name}」恢复为代码默认的名称、上级与排序？`)) return
  resetting.value = true
  try {
    await menuManageApi.resetOne(node.menuId)
    await loadTree()
    const fresh = findNode(node.menuId)
    if (fresh) selectNode(fresh)
  } catch (e) {
    alert('恢复失败：' + (e.message || e))
  } finally {
    resetting.value = false
  }
}

async function resetAll() {
  if (!window.confirm('确认恢复整棵菜单树为代码默认？所有自定义改名、移动、排序都将丢失。')) return
  if (!window.confirm('第二次确认：真的要清除本端全部菜单自定义吗？建议先通知在线用户。')) return
  try {
    await menuManageApi.resetAll()
    selectedId.value = ''
    await loadTree()
    alert('整树已恢复默认并完成一次元数据同步')
  } catch (e) {
    alert('整树恢复失败：' + (e.message || e))
  }
}

onMounted(() => loadTree('ERP'))
</script>

<template>
  <div class="menu-page">
    <div class="card menu-mid">
      <div class="page-head">
        <div class="app-tabs">
          <button
            v-for="t in APP_TABS" :key="t.value"
            class="app-tab" :class="{ active: appTab === t.value }"
            @click="switchTab(t.value)"
          >{{ t.label }}</button>
        </div>
        <div class="head-tools">
          <span class="muted">改名/移动/排序立即对所有用户生效；代码升级后保留自定义（恢复默认除外）</span>
          <button class="btn danger" @click="resetAll">整树恢复默认</button>
        </div>
      </div>

      <div v-if="loading" class="empty-hint">菜单树加载中…</div>
      <div v-else-if="!flatRows.length" class="empty-hint">该端暂无菜单定义</div>
      <div v-else class="tree-scroll">
        <div
          v-for="row in flatRows" :key="row.node.menuId"
          class="tree-row"
          :class="{ selected: row.node.menuId === selectedId, stopped: row.node.status === 'STOPPED' }"
          :style="{ paddingLeft: row.depth * 20 + 10 + 'px' }"
          @click="selectNode(row.node)"
        >
          <span
            class="twisty"
            :class="{ hidden: !row.node.children?.length }"
            @click.stop="row.node.children?.length && toggleCollapse(row.node.menuId)"
          >{{ isExpanded(row.node.menuId) ? '▾' : '▸' }}</span>
          <span class="tree-name">{{ row.node.name }}</span>
          <span class="tag" :class="row.node.menuType === 'DIR' ? 'tag-dir' : 'tag-page'">
            {{ TYPE_LABELS[row.node.menuType] || row.node.menuType }}
          </span>
          <span v-if="row.node.adminOnly" class="tag tag-admin">超管专属</span>
          <span v-if="row.node.status === 'STOPPED'" class="tag tag-stopped">已停用</span>
          <span
            v-if="row.node.nameCustomized || row.node.parentCustomized || row.node.sortCustomized"
            class="tag tag-custom" title="名称/上级/排序存在自定义"
          >自定义</span>
          <span class="tree-code mono">{{ row.node.code }}</span>
          <span class="sort-actions">
            <button
              class="sort-btn" title="上移"
              :disabled="rowIndexInSiblings(row.node) === 0 || row.node.status === 'STOPPED'"
              @click.stop="moveSibling(row.node, -1)"
            >↑</button>
            <button
              class="sort-btn" title="下移"
              :disabled="rowIndexInSiblings(row.node) >= siblingsOf(row.node).length - 1 || row.node.status === 'STOPPED'"
              @click.stop="moveSibling(row.node, 1)"
            >↓</button>
          </span>
        </div>
      </div>
    </div>

    <!-- 右编辑面板 -->
    <div class="card menu-right">
      <div v-if="!selected" class="empty-hint big">
        选择左侧菜单进行编辑。<br/><br/>
        菜单由代码注册，本页只支持改名、换上级、同层排序与恢复默认；<br/>
        新增/删除菜单需发版（被删菜单显示为「已停用」）。
      </div>
      <template v-else>
        <div class="panel-head">
          <b>{{ selected.name }}</b>
          <span class="mono muted">{{ selected.code }}</span>
        </div>

        <!-- 改名 -->
        <div class="panel-section">
          <div class="section-label">
            菜单名称
            <span v-if="selected.nameCustomized" class="tag tag-custom">已自定义</span>
          </div>
          <div class="inline-edit">
            <input v-model="editName" maxlength="100" placeholder="同一上级下不可重名" />
            <button class="btn primary" :disabled="savingName" @click="saveName">
              {{ savingName ? '保存中…' : '保存名称' }}
            </button>
          </div>
        </div>

        <!-- 换上级 -->
        <div class="panel-section">
          <div class="section-label">
            所属上级
            <span v-if="selected.parentCustomized" class="tag tag-custom">已自定义</span>
            <span v-if="selected.adminOnly" class="tag tag-admin">超管专属不可移动</span>
            <span v-if="selected.status === 'STOPPED'" class="tag tag-stopped">已停用不可移动</span>
          </div>
          <select
            v-model="editParentId"
            :disabled="selected.adminOnly || selected.status === 'STOPPED'"
            @change="saveMove(false)"
          >
            <option
              v-for="o in parentOptions" :key="o.menuId || 'ROOT'"
              :value="o.menuId" :disabled="o.disabled"
            >{{ o.label }}</option>
          </select>
          <div class="muted hint">最多三级：目录只能在第 1/2 层，页面只能在第 2/3 层；移动后原目录若变空将对所有用户隐藏（会二次确认）。</div>
        </div>

        <!-- 只读元数据 -->
        <div class="panel-section">
          <div class="section-label">菜单元数据（只读）</div>
          <div class="meta-grid">
            <label>类型</label><span>{{ TYPE_LABELS[selected.menuType] || selected.menuType }}</span>
            <label>编码</label><span class="mono">{{ selected.code }}</span>
            <label>路由</label><span class="mono">{{ selected.path || '—' }}</span>
            <label>组件</label><span class="mono">{{ selected.componentPath || '—' }}</span>
            <label>排序值</label>
            <span>
              {{ selected.sortOrder }}
              <span v-if="selected.sortCustomized" class="tag tag-custom">已自定义</span>
            </span>
            <label>可见</label><span>{{ selected.visible === false ? '隐藏' : '显示' }}</span>
            <label>状态</label><span>{{ selected.status === 'STOPPED' ? '已停用（代码已删除）' : '正常' }}</span>
          </div>
        </div>

        <div class="panel-foot">
          <button class="btn" :disabled="resetting" @click="resetOne">
            {{ resetting ? '恢复中…' : '恢复该菜单默认' }}
          </button>
          <span class="muted">清除该节点的名称/上级/排序自定义，立即按代码默认重算</span>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.menu-page { display: flex; gap: 8px; height: 100%; min-height: 0; }
.menu-mid { flex: 1; min-width: 0; display: flex; flex-direction: column; padding: 0; }
.page-head {
  display: flex; justify-content: space-between; align-items: center;
  padding: 10px 14px; border-bottom: 1px solid var(--line); gap: 12px;
}
.app-tabs { display: flex; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
.app-tab { border: 0; background: #fff; padding: 5px 12px; font-size: 12px; cursor: pointer; color: #475569; }
.app-tab.active { background: var(--primary); color: #fff; }
.head-tools { display: flex; align-items: center; gap: 10px; }
.head-tools .btn { height: 30px; padding: 0 12px; }
.tree-scroll { flex: 1; overflow-y: auto; padding: 8px; min-height: 0; }
.tree-row {
  display: flex; align-items: center; gap: 8px; height: 32px;
  border-radius: 7px; padding-right: 10px; cursor: pointer; font-size: 13px;
}
.tree-row:hover { background: #f6faff; }
.tree-row.selected { background: #eaf4ff; }
.tree-row.stopped { color: #94a3b8; }
.tree-row.stopped .tree-name { text-decoration: line-through; }
.twisty { width: 16px; color: #94a3b8; text-align: center; flex: none; }
.twisty.hidden { visibility: hidden; }
.tree-name { color: #1f2d3d; font-weight: 600; }
.tree-code { color: #94a3b8; font-size: 11px; }
.sort-actions { margin-left: auto; display: flex; gap: 2px; }
.sort-btn {
  width: 22px; height: 22px; border: 1px solid var(--line); background: #fff;
  border-radius: 5px; cursor: pointer; color: #475569; font-size: 12px;
}
.sort-btn:disabled { opacity: .35; cursor: not-allowed; }
.tag { font-size: 10px; border-radius: 4px; padding: 0 5px; line-height: 17px; white-space: nowrap; }
.tag-dir { background: #eef2ff; color: #4f46e5; border: 1px solid #c7d2fe; }
.tag-page { background: #f0f9eb; color: #5b8c2d; border: 1px solid #c2e7a8; }
.tag-admin { background: #fef0f0; color: #cf1322; border: 1px solid #fca5a5; }
.tag-stopped { background: #f4f4f5; color: #71717a; border: 1px solid #e4e4e7; }
.tag-custom { background: #fff7e6; color: #d46b08; border: 1px solid #ffd591; }
.empty-hint { color: #94a3b8; font-size: 12px; text-align: center; padding: 24px; }
.empty-hint.big { margin: auto; line-height: 1.8; }

/* 右面板 */
.menu-right { width: 420px; flex: none; display: flex; flex-direction: column; padding: 14px; gap: 16px; overflow-y: auto; min-height: 0; }
.panel-head { display: flex; flex-direction: column; gap: 4px; border-bottom: 1px solid var(--line-soft, #eef3f8); padding-bottom: 10px; }
.panel-head b { font-size: 15px; color: #12385f; }
.panel-section { display: flex; flex-direction: column; gap: 7px; }
.section-label { font-size: 13px; font-weight: 700; color: #12385f; display: flex; align-items: center; gap: 6px; }
.inline-edit { display: flex; gap: 8px; }
.inline-edit input {
  flex: 1; padding: 6px 10px; border: 1px solid var(--line); border-radius: 6px; font-size: 13px;
}
.inline-edit .btn { height: 32px; }
.panel-section select {
  padding: 6px 10px; border: 1px solid var(--line); border-radius: 6px; font-size: 13px; background: #fff;
}
.panel-section select:disabled { background: #f8fafc; color: #64748b; }
.meta-grid { display: grid; grid-template-columns: 64px 1fr; gap: 6px 12px; font-size: 12.5px; }
.meta-grid label { color: #94a3b8; }
.meta-grid span { color: #334155; word-break: break-all; }
.hint { line-height: 1.6; }
.panel-foot {
  margin-top: auto; border-top: 1px solid var(--line-soft, #eef3f8); padding-top: 12px;
  display: flex; align-items: center; gap: 10px;
}
.muted { color: #94a3b8; font-size: 12px; }
.mono { font-family: var(--font-mono); }
</style>
