from pathlib import Path
import re
p=Path(r'E:\我的工作项目\erp-wms-tms\prototype\v1-erp-complete\customer-archive.html')
s=p.read_text(encoding='utf-8')
s=s.replace('.tabs span{cursor:pointer}.pane{display:none}.pane.on{display:block}', '.tabs span{cursor:pointer}.modal.edit{height:82vh;max-height:82vh}.modal.edit .body{flex:1;overflow-y:auto;overflow-x:hidden;min-height:0;scroll-behavior:smooth}.modal.edit .tabs{position:sticky;top:0;background:#fff;z-index:5}.pane{display:block;padding-top:24px;min-height:220px}.pane.on{display:block}')
s=s.replace('<div class="tabs"><span class="on" onclick="tab(0)">基本信息</span><span onclick="tab(1)">地址信息</span><span onclick="tab(2)">账期设置</span><span onclick="tab(3)">开票信息</span><span onclick="tab(4)">导入修改</span></div><div class="body"><div class="pane on">', '<div class="tabs" id="editTabs"><span class="on" onclick="jumpSec(\'base\')">基本信息</span><span onclick="jumpSec(\'address\')">地址信息</span><span onclick="jumpSec(\'period\')">账期设置</span><span onclick="jumpSec(\'invoice\')">开票信息</span><span onclick="jumpSec(\'import\')">导入修改</span></div><div class="body" id="editBody" onscroll="syncTabs()"><div class="pane on" id="base">')
s=s.replace('<div class="pane"><div class="sec">地址信息</div>', '<div class="pane" id="address"><div class="sec">地址信息</div>')
s=s.replace('<div class="pane"><div class="sec">账期设置</div>', '<div class="pane" id="period"><div class="sec">账期设置</div>')
s=s.replace('<div class="pane"><div class="sec">开票信息</div>', '<div class="pane" id="invoice"><div class="sec">开票信息</div>')
s=s.replace('<div class="pane"><div class="sec">导入修改/批量修改</div>', '<div class="pane" id="import"><div class="sec">导入修改/批量修改</div>')
s=re.sub(r'function tab\(i\)\{.*?\}\nfunction periodChange', 'function jumpSec(id){document.getElementById(id).scrollIntoView({block:"start",behavior:"smooth"});setTimeout(syncTabs,260)}\nconst secIds=["base","address","period","invoice","import"];function syncTabs(){let body=document.getElementById("editBody");if(!body)return;let active=0;secIds.forEach((id,i)=>{let el=document.getElementById(id);if(el&&el.offsetTop-body.scrollTop<150)active=i});document.querySelectorAll("#editTabs span").forEach((x,i)=>x.classList.toggle("on",i===active))}\nfunction periodChange', s)
p.write_text(s,encoding='utf-8')
