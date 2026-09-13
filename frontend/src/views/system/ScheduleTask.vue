<script setup>
/**
 * 动态定时任务管理（PRD-33 §7.1）。
 * 任务体（handler_bean）只来自后端代码白名单，库内允许：改 cron、改备注、启停、立即执行、看日志。
 * 功能点：system.schedule_task.view/.edit/.execute。查询（日志过滤）只在点「查询」时生效。
 */
import { ref, onMounted } from 'vue'
import {
  scheduleTaskPage, scheduleTaskUpdate, scheduleTaskToggle,
  scheduleTaskRunOnce, scheduleTaskNext5, scheduleTaskLogPage,
} from '../../api/schedule-task.js'

const PERM_EDIT = 'system.schedule_task.edit'
const PERM_EXEC = 'system.schedule_task.execute'

const tab = ref('tasks')
const feedback = ref('')
const busy = ref(false)

const tasks = ref([])
const logs = ref([])
const logTotal = ref(0)
const logPageNo = ref(1)
const logPageSize = 20
const logFilter = ref({ taskCode: '' })
let tasksLoaded = false
let logsLoaded = false

/** cron 编辑弹窗状态。 */
const editModal = ref({
  open: false, taskCode: '', taskName: '', cronExpr: '', remark: '',
  nextTimes: [], previewed: false,
})

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 6000)
}
function time(v) { return v == null ? '—' : String(v).slice(0, 19) }
function triggerText(t) { return ({ AUTO: '自动', MANUAL: '手动' })[t] || t || '—' }
function resultTag(r) { return r === 'SUCCESS' ? 'tag-green' : r === 'FAIL' ? 'tag-red' : 'tag-gray' }
function resultText(r) { return ({ SUCCESS: '成功', FAIL: '失败' })[r] || r || '—' }

/** 加载任务列表（进页签加载；改/启停/执行后刷新）。 */
async function loadTasks() {
  try {
    const res = await scheduleTaskPage({ pageNo: 1, pageSize: 100, filters: {} })
    tasks.value = res.records || []
    tasksLoaded = true
  } catch (e) { show('任务列表加载失败：' + (e?.message || e), 'err') }
}

/** 启用/停用开关（edit 权限）。 */
async function toggleTask(row) {
  const want = row.enabled !== 'Y'
  busy.value = true
  try {
    await scheduleTaskToggle(row.taskCode, want)
    row.enabled = want ? 'Y' : 'N'
    show(`${row.taskName} 已${want ? '启用' : '停用'}`)
  } catch (e) { show('启停失败：' + (e?.message || e), 'err') }
  finally { busy.value = false }
}

/** 打开 cron 编辑弹窗。 */
function openEdit(row) {
  editModal.value = {
    open: true, taskCode: row.taskCode, taskName: row.taskName,
    cronExpr: row.cronExpr, remark: row.remark || '', nextTimes: [], previewed: false,
  }
}

/** 预览当前输入 cron 的未来 5 次执行时间（不落库）。 */
async function previewNext() {
  if (!editModal.value.cronExpr.trim()) return show('请填写 6 段 cron 表达式', 'err')
  try {
    editModal.value.nextTimes = await scheduleTaskNext5(editModal.value.cronExpr.trim())
    editModal.value.previewed = true
  } catch (e) {
    editModal.value.previewed = false
    show('cron 校验失败：' + (e?.message || e), 'err')
  }
}

/** 保存 cron/备注（后端再次校验 6 段表达式，返回未来 5 次执行时间）。 */
async function saveEdit() {
  if (!editModal.value.cronExpr.trim()) return show('请填写 6 段 cron 表达式', 'err')
  busy.value = true
  try {
    const r = await scheduleTaskUpdate({
      taskCode: editModal.value.taskCode,
      cronExpr: editModal.value.cronExpr.trim(),
      remark: editModal.value.remark || '',
    })
    editModal.value.nextTimes = r.nextFireTimes || []
    editModal.value.previewed = true
    show('已保存，调度已按新表达式重排')
    editModal.value.open = false
    await loadTasks()
  } catch (e) { show('保存失败：' + (e?.message || e), 'err') }
  finally { busy.value = false }
}

/** 立即执行一次（execute 权限，手动执行有操作日志）。 */
async function runOnce(row) {
  if (!confirm(`确认立即执行任务「${row.taskName}」？执行结果写入执行日志。`)) return
  busy.value = true
  try {
    const r = await scheduleTaskRunOnce(row.taskCode)
    show(`手动执行完成：${r.message || ''}`)
    await loadTasks()
    if (logsLoaded) await loadLogs()
  } catch (e) { show('执行失败：' + (e?.message || e), 'err') }
  finally { busy.value = false }
}

/** 查询执行日志（点查询/翻页触发）。 */
async function loadLogs() {
  try {
    const res = await scheduleTaskLogPage({
      pageNo: logPageNo.value, pageSize: logPageSize,
      filters: { taskCode: logFilter.value.taskCode || null },
    })
    logs.value = res.records || []
    logTotal.value = res.total || 0
    logsLoaded = true
  } catch (e) { show('执行日志加载失败：' + (e?.message || e), 'err') }
}
function searchLogs() { logPageNo.value = 1; loadLogs() }

function switchTab(t) {
  tab.value = t
  if (t === 'tasks' && !tasksLoaded) loadTasks()
  if (t === 'logs' && !logsLoaded) loadLogs()
}

onMounted(loadTasks)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <div class="tabbar">
        <button :class="['tab-btn', { on: tab === 'tasks' }]" @click="switchTab('tasks')">定时任务</button>
        <button :class="['tab-btn', { on: tab === 'logs' }]" @click="switchTab('logs')">执行日志</button>
      </div>
    </div>

    <div v-if="feedback" :class="['toast-inline', feedback.level]">{{ feedback.msg }}</div>

    <!-- 任务列表 -->
    <template v-if="tab === 'tasks'">
      <div class="tip-box">
        任务由后端代码注册白名单，页面只允许调整执行计划（cron）、备注、启停与手动执行，不接受新增任意脚本/类名。
      </div>
      <div class="tablebox">
        <table class="data">
          <thead>
            <tr>
              <th>任务编码</th><th>任务名称</th><th>执行 Bean</th><th>cron 表达式</th>
              <th>状态</th><th>上次触发</th><th>上次完成</th><th>上次结果</th><th>结果说明</th>
              <th>备注</th><th style="width:180px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in tasks" :key="r.taskCode">
              <td>{{ r.taskCode }}</td>
              <td>{{ r.taskName }}</td>
              <td class="mono">{{ r.handlerBean }}</td>
              <td class="mono">{{ r.cronExpr }}</td>
              <td>
                <span :class="['tag', r.enabled === 'Y' ? 'tag-green' : 'tag-gray']">
                  {{ r.enabled === 'Y' ? '启用中' : '已停用' }}
                </span>
              </td>
              <td>{{ time(r.lastFireTime) }}</td>
              <td>{{ time(r.lastFinishTime) }}</td>
              <td><span :class="['tag', resultTag(r.lastResult)]">{{ resultText(r.lastResult) }}</span></td>
              <td class="msg-cell">{{ r.lastMessage || '—' }}</td>
              <td class="msg-cell">{{ r.remark || '—' }}</td>
              <td class="nowrap">
                <a class="lk" v-permission="PERM_EDIT" @click="openEdit(r)">改计划</a>
                <a class="lk" v-permission="PERM_EDIT" @click="toggleTask(r)">{{ r.enabled === 'Y' ? '停用' : '启用' }}</a>
                <a class="lk" v-permission="PERM_EXEC" @click="runOnce(r)">立即执行</a>
              </td>
            </tr>
            <tr v-if="!tasks.length"><td colspan="11" class="empty">暂无注册任务</td></tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- 执行日志 -->
    <template v-if="tab === 'logs'">
      <div class="page-ops">
        <label>任务：</label>
        <select v-model="logFilter.taskCode" style="width:240px">
          <option value="">全部任务</option>
          <option v-for="r in tasks" :key="r.taskCode" :value="r.taskCode">{{ r.taskName }}</option>
        </select>
        <button class="btn primary" @click="searchLogs">查询</button>
        <span class="muted">日志保留 90 天，到期自动清理</span>
      </div>
      <div class="tablebox">
        <table class="data">
          <thead>
            <tr><th>任务编码</th><th>触发方式</th><th>操作人</th><th>开始时间</th>
            <th>完成时间</th><th>结果</th><th>执行信息</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in logs" :key="r.id">
              <td>{{ r.taskCode }}</td>
              <td>{{ triggerText(r.triggerType) }}</td>
              <td>{{ r.operatorName || '系统' }}</td>
              <td>{{ time(r.startTime) }}</td>
              <td>{{ time(r.finishTime) }}</td>
              <td><span :class="['tag', resultTag(r.result)]">{{ resultText(r.result) }}</span></td>
              <td class="msg-cell wide">{{ r.message || '—' }}</td>
            </tr>
            <tr v-if="!logs.length"><td colspan="7" class="empty">暂无执行日志</td></tr>
          </tbody>
        </table>
      </div>
      <div class="pager">
        <button class="btn" :disabled="logPageNo <= 1" @click="logPageNo--; loadLogs()">上一页</button>
        <span>第 {{ logPageNo }} 页 / 共 {{ logTotal }} 条</span>
        <button class="btn" :disabled="logPageNo * logPageSize >= logTotal" @click="logPageNo++; loadLogs()">下一页</button>
      </div>
    </template>

    <!-- cron 编辑弹窗 -->
    <div v-if="editModal.open" class="modal-mask" @click.self="editModal.open = false">
      <div class="modal w560">
        <div class="modal-h">
          修改执行计划：{{ editModal.taskName }}
          <span class="modal-x" @click="editModal.open = false">×</span>
        </div>
        <div class="modal-b">
          <div class="form-line">
            <label>任务编码：</label><span class="mono">{{ editModal.taskCode }}</span>
          </div>
          <div class="form-line">
            <label>cron 表达式：</label>
            <input v-model="editModal.cronExpr" class="mono" style="width:240px"
                   placeholder="秒 分 时 日 月 周（6 段）">
            <button class="btn" @click="previewNext">预览未来 5 次</button>
          </div>
          <p class="tip">
            6 段 Spring cron（秒 分 时 日 月 周）。例：自动日结在凌晨 02:40 执行 →
            <span class="mono">0 40 2 * * ?</span>；每天 03:10 → <span class="mono">0 10 3 * * ?</span>
          </p>
          <div v-if="editModal.previewed && editModal.nextTimes.length" class="next-box">
            <div class="sub-title">未来 5 次执行时间</div>
            <ul>
              <li v-for="(t, i) in editModal.nextTimes" :key="i">{{ t }}</li>
            </ul>
          </div>
          <div class="form-line top">
            <label>备注：</label>
            <textarea v-model="editModal.remark" rows="2" style="flex:1"
                      placeholder="调整原因（可选）"></textarea>
          </div>
          <div style="text-align:right;margin-top:12px">
            <button class="btn" @click="editModal.open = false">取消</button>
            <button class="btn primary" v-permission="PERM_EDIT" :disabled="busy" @click="saveEdit">保存并重排</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.muted { color: #888; font-size: 13px; }
.mono { font-family: 'Fira Code', Consolas, monospace; font-size: 12px; }
.nowrap { white-space: nowrap; }
.tabbar { display: flex; gap: 4px; }
.tab-btn {
  border: 1px solid #d9d9d9; background: #fafafa; padding: 6px 16px; cursor: pointer;
  border-radius: 4px 4px 0 0; font-size: 13px;
}
.tab-btn.on { background: var(--primary, #1d6fd1); color: #fff; border-color: var(--primary, #1d6fd1); }
.tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 12px; }
.tag-green { background: #e8f8ee; color: #1a9e54; }
.tag-gray { background: #f0f0f0; color: #888; }
.tag-red { background: #fdeaea; color: #d33; }
.tip-box {
  background: #e8f1fd; border-left: 4px solid #1d6fd1; color: #345;
  padding: 8px 12px; margin-bottom: 12px; font-size: 13px; border-radius: 2px;
}
.tip { color: #888; font-size: 12px; margin: 6px 0; }
.msg-cell { max-width: 200px; font-size: 12px; color: #555; word-break: break-all; }
.msg-cell.wide { max-width: 420px; }
.lk { color: #1d6fd1; cursor: pointer; margin-right: 10px; font-size: 13px; }
.empty { text-align: center; color: #999; padding: 14px; }
.form-line { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.form-line label { width: 90px; text-align: right; color: #555; font-size: 13px; }
.form-line.top { align-items: flex-start; }
.next-box { background: #fafbfc; border: 1px solid #eee; border-radius: 4px; padding: 8px 12px; margin: 8px 0; }
.next-box ul { margin: 4px 0 0 18px; font-size: 13px; }
.sub-title { font-weight: 700; font-size: 13px; }
</style>
