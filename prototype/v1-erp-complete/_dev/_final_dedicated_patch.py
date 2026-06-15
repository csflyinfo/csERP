from pathlib import Path
p=Path(r"E:\我的工作项目\erp-wms-tms\prototype\v1-erp-complete\index.html")
s=p.read_text(encoding="utf-8")
patch=r'''

// ===== Dedicated master/system/report rendering patch =====
const masterFilters = {
  goodsCategory:['分类编码/名称','上级分类','层级','状态'], brand:['品牌编码/名称','状态'], unit:['单位编码/名称','单位类型','状态'],
  warehouse:['仓库编码/名称','仓库类型','库存类型','成本分组','状态'], priceGroup:['价格组编码/名称','是否启用','状态'], customerPrice:['客户','商品','生效日期','状态'],
  territory:['片区编码/名称','上级片区','状态'], routeLine:['线路编码/名称','默认司机','状态'], employee:['人员编码/姓名','部门','角色','状态'],
  department:['部门编码/名称','上级部门','状态'], owner:['货主编码/名称','货主类型','状态'], expenseType:['费用编码/名称','方向','采购费用','参与成本','状态'],
  counterparty:['往来单位编码/名称','类型','结算方式','状态'], fundAccount:['账户编码/名称','账户类型','状态'], userManage:['账号/姓名/手机号','权限组','状态'],
  roleManage:['权限组编码/名称','状态'], systemParam:['参数键/名称','参数分组','参数类型'], billNoRule:['单据类型','前缀','状态'], precisionSetting:['参数名称','分组'],
  operationLog:['操作时间','用户','模块','操作类型','单据号'], importExportCenter:['创建时间','模块','类型','状态'], product:['商品编号/名称/条码','品牌','商品分类','状态'],
  customer:['客户编码/名称/手机号','客户等级','账期类型','业务员','状态'], supplier:['供应商编码/名称/联系人','结算方式','账期类型','采购员','状态']
};
const reportIds = ['salesReport','purchaseReport','stockReport','financeReport'];
const oldMasterFinal = master;
master = function(id){
  if(reportIds.includes(id)) return renderReportPage(id);
  let m=masters[id]; title.textContent=m.name; desc.textContent='专属查询条件、列表字段、新建/编辑表单';
  const fs = masterFilters[id] || ['关键字','状态'];
  content.innerHTML=`<div class="layout2"><div class="tree">${m.tree.map((x,i)=>`<div class="${i===0?'sel':''}" onclick="this.parentNode.querySelectorAll('div').forEach(d=>d.classList.remove('sel'));this.classList.add('sel');toast('已切换：${x}')">${x}</div>`).join('')}</div><div>${filter(fs)}<div style="text-align:right;margin-bottom:10px"><button class="btn primary" onclick="edit('new')">新建</button><button class="btn">批量编辑</button><button class="btn">导入修改</button><button class="btn">导出</button></div>${table(m.cols,m.row)}${moduleHint(id)}</div></div>`;
};
function moduleHint(id){
  const hints={
    goodsCategory:'分类只允许选择末级分类用于商品档案；有商品引用的分类不可删除。', brand:'品牌被商品引用后不可删除，可停用。', unit:'单位被商品引用后不可删除；多单位换算以基本单位为底层。',
    priceGroup:'启用价格组后，商品价格设置中展示对应价格列。', customerPrice:'客户专属价优先级高于客户等级价和标准售价。', territory:'只有末级片区可关联门店和业务员。',
    routeLine:'线路后续供TMS调度和司机配送使用。', employee:'人员可同时是业务员、采购员、仓管员、司机。', department:'有人员的部门不可删除，只能停用。',
    expenseType:'采购费用且参与成本的费用类型可用于采购费用分摊。', counterparty:'往来单位可产生应收、应付、收款、付款和结算。', fundAccount:'当前余额由收付款单更新，不允许直接修改。',
    userManage:'用户权限由权限组、数据范围和字段权限共同控制。', roleManage:'字段权限控制成本、毛利、采购价、欠款等敏感字段。', systemParam:'应收/应付生成节点为固定口径，不允许随意修改。',
    billNoRule:'编号规则修改后只影响新单据，历史单据不变。', precisionSetting:'显示位数只可增大不可改小。'
  };
  return hints[id]?`<div class="risk">${hints[id]}</div>`:'';
}
function renderReportPage(id){
  const m=masters[id]; title.textContent=m.name; desc.textContent='报表查询、排行、导出和明细下钻';
  const cards = id==='salesReport' ? [['销售金额','¥82,450'],['销售数量','1,260'],['毛利额','¥18,920'],['退货率','3.8%']] : id==='purchaseReport' ? [['采购金额','¥65,200'],['入库数量','980'],['费用分摊','¥3,320'],['供应商数','18']] : id==='stockReport' ? [['库存金额','¥3.86M'],['库存SKU','4,645'],['低库存','48'],['临期商品','21']] : [['应收余额','¥1.28M'],['应付余额','¥860K'],['今日收款','¥56,200'],['今日付款','¥32,800']];
  content.innerHTML=`<div class="cards">${cards.map(c=>`<div class="card"><div class="label">${c[0]}</div><div class="value">${c[1]}</div></div>`).join('')}</div>${filter(masterFilters[id]||['日期','对象'])}<div style="text-align:right;margin-bottom:10px"><button class="btn">导出报表</button><button class="btn">图表/明细切换</button></div>${table(m.cols,m.row)}<div class="rank-grid"><div class="rank-card"><h3>趋势图</h3>${rank(['6月10日','6月11日','6月12日','6月13日'])}</div><div class="rank-card"><h3>排行分析</h3>${rank(['第一名','第二名','第三名','第四名'])}</div></div>`;
}
const oldMasterEditFinal = masterEdit;
masterEdit = function(mode){
  const id=current; const m=masters[id]; const form=getDeepMasterForm(id);
  if(!form) return oldMasterEditFinal(mode);
  title.textContent=(mode==='new'?'新建':'编辑')+' - '+m.name;
  content.innerHTML=`<div class="form-card"><div class="form-head"><b>${title.textContent}</b><div class="spacer"></div><button class="btn" onclick="master('${id}')">返回列表</button><button class="btn">保存草稿</button><button class="btn primary" onclick="toast('已保存')">保存并返回</button></div><div class="form-body"><div class="tabs">${form.map((s,i)=>`<span class="${i===0?'on':''}" onclick="document.getElementById('msec${i}').scrollIntoView({behavior:'smooth'})">${s[0]}</span>`).join('')}</div>${form.map((s,i)=>`<section class="section" id="msec${i}"><h3>${s[0]}</h3>${renderFields(s[1])}${s[2]||''}</section>`).join('')}</div></div>`;
};
// ===== End dedicated master/system/report rendering patch =====
'''
idx=s.rfind('init();')
if idx==-1: raise SystemExit('init not found')
if 'Dedicated master/system/report rendering patch' not in s:
    s=s[:idx]+patch+s[idx:]
p.write_text(s,encoding='utf-8')
