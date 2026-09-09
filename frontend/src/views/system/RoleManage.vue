<script setup>
/**
 * 角色管理（PRD-28 §10.2）三栏布局：
 * 左：角色列表（搜索/新建/复制/删除，内置标记，用户数）
 * 中：菜单 + 功能点授权树（按端切换，含全局功能区）
 * 右：基本信息 / 敏感字段 / 数据范围 三个页签
 *
 * 保存分两条通道：基本信息走 /create|/update；菜单/功能/字段/数据范围四分区走 /grants 全量替换。
 * 内置角色（isSystem=TRUE）：仅备注与数据范围可改，其余分区禁用。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { roleApi, permApi } from '../../api/rbac.js'
import MenuGrantTree from '../../components/rbac/MenuGrantTree.vue'
import DataScopeEditor from '../../components/rbac/DataScopeEditor.vue'

const GROUP_LABELS = { SYS: '系统', BIZ: '业务', WMS: '仓储', TMS: '运输' }
const APP_LABELS = { ERP: 'ERP', WMS_PDA: '仓储 PDA', DRIVER: '司机端', ALL: '全部端' }
const FIELD_GROUP_LABELS = { PRICE: '价格类', AMOUNT: '金额/往来类', STOCK: '库存类', CONTACT: '联系方式类' }
const APP_TABS = [
  { value: 'ERP', label: 'ERP 端' },
  { value: 'WMS_PDA', label: '仓储 PDA' },
  { value: 'DRIVER', label: '司机端' },
]

// ================= 左栏：角色列表 =================
const roles = ref([])
const listLoading = ref(false)
const keywordInput = ref('')
const keywordApplied = ref('')
const selectedId = ref('')

async function loadRoles(keepSelect = true) {
  listLoading.value = true
  try {
    const data = await roleApi.page({ pageNo: 1, pageSize: 200, filters: {} })
    let list = data?.records || []
    if (keywordApplied.value) {
      const kw = keywordApplied.value.toLowerCase()
      list = list.filter(r =>
        (r.roleCode || '').toLowerCase().includes(kw) || (r.roleName || '').toLowerCase().includes(kw))
    }
    roles.value = list
    if (!keepSelect || !list.some(r => r.roleId === selectedId.value)) {
      selectedId.value = list[0]?.roleId || ''
      if (selectedId.value) await loadDetail(selectedId.value)
    }
  } catch (e) {
    alert('角色列表加载失败：' + (e.message || e))
  } finally {
    listLoading.value = false
  }
}

function doSearch() {
  keywordApplied.value = keywordInput.value.trim()
  loadRoles(true)
}

const selectedRole = computed(() => roles.value.find(r => r.roleId === selectedId.value) || null)
const builtIn = computed(() => selectedRole.value?.isSystem === true)

async function selectRole(id) {
  if (id === selectedId.value) return
  if (dirty.value && !window.confirm('当前角色的授权修改尚未保存，切换后将丢失，确认切换？')) return
  selectedId.value = id
  await loadDetail(id)
}

// ================= 中栏：授权树状态 =================
const appTab = ref('ERP')
const treeCache = reactive({}) // appType -> 节点数组
const treeLoading = ref(false)
const globalFuncs = ref([])
const fields = ref([])

// 当前角色授权集合（跨端共享，切端不清空）
const menuChecked = ref(new Set())
const funcChecked = ref(new Set())
const fieldChecked = ref(new Set())
const dataScopes = ref([])
const detail = ref(null)
const dirty = ref(false)
const savingGrants = ref(false)

async function loadTree(appType, force = false) {
  if (force) delete treeCache[appType]
  if (treeCache[appType]) return treeCache[appType]
  treeLoading.value = true
  try {
    treeCache[appType] = await permApi.grantTree(appType)
  } catch (e) {
    treeCache[appType] = []
    alert('授权树加载失败：' + (e.message || e))
  } finally {
    treeLoading.value = false
  }
  return treeCache[appType]
}

async function switchTab(appType) {
  appTab.value = appType
  await loadTree(appType)
}

async function loadDetail(roleId) {
  try {
    const d = await roleApi.detail(roleId)
    detail.value = d
    menuChecked.value = new Set(d.menuIds || [])
    funcChecked.value = new Set(d.funcCodes || [])
    fieldChecked.value = new Set(d.fieldCodes || [])
    dataScopes.value = (d.dataScopes || []).map(s => ({ ...s }))
    dirty.value = false
    Object.assign(basicForm, {
      roleCode: d.roleCode || '',
      roleName: d.roleName || '',
      roleGroup: d.roleGroup || 'BIZ',
      appType: d.appType || 'ERP',
      remark: d.remark || '',
    })
    // 首次选中时把三个端的树与全局功能、字段定义都拉齐（保存授权时需要跨端校验）
    await Promise.all([
      loadTree('ERP'), loadTree('WMS_PDA'), loadTree('DRIVER'),
      (async () => { if (!globalFuncs.value.length) globalFuncs.value = await permApi.funcsByMenu('M_GLOBAL_FUNC') })(),
      (async () => { if (!fields.value.length) fields.value = await permApi.fields() })(),
    ])
    appTab.value = 'ERP'
  } catch (e) {
    alert('角色详情加载失败：' + (e.message || e))
    detail.value = null
  }
}

// 树索引：menuId -> 节点（含 parentId/code/children），跨三端合并
function buildIndex(nodes, map = new Map()) {
  for (const n of nodes) {
    map.set(n.menuId, n)
    if (n.children?.length) buildIndex(n.children, map)
  }
  return map
}
const treeIndex = computed(() => {
  const map = new Map()
  for (const app of Object.keys(treeCache)) buildIndex(treeCache[app] || [], map)
  return map
})

/** 勾选菜单：级联补勾所有祖先目录；取消勾选：连带取消全部子孙菜单及其页面功能点。 */
function onToggleMenu(node, checked) {
  const nextMenus = new Set(menuChecked.value)
  if (checked) {
    nextMenus.add(node.menuId)
    let pid = node.parentId
    const guard = new Set()
    while (pid && guard.add(pid)) {
      nextMenus.add(pid)
      const parent = treeIndex.value.get(pid)
      if (!parent) break
      pid = parent.parentId
    }
  } else {
    const collectMenuIds = (n, acc) => {
      acc.push(n.menuId)
      ;(n.children || []).forEach(c => collectMenuIds(c, acc))
      return acc
    }
    for (const id of collectMenuIds(node, [])) nextMenus.delete(id)

    // 功能点编码 = 菜单 code + '.' + action，被摘掉页面的功能点一并取消
    const prefixes = []
    const walk = (n) => {
      if (n.menuType === 'PAGE' && n.code) prefixes.push(n.code + '.')
      ;(n.children || []).forEach(walk)
    }
    walk(node)
    if (prefixes.length) {
      const nextFuncs = new Set(funcChecked.value)
      for (const code of [...nextFuncs]) {
        if (prefixes.some(p => code.startsWith(p))) nextFuncs.delete(code)
      }
      funcChecked.value = nextFuncs
    }
  }
  menuChecked.value = nextMenus
  dirty.value = true
}

function onToggleFunc(funcCode, checked) {
  const next = new Set(funcChecked.value)
  if (checked) next.add(funcCode)
  else next.delete(funcCode)
  funcChecked.value = next
  dirty.value = true
}

function onToggleField(code, checked) {
  const next = new Set(fieldChecked.value)
  if (checked) next.add(code)
  else next.delete(code)
  fieldChecked.value = next
  dirty.value = true
}

function toggleGroupFields(groupCodes, checked) {
  const next = new Set(fieldChecked.value)
  for (const c of groupCodes) checked ? next.add(c) : next.delete(c)
  fieldChecked.value = next
  dirty.value = true
}

// ================= 保存授权 =================
async function saveGrants() {
  if (!detail.value) return
  // 跨端兜底：功能点必须与其菜单同时授权（菜单未勾的功能点不下发，避免授权悬空）
  const menuCodeChecked = new Set()
  for (const id of menuChecked.value) {
    const n = treeIndex.value.get(id)
    if (n?.code) menuCodeChecked.add(n.code)
  }
  const funcCodes = [...funcChecked.value].filter(code => {
    const dot = code.lastIndexOf('.')
    if (dot < 0) return true
    const menuCode = code.substring(0, dot)
    // global.* 挂在 M_GLOBAL_FUNC 占位菜单上，不受页面菜单勾选约束
    if (menuCode === 'global') return true
    // 树中找不到归属（如已停用）的功能点也不下发
    return menuCodeChecked.has(menuCode)
  })

  const body = builtIn.value
    // 内置角色：后端静默接受数据范围，菜单/功能/字段由种子管理
    ? { roleId: selectedId.value, dataScopes: dataScopes.value }
    : {
        roleId: selectedId.value,
        menuIds: [...menuChecked.value],
        funcCodes,
        fieldCodes: [...fieldChecked.value],
        dataScopes: dataScopes.value,
      }
  savingGrants.value = true
  try {
    await roleApi.grants(body)
    dirty.value = false
    await loadDetail(selectedId.value)
    alert(builtIn.value ? '内置角色数据范围已保存（菜单/功能/字段由系统种子管理）' : '角色授权已保存')
  } catch (e) {
    alert('保存授权失败：' + (e.message || e))
  } finally {
    savingGrants.value = false
  }
}

// ================= 右栏：基本信息 =================
const rightTab = ref('basic')
const basicForm = reactive({ roleCode: '', roleName: '', roleGroup: 'BIZ', appType: 'ERP', remark: '' })
const savingBasic = ref(false)
const CODE_RE = /^[A-Z][A-Z0-9_]{1,49}$/

async function saveBasic() {
  if (!detail.value) return
  if (builtIn.value) {
    // 内置角色：只保存备注（数据范围跟随「保存授权」）
    savingBasic.value = true
    try {
      await roleApi.update({ roleId: selectedId.value, remark: basicForm.remark, dataScopes: dataScopes.value })
      await loadRoles(true)
      alert('内置角色备注已保存')
    } catch (e) {
      alert('保存失败：' + (e.message || e))
    } finally {
      savingBasic.value = false
    }
    return
  }
  const code = basicForm.roleCode.trim().toUpperCase()
  if (!CODE_RE.test(code)) { alert('角色编码需为 2~50 位大写字母/数字/下划线，且以字母开头'); return }
  if (!basicForm.roleName.trim()) { alert('角色名称不能为空'); return }
  savingBasic.value = true
  try {
    await roleApi.update({
      roleId: selectedId.value,
      roleCode: code,
      roleName: basicForm.roleName.trim(),
      roleGroup: basicForm.roleGroup,
      appType: basicForm.appType,
      remark: basicForm.remark,
    })
    await loadRoles(true)
    await loadDetail(selectedId.value)
    alert('基本信息已保存')
  } catch (e) {
    alert('保存失败：' + (e.message || e))
  } finally {
    savingBasic.value = false
  }
}

// ================= 新建 / 复制 / 删除 =================
const createDlg = ref(null)
function openCreate() {
  createDlg.value = { mode: 'create', roleId: '', roleCode: '', roleName: '',
    roleGroup: 'BIZ', appType: 'ERP', remark: '', error: '', saving: false }
}
function openCopy() {
  if (!selectedRole.value) return
  createDlg.value = {
    mode: 'copy',
    sourceRoleId: selectedId.value,
    roleCode: (selectedRole.value.roleCode || '') + '_COPY',
    roleName: (selectedRole.value.roleName || '') + '副本',
    roleGroup: selectedRole.value.roleGroup, appType: selectedRole.value.appType,
    remark: '', error: '', saving: false,
  }
}
async function submitCreate() {
  const d = createDlg.value
  d.error = ''
  const code = d.roleCode.trim().toUpperCase()
  if (!CODE_RE.test(code)) { d.error = '角色编码需为 2~50 位大写字母/数字/下划线，且以字母开头'; return }
  if (!d.roleName.trim()) { d.error = '角色名称不能为空'; return }
  d.saving = true
  try {
    let newId
    if (d.mode === 'create') {
      const r = await roleApi.create({
        roleCode: code, roleName: d.roleName.trim(), roleGroup: d.roleGroup,
        appType: d.appType, remark: d.remark,
        menuIds: [], funcCodes: [], fieldCodes: [], dataScopes: [],
      })
      newId = r.roleId
    } else {
      const r = await roleApi.copy({ sourceRoleId: d.sourceRoleId, newRoleCode: code, newRoleName: d.roleName.trim() })
      newId = r.roleId
    }
    createDlg.value = null
    keywordApplied.value = ''
    keywordInput.value = ''
    await loadRoles(false)
    selectedId.value = newId
    await loadDetail(newId)
  } catch (e) {
    d.error = e.message || '保存失败'
  } finally {
    d.saving = false
  }
}

async function removeRole(role) {
  if (role.isSystem) { alert('内置角色不可删除'); return }
  if (!window.confirm(`确认删除角色「${role.roleName}」？${role.userCount ? '该角色下仍有正常状态用户，需先调整这些用户。' : ''}`)) return
  try {
    await roleApi.delete(role.roleId)
    if (selectedId.value === role.roleId) {
      selectedId.value = ''
      detail.value = null
      dirty.value = false
    }
    await loadRoles(true)
  } catch (e) {
    alert('删除失败：' + (e.message || e))
  }
}

// ================= 字段分组 =================
const fieldGroups = computed(() => {
  const map = new Map()
  for (const f of fields.value) {
    if (f.status && f.status !== 'NORMAL') continue
    if (!map.has(f.fieldGroup)) map.set(f.fieldGroup, [])
    map.get(f.fieldGroup).push(f)
  }
  return [...map.entries()].map(([group, list]) => ({
    group,
    label: FIELD_GROUP_LABELS[group] || group,
    list,
    allChecked: list.every(f => fieldChecked.value.has(f.fieldCode)),
  }))
})

onMounted(() => loadRoles(false))
</script>

<template>
  <div class="role-page">
    <!-- 左栏：角色列表 -->
    <div class="card role-left">
      <div class="left-search">
        <input v-model="keywordInput" placeholder="编码/名称" @keydown.enter="doSearch" />
        <button class="btn" @click="doSearch">查询</button>
      </div>
      <button class="btn primary new-btn" @click="openCreate">+ 新建角色</button>
      <div class="role-list">
        <div v-if="listLoading" class="empty-hint">加载中…</div>
        <div v-else-if="!roles.length" class="empty-hint">暂无角色</div>
        <div
          v-for="r in roles" :key="r.roleId"
          class="role-item" :class="{ active: r.roleId === selectedId }"
          @click="selectRole(r.roleId)"
        >
          <div class="role-item-main">
            <span class="role-name">{{ r.roleName }}</span>
            <span v-if="r.isSystem" class="tag tag-builtin">内置</span>
          </div>
          <div class="role-item-sub">
            <span class="mono">{{ r.roleCode }}</span>
          </div>
          <div class="role-item-tags">
            <span class="tag tag-group">{{ GROUP_LABELS[r.roleGroup] || r.roleGroup }}</span>
            <span class="tag tag-app">{{ APP_LABELS[r.appType] || r.appType }}</span>
            <span v-if="r.userCount > 0" class="tag tag-users">{{ r.userCount }} 人</span>
            <span class="role-actions">
              <button class="link-btn" title="复制角色" @click.stop="openCopy">复制</button>
              <button
                class="link-btn danger-link" :class="{ disabled: r.isSystem }"
                :title="r.isSystem ? '内置角色不可删除' : '删除角色'"
                @click.stop="removeRole(r)"
              >删除</button>
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- 中栏：菜单/功能点授权 -->
    <div class="card role-mid">
      <div class="mid-head">
        <div class="mid-title">
          <b>{{ selectedRole ? selectedRole.roleName : '角色授权' }}</b>
          <span v-if="builtIn" class="tag tag-builtin">内置角色</span>
          <span v-if="dirty" class="tag tag-dirty">未保存</span>
        </div>
        <div class="mid-tools">
          <div class="app-tabs">
            <button
              v-for="t in APP_TABS" :key="t.value"
              class="app-tab" :class="{ active: appTab === t.value }"
              @click="switchTab(t.value)"
            >{{ t.label }}</button>
          </div>
          <button class="btn primary" :disabled="!detail || savingGrants" @click="saveGrants">
            {{ savingGrants ? '保存中…' : '保存授权' }}
          </button>
        </div>
      </div>

      <div v-if="builtIn" class="builtin-banner">
        内置角色的菜单、功能点、敏感字段由系统种子统一管理，页面仅可调整其「数据范围」与备注。
      </div>

      <div v-if="!detail" class="empty-hint big">请在左侧选择角色，或新建一个角色</div>
      <div v-else class="mid-body">
        <!-- 全局功能区 -->
        <div class="global-funcs" :class="{ disabled: builtIn }">
          <div class="section-title">全局功能<span class="title-hint">不依附具体菜单（导出/导入/打印/审核等）</span></div>
          <div class="global-list">
            <label v-for="f in globalFuncs" :key="f.funcId" class="func-chip">
              <input
                type="checkbox"
                :checked="funcChecked.has(f.funcCode)"
                :disabled="builtIn"
                @change="onToggleFunc(f.funcCode, $event.target.checked)"
              />
              <span>{{ f.funcName }}</span>
              <span class="mono muted">{{ f.funcCode }}</span>
            </label>
            <span v-if="!globalFuncs.length" class="muted">无全局功能定义</span>
          </div>
        </div>

        <!-- 菜单 + 页面功能点 -->
        <div class="section-title menu-tree-title">菜单与页面功能点</div>
        <div v-if="treeLoading" class="empty-hint">授权树加载中…</div>
        <MenuGrantTree
          :nodes="treeCache[appTab] || []"
          :menu-checked="menuChecked"
          :func-checked="funcChecked"
          :disabled="builtIn"
          @toggle-menu="onToggleMenu"
          @toggle-func="onToggleFunc"
        />
      </div>
    </div>

    <!-- 右栏：页签 -->
    <div class="card role-right">
      <div class="right-tabs">
        <button :class="{ active: rightTab === 'basic' }" @click="rightTab = 'basic'">基本信息</button>
        <button :class="{ active: rightTab === 'fields' }" @click="rightTab = 'fields'">敏感字段</button>
        <button :class="{ active: rightTab === 'scopes' }" @click="rightTab = 'scopes'">数据范围</button>
      </div>

      <div v-if="!detail" class="empty-hint big">未选择角色</div>

      <!-- 基本信息 -->
      <div v-else-if="rightTab === 'basic'" class="right-body">
        <div class="field">
          <label>角色编码 <span class="req">*</span></label>
          <input
            v-model="basicForm.roleCode"
            :disabled="builtIn"
            :placeholder="builtIn ? '内置角色编码不可修改' : '大写字母开头，2~50 位'"
          />
        </div>
        <div class="field">
          <label>角色名称 <span class="req">*</span></label>
          <input v-model="basicForm.roleName" :disabled="builtIn" placeholder="角色显示名" />
        </div>
        <div class="field">
          <label>角色分类 <span class="req">*</span></label>
          <select v-model="basicForm.roleGroup" :disabled="builtIn">
            <option value="SYS">系统（SYS）</option>
            <option value="BIZ">业务（BIZ）</option>
            <option value="WMS">仓储（WMS）</option>
            <option value="TMS">运输（TMS）</option>
          </select>
        </div>
        <div class="field">
          <label>适用端 <span class="req">*</span></label>
          <select v-model="basicForm.appType" :disabled="builtIn">
            <option value="ERP">ERP</option>
            <option value="WMS_PDA">仓储 PDA</option>
            <option value="DRIVER">司机端</option>
            <option value="ALL">全部端</option>
          </select>
        </div>
        <div class="field field-top">
          <label>备注</label>
          <textarea v-model="basicForm.remark" rows="4" maxlength="500" placeholder="选填"></textarea>
        </div>
        <div v-if="builtIn" class="builtin-banner small">
          内置角色仅允许修改备注与数据范围；编码、名称、菜单、功能点、字段均受系统保护。
        </div>
        <div class="right-foot">
          <button class="btn primary" :disabled="savingBasic" @click="saveBasic">
            {{ savingBasic ? '保存中…' : '保存基本信息' }}
          </button>
        </div>
      </div>

      <!-- 敏感字段 -->
      <div v-else-if="rightTab === 'fields'" class="right-body">
        <div v-if="builtIn" class="builtin-banner small">内置角色字段授权由系统种子管理，不可在此修改。</div>
        <div v-for="g in fieldGroups" :key="g.group" class="field-group">
          <div class="field-group-head">
            <b>{{ g.label }}</b>
            <span class="muted">{{ g.list.filter(f => fieldChecked.has(f.fieldCode)).length }}/{{ g.list.length }}</span>
            <button class="link-btn" :disabled="builtIn" @click="toggleGroupFields(g.list.map(f => f.fieldCode), true)">全选</button>
            <button class="link-btn" :disabled="builtIn" @click="toggleGroupFields(g.list.map(f => f.fieldCode), false)">清空</button>
          </div>
          <div class="field-checks">
            <label v-for="f in g.list" :key="f.fieldId" class="field-check-item" :title="f.remark || ''">
              <input
                type="checkbox"
                :checked="fieldChecked.has(f.fieldCode)"
                :disabled="builtIn"
                @change="onToggleField(f.fieldCode, $event.target.checked)"
              />
              <span>{{ f.fieldName }}</span>
              <span class="mono muted">{{ f.fieldCode }}</span>
            </label>
          </div>
        </div>
        <div class="right-foot hint-foot">
          <span class="muted">勾选 = 该角色可见此敏感字段；不勾选则在列表/详情中脱敏。修改后点中栏「保存授权」。</span>
        </div>
      </div>

      <!-- 数据范围 -->
      <div v-else class="right-body">
        <div class="scope-intro muted">
          不配置时取系统默认（仅自己创建的数据）。「全部/仅自己/自己及下级/指定」按维度生效，
          角色范围与用户层收窄取交集。
        </div>
        <DataScopeEditor
          :model-value="dataScopes"
          default-label="系统默认（仅自己创建）"
          @update:model-value="(v) => { dataScopes = v; dirty = true }"
        />
        <div class="right-foot hint-foot">
          <span class="muted">数据范围与菜单/功能/字段一起由中栏「保存授权」提交。</span>
        </div>
      </div>
    </div>

    <!-- 新建 / 复制角色弹窗 -->
    <div v-if="createDlg" class="mask" @click.self="createDlg = null">
      <div class="dialog create-dialog">
        <div class="dialog-head">
          <b>{{ createDlg.mode === 'create' ? '新建角色' : '复制角色：' + selectedRole?.roleName }}</b>
          <button class="link-btn" @click="createDlg = null">×</button>
        </div>
        <div class="dialog-body">
          <div class="field">
            <label>角色编码 <span class="req">*</span></label>
            <input v-model="createDlg.roleCode" placeholder="大写字母开头，2~50 位，如 SALES_DIRECTOR" />
          </div>
          <div class="field">
            <label>角色名称 <span class="req">*</span></label>
            <input v-model="createDlg.roleName" placeholder="角色显示名" />
          </div>
          <template v-if="createDlg.mode === 'create'">
            <div class="field">
              <label>角色分类 <span class="req">*</span></label>
              <select v-model="createDlg.roleGroup">
                <option value="SYS">系统（SYS）</option>
                <option value="BIZ">业务（BIZ）</option>
                <option value="WMS">仓储（WMS）</option>
                <option value="TMS">运输（TMS）</option>
              </select>
            </div>
            <div class="field">
              <label>适用端 <span class="req">*</span></label>
              <select v-model="createDlg.appType">
                <option value="ERP">ERP</option>
                <option value="WMS_PDA">仓储 PDA</option>
                <option value="DRIVER">司机端</option>
                <option value="ALL">全部端</option>
              </select>
            </div>
            <div class="field">
              <label>备注</label>
              <textarea v-model="createDlg.remark" rows="2" maxlength="500"></textarea>
            </div>
          </template>
          <div v-else class="muted copy-hint">复制会把源角色的菜单、功能点、敏感字段、数据范围授权原样拷贝到新角色，之后可独立调整。</div>
          <div v-if="createDlg.error" class="error-text">{{ createDlg.error }}</div>
        </div>
        <div class="dialog-foot">
          <button class="btn" @click="createDlg = null">取消</button>
          <button class="btn primary" :disabled="createDlg.saving" @click="submitCreate">
            {{ createDlg.saving ? '提交中…' : '确定' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.role-page { display: flex; gap: 8px; height: 100%; min-height: 0; }

/* 左栏 */
.role-left { width: 250px; flex: none; display: flex; flex-direction: column; gap: 8px; padding: 10px; min-height: 0; }
.left-search { display: flex; gap: 6px; }
.left-search input {
  flex: 1; height: 30px; border: 1px solid var(--line); border-radius: 7px; padding: 0 10px; font-size: 13px;
}
.new-btn { width: 100%; }
.role-list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; min-height: 0; }
.role-item {
  border: 1px solid var(--line-soft, #eef3f8); border-radius: 8px; padding: 7px 9px;
  cursor: pointer; display: flex; flex-direction: column; gap: 3px;
}
.role-item:hover { background: #f6faff; }
.role-item.active { border-color: var(--primary); background: #eef6ff; }
.role-item-main { display: flex; align-items: center; gap: 6px; }
.role-name { font-size: 13px; font-weight: 700; color: #12385f; }
.role-item-sub { font-size: 11px; color: #94a3b8; }
.role-item-tags { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.role-actions { margin-left: auto; display: flex; }
.role-actions .disabled { opacity: .4; pointer-events: none; }
.tag { font-size: 10px; border-radius: 4px; padding: 0 5px; line-height: 17px; white-space: nowrap; }
.tag-builtin { background: #e6f4ff; color: #1677ff; border: 1px solid #91caff; }
.tag-group { background: #f4f4f5; color: #52525b; }
.tag-app { background: #f0f9eb; color: #5b8c2d; border: 1px solid #c2e7a8; }
.tag-users { background: #fff7e6; color: #d46b08; border: 1px solid #ffd591; }
.tag-dirty { background: #fef0f0; color: #cf1322; border: 1px solid #fca5a5; }

/* 中栏 */
.role-mid { flex: 1; min-width: 0; display: flex; flex-direction: column; padding: 0; min-height: 0; }
.mid-head {
  display: flex; justify-content: space-between; align-items: center;
  padding: 10px 14px; border-bottom: 1px solid var(--line); gap: 10px;
}
.mid-title { display: flex; align-items: center; gap: 8px; font-size: 14px; }
.mid-tools { display: flex; align-items: center; gap: 10px; }
.app-tabs { display: flex; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
.app-tab { border: 0; background: #fff; padding: 5px 12px; font-size: 12px; cursor: pointer; color: #475569; }
.app-tab.active { background: var(--primary); color: #fff; }
.mid-body { flex: 1; overflow-y: auto; padding: 10px 14px; min-height: 0; }
.builtin-banner {
  margin: 10px 14px 0; padding: 7px 10px; border-radius: 8px;
  background: #fff7e6; border: 1px solid #ffd591; color: #ad4e00; font-size: 12px;
}
.builtin-banner.small { margin: 0 0 10px; }
.global-funcs { border: 1px solid var(--line-soft, #eef3f8); border-radius: 10px; padding: 9px 11px; margin-bottom: 12px; }
.global-funcs.disabled { opacity: .6; background: #fafbfc; }
.global-list { display: flex; flex-wrap: wrap; gap: 6px 10px; margin-top: 6px; }
.func-chip {
  display: inline-flex; align-items: center; gap: 5px; font-size: 12px;
  border: 1px solid var(--line-soft, #eef3f8); border-radius: 6px; padding: 3px 9px; background: #fbfdff;
}
.func-chip input { width: auto; }
.section-title { font-size: 13px; font-weight: 700; color: #12385f; }
.title-hint { font-weight: 400; color: #94a3b8; font-size: 11px; margin-left: 8px; }
.menu-tree-title { margin: 4px 0 6px; }
.empty-hint { color: #94a3b8; font-size: 12px; text-align: center; padding: 20px; }
.empty-hint.big { margin: auto; }

/* 右栏 */
.role-right { width: 380px; flex: none; display: flex; flex-direction: column; padding: 0; min-height: 0; }
.right-tabs { display: flex; border-bottom: 1px solid var(--line); }
.right-tabs button {
  flex: 1; border: 0; background: #fff; padding: 10px 0; font-size: 13px;
  color: #64748b; cursor: pointer; border-bottom: 2px solid transparent;
}
.right-tabs button.active { color: var(--primary); border-bottom-color: var(--primary); font-weight: 700; }
.right-body { flex: 1; overflow-y: auto; padding: 14px; display: flex; flex-direction: column; gap: 12px; min-height: 0; }
.right-body .field { display: grid; grid-template-columns: 76px 1fr; gap: 4px 10px; align-items: center; }
.right-body .field.field-top { align-items: start; }
.right-body .field label { font-size: 12px; color: #606266; text-align: right; }
.right-body .field input, .right-body .field select, .right-body .field textarea {
  width: 100%; padding: 6px 10px; border: 1px solid var(--line); border-radius: 6px; font-size: 13px;
}
.right-body .field input:disabled, .right-body .field select:disabled { background: #f8fafc; color: #64748b; }
.req { color: #f56c6c; }
.right-foot { margin-top: auto; padding-top: 10px; display: flex; justify-content: flex-end; }
.hint-foot { border-top: 1px solid var(--line-soft, #eef3f8); margin: 4px -14px -14px; padding: 10px 14px; }
.field-group-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; font-size: 13px; }
.field-group-head .link-btn { margin-left: auto; font-size: 12px; }
.field-group-head .link-btn + .link-btn { margin-left: 0; }
.field-checks { display: grid; grid-template-columns: 1fr 1fr; gap: 5px; margin-bottom: 12px; }
.field-check-item {
  display: flex; align-items: center; gap: 5px; font-size: 12px;
  border: 1px solid var(--line-soft, #eef3f8); border-radius: 6px; padding: 5px 8px;
}
.field-check-item input { width: auto; }
.scope-intro { line-height: 1.6; }
.muted { color: #94a3b8; font-size: 12px; }
.mono { font-family: var(--font-mono); }
.error-text { color: #d93025; font-size: 12.5px; }
.copy-hint { line-height: 1.6; }

/* 弹窗 */
.mask {
  position: fixed; inset: 0; background: rgba(15, 35, 60, 0.28);
  display: grid; place-items: center; z-index: 240;
}
.dialog {
  width: 520px; background: #fff; border-radius: 8px;
  overflow: hidden; box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
}
.dialog-head {
  padding: 10px 16px; border-bottom: 1px solid var(--line);
  display: flex; justify-content: space-between; align-items: center;
}
.dialog-body { padding: 16px; display: flex; flex-direction: column; gap: 12px; }
.dialog-body .field { display: grid; grid-template-columns: 86px 1fr; gap: 4px 12px; align-items: center; }
.dialog-body .field label { font-size: 12px; color: #606266; text-align: right; }
.dialog-body .field input, .dialog-body .field select, .dialog-body .field textarea {
  padding: 6px 10px; border: 1px solid var(--line); border-radius: 6px; font-size: 13px; width: 100%;
}
.dialog-foot {
  padding: 10px 16px; border-top: 1px solid var(--line);
  display: flex; justify-content: flex-end; gap: 8px;
}
.dialog-foot .btn, .mid-tools .btn { height: 30px; padding: 0 14px; }
.link-btn { border: 0; background: transparent; color: var(--primary); cursor: pointer; font-size: 12px; padding: 0 4px; }
.link-btn:disabled { opacity: .4; cursor: not-allowed; }
</style>
