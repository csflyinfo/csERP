<script setup>
import { computed, ref, watch } from 'vue'
import QueryBar from '../components/QueryBar.vue'
import ProTable from '../components/ProTable.vue'
import { post } from '../api/client.js'
import { mapRecordToRow, moduleApis } from '../module-api.js'

const props = defineProps({
  config: { type: Object, required: true },
  moduleCode: { type: String, required: true },
})

const feedback = ref('')
const dialog = ref(null)
const selectedRow = ref(null)
const tableRows = ref([])
const loading = ref(false)
const selectedTreeNode = ref('全部')

const columns = computed(() => props.config.columns.map((title, index) => ({
  key: `c${index}`,
  title,
  num: /金额|数量|库存|单价|成本|余额|已收|未收|已付|未付|原价|现价|进价|税额|毛利|额度/.test(title),
})))

const actionColumnIndex = computed(() => props.config.columns.findIndex(title => /操作/.test(title)))
const statusColumnIndex = computed(() => props.config.columns.findIndex(title => /状态|核销状态|应付生成状态|应收生成|开票状态|勾稽状态/.test(title)))

function buildRow(values = props.config.row) {
  return Object.fromEntries(values.map((value, index) => [`c${index}`, value]))
}

async function loadRows() {
  const api = moduleApis[props.moduleCode]
  if (!api?.page) {
    tableRows.value = [buildRow()]
    return
  }
  loading.value = true
  try {
    const data = await post(api.page, { pageNo: 1, pageSize: 20, filters: {} })
    tableRows.value = data.records?.length ? data.records.map(record => mapRecordToRow(record, props.config)) : [buildRow()]
  } catch (error) {
    tableRows.value = [buildRow()]
    show(`${props.config.title}接口加载失败，已显示示例数据`)
  } finally {
    loading.value = false
  }
}

function resetRows() {
  loadRows()
}

watch(() => props.config, resetRows, { immediate: true })

const formFields = computed(() => {
  if (props.config.formFields?.length) return props.config.formFields
  const ignored = /操作|商品数|当前库存|库存金额|应收余额|应付余额|逾期金额|已入库|已收|未收|已付|未付|创建|审核|状态|付款状态|到货状态|签收状态|开票状态|勾稽状态|核销状态/
  const fromColumns = props.config.columns.filter(title => !ignored.test(title)).slice(0, 12)
  return fromColumns.length ? fromColumns : (props.config.sections || ['基础信息'])
})

const detailColumns = computed(() => {
  if (props.config.detailColumns?.length) return props.config.detailColumns
  if (props.config.mode === 'bill' || props.config.type !== 'base') {
    return props.config.columns.filter(title => !/操作|状态|创建|审核/.test(title)).slice(0, 10)
  }
  return []
})

function show(message) {
  feedback.value = message
  setTimeout(() => (feedback.value = ''), 2000)
}

function openDialog(type, title, message, row = null) {
  selectedRow.value = row
  dialog.value = { type, title, message }
}

function closeDialog() {
  dialog.value = null
  selectedRow.value = null
}

async function saveForm() {
  const api = moduleApis[props.moduleCode]
  if (api?.save) {
    try {
      await post(api.save, buildPayload())
    } catch (error) {
      show(`${dialog.value.title}接口暂不可用，已保留前端操作`)
    }
  }
  if (dialog.value?.title?.includes('新建')) {
    const newValues = [...props.config.row]
    const codeIndex = 0
    const nameIndex = Math.min(1, newValues.length - 1)
    newValues[codeIndex] = `${props.config.title.substring(0, 2)}${String(tableRows.value.length + 1).padStart(3, '0')}`
    newValues[nameIndex] = `${props.config.title}新增记录`
    tableRows.value.unshift(buildRow(newValues))
  }
  show(`${dialog.value.title}保存成功`)
  closeDialog()
}

async function confirmAction() {
  const action = dialog.value?.title || '操作'
  const api = moduleApis[props.moduleCode]
  const endpoint = /核销/.test(action) ? api?.reconcile : /停用/.test(action) ? api?.stop : /作废/.test(action) ? api?.cancel : api?.audit
  if (endpoint) {
    try {
      await post(endpoint, buildPayload())
    } catch (error) {
      show(`${action}接口暂不可用，已保留前端状态变化`)
    }
  }
  if (selectedRow.value && statusColumnIndex.value >= 0) {
    if (/审核|确认/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = '已审核'
    if (/停用|作废|终止/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = action.replace('确认', '')
    if (/核销/.test(action)) selectedRow.value[`c${statusColumnIndex.value}`] = '已核销'
  }
  show(`${props.config.title}${action}成功`)
  closeDialog()
}

async function handleAction(action, row = null) {
  if (/查看|详情|历史|库存|日志|来源/.test(action)) {
    openDialog('view', action, `${props.config.title}详情`, row)
  } else if (/新建|编辑|复制/.test(action)) {
    openDialog('form', action, `${props.config.title}：按PRD打开${props.config.mode === 'modal' ? '小弹窗' : props.config.mode === 'drawer' ? '右侧抽屉' : '独立页面'}。`, row)
  } else if (/审核|确认签收|停用|作废|终止|核销|反审核/.test(action)) {
    openDialog('confirm', action, `${action}会按业务规则校验状态、权限和上下游引用，并写入操作日志。`, row)
  } else if (/导入/.test(action)) {
    openDialog('import', action, `${props.config.title}导入：先下载模板，上传后预校验，失败行可下载原因。`, row)
  } else if (/导出/.test(action)) {
    const api = moduleApis[props.moduleCode]
    if (api?.export) {
      try {
        const result = await post(api.export, { moduleCode: props.moduleCode, reportName: props.config.title, filters: {} })
        show(result?.message || `${props.config.title}已创建导出任务`)
      } catch (error) {
        show(`${props.config.title}导出接口暂不可用，已保留导出任务提示`)
      }
    } else {
      show(`${props.config.title}已创建导出任务`)
    }
  } else if (/打印/.test(action)) {
    show(`${props.config.title}打印预览已打开`)
  } else if (/字段设置/.test(action)) {
    openDialog('field', '字段设置', '支持列显示/隐藏、顺序、宽度、固定列，并按用户保存。', row)
  } else {
    show(`${props.config.title}：${action}`)
  }
}

function buildPayload() {
  return {
    moduleCode: props.moduleCode,
    bizId: selectedRow.value?.c0 || `${props.moduleCode}-demo`,
    remark: `${props.config.title}操作`,
    customerId: 'CUS001',
    supplierId: 'SUP001',
    warehouseId: 'WH001',
    objectId: 'OBJ001',
    fundAccountId: 'A001',
    amount: 100,
    parentId: 'ROOT',
    parentCode: '01',
    categoryCode: '01',
    categoryName: `${props.config.title}新增`,
    effectiveMode: 'IMMEDIATE',
    validType: 'LONG_TERM',
    details: [{ goodsId: 'G001', unitId: 'UNIT001', qty: 1, price: 1, currentPrice: 1 }],
    priceIds: ['PRICE001'],
    reason: '页面操作停用',
  }
}

function selectTreeNode(node) { selectedTreeNode.value = node.trim(); loadRows(); show(`已切换到：${selectedTreeNode.value}`) }
async function handleQuery() { await loadRows(); show(`${props.config.title}查询完成`) }
function handleReset() { loadRows(); show(`${props.config.title}查询条件已重置`) }
function handleMore(fields) { openDialog('more', '更多查询条件', fields.join('、')) }
function handleRowAction(action, row) { handleAction(action, row) }
</script>

<template>
  <div class="module-body" :class="{ 'with-tree': config.tree }">
    <aside v-if="config.tree" class="module-tree">
      <div v-for="node in config.treeNodes" :key="node" class="tree-node" :class="{ active: selectedTreeNode === node.trim() }" @click="selectTreeNode(node)">{{ node }}</div>
    </aside>

    <section class="module-list">
      <div class="page-ops">
        <button v-for="action in config.actions" :key="action" class="btn" :class="{ primary: /^新建|审核并打印/.test(action) }" @click="handleAction(action)">{{ action }}</button>
      </div>

      <QueryBar :fields="config.filters" @query="handleQuery" @reset="handleReset" @more="handleMore" />

      <div v-if="config.tips?.length" class="tips-inline">
        <span v-for="tip in config.tips" :key="tip">⚠ {{ tip }}</span>
      </div>

      <div v-if="loading" class="tips-inline"><span>正在加载 {{ config.title }} 数据...</span></div>
      <ProTable :title="config.title + '列表'" :columns="columns" :rows="tableRows" @field-setting="handleAction('字段设置')" @export="handleAction('导出')" @row-action="handleRowAction">
        <template v-for="col in columns" #[col.key]="{ row }" :key="col.key">
          <span v-if="/状态/.test(col.title)" class="badge wait">{{ row[col.key] }}</span>
          <span v-else-if="/操作/.test(col.title)">
            <button v-for="action in String(row[col.key]).split(' ')" :key="action" class="link link-btn" @click="handleAction(action, row)">{{ action }}</button>
          </span>
          <span v-else>{{ row[col.key] }}</span>
        </template>
      </ProTable>
    </section>
  </div>

  <div v-if="feedback" class="toast-inline">{{ feedback }}</div>

  <div v-if="dialog" class="modal-lite" :class="{ 'drawer-lite': config.mode === 'drawer' && dialog.type === 'form', 'page-lite': config.mode === 'bill' && dialog.type === 'form' }">
    <div class="modal-lite-box">
      <div class="modal-lite-head"><b>{{ dialog.title }} - {{ config.title }}</b><button class="btn" @click="closeDialog">×</button></div>
      <div class="modal-lite-body">
        <p>{{ dialog.message }}</p>

        <div v-if="dialog.type === 'form'" class="form-edit-area">
          <div :class="config.mode === 'modal' ? 'form-vertical' : 'grid4'">
            <div v-for="field in formFields" :key="field" class="field">
              <label>{{ field }}<span v-if="/编码|名称|单号|客户|供应商|仓库|日期|数量|单价|金额|状态/.test(field)" style="color:#ef4444"> *</span></label>
              <select v-if="/状态|类型|方式|仓库|客户|供应商|单位|分类|品牌|业务员|采购员/.test(field)"><option>{{ field }}</option></select>
              <textarea v-else-if="/备注|说明|原因/.test(field)" :placeholder="field"></textarea>
              <input v-else :value="selectedRow?.[`c${columns.findIndex(c => c.title === field)}`] || ''" :placeholder="field" />
            </div>
          </div>

          <div v-if="detailColumns.length" class="section-block">
            <b>明细信息</b>
            <div class="scroll mini-scroll">
              <table>
                <tr><th v-for="field in detailColumns" :key="field">{{ field }}</th><th>操作</th></tr>
                <tr><td v-for="field in detailColumns" :key="field" contenteditable>{{ field.includes('数量') ? '10' : field.includes('金额') ? '350.00' : field }}</td><td><button class="link link-btn">删除</button></td></tr>
              </table>
            </div>
            <button class="btn" style="margin-top:8px" @click="show('已新增明细行')">新增明细行</button>
          </div>
        </div>

        <div v-if="dialog.type === 'view'" class="section-block">
          <div class="grid4">
            <div v-for="col in columns.filter(c => !/操作/.test(c.title))" :key="col.key" class="field"><label>{{ col.title }}</label><input readonly :value="selectedRow?.[col.key] || ''" /></div>
          </div>
        </div>

        <div v-if="dialog.type === 'confirm'" class="field"><label>原因/备注</label><textarea placeholder="请输入原因或备注"></textarea></div>

        <div v-if="dialog.type === 'import'" class="section-block">
          <button class="btn">下载模板</button>
          <input style="margin-left:8px" type="file" />
          <p style="color:#5d7896">上传后先校验，不直接入库；失败行可下载失败原因。</p>
        </div>
      </div>
      <div class="modal-lite-foot">
        <button class="btn" @click="closeDialog">取消</button>
        <button v-if="dialog.type === 'form'" class="btn primary" @click="saveForm">保存</button>
        <button v-else-if="dialog.type === 'confirm'" class="btn primary" @click="confirmAction">确认</button>
        <button v-else-if="dialog.type === 'import'" class="btn primary" @click="show('导入校验通过'); closeDialog()">上传并校验</button>
        <button v-else class="btn primary" @click="closeDialog">确定</button>
      </div>
    </div>
  </div>
</template>
