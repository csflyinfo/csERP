<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        报表导出中心
        <details class="rpt-help">
          <summary>说明</summary>
          <div class="rpt-help-pop">
            各报表点「异步导出」后在此生成 Excel（后台排队执行，不影响正常开单）。
            处理中的任务每 5 秒自动刷新；完成后点「下载」保存文件。失败任务展示失败原因，
            可回原报表缩小日期范围或分批导出。任务保留最近 500 条。
          </div>
        </details>
      </div>
    </div>

    <div class="rpt-filter-card" style="background:#fff;border-radius:6px;padding:12px 14px;box-shadow:0 1px 3px rgba(0,0,0,.06);margin-bottom:10px;">
      <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;">
        <input v-model="keyword" placeholder="任务号 / 报表名称" style="height:30px;border:1px solid #dcdfe6;border-radius:4px;padding:0 8px;min-width:220px;"
               @keyup.enter="loadTasks">
        <button class="btn-primary" @click="loadTasks">查询</button>
        <button class="btn-plain" @click="keyword = ''; loadTasks()">刷新</button>
        <span v-if="runningCount" class="warn-text">有 {{ runningCount }} 个任务处理中，自动刷新中…</span>
      </div>
    </div>

    <div class="drill-card">
      <div class="drill-scroll" style="max-height:calc(100vh - 240px);">
        <table class="drill-table">
          <thead>
            <tr>
              <th style="width:170px;">任务号</th>
              <th style="width:160px;">报表</th>
              <th style="width:80px;">状态</th>
              <th>查询条件</th>
              <th style="width:200px;">文件名</th>
              <th style="width:90px;" class="num">行数</th>
              <th style="width:120px;">发起人</th>
              <th style="width:150px;">创建时间</th>
              <th style="width:150px;">完成时间</th>
              <th style="width:90px;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in tasks" :key="t.taskNo" class="leaf-row">
              <td>{{ t.taskNo }}</td>
              <td>{{ t.reportName }}</td>
              <td>
                <span :class="statusClass(t.rawStatus)">{{ t.statusText }}</span>
              </td>
              <td style="white-space:normal;min-width:260px;color:#606266;">
                {{ t.remark || '' }}
                <div v-if="t.errorMsg" class="warn-text" style="font-size:11px;">失败原因：{{ t.errorMsg }}</div>
              </td>
              <td style="white-space:normal;">{{ t.fileName || '' }}</td>
              <td class="num">{{ t.totalRows == null ? '' : Number(t.totalRows).toLocaleString() }}</td>
              <td>{{ t.createdBy || '' }}</td>
              <td>{{ fmtDateTime(t.createdAt) }}</td>
              <td>{{ fmtDateTime(t.finishedAt) }}</td>
              <td>
                <a v-if="t.rawStatus === 'FINISHED'" class="link-num" @click="download(t)">下载</a>
                <span v-else-if="t.rawStatus === 'FAILED'" class="warn-text">已失败</span>
                <span v-else class="muted">处理中</span>
              </td>
            </tr>
            <tr v-if="!loading && tasks.length === 0">
              <td colspan="10" class="empty-cell">暂无导出任务，到报表页点「异步导出」即可在此下载</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="loading" class="drill-loading">加载中…</div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { exportTaskPage, downloadExportTask } from '@/api/report-center.js'
import { saveBlobFile } from '@/api/client.js'
import { fmtDateTime } from '@/components/report/report-table.js'

const tasks = ref([])
const keyword = ref('')
const loading = ref(false)
let timer = null

const runningCount = computed(() =>
  tasks.value.filter(t => t.rawStatus === 'CREATED' || t.rawStatus === 'RUNNING').length)

async function loadTasks(silent = false) {
  if (!silent) loading.value = true
  try {
    const list = await exportTaskPage({ keyword: keyword.value || null, pageSize: 500 })
    tasks.value = list || []
  } catch (e) {
    if (!silent) alert('加载失败：' + (e.message || '未知错误'))
  } finally {
    if (!silent) loading.value = false
  }
}

function statusClass(s) {
  if (s === 'FINISHED') return 'ok-text'
  if (s === 'FAILED') return 'warn-text'
  return 'muted'
}

async function download(t) {
  try {
    const blob = await downloadExportTask(t.taskNo)
    saveBlobFile(t.fileName || `${t.taskNo}.xlsx`, blob)
  } catch (e) {
    alert('下载失败：' + (e.message || '未知错误'))
  }
}

onMounted(() => {
  loadTasks()
  // 有处理中任务时 5 秒轮询；无任务时停表，不空转
  timer = setInterval(async () => {
    if (runningCount.value > 0) await loadTasks(true)
  }, 5000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
@import './report-page.css';

.link-num {
  color: #409eff;
  text-decoration: underline;
  cursor: pointer;
}
</style>
