
const ref={customer:['全部客户','华联超市','城西便利','民生烟酒','万科物业'],supplier:['全部供应商','农夫山泉杭州经销','康师傅食品','顺丰物流'],warehouse:['全部仓库','总仓','东区仓','冷藏仓','退货仓'],user:['全部人员','张三','李四','王五','赵六'],status:['全部状态','草稿','待审核','已审核','已完成','已关闭'],goods:['全部商品','农夫山泉500ml*24','康师傅红烧牛肉面','临期酸奶200g']};
const mods={
 dashboard:{group:'首页',name:'经营概览',type:'dashboard'},
 product:{group:'基础资料',name:'商品档案',type:'masterTree',tree:['全部商品','饮料','　瓶装水','　茶饮料','方便食品','日化护理'],filters:[['关键字','商品编号/名称/条码','text'],['品牌','品牌','select', ['全部品牌','百草味','可口可乐']]],cols:['商品编号','商品名称','规格型号','商品分类','品牌','基本单位','保质期','是否可售','是否可采购','标准售价','参考进价','库存下限','状态'],row:['SP001','农夫山泉500ml*24','500ml*24','瓶装水','农夫山泉','瓶','365','是','是','35.00','31.20','100','正常'],sections:[['基础档案',['商品编号','商品名称','规格型号','商品分类','品牌','商品类型','保质期天数','存储属性','是否可售','是否可采购','是否可退','是否称重','是否预售','默认供应商','默认仓库','税率%','起订量','起订倍数','库存上限','库存下限','临期预警天数']],['单位设置',['基本单位','基本条码','大单位','大单位换算率','中单位','中单位换算率','重量','体积']],['价格设置',['标准售价','最低售价','参考进价','建议零售价','价格组1','价格组2']],['商品介绍',['商品介绍']]]},
 customer:{group:'基础资料',name:'门店/客户资料',type:'masterTree',tree:['全部客户','KA客户','流通客户','餐饮客户','西湖区','　朝阳线','拱墅区'],filters:[['关键字','客户编码/名称/手机号','text'],['客户等级','客户等级','select',['全部等级','金牌','银牌','普通']],['账期类型','账期类型','select',['全部账期','月结固定付款日','固定账期天数','周结']],['业务员','业务员','select',ref.user]],cols:['客户编码','客户名称','客户类型','客户等级','联系人','手机','片区','线路','业务员','结算方式','账期类型','账期规则','信用额度','应收余额','状态'],row:['C001','华联超市','KA客户','金牌','王店长','138****8888','西湖区','朝阳线','张三','月结','月结固定付款日','每月25日截账，次月10日付款','50000','12000','正常'],sections:[['基本信息',['客户编码','客户名称','客户类型','客户等级','渠道类型','联系人','手机号','片区','线路','业务员','状态']],['地址信息',['地址别名','收货联系人','收货电话','省市区','详细地址','经度','纬度','是否默认']],['账期设置',['是否设账期','结算方式','信用额度','账期类型','截账日','付款月','付款日','超额控制','逾期控制']],['开票信息',['发票抬头','税号','开户行','银行账号','开票地址','开票电话']]]},
 supplier:{group:'基础资料',name:'供应商资料',type:'masterTree',tree:['全部供应商','食品饮料供应商','　饮料供应商','　食品供应商','物流服务商','设备耗材供应商'],filters:[['关键字','供应商编码/名称/联系人','text'],['结算方式','结算方式','select',['全部','月结','现结','预付']],['账期类型','账期类型','select',['全部','月结固定付款日','固定账期天数','周结']],['采购员','采购员','select',ref.user]],cols:['供应商编码','供应商名称','供应商类型','联系人','电话','到货天数','结算方式','账期类型','账期规则','默认采购员','默认收款账户','应付余额','预付余额','状态'],row:['G001','农夫山泉杭州经销','饮料供应商','赵经理','0571-8888','5','月结','月结固定付款日','每月25日截账，次月10日付款','李四','工商银行 6222****','6600','1000','正常'],sections:[['基础信息',['供应商编码','供应商名称','供应商类型','联系人','联系电话','地址','到货天数','默认采购员','状态']],['结算设置',['是否设账期','结算方式','账期类型','截账日','付款月','付款日','发票要求','默认付款账户','付款控制']],['收款账户',['账户名称','开户行','银行账号','收款户名','是否默认','状态']],['发票信息',['发票抬头','税号','开票地址','开票电话']],['联系人',['姓名','职务','手机','邮箱','是否默认']]]},
 salesQuick:{group:'销售管理',name:'销售快速开单',type:'quick'},
 biz:{group:'业务单据',name:'业务单据工作台',type:'biz'},
 init:{group:'系统',name:'数据初始化向导',type:'init'}
};
const billMods={purchaseOrder:['采购订单','采购单号','供应商','采购员','仓库','状态'],purchaseInbound:['采购入库','入库单号','采购单号','供应商','仓库','状态'],purchaseReceipt:['采购收货单','收货单号','入库单号','供应商','应付生成','开票状态'],purchaseExpense:['采购费用单','费用单号','费用类型','供应商/往来单位','分摊状态','应付生成'],salesOrder:['销售订单','销售单号','客户','业务员','仓库','状态'],salesOutbound:['销售出库','出库单号','销售单号','客户','仓库','状态'],salesReceipt:['销售收货单','收货单号','销售单号','客户','签收状态','应收生成'],otherIn:['其他入库','入库单号','仓库','入库原因','状态'],otherOut:['其他出库','出库单号','仓库','出库原因','状态'],transfer:['调拨单','调拨单号','调出仓库','调入仓库','状态'],damage:['报损单','报损单号','仓库','报损类型','状态'],costAdjust:['成本调整单','调整单号','仓库','调整方式','状态'],ar:['应收账款/结算','应收单号','客户/往来单位','核销状态','逾期状态'],ap:['应付账款/结算','应付单号','供应商/往来单位','核销状态','到期状态']};
let current='dashboard',curBill='purchaseOrder';function init(){renderMenu();route('dashboard')}function renderMenu(){let groups={};Object.entries(mods).forEach(([k,m])=>(groups[m.group]??=[]).push([k,m.name]));let html='';Object.entries(groups).forEach(([g,arr])=>{html+=`<div class="side-title">${g}</div>`+arr.map(a=>`<div class="nav" data-id="${a[0]}" onclick="route('${a[0]}')"><span class="dot"></span>${a[1]}</div>`).join('')});side.innerHTML=html}function route(id){current=id;document.querySelectorAll('.nav').forEach(n=>n.classList.toggle('on',n.dataset.id===id));let m=mods[id];title.textContent=m.name;desc.textContent=m.group; if(m.type==='dashboard')renderDashboard(); if(m.type==='masterTree')renderMaster(id); if(m.type==='quick')renderQuick(); if(m.type==='biz')renderBiz(); if(m.type==='init')renderInit()}
function renderDashboard(){content.innerHTML=`<div class="cards"><div class="card"><div class="label">已统一原型</div><div class="value">ALL</div></div><div class="card"><div class="label">嵌套 iframe</div><div class="value">0</div></div><div class="card"><div class="label">科技蓝主题</div><div class="value">ON</div></div><div class="card"><div class="label">单据流程</div><div class="value">列表→编辑</div></div></div><div class="quick"><div class="q" onclick="route('product')"><h3>商品档案</h3><p>左树右表、PRD字段、单页锚点编辑。</p></div><div class="q" onclick="route('customer')"><h3>门店/客户</h3><p>灵活账期、地址、开票，不展示历史销售。</p></div><div class="q" onclick="route('supplier')"><h3>供应商</h3><p>灵活账期、多个收款账户、付款账户选择。</p></div><div class="q" onclick="route('biz')"><h3>业务单据</h3><p>所有单据先列表，再新建/编辑。</p></div></div>`}
function filterHtml(filters){return `<div class="filter">${filters.map(f=>`<div class="fi ${f[2]==='text'?'wide':''}"><label>${f[0]}</label>${f[2]==='select'?`<select>${(f[3]||ref.status).map(x=>`<option>${x}</option>`).join('')}</select>`:`<input placeholder="${f[1]}">`}</div>`).join('')}<button class="btn primary">查询</button><button class="btn">重置</button></div>`}
function tableHtml(cols,row){return `<div class="tablebox"><div class="toolbar"><b>记录列表</b><div class="spacer"></div><button class="btn">字段设置</button><button class="btn">导出主单</button><button class="btn">导出明细</button></div><div class="scroll"><table><tr>${cols.map(c=>`<th>${c}</th>`).join('')}<th>操作</th></tr><tr>${row.map((x,i)=>`<td class="${/金额|余额|额度|数量|单价|库存|成本/.test(cols[i])?'num':''}">${fmt(x)}</td>`).join('')}<td><span class="link" onclick="openEdit()">编辑</span><span class="link" onclick="toast('已审核')">审核</span></td></tr></table></div><div class="sum"><span>当前页合计：金额 <b>¥1,000.00</b></span><span>数量 <b>100</b></span></div></div>`}function fmt(x){return ['正常','已审核','已生成'].includes(x)?`<span class="badge ok">${x}</span>`:['待审核','未核销'].includes(x)?`<span class="badge wait">${x}</span>`:x}
function renderMaster(id){let m=mods[id];content.innerHTML=`<div class="layout2"><div class="tree">${m.tree.map((x,i)=>`<div class="${i===0?'sel':''}" onclick="this.parentNode.querySelectorAll('div').forEach(d=>d.classList.remove('sel'));this.classList.add('sel');toast('已切换：${x.replace(/　/g,'')}')">${x}</div>`).join('')}</div><div>${filterHtml(m.filters)}${tableHtml(m.cols,m.row)}</div></div>`}
function openEdit(){let m=mods[current];if(current==='biz')return openBillEdit();let sections=m.sections||[];modal.innerHTML=`<div class="modalbox"><div class="modalhead">${m.name} - 新建/编辑<div class="spacer"></div><button class="btn" onclick="closeModal()">×</button></div><div class="modalbody"><div class="tabs" id="tabs">${sections.map((s,i)=>`<span class="${i===0?'on':''}" onclick="jumpSec('s${i}')">${s[0]}</span>`).join('')}</div>${sections.map((s,i)=>`<section class="section" id="s${i}"><h3>${s[0]}</h3><div class="grid4">${s[1].map(f=>`<div class="field"><label>${f}</label><input placeholder="${f}"></div>`).join('')}</div>${s[0].includes('账期')?periodBox():''}${s[0].includes('收款账户')?accountBox():''}</section>`).join('')}</div><div class="modalfoot"><button class="btn primary" onclick="toast('已保存');closeModal()">保存</button><button class="btn" onclick="closeModal()">取消</button></div></div>`;modal.classList.add('show')}
function periodBox(){return `<div class="period-box"><b>灵活账期规则</b><div class="grid4" style="margin-top:10px"><div class="field"><label>账期类型</label><select><option>月结固定付款日</option><option>月结账期天数</option><option>半月结</option><option>周结</option><option>固定账期天数</option></select></div><div class="field"><label>截账日</label><input value="25"></div><div class="field"><label>付款月/日</label><input value="次月10日"></div><div class="field"><label>控制方式</label><select><option>提醒</option><option>阻断</option><option>审批</option></select></div></div></div>`}function accountBox(){return `<table class="matrix"><tr><th>账户名称</th><th>开户行</th><th>银行账号</th><th>收款户名</th><th>默认</th><th>状态</th></tr><tr><td>默认账户</td><td>工商银行</td><td>6222****0000</td><td>供应商公司</td><td>是</td><td>正常</td></tr></table>`}
function jumpSec(id){document.getElementById(id).scrollIntoView({behavior:'smooth',block:'start'})}
function renderQuick(){content.innerHTML=`<div class="form-card"><div class="form-head"><b>销售快速开单</b><div class="spacer"></div><button class="btn primary" onclick="toast('销售订单已审核并锁定库存')">审核并打印</button></div><div class="form-body"><div class="grid4"><div class="field"><label>客户</label><select>${ref.customer.map(x=>`<option>${x}</option>`)}</select></div><div class="field"><label>仓库</label><select>${ref.warehouse.map(x=>`<option>${x}</option>`)}</select></div><div class="field"><label>业务员</label><select>${ref.user.map(x=>`<option>${x}</option>`)}</select></div><div class="field"><label>结算方式</label><select><option>月结30天</option><option>现结</option></select></div></div><div class="risk">欠款 ¥12,000　逾期 ¥2,000　信用额度 ¥50,000　可用额度 ¥38,000</div><div class="field"><label>商品/扫码</label><input placeholder="扫码/编码/名称/简拼，回车添加"></div><table><tr><th>商品</th><th>单位</th><th>数量</th><th>可用库存</th><th>单价</th><th>金额</th><th>成本</th><th>毛利</th><th>赠品</th></tr><tr><td>农夫山泉500ml*24</td><td>箱</td><td contenteditable>10</td><td>120</td><td contenteditable>35.00</td><td>350.00</td><td>312.00</td><td>38.00</td><td>否</td></tr></table></div><div class="summary"><span>应收 <b>¥350.00</b></span><span>未收 <b>¥350.00</b></span></div></div>`}
function renderBiz(){let keys=Object.keys(billMods);content.innerHTML=`<div class="layout2"><div class="tree">${keys.map((k,i)=>`<div class="${k===curBill?'sel':''}" onclick="curBill='${k}';renderBiz()">${billMods[k][0]}</div>`).join('')}</div><div id="bizArea"></div></div>`;renderBillList()}function renderBillList(){let b=billMods[curBill],cols=[b[1],...b.slice(2),'金额','状态'],row=[curBill.toUpperCase()+'001',...b.slice(2).map(x=>x.includes('客户')?'华联超市':x.includes('供应商')?'农夫山泉杭州经销':x.includes('仓库')?'总仓':'示例'),'1000.00','待审核'];bizArea.innerHTML=`${filterHtml(b.slice(1).map(x=>[x,x,'select',x.includes('客户')?ref.customer:x.includes('供应商')?ref.supplier:x.includes('仓库')?ref.warehouse:ref.status]))}${tableHtml(cols,row)}`}function newRecord(){if(current==='biz')openBillEdit();else openEdit()}function openBillEdit(){let b=billMods[curBill];modal.innerHTML=`<div class="modalbox"><div class="modalhead">${b[0]} - 新建/编辑<div class="spacer"></div><button class="btn" onclick="closeModal()">×</button></div><div class="modalbody"><h3>单据头</h3><div class="grid4">${b.slice(1).map(x=>`<div class="field"><label>${x}</label><input placeholder="${x}"></div>`).join('')}</div><h3>明细信息</h3><table><tr><th>商品/项目</th><th>单位</th><th>数量</th><th>单价</th><th>金额</th><th>成本单价</th><th>成本金额</th><th>操作</th></tr><tr><td contenteditable>示例商品</td><td>箱</td><td contenteditable>10</td><td contenteditable>35.00</td><td>350.00</td><td>31.2000</td><td>312.00</td><td><span class="link">删除</span></td></tr></table></div><div class="modalfoot"><button class="btn" onclick="toast('草稿已保存')">保存草稿</button><button class="btn primary" onclick="toast('${b[0]}已提交/审核');closeModal()">提交/审核</button></div></div>`;modal.classList.add('show')}
function renderInit(){content.innerHTML=`<div class="form-card"><div class="form-head"><b>数据初始化向导</b></div><div class="form-body"><div class="tabs"><span class="on">1 初始化说明</span><span>2 基础资料</span><span>3 期初库存</span><span>4 期初应收</span><span>5 期初应付</span><span>6 预收/预付</span><span>7 资金账户</span><span>8 校验确认</span></div><div class="cards"><div class="card"><div class="label">商品资料</div><div class="value">4645</div></div><div class="card"><div class="label">期初库存</div><div class="value">12350</div></div><div class="card"><div class="label">期初应收</div><div class="value">¥126K</div></div><div class="card"><div class="label">资金余额</div><div class="value">¥58K</div></div></div><br><button class="btn">下载模板</button> <button class="btn primary" onclick="toast('文件已上传并校验通过')">上传文件</button> <button class="btn primary" onclick="toast('初始化已确认，系统启用')">确认启用系统</button></div></div>`}
function closeModal(){modal.classList.remove('show')}function backList(){if(current==='biz')renderBillList();else toast('当前模块无列表返回')}function reloadFrame(){}function openFull(){}function toast(msg){const t=document.getElementById('toast');t.textContent=msg;t.classList.add('show');clearTimeout(window.tt);window.tt=setTimeout(()=>t.classList.remove('show'),2200)}
// ===== Product-manager refined interactions: no iframe, richer master data and bill filters =====
const oldFilterHtml = filterHtml;
filterHtml = function(filters){
  return `<div class="filter">${filters.map(f=>{
    const name=f[0];
    let opts=f[3];
    if(!opts){
      if(name.includes('客户')) opts=ref.customer;
      else if(name.includes('供应商')) opts=ref.supplier;
      else if(name.includes('仓库')) opts=ref.warehouse;
      else if(name.includes('商品')) opts=ref.goods;
      else if(name.includes('人员')||name.includes('业务员')||name.includes('采购员')||name.includes('审核人')||name.includes('创建人')) opts=ref.user;
      else if(name.includes('状态')) opts=ref.status;
    }
    const wide=f[2]==='text'||name.includes('单号')||name.includes('关键字')?'wide':'';
    if(opts) return `<div class="fi ${wide}"><label>${name}</label><select>${opts.map(x=>`<option>${x}</option>`).join('')}</select></div>`;
    if(name.includes('日期')) return `<div class="fi"><label>${name}</label><input value="2026-06-01 ~ 2026-06-13"></div>`;
    if(name.includes('金额')) return `<div class="fi"><label>${name}</label><input placeholder="最小~最大"></div>`;
    return `<div class="fi ${wide}"><label>${name}</label><input placeholder="${f[1]||name}"></div>`;
  }).join('')}<button class="btn primary">查询</button><button class="btn">重置</button><button class="btn">展开更多</button></div>`;
}
function masterSections(id){
  if(id==='product') return `<section class="section" id="base"><h3>基础档案</h3><div class="grid4">${['商品编号','商品名称','规格型号','商品分类','品牌','商品类型','保质期天数','存储属性','是否可售','是否可采购','是否可退','是否称重','是否预售','默认供应商','默认仓库','税率%','起订量','起订倍数','库存上限','库存下限','临期预警天数','状态'].map(f=>`<div class="field"><label>${f}</label><input placeholder="${f}"></div>`).join('')}</div></section><section class="section" id="unit"><h3>单位设置</h3>${matrix(['单位设置','基本单位','大单位','中单位'],[['是否启用','☑','☐','☐'],['选择单位','瓶','箱',''],['单位条码','6941410749551','',''],['单位换算','1','24',''],['重量(kg)','','',''],['体积(m³)','','','']])}</section><section class="section" id="price"><h3>价格设置</h3>${matrix(['价格项','基本单位','大单位','中单位'],[['标准售价','2.40','',''],['最低售价','','',''],['参考进价','1.00','',''],['建议零售价','3.00','',''],['价格组1','','',''],['价格组2','','','']])}</section><section class="section" id="intro"><h3>商品介绍</h3><textarea placeholder="商品图文介绍、附件说明"></textarea></section>`;
  if(id==='customer') return `<section class="section" id="base"><h3>基本信息</h3><div class="grid4">${['客户编码','客户名称','客户类型','客户等级','渠道类型','联系人','手机号','片区','线路','业务员','状态','备注'].map(f=>`<div class="field"><label>${f}</label><input placeholder="${f}"></div>`).join('')}</div></section><section class="section" id="address"><h3>地址信息</h3>${matrix(['地址别名','联系人','电话','省市区','详细地址','默认'],[['总店','王店长','13888888888','浙江/杭州/西湖','文三路100号','是']])}</section><section class="section" id="period"><h3>账期设置</h3>${periodBox('客户')}</section><section class="section" id="invoice"><h3>开票信息</h3><div class="grid4">${['发票抬头','税号','开户行','银行账号','开票地址','开票电话'].map(f=>`<div class="field"><label>${f}</label><input placeholder="${f}"></div>`).join('')}</div></section><section class="section" id="import"><h3>导入修改/批量修改</h3><p class="muted">支持批量修改客户等级、片区、线路、业务员、结算方式、账期、信用额度、状态。</p></section>`;
  if(id==='supplier') return `<section class="section" id="base"><h3>基础信息</h3><div class="grid4">${['供应商编码','供应商名称','供应商类型','联系人','联系电话','地址','到货天数','默认采购员','状态'].map(f=>`<div class="field"><label>${f}</label><input placeholder="${f}"></div>`).join('')}</div></section><section class="section" id="settle"><h3>结算设置</h3>${periodBox('供应商')}<div class="risk">付款时默认带出供应商默认收款账户，也可选择其他正常账户。</div></section><section class="section" id="accounts"><h3>收款账户</h3>${matrix(['账户名称','开户行','银行账号','收款户名','默认','状态'],[['默认账户','工商银行','6222****0000','供应商公司','是','正常'],['备用账户','建设银行','6227****0000','供应商公司','否','正常']])}</section><section class="section" id="invoice"><h3>发票信息</h3><div class="grid4">${['发票抬头','税号','开票地址','开票电话'].map(f=>`<div class="field"><label>${f}</label><input placeholder="${f}"></div>`).join('')}</div></section><section class="section" id="contacts"><h3>联系人</h3>${matrix(['姓名','职务','手机','邮箱','默认'],[['赵经理','销售经理','138****2222','zhao@example.com','是']])}</section>`;
  return '';
}
function periodBox(who){return `<div class="period-box"><div class="grid4">${['是否设账期','结算方式','账期类型','信用额度/额度控制','截账日','付款月/日','账期天数','逾期控制'].map(f=>`<div class="field"><label>${f}</label><input placeholder="${f}"></div>`).join('')}</div><div class="risk">${who}账期示例：每月25日截账，次月10日付款；也支持固定账期天数、半月结、周结。</div></div>`}
function matrix(head,rows){return `<table><tr>${head.map(h=>`<th>${h}</th>`).join('')}</tr>${rows.map(r=>`<tr>${r.map(c=>`<td contenteditable>${c}</td>`).join('')}</tr>`).join('')}</table>`}
const oldOpenEdit = openEdit;
openEdit = function(){
  let m=mods[current];
  if(!m || !['product','customer','supplier'].includes(current)) return oldOpenEdit();
  const tabs=current==='product' ? [['base','基础档案'],['unit','单位设置'],['price','价格设置'],['intro','商品介绍']] : current==='customer' ? [['base','基本信息'],['address','地址信息'],['period','账期设置'],['invoice','开票信息'],['import','导入修改']] : [['base','基础信息'],['settle','结算设置'],['accounts','收款账户'],['invoice','发票信息'],['contacts','联系人']];
  modal.innerHTML=`<div class="modalbox"><div class="modalhead">${m.name} - 新建/编辑<div class="spacer"></div><button class="btn" onclick="closeModal()">×</button></div><div class="modalbody"><div class="tabs" id="editTabs">${tabs.map((t,i)=>`<span class="${i===0?'on':''}" onclick="document.getElementById('${t[0]}').scrollIntoView({behavior:'smooth',block:'start'})">${t[1]}</span>`).join('')}</div>${masterSections(current)}</div><div class="modalfoot"><button class="btn primary" onclick="toast('已保存');closeModal()">保存并继续</button><button class="btn">复制新增</button><button class="btn" onclick="closeModal()">保存并退出</button><button class="btn" onclick="closeModal()">取消</button></div></div>`;
  modal.classList.add('show');
}
// ===== Final pass: page-level edit flow, no modal for main modules =====
function editShell(titleText, subText, bodyHtml, footerHtml){
  content.innerHTML = `<div class="form-card"><div class="form-head"><b>${titleText}</b><span class="desc">${subText||''}</span><div class="spacer"></div><button class="btn" onclick="backList()">返回列表</button><button class="btn">保存草稿</button><button class="btn primary" onclick="toast('已提交/审核')">提交/审核</button></div><div class="form-body">${bodyHtml}</div>${footerHtml||''}</div>`;
}
function renderMasterEditPage(id, mode='edit'){
  const m=mods[id];
  const sections = masterSections(id);
  editShell(`${mode==='new'?'新建':'编辑'} - ${m.name}`, '页面式编辑：所有字段在当前内容区展示，非弹窗；顶部返回列表', `<div class="tabs" id="editTabs">${masterTabList(id).map((t,i)=>`<span class="${i===0?'on':''}" onclick="document.getElementById('${t[0]}').scrollIntoView({behavior:'smooth',block:'start'})">${t[1]}</span>`).join('')}</div>${sections}`, `<div class="summary"><span>编辑状态 <b>${mode==='new'?'新建':'编辑'}</b></span><span>字段已按PRD展示</span><div style="flex:1"></div><button class="btn primary" onclick="toast('已保存${m.name}')">保存并继续</button><button class="btn" onclick="renderMaster('${id}')">保存并返回</button></div>`);
}
function masterTabList(id){
  if(id==='product') return [['base','基础档案'],['unit','单位设置'],['price','价格设置'],['intro','商品介绍']];
  if(id==='customer') return [['base','基本信息'],['address','地址信息'],['period','账期设置'],['invoice','开票信息'],['import','导入修改']];
  if(id==='supplier') return [['base','基础信息'],['settle','结算设置'],['accounts','收款账户'],['invoice','发票信息'],['contacts','联系人']];
  return [];
}
const oldTableHtmlFinal = tableHtml;
tableHtml = function(cols,row){
  return `<div class="tablebox"><div class="toolbar"><b>记录列表</b><div class="spacer"></div><button class="btn">字段设置</button><button class="btn">导出主单</button><button class="btn">导出明细</button></div><div class="scroll"><table><tr>${cols.map(c=>`<th>${c}</th>`).join('')}<th>操作</th></tr><tr>${row.map((x,i)=>`<td class="${/金额|余额|额度|数量|单价|库存|成本/.test(cols[i])?'num':''}">${fmt(x)}</td>`).join('')}<td><span class="link" onclick="newRecord('view')">查看</span><span class="link" onclick="newRecord('edit')">编辑</span><span class="link" onclick="toast('已审核')">审核</span></td></tr></table></div><div class="sum"><span>当前页合计：金额 <b>¥1,000.00</b></span><span>数量 <b>100</b></span></div></div>`;
}
function billEditBody(m){
  return `<div class="section"><h3>单据头</h3><div class="grid4">${m.fields.map(f=>`<div class="field"><label>${f}</label>${fieldControl(f)}</div>`).join('')}</div></div><div class="section"><h3>明细信息</h3><table><tr>${m.details.map(c=>`<th>${c}</th>`).join('')}<th>操作</th></tr><tr>${m.details.map((c,i)=>`<td contenteditable class="${/金额|数量|成本|单价|税率|库存|本次|未收|未付/.test(c)?'num':''}">${i===0?'示例商品':''}</td>`).join('')}<td><span class="link" onclick="this.closest('tr').remove();toast('已删除明细')">删除</span></td></tr></table><br><button class="btn" onclick="addEditLine(this)">新增明细行</button><button class="btn">导入明细</button><button class="btn">选择来源单据</button></div>`;
}
function fieldControl(f){
  if(f.includes('客户')) return `<select>${ref.customer.map(x=>`<option>${x}</option>`).join('')}</select>`;
  if(f.includes('供应商')) return `<select>${ref.supplier.map(x=>`<option>${x}</option>`).join('')}</select>`;
  if(f.includes('仓库')) return `<select>${ref.warehouse.map(x=>`<option>${x}</option>`).join('')}</select>`;
  if(f.includes('业务员')||f.includes('采购员')||f.includes('申请人')||f.includes('领用人')) return `<select>${ref.user.map(x=>`<option>${x}</option>`).join('')}</select>`;
  if(f.includes('状态')) return `<select>${ref.status.map(x=>`<option>${x}</option>`).join('')}</select>`;
  if(f.includes('日期')) return `<input value="2026-06-13">`;
  if(f.includes('备注')||f.includes('原因')||f.includes('说明')) return `<textarea placeholder="${f}"></textarea>`;
  return `<input placeholder="${f}">`;
}
function addEditLine(btn){
  const table=btn.parentNode.querySelector('table');
  const cols=table.querySelectorAll('th').length-1;
  table.insertAdjacentHTML('beforeend', `<tr>${Array.from({length:cols}).map((_,i)=>`<td contenteditable class="${i>1?'num':''}">${i===0?'新增明细':''}</td>`).join('')}<td><span class="link" onclick="this.closest('tr').remove()">删除</span></td></tr>`);
  toast('已新增明细行');
}
function showBillEdit(mode='edit'){
  const m=mods[curBill];
  editShell(`${mode==='new'?'新建':'编辑'} - ${m.name}`, '页面式单据编辑：从列表进入，可返回列表；字段和明细按单据类型展示', billEditBody(m), `<div class="summary"><span>数量 <b>100</b></span><span>金额 <b>¥1,000.00</b></span><span>成本 <b>¥800.00</b></span><div style="flex:1"></div><button class="btn" onclick="renderBiz()">返回列表</button><button class="btn primary" onclick="toast('${m.audit}')">审核</button></div>`);
}
function newRecord(mode='new'){
  if(current==='biz') return showBillEdit(mode);
  if(mods[current] && mods[current].type==='masterTree') return renderMasterEditPage(current,mode);
  if(current==='salesQuick') return toast('销售快速开单为直接录入页面');
  if(current==='init') return toast('初始化向导按步骤操作');
  toast('当前模块暂无新建页');
}
function backList(){
  if(current==='biz') return renderBiz();
  if(mods[current] && mods[current].type==='masterTree') return renderMaster(current);
  route(current);
}
openEdit = function(){ newRecord('edit'); }
openBillEdit = function(){ showBillEdit('edit'); }
// ===== Direct module menu entries for every V1.0 business bill =====
Object.assign(mods, {
  purchaseOrderDirect:{group:'采购管理',name:'采购订单',type:'bill',bill:'purchaseOrder',desc:'采购订单列表、新建、编辑、审核、采购在途'},
  purchaseInboundDirect:{group:'采购管理',name:'采购入库',type:'bill',bill:'purchaseInbound',desc:'采购入库列表、引入采购订单、批次、成本重算'},
  purchaseReceiptDirect:{group:'采购管理',name:'采购收货单',type:'bill',bill:'purchaseReceipt',desc:'采购收货确认、生成应付、开票状态'},
  purchaseExpenseDirect:{group:'采购管理',name:'采购费用单',type:'bill',bill:'purchaseExpense',desc:'采购费用分摊、重算成本、生成应付'},
  salesOrderDirect:{group:'销售管理',name:'销售订单',type:'bill',bill:'salesOrder',desc:'销售订单列表、新建、信用库存校验、锁定库存'},
  salesOutboundDirect:{group:'销售管理',name:'销售出库',type:'bill',bill:'salesOutbound',desc:'销售出库、扣减库存、生成销售收货单'},
  salesReceiptDirect:{group:'销售管理',name:'销售收货单',type:'bill',bill:'salesReceipt',desc:'签收确认、差异处理、生成应收'},
  otherInDirect:{group:'库存管理',name:'其他入库',type:'bill',bill:'otherIn',desc:'不记往来的库存增加'},
  otherOutDirect:{group:'库存管理',name:'其他出库',type:'bill',bill:'otherOut',desc:'不记往来的库存减少'},
  transferDirect:{group:'库存管理',name:'调拨单',type:'bill',bill:'transfer',desc:'两步调拨、调拨在途、确认调入'},
  damageDirect:{group:'库存管理',name:'报损单',type:'bill',bill:'damage',desc:'过期破损报损、扣减库存、记录损失'},
  costAdjustDirect:{group:'库存管理',name:'成本调整单',type:'bill',bill:'costAdjust',desc:'调整库存成本，不改变库存数量'},
  arDirect:{group:'财务管理',name:'应收账款/结算',type:'bill',bill:'ar',desc:'应收明细、预收抵扣、收款核销'},
  apDirect:{group:'财务管理',name:'应付账款/结算',type:'bill',bill:'ap',desc:'应付明细、预付抵扣、付款核销'}
});
const routeBeforeDirect = route;
route = function(id){
  current=id;
  document.querySelectorAll('.nav').forEach(n=>n.classList.toggle('on',n.dataset.id===id));
  const m=mods[id];
  if(m && m.type==='bill'){
    curBill=m.bill;
    title.textContent=m.name;
    desc.textContent=m.desc;
    renderBiz();
    return;
  }
  return routeBeforeDirect(id);
}
// ===== Final menu order and direct bill new/back behavior =====
renderMenu = function(){
  const order=['首页','基础资料','销售管理','采购管理','库存管理','财务管理','业务单据','系统'];
  let groups={}; Object.entries(mods).forEach(([k,m])=>(groups[m.group]??=[]).push([k,m.name]));
  let html=''; order.forEach(g=>{ if(groups[g]) html+=`<div class="side-title">${g}</div>`+groups[g].map(a=>`<div class="nav" data-id="${a[0]}" onclick="route('${a[0]}')"><span class="dot"></span>${a[1]}</div>`).join('') });
  side.innerHTML=html;
};
const newRecordBeforeFinal = newRecord;
newRecord = function(mode='new'){
  const m=mods[current];
  if(current==='biz' || (m && m.type==='bill')) return showBillEdit(mode);
  return newRecordBeforeFinal(mode);
};
backList = function(){
  const m=mods[current];
  if(current==='biz' || (m && m.type==='bill')) return renderBiz();
  if(m && m.type==='masterTree') return renderMaster(current);
  return route(current);
};
// ===== Final direct bill module: no inner nested tree for direct menu entries =====
function renderBillDirect(){
  const b=billMods[curBill];
  const billName=b[0];
  const isSales=billName.includes('销售');
  const isPurchase=billName.includes('采购');
  const isStock=['其他入库','其他出库','调拨单','报损单','成本调整单'].includes(billName);
  const isFinance=billName.includes('应收')||billName.includes('应付');
  const statusTabs=isFinance?['全部','未核销 18','部分核销 6','已核销']:isStock?['全部','待审核 5','已审核','已关闭']:['全部','待审核 3','已审核'];
  const typeTabs=isSales?['销售单','退货单','换货单','还货单']:isPurchase?['采购订单','采购入库','采购收货','采购费用']:isStock?['其他入库','其他出库','调拨单','报损单']:['明细','结算处理','预收/预付','核销记录'];
  const cols=isSales?['进度','单据状态','单据编号','单据日期','创建时间','线路','渠道','客户','仓库','业务员','金额','操作']:isPurchase?['进度','单据状态','单据编号','单据日期','供应商','采购员','仓库','到货/收货状态','金额','应付状态','操作']:isStock?['进度','单据状态','单据编号','单据日期','仓库','原因/类型','商品种类','数量','成本金额','操作']:['核销状态','单据编号','对象','来源单据','单据日期','预计日期','金额','已结','未结','逾期天数','操作'];
  const rows=Array.from({length:10}).map((_,i)=>{
    const no=(isSales?'XS':isPurchase?'CG':isStock?'KC':'CW')+String(202606130001+i);
    if(isSales) return ['待出库',i===0?'未审核':'已审核',no,'2026-06-13',`2026-06-13 16:${String(50-i).padStart(2,'0')}:33`,i%3?'江南':'自提订单',i%2?'流通店':'公司客户',['华联超市','阿芬薄利店','公司客户'][i%3],'总仓',['张三','李娜'][i%2],(350+i*120).toFixed(2),'修改  详情  取消审核'];
    if(isPurchase) return ['待入库',i===0?'待审核':'已审核',no,'2026-06-13',['农夫山泉杭州经销','康师傅食品','顺丰物流'][i%3],'李四','总仓',i%2?'部分入库':'未入库',(1200+i*210).toFixed(2),i%2?'已生成':'未生成','修改  详情  反审核'];
    if(isStock) return ['待处理',i===0?'待审核':'已审核',no,'2026-06-13',['总仓','冷藏仓'][i%2],['样品入库','内部领用','调拨','过期报损'][i%4],1+i,10+i*2,(300+i*50).toFixed(2),'修改  详情  审核'];
    return [i%2?'部分核销':'未核销',no,['华联超市','万科物业','农夫山泉杭州经销'][i%3],['SR001','FE001','PR001'][i%3],'2026-06-13','2026-07-13',(800+i*200).toFixed(2),(i*50).toFixed(2),(800+i*150).toFixed(2),i,'结算  详情'];
  });
  const filter1=isSales?`<select><option>单据日期</option></select><input class="date" value="2026-06-08 00:00:00  -  2026-06-13 23:59:59"><input placeholder="单据编号"><select><option>单据状态</option><option>未审核</option><option>已审核</option></select><select><option>业务员</option><option>张三</option><option>李娜</option></select>`:
    isPurchase?`<select><option>单据日期</option></select><input class="date" value="2026-06-01 00:00:00  -  2026-06-13 23:59:59"><input placeholder="单据编号"><select><option>供应商</option><option>农夫山泉杭州经销</option><option>康师傅食品</option></select><select><option>采购员</option><option>李四</option></select><select><option>单据状态</option></select>`:
    isStock?`<select><option>单据日期</option></select><input class="date" value="2026-06-01 00:00:00  -  2026-06-13 23:59:59"><input placeholder="单据编号"><select><option>仓库</option><option>总仓</option><option>冷藏仓</option></select><select><option>原因/类型</option></select><select><option>单据状态</option></select>`:
    `<select><option>单据日期</option></select><input class="date" value="2026-06-01 00:00:00  -  2026-06-13 23:59:59"><select><option>对象类型</option><option>客户</option><option>供应商</option><option>往来单位</option></select><select><option>客户/供应商/往来单位</option></select><select><option>核销状态</option></select><select><option>逾期状态</option></select>`;
  const filter2=isSales?`<select><option>线路</option></select><select><option>渠道</option></select><select><option>客户</option><option>华联超市</option></select><select><option>仓库</option><option>总仓</option></select><select><option>配送员</option></select><select><option>商品</option><option>农夫山泉500ml*24</option></select><select><option>下单方式</option></select><select><option>支付方式</option></select>`:
    isPurchase?`<select><option>仓库</option><option>总仓</option></select><select><option>商品</option><option>农夫山泉500ml*24</option></select><select><option>到货状态</option></select><select><option>应付状态</option></select><select><option>开票状态</option></select><select><option>费用分摊</option></select><select><option>综合搜索</option></select><input placeholder="请输入">`:
    isStock?`<select><option>商品</option><option>农夫山泉500ml*24</option></select><select><option>批次</option></select><select><option>成本分组</option></select><select><option>操作人</option></select><select><option>综合搜索</option></select><input placeholder="请输入">`:
    `<select><option>来源单据类型</option></select><select><option>业务员/采购员</option></select><select><option>资金账户</option></select><select><option>结算方式</option></select><select><option>综合搜索</option></select><input placeholder="请输入">`;
  content.innerHTML=`<div class="bill-tabs">${statusTabs.map((x,i)=>`<span class="${i===0?'on':''}">${x}</span>`).join('')}</div><div class="type-tabs">${typeTabs.map((x,i)=>`<button class="${i===0?'on':''}">${x}</button>`).join('')}</div><div class="bill-filter"><div class="bill-filter-row">${filter1}<div class="filter-actions"><button class="btn">收起</button><button class="btn primary">🔍 搜索</button><button class="btn">重置</button></div></div><div class="bill-filter-row">${filter2}</div></div><div class="ops" style="justify-content:flex-end;margin-bottom:10px"><button class="btn primary" onclick="showBillEdit('new')">新建</button><button class="btn">导入</button><button class="btn">导出⌄</button><button class="btn">打印</button></div><div class="bill-card"><div class="bill-table-wrap"><table class="bill-table"><tr><th><input type="checkbox"></th><th>序号</th>${cols.map(c=>`<th class="${c==='操作'?'fixed-action':''}">${c}</th>`).join('')}</tr>${rows.map((r,i)=>`<tr><td><input type="checkbox"></td><td>${i+1}</td>${r.map((x,ci)=>`<td class="${cols[ci]==='操作'?'fixed-action':''}">${ci===cols.length-1?`<span class="link" onclick="showBillEdit('edit')">${String(x).split('  ')[0]}</span><span class="link">详情</span><span class="disabled-link">取消审核</span>`:x}</td>`).join('')}</tr>`).join('')}</table></div><div class="batchbar"><span>已选 <b class="blue">0</b> 条</span><button class="btn subtle-btn">批量审核</button><button class="btn subtle-btn">批量作废</button><button class="btn subtle-btn">批量删除</button>${isSales?'<button class="btn subtle-btn">生成采购单⌃</button>':''}<div class="right"><span>当前页共计 <b class="blue">100</b> 条　合计 <b class="blue">¥51310.15</b>　共1120条</span><button class="pagebtn">100条/页</button><button class="pagebtn">‹</button><button class="pagebtn on">1</button><button class="pagebtn">›</button><span>前往</span><button class="pagebtn">1</button><span>页</span></div></div></div>`;
}
const routeBeforeBillDirectFinal = route;
route = function(id){
  current=id;
  document.querySelectorAll('.nav').forEach(n=>n.classList.toggle('on',n.dataset.id===id));
  const m=mods[id];
  if(m && m.type==='bill'){
    curBill=m.bill;
    title.textContent=m.name;
    desc.textContent=m.desc;
    renderBillDirect();
    return;
  }
  return routeBeforeBillDirectFinal(id);
}
const backListBeforeDirectFinal = backList;
backList = function(){
  const m=mods[current];
  if(m && m.type==='bill') return renderBillDirect();
  return backListBeforeDirectFinal();
}
// ===== Additional V1.0 modules completed in the unified index =====
Object.assign(billMods, {
  purchaseInvoice:['采购发票','发票单号','发票号码','供应商','发票类型','勾稽状态','认证状态'],
  financeExpense:['费用单','费用单号','费用方向','费用类型','对象类型','对象名称','状态'],
  receiptPayment:['收款单/付款单','单据编号','对象类型','对象名称','收付款方式','资金账户','核销状态'],
  stockAdjust:['库存调整单','调整单号','仓库','调整原因','状态']
});
Object.assign(mods, {
  purchaseInvoiceDirect:{group:'采购管理',name:'采购发票',type:'bill',bill:'purchaseInvoice',desc:'采购发票列表、新建、勾稽、认证、作废'},
  financeExpenseDirect:{group:'财务管理',name:'费用单',type:'bill',bill:'financeExpense',desc:'费用收入/支出，生成应收/应付或直接收付款'},
  receiptPaymentDirect:{group:'财务管理',name:'收款单/付款单',type:'bill',bill:'receiptPayment',desc:'收付款单列表、新建、供应商账户选择、核销'},
  stockAdjustDirect:{group:'库存管理',name:'库存调整单',type:'bill',bill:'stockAdjust',desc:'库存数量调整，生成库存流水'}
});
// Override bill editor to show module-specific source/action panels for common gaps.
const showBillEditBeforeMore = showBillEdit;
showBillEdit = function(mode='edit'){
  const b=billMods[curBill];
  const titleText=`${mode==='new'?'新建':'编辑'} - ${b[0]}`;
  let extra='';
  if(curBill==='purchaseInvoice') extra='<div class="section"><h3>发票勾稽</h3>'+matrix(['采购收货单','应付单号','未开票金额','本次勾稽','税额','差异'],[['PR202606130001','AP202606130001','3955.00','3955.00','455.00','0.00']])+'</div>';
  if(curBill==='receiptPayment') extra='<div class="section"><h3>收付款与核销</h3><div class="grid4"><div class="field"><label>对象类型</label><select><option>客户</option><option>供应商</option><option>往来单位</option></select></div><div class="field"><label>供应商收款账户</label><select><option>默认账户-工商银行 6222****0000</option><option>备用账户-建设银行 6227****0000</option></select></div><div class="field"><label>资金账户</label><select><option>工行基本户</option><option>现金账户</option></select></div><div class="field"><label>本次金额</label><input value="1000.00"></div></div>'+matrix(['待核销单号','来源单据','未结金额','本次核销','预收/预付抵扣','折让'],[['AR/AP001','SR/PR001','1000.00','1000.00','0.00','0.00']])+'</div>';
  if(curBill==='financeExpense') extra='<div class="section"><h3>费用生成结果</h3><div class="grid4"><div class="field"><label>是否生成往来</label><select><option>生成应付</option><option>生成应收</option><option>不生成</option></select></div><div class="field"><label>是否直接收付款</label><select><option>否</option><option>是</option></select></div><div class="field"><label>资金账户</label><select><option>工行基本户</option></select></div><div class="field"><label>附件</label><input placeholder="上传发票/凭证"></div></div></div>';
  if(curBill==='stockAdjust') extra='<div class="section"><h3>调整校验</h3><div class="risk">库存调整审核后会生成库存流水；已结账期间不可反审核。</div></div>';
  editShell(titleText, '页面式单据编辑：从列表进入，可返回列表；字段和明细按单据类型展示', billEditBody(mods[curBill]||mods.biz)+extra, `<div class="summary"><span>数量 <b>100</b></span><span>金额 <b>¥1,000.00</b></span><span>成本 <b>¥800.00</b></span><div style="flex:1"></div><button class="btn" onclick="renderBiz()">返回列表</button><button class="btn primary" onclick="toast('${(mods[curBill]&&mods[curBill].audit)||b[0]+'已提交/审核'}')">审核</button></div>`);
};
// ===== Final UX refinements: init steps, AR/AP tabs, invoice match, expense and receipt/payment modes =====
let initStep = 0;
const initSteps = ['初始化说明','基础资料导入','期初库存','期初应收','期初应付','预收/预付','资金账户','校验确认'];
renderInit = function(){
  const panels = [
    `<h3>初始化说明</h3><p>通过向导导入商品、客户、供应商、期初库存、期初应收、期初应付、预收预付与资金账户。确认启用后，期初数据锁定。</p><div class="risk">初始化未完成前，不允许正式审核采购、销售、库存、财务业务单据。</div>`,
    initUpload('基础资料导入','商品、客户、供应商、仓库、费用类型、往来单位'),
    initUpload('期初库存','商品编码、仓库、批次、数量、成本单价、成本金额'),
    initUpload('期初应收','客户/往来单位、应收日期、应收金额、未收金额、预计收款日'),
    initUpload('期初应付','供应商/往来单位、应付日期、应付金额、未付金额、预计付款日'),
    initUpload('预收/预付','客户预收余额、供应商预付余额、往来单位预收预付'),
    initUpload('资金账户','现金、银行、线上账户期初余额'),
    `<h3>校验确认</h3><div class="cards"><div class="card"><div class="label">库存校验</div><div class="value">通过</div></div><div class="card"><div class="label">应收校验</div><div class="value">80</div></div><div class="card"><div class="label">应付校验</div><div class="value">62</div></div><div class="card"><div class="label">资金余额</div><div class="value">¥58K</div></div></div><br><button class="btn primary" onclick="toast('初始化已确认，系统正式启用')">确认启用系统</button>`
  ];
  content.innerHTML=`<div class="form-card"><div class="form-head"><b>数据初始化向导</b><span class="desc">步骤式导入与校验</span></div><div class="form-body"><div class="tabs">${initSteps.map((s,i)=>`<span class="${i===initStep?'on':''}" onclick="initStep=${i};renderInit()">${i+1} ${s}</span>`).join('')}</div><div class="section">${panels[initStep]}</div></div><div class="summary"><button class="btn" onclick="initStep=Math.max(0,initStep-1);renderInit()">上一步</button><button class="btn primary" onclick="initStep=Math.min(initSteps.length-1,initStep+1);renderInit()">下一步</button><span>当前步骤 <b>${initStep+1}/${initSteps.length}</b></span></div></div>`;
}
function initUpload(title,desc){return `<h3>${title}</h3><p>${desc}</p><div class="period-box"><div class="grid4"><div class="field"><label>下载模板</label><button class="btn">下载Excel模板</button></div><div class="field"><label>上传文件</label><button class="btn primary" onclick="toast('文件已上传，开始预校验')">上传</button></div><div class="field"><label>校验结果</label><input value="成功 98 / 失败 2"></div><div class="field"><label>失败处理</label><button class="btn">下载失败原因</button></div></div></div>${matrix(['导入对象','总数','成功','失败','状态','操作'],[[title,'100','98','2','部分成功','查看/下载失败']])}`}
function renderFinanceAccount(kind){
  const isAr = kind==='ar';
  const tabs = isAr?['应收明细','应收结算','预收款','收款记录','账龄分析','逾期预警']:['应付明细','应付结算','预付款','付款记录','账龄分析','付款排期'];
  content.innerHTML=`<div class="tabs">${tabs.map((t,i)=>`<span class="${i===0?'on':''}" onclick="toast('已切换到：${t}')">${t}</span>`).join('')}</div>${filterHtml([[isAr?'客户/往来单位':'供应商/往来单位','对象','select',isAr?ref.customer:ref.supplier],['单据日期','日期','date'],['核销状态','状态','select',ref.status],['逾期/到期状态','状态','select',['全部','未到期','逾期','即将到期']]])}${tableHtml(isAr?['应收单号','对象','来源单据','应收金额','已收','未收','预计收款日','逾期天数','状态']:['应付单号','对象','来源单据','应付金额','已付','未付','预计付款日','到期状态','状态'],isAr?['AR202606130001','华联超市','SR202606130001','1000.00','200.00','800.00','2026-07-13','0','未核销']:['AP202606130001','农夫山泉杭州经销','PR202606130001','3500.00','0.00','3500.00','2026-07-13','未到期','未核销'])}<br><div class="split"><div class="card"><h3>${isAr?'收款结算':'付款结算'}</h3><p>勾选明细后可直接结算，支持${isAr?'预收款':'预付款'}抵扣、折让与生成${isAr?'收款单':'付款单'}。</p></div><div class="sidecard"><b>本次结算</b><br><br><div class="field"><label>资金账户</label><select><option>工行基本户</option></select></div><br><div class="field"><label>${isAr?'本次收款':'本次付款'}</label><input value="800.00"></div><br><div class="field"><label>${isAr?'预收抵扣':'预付抵扣'}</label><input value="0.00"></div><br><button class="btn primary" style="width:100%" onclick="toast('${isAr?'收款':'付款'}单已生成并核销')">生成${isAr?'收款':'付款'}单并核销</button></div></div>`;
}
function renderReceiptPayment(){
  content.innerHTML=`<div class="tabs"><span class="on" onclick="toast('收款模式')">收款单</span><span onclick="toast('付款模式')">付款单</span></div><div class="form-card"><div class="form-head"><b>收款单/付款单</b><span class="desc">支持客户、供应商、往来单位；供应商付款可选择收款账户</span></div><div class="form-body"><div class="grid4">${['对象类型','对象名称','收付款类型','收付款方式','资金账户','供应商收款账户','本次金额','单据日期'].map(f=>`<div class="field"><label>${f}</label>${fieldControl(f)}</div>`).join('')}</div><h3>待核销单据</h3>${matrix(['选择','来源单号','金额','未结','本次核销','预收/预付抵扣'],[['☑','AR/AP001','1000.00','1000.00','1000.00','0.00']])}</div><div class="summary"><span>本次金额 <b>¥1,000.00</b></span><span>已核销 <b>¥1,000.00</b></span><div style="flex:1"></div><button class="btn primary" onclick="toast('收付款单已审核，资金流水已生成')">审核</button></div></div>`;
}
function renderPurchaseInvoice(){
  content.innerHTML=`${filterHtml([['发票日期','日期','date'],['发票号码','号码','text'],['供应商','供应商','select',ref.supplier],['勾稽状态','状态','select',['全部','未勾稽','部分勾稽','已勾稽']],['认证状态','状态','select',['全部','未认证','已认证']]])}${tableHtml(['发票单号','发票号码','供应商','开票日期','不含税金额','税额','含税金额','勾稽状态','认证状态'],['PINV202606130001','330001','农夫山泉杭州经销','2026-06-13','3500.00','455.00','3955.00','未勾稽','未认证'])}<br><div class="form-card"><div class="form-head"><b>发票勾稽</b></div><div class="form-body">${matrix(['采购收货单','应付单号','未开票金额','本次勾稽','税额','差异'],[['PR202606130001','AP202606130001','3955.00','3955.00','455.00','0.00']])}<button class="btn primary" onclick="toast('采购发票已审核并完成勾稽')">审核并勾稽</button></div></div>`;
}
const routeBeforeMoreFinal = route;
route = function(id){
  current=id; document.querySelectorAll('.nav').forEach(n=>n.classList.toggle('on',n.dataset.id===id)); const m=mods[id];
  if(m && m.type==='bill'){
    curBill=m.bill; title.textContent=m.name; desc.textContent=m.desc;
    if(m.bill==='ar') return renderFinanceAccount('ar');
    if(m.bill==='ap') return renderFinanceAccount('ap');
    if(m.bill==='receiptPayment') return renderReceiptPayment();
    if(m.bill==='purchaseInvoice') return renderPurchaseInvoice();
    return renderBillDirect();
  }
  return routeBeforeMoreFinal(id);
}
// ===== Final polish: dashboard business metrics, master list operation bar, product edit scroll =====
const finalStyle=document.createElement('style');
finalStyle.textContent=`.rank-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.rank-card{background:#fff;border:1px solid #cfe0f5;border-radius:14px;padding:14px;box-shadow:0 10px 24px rgba(21,56,95,.06)}.rank-card h3{margin:0 0 10px;color:#12385f}.rank-card table{min-width:100%;font-size:13px}.todo-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}.todo{background:#fff;border:1px solid #cfe0f5;border-radius:12px;padding:12px}.todo b{font-size:20px;color:#1677ff}.master-ops{display:flex;justify-content:flex-end;gap:10px;margin:0 0 10px}.form-card{min-height:calc(100vh - 104px)}.form-body{overflow:visible}.section{scroll-margin-top:74px}.product-section{min-height:360px}.wide-table table{min-width:1600px}`;
document.head.appendChild(finalStyle);
renderDashboard = function(){
  content.innerHTML=`<div class="cards"><div class="card"><div class="label">动销门店数</div><div class="value">286</div></div><div class="card"><div class="label">订单金额</div><div class="value">¥82,450</div></div><div class="card"><div class="label">出库金额</div><div class="value">¥76,320</div></div><div class="card"><div class="label">退货金额</div><div class="value">¥3,180</div></div><div class="card"><div class="label">净销售额</div><div class="value">¥79,270</div></div><div class="card"><div class="label">毛利额</div><div class="value">¥18,920</div></div><div class="card"><div class="label">今日收款额 / 总应收</div><div class="value">¥56,200 / ¥1.28M</div></div><div class="card"><div class="label">今日付款额 / 总应付</div><div class="value">¥32,800 / ¥860K</div></div></div><div style="height:12px"></div><div class="todo-grid"><div class="todo">待审核销售单<br><b>12</b></div><div class="todo">待确认收货<br><b>16</b></div><div class="todo">待处理报损<br><b>3</b></div><div class="todo">逾期应收客户<br><b>35</b></div></div><div style="height:12px"></div><div class="rank-grid"><div class="rank-card"><h3>商品动销排行</h3>${rankTable(['农夫山泉500ml*24','康师傅红烧牛肉面','统一冰红茶'],['1,260箱','880箱','760箱'])}</div><div class="rank-card"><h3>商品大类动销排行</h3>${rankTable(['饮料','方便食品','休闲零食'],['¥32,800','¥21,500','¥16,900'])}</div><div class="rank-card"><h3>门店销售排行</h3>${rankTable(['华联超市','城西便利','民生烟酒'],['¥12,600','¥8,800','¥7,650'])}</div><div class="rank-card"><h3>业务员销售排行</h3>${rankTable(['张三','李娜','王五'],['¥28,600','¥19,800','¥15,300'])}</div></div>`;
}
function rankTable(names,vals){return `<table><tr><th>排名</th><th>名称</th><th class="num">数值</th><th>趋势</th></tr>${names.map((n,i)=>`<tr><td>${i+1}</td><td>${n}</td><td class="num">${vals[i]}</td><td><span class="badge ok">上升</span></td></tr>`).join('')}</table>`}
renderMaster = function(id){
  let m=mods[id];
  content.innerHTML=`<div class="layout2"><div class="tree">${m.tree.map((x,i)=>`<div class="${i===0?'sel':''}" onclick="this.parentNode.querySelectorAll('div').forEach(d=>d.classList.remove('sel'));this.classList.add('sel');toast('已切换：${x.replace(/　/g,'')}')">${x}</div>`).join('')}</div><div>${filterHtml(m.filters)}<div class="master-ops"><button class="btn primary" onclick="renderMasterEditPage('${id}','new')">新建</button><button class="btn">删除</button><button class="btn" onclick="toast('批量编辑')">批量编辑</button><button class="btn">导入修改</button><button class="btn">导出</button></div><div class="wide-table">${tableHtml(m.cols,m.row)}</div></div></div>`;
}
renderMasterEditPage = function(id, mode='edit'){
  const m=mods[id];
  const tabs=masterTabList(id);
  const sections=masterSections(id).replaceAll('<section class="section"','<section class="section product-section"');
  editShell(`${mode==='new'?'新建':'编辑'} - ${m.name}`, '页面式编辑：单页下滑展示所有字段；点击标题快速定位，滚动可查看完整内容', `<div class="tabs" id="editTabs">${tabs.map((t,i)=>`<span class="${i===0?'on':''}" onclick="document.getElementById('${t[0]}').scrollIntoView({behavior:'smooth',block:'start'})">${t[1]}</span>`).join('')}</div>${sections}`, `<div class="summary"><span>编辑状态 <b>${mode==='new'?'新建':'编辑'}</b></span><span>可继续向下滚动查看全部字段</span><div style="flex:1"></div><button class="btn primary" onclick="toast('已保存${m.name}')">保存并继续</button><button class="btn" onclick="renderMaster('${id}')">保存并返回</button></div>`);
}
init();

