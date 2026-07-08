<script setup>
import { watch, ref, onMounted } from 'vue'
import { post } from '../../../api/client.js'
import { pinyin } from 'pinyin-pro'

const props = defineProps({
  modelValue: { type: Object, required: true },
  /** 抽屉可见时重新加载下拉数据（保证新建仓库/分类等后立刻看到） */
  reloadTrigger: { type: [Number, Boolean, String], default: 0 },
})

// 真实数据源：从后端加载
// 分类保留完整对象 { name, taxRate } 以便选择时带出税率
const categoryList = ref([])
const brandOptions = ref([])
const warehouseOptions = ref([])
const employeeOptions = ref([])
const supplierOptions = ref([])

async function loadOptions() {
  const params = { pageNo: 1, pageSize: 500, filters: {} }
  try {
    const [cat, brand, wh, emp, sup] = await Promise.all([
      post('/base/category/page', params).catch(() => ({ records: [] })),
      post('/base/brand/page', params).catch(() => ({ records: [] })),
      post('/base/warehouse/page', params).catch(() => ({ records: [] })),
      post('/base/master/employee/page', params).catch(() => ({ records: [] })),
      post('/base/supplier/page', params).catch(() => ({ records: [] })),
    ])
    // 分类：只显示末级分类（自己的 categoryCode 未被其他节点作为 parentCode）
    const allCats = cat.records || []
    const parentCodes = new Set(allCats.map(r => r.parentCode).filter(Boolean))
    categoryList.value = allCats
      .filter(r => !parentCodes.has(r.categoryCode) && r.categoryName)
      .map(r => ({ name: r.categoryName, taxRate: r.defaultTaxRate || '' }))
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
// 选择分类时，若分类有默认税率，则填入税率字段（用户仍可手动修改）
function onCategoryChange(e) {
  const name = e.target.value
  props.modelValue.categoryName = name
  const hit = categoryList.value.find(c => c.name === name)
  if (hit && hit.taxRate) {
    // 归一化：既支持 "13%" 也支持数字
    const rate = /%$/.test(hit.taxRate) ? hit.taxRate : `${hit.taxRate}%`
    props.modelValue.taxRate = rate
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
        <select :value="modelValue.categoryName" @change="onCategoryChange">
          <option value="">{{ categoryList.length ? '请选择' : '请先在【商品分类】维护' }}</option>
          <option v-for="opt in categoryList" :key="opt.name" :value="opt.name">{{ opt.name }}</option>
        </select>
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
        <select v-model="modelValue.defaultSupplier">
          <option value="">{{ supplierOptions.length ? '请选择' : '请先在【供应商资料】维护' }}</option>
          <option v-for="opt in supplierOptions" :key="opt" :value="opt">{{ opt }}</option>
        </select>
      </div>
    </div>

    <!-- 第三行：品牌 · 类型 · 等级 · 默认仓库 -->
    <div class="row">
      <div class="field">
        <label>品牌 <span class="required">*</span></label>
        <select v-model="modelValue.brandName">
          <option value="">{{ brandOptions.length ? '请选择' : '请先在【品牌管理】维护' }}</option>
          <option v-for="opt in brandOptions" :key="opt" :value="opt">{{ opt }}</option>
        </select>
      </div>
      <div class="field">
        <label>商品类型</label>
        <select v-model="modelValue.goodsType">
          <option value="正常商品">正常商品</option>
          <option value="组合商品">组合商品</option>
          <option value="服务商品">服务商品</option>
          <option value="赠品">赠品</option>
        </select>
      </div>
      <div class="field">
        <label>商品等级</label>
        <select v-model="modelValue.goodsLevel">
          <option value="">请选择</option>
          <option value="A级">A级</option>
          <option value="B级">B级</option>
          <option value="C级">C级</option>
        </select>
      </div>
      <div class="field">
        <label>默认仓库 <span class="required">*</span></label>
        <select v-model="modelValue.defaultWarehouse">
          <option value="">{{ warehouseOptions.length ? '请选择' : '请先在【仓库资料】维护' }}</option>
          <option v-for="opt in warehouseOptions" :key="opt" :value="opt">{{ opt }}</option>
        </select>
      </div>
    </div>

    <!-- 第四行：税率 · 负责人 · 状态 · 可退货 -->
    <div class="row">
      <div class="field">
        <label>税率</label>
        <select v-model="modelValue.taxRate">
          <option value="">请选择</option>
          <option value="13%">13%</option>
          <option value="9%">9%</option>
          <option value="6%">6%</option>
          <option value="0%">免税</option>
        </select>
      </div>
      <div class="field">
        <label>商品负责人</label>
        <select v-model="modelValue.goodsManager">
          <option value="">{{ employeeOptions.length ? '请选择' : '请先在【人员信息】维护' }}</option>
          <option v-for="opt in employeeOptions" :key="opt" :value="opt">{{ opt }}</option>
        </select>
      </div>
      <div class="field">
        <label>状态</label>
        <select v-model="modelValue.status">
          <option value="正常">正常</option>
          <option value="停用">停用</option>
        </select>
      </div>
      <div class="field">
        <label>可退货</label>
        <select v-model="modelValue.canReturn">
          <option :value="true">是</option>
          <option :value="false">否</option>
        </select>
      </div>
    </div>

    <!-- 第五行：是否称重 · 是否预售 -->
    <div class="row">
      <div class="field">
        <label>是否称重</label>
        <select v-model="modelValue.isWeighted">
          <option :value="true">是</option>
          <option :value="false">否</option>
        </select>
      </div>
      <div class="field">
        <label>是否预售</label>
        <select v-model="modelValue.isPresale">
          <option :value="true">是</option>
          <option :value="false">否</option>
        </select>
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
