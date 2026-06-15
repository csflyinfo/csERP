from pathlib import Path
p=Path(r'E:\我的工作项目\erp-wms-tms\prototype\v1-erp-complete\customer-archive.html')
s=p.read_text(encoding='utf-8')
# CSS: panes become visible sections, body scrolls smoothly; keep simple
s=s.replace('.tabs span{cursor:pointer}.pane{display:none}.pane.on{display:block}', '.tabs span{cursor:pointer}.tabs .on{color:#1677ff;border-bottom:2px solid #1677ff}.body{scroll-behavior:smooth}.pane{display:block;padding-top:26px;min-height:220px}.pane.on{display:block}')
# Replace tab bar with anchor navigation, remove sales history
s=s.replace('<div class="tabs"><span class="on" onclick="tab(0)">基本信息</span><span onclick="tab(1)">地址信息</span><span onclick="tab(2)">账期设置</span><span onclick="tab(3)">开票信息</span><span onclick="tab(4)">导入修改</span><span onclick="tab(5)">销售历史</span></div><div class="body">', '<div class="tabs" id="editTabs"><span class="on" onclick="jumpSec(\'base\')">基本信息</span><span onclick="jumpSec(\'address\')">地址信息</span><span onclick="jumpSec(\'period\')">账期设置</span><span onclick="jumpSec(\'invoice\')">开票信息</span><span onclick="jumpSec(\'import\')">导入修改</span></div><div class="body" id="editBody" onscroll="syncTabs()">')
# Add ids to panes in order and remove on class dependence
repls=[('<div class="pane on"><div class="sec">基本信息</div>', '<div class="pane on" id="base"><div class="sec">基本信息</div>'),
       ('<div class="pane"><div class="sec">地址信息</div>', '<div class="pane" id="address"><div class="sec">地址信息</div>'),
       ('<div class="pane"><div class="sec">账期设置</div>', '<div class="pane" id="period"><div class="sec">账期设置</div>'),
       ('<div class="pane"><div class="sec">开票信息</div>', '<div class="pane" id="invoice"><div class="sec">开票信息</div>'),
       ('<div class="pane"><div class="sec">导入修改/批量修改</div>', '<div class="pane" id="import"><div class="sec">导入修改/批量修改</div>')]
for a,b in repls: s=s.replace(a,b,1)
# Remove sales history pane if present
start=s.find('<div class="pane"><div class="sec">销售历史</div>')
if start!=-1:
    end=s.find('</div></div><div class="bottom">', start)
    if end!=-1:
        s=s[:start]+s[end+6:]
# Replace tab function with anchor scroll functions
old="function tab(i){document.querySelectorAll('.tabs span').forEach((x,n)=>x.classList.toggle('on',n==i));document.querySelectorAll('.pane').forEach((x,n)=>x.classList.toggle('on',n==i))}"
new="function jumpSec(id){document.getElementById(id).scrollIntoView({block:'start',behavior:'smooth'});setTimeout(syncTabs,260)}\nconst secIds=['base','address','period','invoice','import'];function syncTabs(){let body=document.getElementById('editBody');if(!body)return;let active=0;secIds.forEach((id,i)=>{let el=document.getElementById(id);if(el&&el.offsetTop-body.scrollTop<170)active=i});document.querySelectorAll('#editTabs span').forEach((x,i)=>x.classList.toggle('on',i===active))}"
s=s.replace(old,new)
p.write_text(s,encoding='utf-8')
