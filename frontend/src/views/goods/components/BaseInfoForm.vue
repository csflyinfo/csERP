<script setup>
import { watch } from 'vue'

const props = defineProps({
  modelValue: { type: Object, required: true },
})

// 简拼生成：根据商品名称自动提取拼音首字母
function generateSimpleCode(name) {
  if (!name) return ''
  let result = ''
  for (const char of name) {
    const code = char.charCodeAt(0)
    // 英文字母直接取大写
    if (code >= 65 && code <= 90) { result += char }
    else if (code >= 97 && code <= 122) { result += String.fromCharCode(code - 32) }
    // 中文取拼音首字母（基于Unicode范围近似）
    else if (code >= 0x4e00 && code <= 0x9fa5) {
      result += getPinyinFirstLetter(char, code)
    }
  }
  return result
}

// 常用汉字拼音首字母映射（覆盖大部分商品名称用字）
function getPinyinFirstLetter(char, code) {
  // 按Unicode范围分组的拼音首字母（近似但覆盖常用字）
  const ranges = [
    { start: 0x4e00, end: 0x5475, letters: 'GDYBKZSCJMQLTWNXFPHDR' },
    { start: 0x5476, end: 0x5c22, letters: 'ZHCSWLYPMJTXQGNBKFDR' },
    { start: 0x5c23, end: 0x63cf, letters: 'LYXWZSHJCNBTKMGPQFD' },
    { start: 0x63d0, end: 0x6b7c, letters: 'TSLWMZJYCQPHXGNBKFD' },
    { start: 0x6b7d, end: 0x732b, letters: 'ZYSHLWJCMPTQNGXBKFD' },
    { start: 0x732c, end: 0x79d1, letters: 'LYXHSJWZCMQTPNGBKFD' },
    { start: 0x79d2, end: 0x7fd5, letters: 'MYXWZLSTQJCPHGNBKFD' },
    { start: 0x7fd6, end: 0x8564, letters: 'YXWZMJSCLQTPHNGBKFD' },
    { start: 0x8565, end: 0x8ad6, letters: 'ZYXWSLMJCPQHTNGBKFD' },
    { start: 0x8ad7, end: 0x8ff0, letters: 'SYXLZMJWCPQHTNGBKFD' },
    { start: 0x8ff1, end: 0x95e5, letters: 'YZXWSLMJCPQHTNGBKFD' },
    { start: 0x95e6, end: 0x9fa5, letters: 'ZYXWSLMJCPQHTNGBKFD' },
  ]
  for (const r of ranges) {
    if (code >= r.start && code <= r.end) {
      const idx = Math.floor(((code - r.start) / (r.end - r.start + 1)) * r.letters.length)
      return r.letters[Math.min(idx, r.letters.length - 1)]
    }
  }
  return char
}

// 监听商品名称变化，自动更新简拼
watch(() => props.modelValue.goodsName, (newName) => {
  if (newName && !props.modelValue.simpleCode) {
    props.modelValue.simpleCode = generateSimpleCode(newName)
  }
})
</script>

<template>
  <div class="base-info-form">
    <!-- 第一行 -->
    <div class="row">
      <div class="field">
        <label>商品编码</label>
        <input type="text" v-model="modelValue.goodsCode" placeholder="自动生成或手动录入" />
      </div>
      <div class="field">
        <label>商品名称 <span class="required">*</span></label>
        <input type="text" v-model="modelValue.goodsName" placeholder="请输入商品名称" />
      </div>
      <div class="field">
        <label>规格型号</label>
        <input type="text" v-model="modelValue.spec" placeholder="如: 500ml*24瓶/箱" />
      </div>
      <div class="field">
        <label>简拼</label>
        <input type="text" v-model="modelValue.simpleCode" placeholder="自动生成拼音首字母" />
      </div>
    </div>

    <!-- 第二行 -->
    <div class="row">
      <div class="field">
        <label>商品分类</label>
        <select v-model="modelValue.categoryName">
          <option value="">请选择</option>
          <option value="瓶装水">瓶装水</option>
          <option value="饮料">饮料</option>
          <option value="方便食品">方便食品</option>
          <option value="日化百货">日化百货</option>
        </select>
      </div>
      <div class="field">
        <label>品牌</label>
        <select v-model="modelValue.brandName">
          <option value="">请选择</option>
          <option value="农夫山泉">农夫山泉</option>
          <option value="可口可乐">可口可乐</option>
          <option value="百事可乐">百事可乐</option>
          <option value="统一">统一</option>
          <option value="康师傅">康师傅</option>
          <option value="娃哈哈">娃哈哈</option>
          <option value="伊利">伊利</option>
          <option value="蒙牛">蒙牛</option>
          <option value="三只松鼠">三只松鼠</option>
          <option value="百草味">百草味</option>
          <option value="良品铺子">良品铺子</option>
          <option value="达能">达能</option>
          <option value="雀巢">雀巢</option>
          <option value="王老吉">王老吉</option>
          <option value="红牛">红牛</option>
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
    </div>

    <!-- 第三行 -->
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
          <option value="">请选择</option>
          <option value="张三">张三</option>
          <option value="李四">李四</option>
          <option value="王五">王五</option>
        </select>
      </div>
    </div>

    <!-- 第四行 -->
    <div class="row">
      <div class="field">
        <label>可退货</label>
        <select v-model="modelValue.canReturn">
          <option :value="true">是</option>
          <option :value="false">否</option>
        </select>
      </div>
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
      <div class="field">
        <label>状态</label>
        <select v-model="modelValue.status">
          <option value="正常">正常</option>
          <option value="停用">停用</option>
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

.field.full-width {
  grid-column: 1 / -1;
}

.field label {
  font-size: 12px;
  font-weight: 600; /* 加粗 */
  color: #303133; /* 颜色加深，与字段名统一 */
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
  color: #606266; /* 输入值颜色稍浅，与标签区分 */
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
