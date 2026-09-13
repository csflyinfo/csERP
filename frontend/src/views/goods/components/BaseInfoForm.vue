<script setup>
import { watch, ref, onMounted } from 'vue'
import { post } from '../../../api/client.js'
import { pinyin } from 'pinyin-pro'
import CategoryTreeSelect from './CategoryTreeSelect.vue'
import SearchSelect from '../../../components/SearchSelect.vue'

// 写死枚举选项（SearchSelect 保留原值类型，布尔字段必须用 {value:true/false} 而非字符串）
const YES_NO_OPTS = [{ value: true, label: '是' }, { value: false, label: '否' }]
const GOODS_TYPE_OPTS = ['正常商品', '组合商品', '服务商品', '赠品'].map(v => ({ value: v, label: v }))
const GOODS_LEVEL_OPTS = [
  { value: '', label: '请选择' },
  { value: 'A级', label: 'A级' }, { value: 'B级', label: 'B级' }, { value: 'C级', label: 'C级' },
]
const TAX_RATE_OPTS = [
  { value: '', label: '请选择' },
  { value: '13%', label: '13%' }, { value: '9%', label: '9%' },
  { value: '6%', label: '6%' }, { value: '0%', label: '免税' },
]
const STATUS_OPTS = [{ value: '正常', label: '正常' }, { value: '停用', label: '停用' }]

const props = defineProps({
  modelValue: { type: Object, required: true },
  /** 抽屉可见时重新加载下拉数据（保证新建仓库/分类等后立刻看到） */
  reloadTrigger: { type: [Number, Boolean, String], default: 0 },
})

// 真实数据源：从后端加载
// 分类：categoryNodes 为全量「正常」分类扁平节点（交给树选择器建树）
const categoryNodes = ref([])
const brandOptions = ref([])
const warehouseOptions = ref([])
const employeeOptions = ref([])
const supplierOptions = ref([])

// 后端 /page 单页硬上限 200，分类已有数百条，必须循环翻页拉全量；
// 否则末级判定所用的 parentCode 集合只来自第一页，中间层会被误判为末级
async function fetchAllCategories() {
  const all = []
  const pageSize = 200
  for (let pageNo = 1; pageNo <= 50; pageNo++) {
    const res = await post('/base/category/page', { pageNo, pageSize, filters: {} })
    all.push(...(res.records || []))
    if (all.length >= (res.total || 0) || !(res.records || []).length) break
  }
  return all
}

async function loadOptions() {
  const params = { pageNo: 1, pageSize: 500, filters: {} }
  try {
    const [allCats, brand, wh, emp, sup] = await Promise.all([
      fetchAllCategories().catch(() => []),
      post('/base/brand/page', params).catch(() => ({ records: [] })),
      post('/base/warehouse/page', params).catch(() => ({ records: [] })),
      post('/base/master/employee/page', params).catch(() => ({ records: [] })),
      post('/base/supplier/page', params).catch(() => ({ records: [] })),
    ])
    // 只展示「正常」状态分类；树选择器内部据此建树并仅允许选择末级
    categoryNodes.value = allCats
      .filter(r => r.categoryName && (!r.status || r.status === 'NORMAL' || r.status === '正常'))
      .map(r => ({
        categoryCode: r.categoryCode,
        categoryName: r.categoryName,
        parentCode: r.parentCode || '',
        defaultTaxRate: r.defaultTaxRate || '',
      }))
    brandOptions.value = (brand.records || []).map(r => r.brandName).filter(Boolean)
    warehouseOptions.value = (wh.records || []).map(r => r.warehouseName).filter(Boolean)
    employeeOptions.value = (emp.records || []).map(r => r.employeeName).filter(Boolean)
    supplierOptions.value = (sup.records || []).map(r => r.supplierName).filter(Boolean)
  } catch (e) {
    console.warn('加载下拉数据失败', e)
  }
}

onMounted(loadOptions)
// 抽屉每次打开都重新加载，避免用户刚建仓库/分类却看不到
watch(() => props.reloadTrigger, (val) => {
  if (val) loadOptions()
})

// ==================== 简拼生成 ====================
// 用 pinyin-pro 取中文首字母，英文/数字大写保留
function generateSimpleCode(name) {
  if (!name) return ''
  // pinyin-pro：pattern:'first' 返回每个字的首字母，type:'string' 拼接返回，separator:'' 无分隔
  const first = pinyin(name, { pattern: 'first', type: 'string', separator: '', v: true })
  let result = ''
  for (const char of first) {
    const code = char.charCodeAt(0)
    if (code >= 65 && code <= 90) result += char
    else if (code >= 97 && code <= 122) result += String.fromCharCode(code - 32)
    else if (code >= 48 && code <= 57) result += char
  }
  return result
}

// 商品名称变化时，简拼始终随之重新生成（覆盖用户手工修改）
watch(() => props.modelValue.goodsName, (newName) => {
  props.modelValue.simpleCode = generateSimpleCode(newName || '')
})

function onSimpleCodeInput(e) {
  props.modelValue.simpleCode = e.target.value
}

// ==================== 分类 → 税率 联动 ====================
// 树选择器选中末级分类时回调；若该分类有默认税率，则填入税率字段（用户仍可手动修改）
function onCategorySelect({ name, taxRate }) {
  props.modelValue.categoryName = name
  if (!name) {
    // 清除分类时税率保留（可能是手工设置），不联动清空
    return
  }
  if (taxRate) {
    // 归一化：既支持 "13%" 也支持数字
    props.modelValue.taxRate = /%$/.test(taxRate) ? taxRate : `${taxRate}%`
  }
}
</script>

<template>
  <div class="base-info-form">
    <!-- 第一行：编码 · 商品分类 · 商品名称（占 2） -->
    <div class="row">
      <div class="field">
        <label>商品编码 <span class="required">*</span></label>
        <input type="text" v-model="modelValue.goodsCode" placeholder="请输入商品编码" />
      </div>
      <div class="field">
        <label>商品分类 <span class="required">*</span></label>
        <CategoryTreeSelect
          v-model="modelValue.categoryName"
          :nodes="categoryNodes"
          :placeholder="categoryNodes.length ? '请选择末级分类' : '请先在【商品分类】维护'"
          @select="onCategorySelect"
        />
      </div>
      <div class="field field-wide">
        <label>商品名称 <span class="required">*</span></label>
        <input type="text" v-model="modelValue.goodsName" placeholder="请输入商品名称" />
      </div>
    </div>

    <!-- 第二行：规格 · 简拼 · 默认供应商（占 2） -->
    <div class="row">
      <div class="field">
        <label>规格型号 <span class="required">*</span></label>
        <input type="text" v-model="modelValue.spec" placeholder="如: 500ml*24瓶/箱" />
      </div>
      <div class="field">
        <label>简拼</label>
        <input type="text" :value="modelValue.simpleCode" @input="onSimpleCodeInput" placeholder="自动生成拼音首字母" />
      </div>
      <div class="field field-wide">
        <label>默认供应商</label>
        <SearchSelect
          v-model="modelValue.defaultSupplier"
          :options="supplierOptions"
          :placeholder="supplierOptions.length ? '请选择' : '请先在【供应商资料】维护'"
          empty-text="请先在【供应商资料】维护供应商"
          search-placeholder="输入名称/拼音首字母搜索"
        />
      </div>
    </div>

    <!-- 第三行：品牌 · 类型 · 等级 · 默认仓库 -->
    <div class="row">
      <div class="field">
        <label>品牌 <span class="required">*</span></label>
        <SearchSelect
          v-model="modelValue.brandName"
          :options="brandOptions"
          :placeholder="brandOptions.length ? '请选择' : '请先在【品牌管理】维护'"
          empty-text="请先在【品牌管理】维护品牌"
          search-placeholder="输入名称/拼音首字母搜索"
        />
      </div>
      <div class="field">
        <label>商品类型</label>
        <SearchSelect v-model="modelValue.goodsType" :options="GOODS_TYPE_OPTS" search-placeholder="输入关键字搜索" />
      </div>
      <div class="field">
        <label>商品等级</label>
        <SearchSelect v-model="modelValue.goodsLevel" :options="GOODS_LEVEL_OPTS" search-placeholder="输入关键字搜索" />
      </div>
      <div class="field">
        <label>默认仓库 <span class="required">*</span></label>
        <SearchSelect
          v-model="modelValue.defaultWarehouse"
          :options="warehouseOptions"
          :placeholder="warehouseOptions.length ? '请选择' : '请先在【仓库资料】维护'"
          empty-text="请先在【仓库资料】维护仓库"
          search-placeholder="输入名称/拼音首字母搜索"
        />
      </div>
    </div>

    <!-- 第四行：税率 · 负责人 · 状态 · 可退货 -->
    <div class="row">
      <div class="field">
        <label>税率</label>
        <SearchSelect v-model="modelValue.taxRate" :options="TAX_RATE_OPTS" search-placeholder="输入关键字搜索" />
      </div>
      <div class="field">
        <label>商品负责人</label>
        <SearchSelect
          v-model="modelValue.goodsManager"
          :options="employeeOptions"
          :placeholder="employeeOptions.length ? '请选择' : '请先在【人员信息】维护'"
          empty-text="请先在【人员信息】维护人员"
          search-placeholder="输入姓名/拼音首字母搜索"
        />
      </div>
      <div class="field">
        <label>状态</label>
        <SearchSelect v-model="modelValue.status" :options="STATUS_OPTS" search-placeholder="输入关键字搜索" />
      </div>
      <div class="field">
        <label>可退货</label>
        <SearchSelect v-model="modelValue.canReturn" :options="YES_NO_OPTS" search-placeholder="输入关键字搜索" />
      </div>
    </div>

    <!-- 第五行：是否称重 · 是否预售 -->
    <div class="row">
      <div class="field">
        <label>是否称重</label>
        <SearchSelect v-model="modelValue.isWeighted" :options="YES_NO_OPTS" search-placeholder="输入关键字搜索" />
      </div>
      <div class="field">
        <label>是否预售</label>
        <SearchSelect v-model="modelValue.isPresale" :options="YES_NO_OPTS" search-placeholder="输入关键字搜索" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.base-info-form {
  max-width: 100%;
  margin: 0 auto;
}

.row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px 20px;
  margin-bottom: 16px;
  align-items: center;
}

.field {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 10px;
}

/** 双栏字段（如商品名称、默认供应商） */
.field.field-wide {
  grid-column: span 2;
}

.field.full-width {
  grid-column: 1 / -1;
}

.field label {
  font-size: 12px;
  font-weight: 600;
  color: #303133;
  width: 80px;
  text-align: right;
  flex-shrink: 0;
  line-height: 32px;
}

.field .required {
  color: #f56c6c;
  margin-left: 2px;
}

.field input,
.field select {
  flex: 1;
  height: 32px;
  padding: 0 12px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 12px;
  color: #606266;
  transition: all 0.2s;
  box-sizing: border-box;
  min-width: 80px;
  outline: none;
  background: #fff;
  font-weight: 400;
}

.field input:focus,
.field select:focus {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.1);
}

.field select {
  cursor: pointer;
  appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 1024 1024' xmlns='http://www.w3.org/2000/svg' width='12' height='12'%3E%3Cpath d='M256 384l256 256 256-256H256z' fill='%23909399'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 8px center;
  padding-right: 28px;
}
</style>
