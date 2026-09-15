<script setup>
/**
 * 客户应收期初初始化（PRD-34）：导入/手工行 → 暂存核对 → 期初建账写 fin_ar → 可反建账。
 */
import { ref, computed } from 'vue'
import { post } from '../../api/client.js'
import OpeningShell from './OpeningShell.vue'
import OpeningInitPanel from './OpeningInitPanel.vue'

const shellRef = ref(null)
const customers = ref([])

async function loadMasters() {
  try {
    const res = await post('/base/customer/page', { pageNo: 1, pageSize: 500, filters: {} })
    customers.value = (res.records || [])
      .filter(c => c.status !== 'DELETED' && c.customerStatus !== 'DELETED')
      .map(c => ({ value: c.customerCode, label: `${c.customerCode} ${c.customerName}` }))
  } catch (e) {
    customers.value = []
  }
}
loadMasters()

const columns = [
  { f: 'customerCode', t: '客户编码', w: '140px' },
  { f: 'customerName', t: '客户名称' },
  { f: 'originalBillNo', t: '原单据号', w: '150px' },
  { f: 'originalBillDate', t: '原单据日期', w: '110px' },
  { f: 'arAmount', t: '应收金额', num: true, w: '130px' },
  { f: 'salesman', t: '业务员', w: '100px' },
  { f: 'remark', t: '备注' },
  { f: 'generatedArNo', t: '应收单号', w: '150px' },
]

const formDef = computed(() => [
  { f: 'customerCode', l: '客户编码', required: true, type: 'select', options: customers.value, placeholder: '选择客户' },
  { f: 'customerName', l: '客户名称（留空取档案）', type: 'text', placeholder: '可不填，建账时按编码回写' },
  { f: 'originalBillNo', l: '原单据号（仅留存）', type: 'text' },
  { f: 'originalBillDate', l: '原单据日期（仅留存）', type: 'date' },
  { f: 'arAmount', l: '应收金额（未收余额）', required: true, type: 'number', step: '0.01', hint: '含税、>0；账龄自建账日起算，到期日=建账日+30天' },
  { f: 'salesman', l: '业务员', type: 'text' },
  { f: 'remark', l: '备注', type: 'textarea' },
])

const perm = { view: 'fin.init_ar.view', edit: 'fin.init_ar.edit', delete: 'fin.init_ar.delete', import: 'fin.init_ar.import' }

function fmt(v) {
  return (Number(v || 0)).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<template>
  <OpeningShell ref="shellRef" api-base="/init/ar" entity-label="客户应收"
                perm-post="fin.init_ar.post" perm-reverse="fin.init_ar.reverse"
                post-confirm="确认将暂存的客户应收期初建账？将逐行生成应收单（来源号 QCAR- 开头），账龄自建账日起算。">
    <template #summary="{ status }">
      <div v-if="!status.posted" class="summary-bar">
        <span>暂存有效行 <b>{{ status.lineCount || 0 }}</b></span>
        <span class="sep">|</span>
        <span>错误行 <b :class="{ red: status.errorCount > 0 }">{{ status.errorCount || 0 }}</b></span>
        <span class="sep">|</span>
        <span>应收合计 <b class="money">￥{{ fmt(status.totalAmount) }}</b></span>
        <div class="spacer"></div>
        <span v-if="status.errorCount > 0" class="warn-text">存在错误行，修正或删除后才能建账</span>
        <span v-else-if="!status.lineCount" class="muted">请先导入或手工新增期初行</span>
      </div>
      <div v-else class="summary-bar">
        <span>建账行数 <b>{{ status.postLineCount || 0 }}</b></span>
        <span class="sep">|</span>
        <span>应收合计 <b class="money">￥{{ fmt(status.postTotalAmount) }}</b></span>
      </div>
    </template>

    <template #default="{ locked }">
      <OpeningInitPanel
        api-base="/init/ar"
        :perm="perm"
        preset-key="initAr"
        entity-label="客户应收"
        :columns="columns"
        :form-def="formDef"
        :locked="locked"
        @changed="shellRef?.loadStatus()"
      />
    </template>
  </OpeningShell>
</template>

<style scoped>
.summary-bar { display: flex; align-items: center; gap: 10px; background: #fff; border: 1px solid #f0f0f0;
  border-radius: 8px; padding: 10px 14px; margin-top: 10px; font-size: 13px; }
.spacer { flex: 1; }
.sep { color: #e0e0e0; }
.money { color: #cf1322; font-size: 15px; }
.red { color: #cf1322; }
.warn-text { color: #d46b08; font-size: 12px; }
.muted { color: #999; font-size: 12px; }
</style>
