<script setup>
/**
 * 供应商应付期初初始化（PRD-34）：导入/手工行 → 暂存核对 → 期初建账写 fin_ap → 可反建账。
 */
import { ref, computed } from 'vue'
import { post } from '../../api/client.js'
import OpeningShell from './OpeningShell.vue'
import OpeningInitPanel from './OpeningInitPanel.vue'

const shellRef = ref(null)
const suppliers = ref([])

async function loadMasters() {
  try {
    const res = await post('/base/supplier/page', { pageNo: 1, pageSize: 500, filters: {} })
    suppliers.value = (res.records || [])
      .filter(s => s.status !== 'DELETED' && s.supplierStatus !== 'DELETED')
      .map(s => ({ value: s.supplierCode, label: `${s.supplierCode} ${s.supplierName}` }))
  } catch (e) {
    suppliers.value = []
  }
}
loadMasters()

const columns = [
  { f: 'supplierCode', t: '供应商编码', w: '140px' },
  { f: 'supplierName', t: '供应商名称' },
  { f: 'originalBillNo', t: '原单据号', w: '150px' },
  { f: 'originalBillDate', t: '原单据日期', w: '110px' },
  { f: 'apAmount', t: '应付金额', num: true, w: '130px' },
  { f: 'remark', t: '备注' },
  { f: 'generatedApNo', t: '应付单号', w: '150px' },
]

const formDef = computed(() => [
  { f: 'supplierCode', l: '供应商编码', required: true, type: 'select', options: suppliers.value, placeholder: '选择供应商' },
  { f: 'supplierName', l: '供应商名称（留空取档案）', type: 'text', placeholder: '可不填，建账时按编码回写' },
  { f: 'originalBillNo', l: '原单据号（仅留存）', type: 'text' },
  { f: 'originalBillDate', l: '原单据日期（仅留存）', type: 'date' },
  { f: 'apAmount', l: '应付金额（未付余额）', required: true, type: 'number', step: '0.01', hint: '含税、>0；账龄自建账日起算，到期日=建账日+30天' },
  { f: 'remark', l: '备注', type: 'textarea' },
])

const perm = { view: 'fin.init_ap.view', edit: 'fin.init_ap.edit', delete: 'fin.init_ap.delete', import: 'fin.init_ap.import' }

function fmt(v) {
  return (Number(v || 0)).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<template>
  <OpeningShell ref="shellRef" api-base="/init/ap" entity-label="供应商应付"
                perm-post="fin.init_ap.post" perm-reverse="fin.init_ap.reverse"
                post-confirm="确认将暂存的供应商应付期初建账？将逐行生成应付单（来源号 QCAP- 开头），账龄自建账日起算。">
    <template #summary="{ status }">
      <div v-if="!status.posted" class="summary-bar">
        <span>暂存有效行 <b>{{ status.lineCount || 0 }}</b></span>
        <span class="sep">|</span>
        <span>错误行 <b :class="{ red: status.errorCount > 0 }">{{ status.errorCount || 0 }}</b></span>
        <span class="sep">|</span>
        <span>应付合计 <b class="money">￥{{ fmt(status.totalAmount) }}</b></span>
        <div class="spacer"></div>
        <span v-if="status.errorCount > 0" class="warn-text">存在错误行，修正或删除后才能建账</span>
        <span v-else-if="!status.lineCount" class="muted">请先导入或手工新增期初行</span>
      </div>
      <div v-else class="summary-bar">
        <span>建账行数 <b>{{ status.postLineCount || 0 }}</b></span>
        <span class="sep">|</span>
        <span>应付合计 <b class="money">￥{{ fmt(status.postTotalAmount) }}</b></span>
      </div>
    </template>

    <template #default="{ locked }">
      <OpeningInitPanel
        api-base="/init/ap"
        :perm="perm"
        preset-key="initAp"
        entity-label="供应商应付"
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
