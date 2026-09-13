<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useToast } from '../composables/useToast.js'
import { useDashboard } from '../composables/useDashboard.js'
import { post } from '../api/client.js'

const router = useRouter()
const { toast } = useToast()
const {
  flowResult,
  dashboardLoading,
  dashboardError,
  recentLogs,
  dayCloseReminder,
  dashboardCards,
  loadRecentLogs,
  loadDashboardSummary,
  loadDayCloseReminder,
  runCoreFlow
} = useDashboard(toast)

const todoCount = ref(0)
const notifyCount = ref(0)
const todoList = ref([])

async function loadTodoSummary() {
  try {
    const [pending, list] = await Promise.all([
      post('/system/todo/pending-count', {}),
      post('/system/todo/page', { pageNo: 1, pageSize: 5 }),
    ])
    todoCount.value = pending?.count || 0
    todoList.value = (list?.records || []).slice(0, 5)
  } catch (e) {}
}

async function loadNotifyCount() {
  try {
    const data = await post('/system/notification/unread-count', {})
    notifyCount.value = data?.count || 0
  } catch (e) {}
}

function refreshSummary() {
  loadDashboardSummary()
}

function refreshLogs() {
  loadRecentLogs()
}

function doRunCoreFlow() {
  runCoreFlow()
}

function gotoTodo() {
  router.push('/todo')
}

function gotoNotification() {
  router.push('/notification')
}

/** 跳转日结管理处理红色提醒。 */
function gotoDayClose() {
  router.push('/finance/day-close')
}

onMounted(() => {
  loadDashboardSummary()
  loadRecentLogs()
  loadTodoSummary()
  loadNotifyCount()
  loadDayCloseReminder()
})
</script>

<template>
  <section>
    <div class="page-ops">
      <button class="btn" @click="refreshSummary" :disabled="dashboardLoading">刷新经营指标</button>
      <button class="btn primary" @click="doRunCoreFlow">核心闭环自测</button>
      <span v-if="dashboardLoading" class="muted">正在加载经营概览...</span>
      <span v-else-if="dashboardError" class="muted">{{ dashboardError }}</span>
    </div>

    <!-- 业务日结红色提醒（PRD-33：过 P0188 时刻昨日未结 / 自动日结失败） -->
    <div v-if="dayCloseReminder?.show" class="day-close-alert" @click="gotoDayClose">
      <span class="alert-ic">⚠</span>
      <div style="flex:1">
        <template v-if="dayCloseReminder.late">
          <b>业务日结提醒：</b>截至今日 {{ dayCloseReminder.lateHour }}:00，
          {{ dayCloseReminder.yesterday }} 的业务日结尚未执行
          （当前封单日：{{ dayCloseReminder.lastClosed || '无' }}）。
          未日结将阻断总账月末结账，请及时前往「财务管理 - 日结管理」执行封单。
        </template>
        <template v-if="dayCloseReminder.lastFail">
          <b>自动日结失败：</b>{{ dayCloseReminder.lastFail.closeDate }} 自动日结未成功
          （{{ dayCloseReminder.lastFail.detail || '详见日结管理操作日志' }}），请处理后补结。
        </template>
      </div>
      <button class="btn danger-btn" style="height:26px;padding:0 12px;font-size:12px">前往日结</button>
    </div>

    <!-- 待办快捷入口 -->
    <div class="cards" style="grid-template-columns: repeat(6, 1fr); padding: 0 12px 12px">
      <div v-for="item in dashboardCards" :key="item.label" class="card">
        <div>{{ item.label }}</div>
        <div class="value">{{ item.value }}</div>
      </div>
    </div>

    <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px; padding: 0 12px 12px">
      <!-- 待办卡片 -->
      <div class="card" style="padding: 12px">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px">
          <div style="font-weight: 900; color: var(--primary)">待办事项 ({{ todoCount }})</div>
          <button class="btn" style="height: 26px; padding: 0 10px; font-size: 12px" @click="gotoTodo">查看全部</button>
        </div>
        <div v-if="todoList.length === 0" style="color: var(--muted); font-size: 13px; padding: 10px 0">暂无待办事项 🎉</div>
        <div v-for="todo in todoList" :key="todo.todoId" style="display: flex; justify-content: space-between; align-items: center; padding: 6px 0; border-bottom: 1px solid var(--line-soft)">
          <div>
            <div style="font-weight: 700; font-size: 13px">{{ todo.title }}</div>
            <div style="font-size: 11px; color: var(--muted)">{{ todo.bizNo }} | {{ todo.priority === 'HIGH' ? '高优先级' : '普通' }}</div>
          </div>
          <span class="badge wait" style="font-size: 11px; padding: 2px 6px">待处理</span>
        </div>
      </div>

      <!-- 消息卡片 -->
      <div class="card" style="padding: 12px">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px">
          <div style="font-weight: 900; color: var(--primary)">未读消息 ({{ notifyCount }})</div>
          <button class="btn" style="height: 26px; padding: 0 10px; font-size: 12px" @click="gotoNotification">查看全部</button>
        </div>
        <div v-if="notifyCount === 0" style="color: var(--muted); font-size: 13px; padding: 10px 0">暂无未读消息</div>
        <div v-else style="color: var(--muted); font-size: 13px; padding: 10px 0">
          您有 {{ notifyCount }} 条未读消息，点击"查看全部"前往消息中心。
        </div>
      </div>
    </div>

    <div class="tablebox" style="margin-bottom:8px">
      <div class="toolbar">
        <b>最近操作动态</b>
        <div class="spacer"></div>
        <button class="btn" @click="refreshLogs">刷新动态</button>
      </div>
      <div style="padding:12px;display:grid;grid-template-columns:repeat(3,1fr);gap:8px">
        <div
          v-for="log in recentLogs"
          :key="log.bizNo + log.action + log.operateAt"
          class="card"
        >
          <div style="color:var(--muted);font-size:12px">{{ log.operateAt }}</div>
          <div style="font-weight:700">{{ log.action }}</div>
          <div style="font-size:12px">单号: {{ log.bizNo }} | 操作人: {{ log.operator }}</div>
        </div>
      </div>
    </div>
    <div v-if="flowResult" class="card" style="border-left:4px solid var(--primary)">
      <b>自测结果：</b>{{ flowResult.message }}
    </div>
  </section>
</template>

<style scoped>
.cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; padding: 0 12px 12px; }
.cards .card { text-align: center; padding: 16px; }
.cards .value { font-size: 22px; font-weight: 800; color: var(--primary); margin-top: 6px; }
.day-close-alert {
  display: flex; align-items: center; gap: 10px; margin: 0 12px 12px;
  padding: 10px 14px; background: #fdeaea; border: 1px solid #f5b5b5;
  border-left: 4px solid #d33; border-radius: 4px; color: #a02121;
  font-size: 13px; cursor: pointer;
}
.alert-ic { font-size: 18px; }
.danger-btn { background: #d33; color: #fff; border-color: #d33; }
</style>
