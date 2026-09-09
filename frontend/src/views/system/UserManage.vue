<script setup>
/**
 * 用户管理（PRD-28 §10.1）。
 * 多角色 + 主角色、绑定员工（唯一）、多绑定仓、用户层七维数据范围收窄；
 * 不提供删除，只支持停用；重置密码必须填写原因并二次确认（AUDIT-002）。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { userApi } from '../../api/rbac.js'
import DataScopeEditor from '../../components/rbac/DataScopeEditor.vue'

// ================= 列表 / 查询 =================
const loading = ref(false)
const records = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)

// 输入中的查询条件（点查询才生效，全局查询约定）
const queryInput = reactive({ keyword: '', status: '', roleId: '' })
const queryApplied = reactive({ keyword: '', status: '', roleId: '' })

const options = ref({ roles: [], warehouses: [], scopeTypes: [] })

async function loadOptions() {
  try {
    options.value = await userApi.options()
  } catch (e) {
    alert('选项加载失败：' + (e.message || e))
  }
}

async function loadPage() {
  loading.value = true
  try {
    const filters = {}
    if (queryApplied.keyword) filters.keyword = queryApplied.keyword
    if (queryApplied.status) filters.status = queryApplied.status
    if (queryApplied.roleId) filters.roleId = queryApplied.roleId
    const data = await userApi.page({ pageNo: pageNo.value, pageSize: pageSize.value, filters })
    records.value = data?.records || []
    total.value = data?.total || 0
  } catch (e) {
    records.value = []
    alert('用户列表加载失败：' + (e.message || e))
  } finally {
    loading.value = false
  }
}

function doQuery() {
  Object.assign(queryApplied, queryInput)
  pageNo.value = 1
  loadPage()
}
function resetQuery() {
  queryInput.keyword = ''; queryInput.status = ''; queryInput.roleId = ''
  Object.assign(queryApplied, queryInput)
  pageNo.value = 1
  loadPage()
}
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

onMounted(() => { loadOptions(); loadPage() })

// ================= 新建 / 编辑抽屉 =================
const drawer = ref(null)
// drawer.form: roleIds/warehouseIds 字符串数组；dataScopes 后端形态；employee 选中对象
function emptyForm() {
  return {
    userId: '', username: '', displayName: '', mobile: '', email: '', remark: '',
    password: '', mustChangePwd: true,
    roleIds: [], primaryRoleId: '',
    employeeId: '', employeeName: '',
    warehouseIds: [],
    dataScopes: [],
  }
}

function openCreate() {
  drawer.value = { mode: 'create', form: emptyForm(), saving: false }
}

async function openEdit(row) {
  try {
    const d = await userApi.detail(row.userId)
    drawer.value = {
      mode: 'edit', saving: false,
      form: {
        userId: d.userId, username: d.username, displayName: d.displayName || '',
        mobile: d.mobile || '', email: d.email || '', remark: d.remark || '',
        password: '', mustChangePwd: true,
        roleIds: (d.roles || []).map(r => r.roleId),
        primaryRoleId: d.primaryRole?.roleId || '',
        employeeId: d.employeeId || '',
        employeeName: d.employeeName || '',
        warehouseIds: (d.warehouses || []).map(w => w.warehouseId),
        dataScopes: d.dataScopes || [],
      },
    }
  } catch (e) {
    alert('详情加载失败：' + (e.message || e))
  }
}

const selectedRoles = computed(() =>
  options.value.roles.filter(r => drawer.value?.form.roleIds.includes(r.roleId)))

function toggleRole(roleId, checked) {
  const ids = drawer.value.form.roleIds
  if (checked) {
    if (!ids.includes(roleId)) ids.push(roleId)
  } else {
    const i = ids.indexOf(roleId)
    if (i >= 0) ids.splice(i, 1)
    if (drawer.value.form.primaryRoleId === roleId) drawer.value.form.primaryRoleId = ''
  }
  // 主角色兜底为第一个已选
  if (!ids.includes(drawer.value.form.primaryRoleId)) {
    drawer.value.form.primaryRoleId = ids[0] || ''
  }
}

function toggleWarehouse(id, checked) {
  const ids = drawer.value.form.warehouseIds
  if (checked) { if (!ids.includes(id)) ids.push(id) }
  else { const i = ids.indexOf(id); if (i >= 0) ids.splice(i, 1) }
}

// ---------- 绑定员工搜索 ----------
const empPanel = ref({ open: false, keyword: '', loading: false, list: [] })
function openEmpPanel() {
  empPanel.value.open = true
  empPanel.value.keyword = ''
  searchEmployees()
}
async function searchEmployees() {
  empPanel.value.loading = true
  try {
    empPanel.value.list = await userApi.employees(
      empPanel.value.keyword, drawer.value?.form.userId || '')
  } catch (e) {
    empPanel.value.list = []
  } finally {
    empPanel.value.loading = false
  }
}
function pickEmployee(e) {
  drawer.value.form.employeeId = e.employeeId
  drawer.value.form.employeeName = `${e.employeeName}（${e.employeeCode}）`
  empPanel.value.open = false
}
function clearEmployee() {
  drawer.value.form.employeeId = ''
  drawer.value.form.employeeName = ''
}

// ---------- 保存 ----------
const formError = ref('')
function validateForm(f) {
  if (!f.username?.trim()) return '账号不能为空'
  if (!f.displayName?.trim()) return '姓名不能为空'
  if (!f.roleIds.length) return '请至少选择一个角色'
  if (f.mode === 'create' || drawer.value.mode === 'create') {
    // create 分支
  }
  if (drawer.value.mode === 'create') {
    const pwd = f.password || ''
    if (pwd.length < 8 || pwd.length > 50) return '初始密码长度需为 8~50 位'
    let kinds = 0
    if (/[a-z]/.test(pwd)) kinds++
    if (/[A-Z]/.test(pwd)) kinds++
    if (/[0-9]/.test(pwd)) kinds++
    if (/[^A-Za-z0-9]/.test(pwd)) kinds++
    if (kinds < 3) return '初始密码需包含大写字母、小写字母、数字、特殊字符中的至少 3 种'
  }
  return ''
}

async function save() {
  const f = drawer.value.form
  formError.value = validateForm(f)
  if (formError.value) return
  drawer.value.saving = true
  const body = {
    userId: f.userId,
    username: f.username.trim(),
    displayName: f.displayName.trim(),
    mobile: f.mobile, email: f.email, remark: f.remark,
    roleIds: f.roleIds, primaryRoleId: f.primaryRoleId,
    employeeId: f.employeeId, warehouseIds: f.warehouseIds,
    dataScopes: f.dataScopes,
  }
  try {
    if (drawer.value.mode === 'create') {
      await userApi.create({ ...body, password: f.password, mustChangePwd: f.mustChangePwd })
    } else {
      await userApi.update(body)
    }
    drawer.value = null
    await loadPage()
  } catch (e) {
    formError.value = e.message || '保存失败'
  } finally {
    drawer.value.saving = false
  }
}

// ================= 重置密码 =================
const resetDlg = ref(null)
function openReset(row) {
  resetDlg.value = { userId: row.userId, username: row.username, displayName: row.displayName,
    newPassword: '', confirm: '', reason: '', mustChangePwd: true, saving: false, error: '' }
}
async function submitReset() {
  const d = resetDlg.value
  d.error = ''
  if (d.newPassword.length < 8 || d.newPassword.length > 50) { d.error = '密码长度需为 8~50 位'; return }
  let kinds = 0
  if (/[a-z]/.test(d.newPassword)) kinds++
  if (/[A-Z]/.test(d.newPassword)) kinds++
  if (/[0-9]/.test(d.newPassword)) kinds++
  if (/[^A-Za-z0-9]/.test(d.newPassword)) kinds++
  if (kinds < 3) { d.error = '密码需包含大写字母、小写字母、数字、特殊字符中的至少 3 种'; return }
  if (d.newPassword !== d.confirm) { d.error = '两次输入的密码不一致'; return }
  if (!d.reason.trim()) { d.error = '必须填写重置原因（审计要求）'; return }
  if (!window.confirm(`确认为用户「${d.displayName}」重置密码？该操作会记录敏感审计日志。`)) return
  d.saving = true
  try {
    await userApi.resetPassword({
      userId: d.userId, newPassword: d.newPassword, reason: d.reason.trim(),
      mustChangePwd: d.mustChangePwd,
    })
    resetDlg.value = null
  } catch (e) {
    d.error = e.message || '重置失败'
  } finally {
    d.saving = false
  }
}

// ================= 启停 / 解锁 =================
async function toggleStatus(row) {
    const disable = row.status === 'NORMAL'
    if (disable && !window.confirm(`确认停用账号「${row.displayName}」？停用后该账号无法登录。`)) return
    try {
      if (disable) await userApi.disable(row.userId)
      else await userApi.enable(row.userId)
      await loadPage()
    } catch (e) {
      alert((disable ? '停用' : '启用') + '失败：' + (e.message || e))
    }
}
async function unlock(row) {
  if (!window.confirm(`确认解锁账号「${row.displayName}」的登录锁定？`)) return
  try {
    await userApi.unlock(row.userId)
    await loadPage()
  } catch (e) {
    alert('解锁失败：' + (e.message || e))
  }
}

function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '—' }
function roleNames(row) { return (row.roles || []).map(r => r.roleName).join('、') }
function whNames(row) { return (row.warehouses || []).map(w => w.warehouseName).join('、') || '—' }
</script>

<template>
  <div class="user-page">
    <div class="card query-card">
      <div class="query-row">
        <input v-model="queryInput.keyword" placeholder="账号/姓名/手机/角色名" @keydown.enter="doQuery" />
        <select v-model="queryInput.status">
          <option value="">全部状态</option>
          <option value="NORMAL">正常</option>
          <option value="DISABLED">停用</option>
        </select>
        <select v-model="queryInput.roleId">
          <option value="">全部角色</option>
          <option v-for="r in options.roles" :key="r.roleId" :value="r.roleId">{{ r.roleName }}</option>
        </select>
        <button class="btn primary" @click="doQuery">查询</button>
        <button class="btn" @click="resetQuery">重置</button>
      </div>
    </div>

    <div class="tablebox">
      <div class="toolbar">
        <b>用户列表</b>
        <span class="muted">共 {{ total }} 个账号（账号不提供删除，仅可停用）</span>
        <span style="flex:1"></span>
        <button class="btn primary" @click="openCreate">+ 新建用户</button>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>账号</th><th>姓名</th><th>角色</th><th>主角色</th><th>绑定仓库</th>
              <th>手机</th><th>状态</th><th>锁定</th><th>最后登录</th><th style="width:210px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading"><td colspan="10" class="empty">加载中…</td></tr>
            <tr v-else-if="!records.length"><td colspan="10" class="empty">暂无用户</td></tr>
            <tr v-for="u in records" :key="u.userId" :class="{ disabled: u.status === 'DISABLED' }">
              <td>
                {{ u.username }}
                <span v-if="u.isSystem" class="tag-sys">内置</span>
              </td>
              <td>{{ u.displayName }}</td>
              <td class="roles-cell">{{ roleNames(u) }}</td>
              <td>{{ u.primaryRole?.roleName || '—' }}</td>
              <td>{{ whNames(u) }}</td>
              <td>{{ u.mobile || '—' }}</td>
              <td>
                <span :class="u.status === 'NORMAL' ? 'badge-on' : 'badge-off'">
                  {{ u.status === 'NORMAL' ? '正常' : '停用' }}
                </span>
                <span v-if="u.mustChangePwd" class="tag-must">待改密</span>
              </td>
              <td>
                <span v-if="u.locked" class="tag-lock">已锁定</span>
                <span v-else class="muted">—</span>
              </td>
              <td class="muted">{{ fmtTime(u.lastLoginTime) }}</td>
              <td class="ops">
                <button class="link-btn" @click="openEdit(u)">编辑</button>
                <button class="link-btn" @click="openReset(u)">重置密码</button>
                <button v-if="u.locked" class="link-btn" @click="unlock(u)">解锁</button>
                <button
                  class="link-btn" :class="{ 'danger-link': u.status === 'NORMAL' }"
                  @click="toggleStatus(u)"
                >{{ u.status === 'NORMAL' ? '停用' : '启用' }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pager">
        <span>第 {{ pageNo }} / {{ totalPages }} 页</span>
        <button class="btn" :disabled="pageNo <= 1" @click="pageNo--; loadPage()">上一页</button>
        <button class="btn" :disabled="pageNo >= totalPages" @click="pageNo++; loadPage()">下一页</button>
      </div>
    </div>

    <!-- 新建/编辑抽屉 -->
    <div v-if="drawer" class="drawer-overlay drawer-lite" @click.self="drawer = null">
      <div class="modal-lite-box">
        <div class="modal-lite-head">
          <b>{{ drawer.mode === 'create' ? '新建用户' : '编辑用户：' + drawer.form.username }}</b>
          <button class="link-btn" @click="drawer = null">×</button>
        </div>
        <div class="modal-lite-body">
          <div class="form-vertical">
            <div class="field">
              <label>账号 <span class="req">*</span></label>
              <input v-model="drawer.form.username" :readonly="drawer.mode === 'edit'"
                     placeholder="登录账号，创建后不可修改" />
            </div>
            <div class="field">
              <label>姓名 <span class="req">*</span></label>
              <input v-model="drawer.form.displayName" placeholder="用户显示名" />
            </div>
            <div class="field">
              <label>手机</label>
              <input v-model="drawer.form.mobile" placeholder="选填" />
            </div>
            <div class="field">
              <label>邮箱</label>
              <input v-model="drawer.form.email" placeholder="选填" />
            </div>

            <div class="field field-top">
              <label>角色 <span class="req">*</span></label>
              <div class="check-grid">
                <label v-for="r in options.roles" :key="r.roleId" class="check-item">
                  <input
                    type="checkbox"
                    :checked="drawer.form.roleIds.includes(r.roleId)"
                    @change="toggleRole(r.roleId, $event.target.checked)"
                  />
                  <span>{{ r.roleName }}</span>
                  <span class="muted">{{ r.roleCode }}</span>
                </label>
              </div>
            </div>
            <div class="field">
              <label>主角色 <span class="req">*</span></label>
              <select v-model="drawer.form.primaryRoleId">
                <option v-for="r in selectedRoles" :key="r.roleId" :value="r.roleId">{{ r.roleName }}</option>
              </select>
            </div>

            <div class="field">
              <label>绑定员工</label>
              <div class="inline-line">
                <input :value="drawer.form.employeeName" readonly placeholder="一个员工只能绑定一个账号" />
                <button type="button" class="btn" @click="openEmpPanel">选择</button>
                <button type="button" class="btn" @click="clearEmployee">清除</button>
              </div>
            </div>

            <div class="field field-top">
              <label>绑定仓库</label>
              <div class="check-grid warehouse-grid">
                <label v-for="w in options.warehouses" :key="w.warehouseId" class="check-item">
                  <input
                    type="checkbox"
                    :checked="drawer.form.warehouseIds.includes(w.warehouseId)"
                    @change="toggleWarehouse(w.warehouseId, $event.target.checked)"
                  />
                  <span>{{ w.warehouseName }}</span>
                </label>
                <span v-if="!options.warehouses.length" class="muted">暂无正常状态仓库</span>
              </div>
              <div class="hint">PDA 作业角色账号必须至少绑定一个仓库</div>
            </div>

            <div v-if="drawer.mode === 'create'" class="field">
              <label>初始密码 <span class="req">*</span></label>
              <div>
                <input v-model="drawer.form.password" type="password" placeholder="8~50 位，四类字符至少 3 种" />
                <label class="inline-check">
                  <input type="checkbox" v-model="drawer.form.mustChangePwd" />
                  首次登录必须修改密码
                </label>
              </div>
            </div>

            <div class="field field-top">
              <label>数据范围收窄</label>
              <div>
                <div class="hint" style="margin-bottom:6px">
                  不配置则完全跟随角色授权；配置后与角色结果取交集（仅缩小不能放大）
                </div>
                <DataScopeEditor v-model="drawer.form.dataScopes" default-label="跟随角色" />
              </div>
            </div>

            <div class="field">
              <label>备注</label>
              <textarea v-model="drawer.form.remark" rows="2" maxlength="500"></textarea>
            </div>

            <div v-if="formError" class="error-text">{{ formError }}</div>
          </div>
        </div>
        <div class="modal-lite-foot">
          <button class="btn" @click="drawer = null">取消</button>
          <button class="btn primary" :disabled="drawer.saving" @click="save">
            {{ drawer.saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 员工选择浮层 -->
    <div v-if="empPanel.open" class="emp-mask" @click.self="empPanel.open = false">
      <div class="emp-panel">
        <div class="emp-head">
          <b>选择绑定员工</b>
          <button class="link-btn" @click="empPanel.open = false">×</button>
        </div>
        <div class="emp-search">
          <input v-model="empPanel.keyword" placeholder="姓名/编码/手机" @keydown.enter="searchEmployees" />
          <button class="btn" @click="searchEmployees">查询</button>
        </div>
        <div class="emp-list">
          <div v-if="empPanel.loading" class="muted empty">加载中…</div>
          <div v-else-if="!empPanel.list.length" class="muted empty">未找到可绑定员工（已绑定其他账号的员工不显示）</div>
          <div v-for="e in empPanel.list" :key="e.employeeId" class="emp-item" @click="pickEmployee(e)">
            <span>{{ e.employeeName }}</span>
            <span class="muted">{{ e.employeeCode }} {{ e.mobile || '' }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 重置密码弹窗 -->
    <div v-if="resetDlg" class="mask" @click.self="resetDlg = null">
      <div class="dialog">
        <div class="dialog-head">
          <b>重置密码：{{ resetDlg.displayName }}（{{ resetDlg.username }}）</b>
          <button class="link-btn" @click="resetDlg = null">×</button>
        </div>
        <div class="dialog-body">
          <div class="field">
            <label>新密码 <span class="req">*</span></label>
            <input v-model="resetDlg.newPassword" type="password" placeholder="8~50 位，四类字符至少 3 种" />
          </div>
          <div class="field">
            <label>确认新密码 <span class="req">*</span></label>
            <input v-model="resetDlg.confirm" type="password" />
          </div>
          <div class="field">
            <label>重置原因 <span class="req">*</span></label>
            <textarea v-model="resetDlg.reason" rows="3" placeholder="必填，将写入敏感操作审计日志"></textarea>
          </div>
          <label class="inline-check">
            <input type="checkbox" v-model="resetDlg.mustChangePwd" />
            要求用户下次登录修改密码
          </label>
          <div v-if="resetDlg.error" class="error-text">{{ resetDlg.error }}</div>
        </div>
        <div class="dialog-foot">
          <button class="btn" @click="resetDlg = null">取消</button>
          <button class="btn primary" :disabled="resetDlg.saving" @click="submitReset">
            {{ resetDlg.saving ? '提交中…' : '确认重置' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.user-page { display: flex; flex-direction: column; gap: 8px; height: 100%; min-height: 0; }
.query-card { padding: 10px 12px; }
.query-row { display: flex; gap: 8px; align-items: center; }
.query-row input, .query-row select {
  height: 30px; border: 1px solid var(--line); border-radius: 7px; padding: 0 10px; font-size: 13px;
}
.query-row input { width: 240px; }
.muted { color: #94a3b8; font-size: 12px; }
.toolbar b { font-size: 14px; color: #12385f; }
.toolbar .muted { margin-left: 10px; }
.table-wrap { flex: 1; overflow: auto; }
table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
th, td { border-bottom: 1px solid #eef3f8; padding: 7px 10px; text-align: left; white-space: nowrap; }
th { background: #f7fbff; color: #24415f; font-weight: 600; position: sticky; top: 0; z-index: 1; }
tr.disabled { color: #94a3b8; background: #fafbfc; }
.roles-cell { max-width: 200px; white-space: normal; }
.empty { text-align: center; color: #94a3b8; padding: 28px; }
.ops { white-space: nowrap; }
.tag-sys {
  background: #e6f4ff; color: #1677ff; border: 1px solid #91caff;
  border-radius: 4px; padding: 0 4px; font-size: 10px; margin-left: 4px;
}
.tag-must {
  background: #fff7e6; color: #d46b08; border: 1px solid #ffd591;
  border-radius: 4px; padding: 0 4px; font-size: 10px; margin-left: 4px;
}
.tag-lock {
  background: #fef0f0; color: #cf1322; border: 1px solid #fca5a5;
  border-radius: 4px; padding: 1px 5px; font-size: 11px;
}
.badge-on { background: #f0f9eb; color: #67c23a; border-radius: 4px; padding: 2px 6px; font-size: 11px; }
.badge-off { background: #fef0f0; color: #f56c6c; border-radius: 4px; padding: 2px 6px; font-size: 11px; }
.link-btn { border: 0; background: transparent; color: var(--primary); cursor: pointer; font-size: 12px; margin: 0 4px; }
.link-btn.danger-link { color: #d93025; }

/* 抽屉表单 */
.form-vertical .field { grid-template-columns: 110px minmax(0, 1fr); }
.form-vertical .field-top { align-items: start; }
.form-vertical .field-top > label { margin-top: 6px; }
.field input, .field select, .field textarea {
  width: 100%; padding: 6px 10px; border: 1px solid var(--line);
  border-radius: 6px; font-size: 13px; outline: none;
}
.field input[readonly] { background: #f8fafc; color: #64748b; }
.check-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.warehouse-grid { grid-template-columns: repeat(4, 1fr); }
.check-item {
  display: flex; align-items: center; gap: 6px; font-size: 12.5px;
  border: 1px solid var(--line-soft, #eef3f8); border-radius: 7px; padding: 5px 8px;
}
.check-item input { width: auto; }
.check-item .muted { margin-left: auto; font-family: var(--font-mono); font-size: 10px; }
.inline-line { display: flex; gap: 6px; }
.inline-line input { flex: 1; }
.inline-check { display: inline-flex; align-items: center; gap: 6px; font-size: 12.5px; margin-top: 6px; }
.hint { color: #94a3b8; font-size: 12px; }
.error-text { color: #d93025; font-size: 12.5px; }
.req { color: #f56c6c; }

/* 员工浮层 */
.emp-mask {
  position: fixed; inset: 0; background: rgba(15, 35, 60, 0.28);
  display: grid; place-items: center; z-index: 230;
}
.emp-panel {
  width: 520px; max-height: 70vh; background: #fff; border-radius: 10px;
  display: flex; flex-direction: column; overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
}
.emp-head {
  padding: 10px 14px; border-bottom: 1px solid var(--line);
  display: flex; justify-content: space-between; align-items: center;
}
.emp-search { display: flex; gap: 8px; padding: 10px 14px; }
.emp-search input {
  flex: 1; height: 30px; border: 1px solid var(--line); border-radius: 7px; padding: 0 10px;
}
.emp-list { overflow-y: auto; padding: 0 8px 10px; }
.emp-item {
  display: flex; justify-content: space-between; padding: 8px 10px;
  border-radius: 7px; cursor: pointer; font-size: 13px;
}
.emp-item:hover { background: #eaf4ff; }

/* 重置密码弹窗 */
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
.dialog-body .field { display: grid; grid-template-columns: 96px 1fr; gap: 4px 12px; align-items: center; }
.dialog-body .field label { font-size: 12px; color: #606266; text-align: right; }
.dialog-body .field input, .dialog-body .field textarea {
  padding: 6px 10px; border: 1px solid var(--line); border-radius: 6px; font-size: 13px; width: 100%;
}
.dialog-foot {
  padding: 10px 16px; border-top: 1px solid var(--line);
  display: flex; justify-content: flex-end; gap: 8px;
}
.dialog-foot .btn { height: 30px; padding: 0 16px; }
</style>
