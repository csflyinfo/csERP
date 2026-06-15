from pathlib import Path
import re
p=Path(r'E:\我的工作项目\erp-wms-tms\prototype\v1-erp-complete\index.html')
s=p.read_text(encoding='utf-8')
# Remove the business type tab block from renderBillDirect template.
s=re.sub(r'<div class="type-tabs">\$\{typeTabs\.map\(\(x,i\)=>`<button class="\$\{i===0\?\'on\':\'\'\}">\$\{x\}</button>`\)\.join\(\'\'\)\}</div>', '', s)
# Remove variable assignment to avoid PRD-unlisted labels existing in source.
s=re.sub(r"\n\s*const typeTabs=.*?;", "", s)
# Remove CSS for type-tabs if present.
s=re.sub(r"\.type-tabs\{[^}]*\}\.type-tabs button\{[^}]*\}\.type-tabs \.on\{[^}]*\}", "", s)
p.write_text(s, encoding='utf-8')
