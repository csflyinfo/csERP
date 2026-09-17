<script setup>
/**
 * 供应商应付期初初始化（PRD-34 / PRD-36 M4）：
 * 导入/手工行（应付 + 期初预付同批）→ 暂存核对 → 期初建账写 fin_ap（QCAP）与预付流水（QCYF）→ 可反建账；
 * 已上线后漏录的期初预付走「期初预付补录」独立通道（可多批）。
 */
import { ref, computed } from 'vue'
import { post } from '../../api/client.js'
import OpeningShell from './OpeningShell.vue'
import OpeningInitPanel from './OpeningInitPanel.vue'
import InitApPrepayPanel from './InitApPrepayPanel.vue'

const shellRef = ref(null)
const suppliers = ref([])
const supplementOpen = ref(false)

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
  { f: 'apAmount', t: '应付金额', num: true, w: '120px' },
  { f: 'prepayAmount', t: '期初预付金额', num: true, w: '130px' },
  { f: 'remark', t: '备注' },
  { f: 'generatedApNo', t: '应付单号', w: '150px' },
  { f: 'generatedPrepayFlowId', t: '预付流水号', w: '160px' },
]

const formDef = computed(() => [
  { f: 'supplierCode', l: '供应商编码', required: true, type: 'select', options: suppliers.value, placeholder: '选择供应商' },
  { f: 'supplierName', l: '供应商名称（留空取档案）', type: 'text', placeholder: '可不填，建账时按编码回写' },
  { f: 'originalBillNo', l: '原单据号（仅留存）', type: 'text' },
  { f: 'originalBillDate', l: '原单据日期（仅留存）', type: 'date' },
  { f: 'apAmount', l: '应付金额（未付余额）', type: 'number', step: '0.01',
    hint: '含税、≥0；与期初预付至少一项大于 0；账龄自建账日起算，到期日=建账日+30天' },
  { f: 'prepayAmount', l: '期初预付金额', type: 'number', step: '0.01',
    hint: '≥0；建账只登供应商账户预付余额（QCYF 流水），不动资金、不造单；上线后补录请走补录通道' },
  { f: 'remark', l: '备注', type: 'textarea' },
])

const perm = { view: 'fin.init_ap.view', edit: 'fin.init_ap.edit', delete: 'fin.init_ap.delete', import: 'fin.init_ap.import' }

function fmt(v) {
  return (Number(v || 0)).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<template>
  <OpeningShell ref="shellRef" api-base="/init/ap" entity-label="供应商应付/预付"
                perm-post="fin.init_ap.post" perm-reverse="fin.init_ap.reverse"
                post-confirm="确认将暂存的供应商期初建账？应付行生成应付单（QCAP- 开头，账龄自建账日起算），预付行登记供应商账户预付流水（QCYF- 开头，不动资金不造单）。">
    <template #summary="{ status }">
      <div v-if="!status.posted" class="summary-bar">
        <span>暂存有效行 <b>{{ status.lineCount || 0 }}</b></span>
        <span class="sep">|</span>
        <span>错误行 <b :class="{ red: status.errorCount > 0 }">{{ status.errorCount || 0 }}</b></span>
        <span class="sep">|</span>
        <span>应付合计 <b class="money">￥{{ fmt(status.totalAmount) }}</b></span>
        <span class="sep">|</span>
        <span>期初预付合计 <b class="money prepay">￥{{ fmt(status.totalPrepayAmount) }}</b></span>
        <div class="spacer"></div>
        <span v-if="status.errorCount > 0" class="warn-text">存在错误行，修正或删除后才能建账</span>
        <span v-else-if="!status.lineCount" class="muted">请先导入或手工新增期初行</span>
      </div>
      <div v-else class="summary-bar">
        <span>建账行数 <b>{{ status.postLineCount || 0 }}</b></span>
        <span class="sep">|</span>
        <span>应付合计 <b class="money">￥{{ fmt(status.postTotalAmount) }}</b></span>
        <span class="sep">|</span>
        <span>期初预付合计 <b class="money prepay">￥{{ fmt(status.postTotalPrepayAmount) }}</b></span>
      </div>
    </template>

    <template #default="{ locked }">
      <div class="supplement-entry">
        <span class="muted">已上线后漏录的期初预付走独立补录通道（独立批号、可多批，不影响应付初始化与建账锁定）</span>
        <div class="spacer"></div>
        <button class="btn sm" v-permission="'fin.init_ap.post'" @click="supplementOpen = true">期初预付补录</button>
      </div>
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
      <InitApPrepayPanel :visible="supplementOpen" :supplier-options="suppliers"
                        @close="supplementOpen = false" />
    </template>
  </OpeningShell>
</template>

<style scoped>
.summary-bar { display: flex; align-items: center; gap: 10px; background: #fff; border: 1px solid #f0f0f0;
  border-radius: 8px; padding: 10px 14px; margin-top: 10px; font-size: 13px; }
.spacer { flex: 1; }
.sep { color: #e0e0e0; }
.money { color: #cf1322; font-size: 15px; }
.money.prepay { color: #d46b08; }
.red { color: #cf1322; }
.warn-text { color: #d46b08; font-size: 12px; }
.muted { color: #999; font-size: 12px; }
.supplement-entry { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
.btn.sm { padding: 4px 12px; font-size: 13px; }
</style>
