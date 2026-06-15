<template>
  <section class="form-card">
    <div class="form-head">
      <b>新建 - 客户价格调整单</b>
      <span class="badge wait" style="margin-left:10px">待审核</span>
    </div>
    <div class="form-body">
      <div class="section">
        <h3>调价信息</h3>
        <div class="grid4">
          <div class="field"><label>客户 *</label><select><option>C001 华联超市</option></select></div>
          <div class="field"><label>生效方式 *</label><select><option>立即生效</option><option selected>定时生效</option></select></div>
          <div class="field"><label>生效时间点</label><input value="2026-06-15 08:00" /></div>
          <div class="field"><label>价格有效范围 *</label><select><option>长期有效</option><option selected>2026-06-15 ~ 2026-12-31</option></select></div>
        </div>
      </div>
      <div class="section">
        <h3>调价商品明细</h3>
        <div class="toolbar">
          <button class="btn primary">表格录入商品</button>
          <button class="btn">从商品档案添加</button>
          <button class="btn">导入商品</button>
        </div>
        <div class="scroll">
          <table>
            <tr><th>商品编号</th><th>名称</th><th>基本单位</th><th>规格</th><th>条码</th><th>原价</th><th>现价</th><th>最新进价</th><th>成本价</th></tr>
            <tr><td>SP001</td><td>农夫山泉500ml*24</td><td>瓶</td><td>500ml*24</td><td>6941410749551</td><td class="num">35.00</td><td contenteditable class="num">34.50</td><td class="num">31.20</td><td class="num">30.80</td></tr>
          </table>
        </div>
      </div>
    </div>
    <div class="summary"><span>审核后生效，旧价自动停用</span><div class="spacer"></div><button class="btn primary">审核</button></div>
  </section>
</template>
