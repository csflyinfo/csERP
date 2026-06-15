from pathlib import Path
p=Path(r'E:\我的工作项目\erp-wms-tms\prototype\v1-erp-complete\index.html')
s=p.read_text(encoding='utf-8')
old='<header class="top"><div class="brand"><div class="mark"></div>商贸云 ERP V1.0</div><input class="quick-search" placeholder="模块快捷搜索：客户、商品、供应商、单据号"><div class="top-spacer"></div><button class="topbtn" onclick="toast(\'导出中心：3个任务\')">导出中心</button><button class="topbtn" onclick="toast(\'消息：12个待办\')">消息</button><div class="user" onclick="toggleUser()"><div class="avatar">管</div><span>管理员</span></div><div class="user-pop" id="userPop"><h3>用户信息</h3><div class="user-row"><span>姓名</span><b>系统管理员</b></div><div class="user-row"><span>账号</span><b>admin</b></div><div class="user-row"><span>角色</span><b>管理员组</b></div><div class="user-row"><span>数据范围</span><b>全部</b></div><br><button class="btn" style="width:100%">退出登录</button></div></header>'
new='<header class="top"><div class="brand"><div class="mark"></div>商贸云 ERP V1.0</div><button class="hamb" onclick="toast(\'菜单展开/收起\')">☰</button><div class="top-tab home-tab" onclick="route(\'dashboard\')">首页 <span class="x">×</span></div><div class="top-tabs" id="topTabs"></div><input class="quick-search" placeholder="搜索客户、商品、供应商、单据号"><div class="top-spacer"></div><button class="topbtn" onclick="toast(\'导出中心：3个任务\')">导出中心</button><button class="topbtn" onclick="toast(\'消息：12个待办\')">消息</button><div class="user" onclick="toggleUser()"><div class="avatar">管</div><span>管理员</span></div><div class="user-pop" id="userPop"><h3>用户信息</h3><div class="user-row"><span>姓名</span><b>系统管理员</b></div><div class="user-row"><span>账号</span><b>admin</b></div><div class="user-row"><span>角色</span><b>管理员组</b></div><div class="user-row"><span>数据范围</span><b>全部</b></div><br><button class="btn" style="width:100%">退出登录</button></div></header>'
if old not in s:
    print('top old not found')
else:
    s=s.replace(old,new)
css='.hamb{width:36px;height:36px;border:0;background:#fff;font-size:22px;color:#111827;cursor:pointer}.top-tabs{display:flex;gap:6px;align-items:center;min-width:0;max-width:560px;overflow:hidden}.top-tab{height:34px;display:flex;align-items:center;gap:8px;border:1px solid #d7e5f6;background:#f8fbff;color:#244b74;border-radius:6px;padding:0 12px;cursor:pointer;white-space:nowrap}.top-tab.active{background:#1677ff;color:#fff;border-color:#1677ff}.top-tab .x{font-size:13px;opacity:.75}.home-tab{background:#fff;border-color:transparent;color:#1f2937}.quick-search{width:300px!important}'
s=s.replace('</style>', css+'</style>')
insert="""
// ===== Top tab navigation =====
let openedTabs=[];
function moduleName(id){
  for(const [g,items] of Object.entries(nav)){ const hit=items.find(x=>x[0]===id); if(hit) return hit[1]; }
  return id;
}
function addTopTab(id){
  if(id==='dashboard'){ renderTopTabs(); return; }
  if(!openedTabs.includes(id)) openedTabs.push(id);
  renderTopTabs();
}
function renderTopTabs(){
  const box=document.getElementById('topTabs'); if(!box) return;
  box.innerHTML=openedTabs.map(id=>`<div class=\"top-tab ${current===id?'active':''}\" onclick=\"route('${id}')\">${moduleName(id)} <span class=\"x\" onclick=\"event.stopPropagation();closeTopTab('${id}')\">×</span></div>`).join('');
}
function closeTopTab(id){
  openedTabs=openedTabs.filter(x=>x!==id);
  if(current===id){ const next=openedTabs[openedTabs.length-1]||'dashboard'; route(next); }
  else renderTopTabs();
}
const routeBeforeTabs=route;
route=function(id){ routeBeforeTabs(id); addTopTab(id); };
"""
s=s.replace('\ninit();\n</script>', insert+'\ninit();\n</script>')
p.write_text(s,encoding='utf-8')
