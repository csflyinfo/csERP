from pathlib import Path
s=Path(r"E:\我的工作项目\erp-wms-tms\prototype\v1-erp-complete\product-archive.html").read_text(encoding="utf-8")
for token in ["采购与库存","商品负责人","id=\"warehouse\"","secIds"]:
    i=s.find(token)
    print('TOKEN',token,'IDX',i)
    print(s[i-200:i+300] if i!=-1 else 'not found')
