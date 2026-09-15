# -*- coding: utf-8 -*-
"""
PRD-34 期初初始化导入模板生成器（库存按批次/按库位、应收、应付）。

只用 Python 标准库（zipfile + 手写最小 OOXML），不依赖 openpyxl，离线可跑：
    python development/04-backend/gen-init-templates.py
产物直接写到 backend/src/main/resources/templates/，随代码同分支提交。

样式：必填列表头=白字红底加粗，非必填=浅蓝底加粗；首行冻结+自动筛选；
第二个页签「导入说明」写校验/批次/建账规则。字符串用 inlineStr，
WPS/Excel/SheetJS 均可解析。
"""
import os
import zipfile

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "..", "backend", "src", "main", "resources", "templates")

# ---------------- 样式 ----------------
# cellXfs 索引：0 常规 / 1 必填表头 / 2 选填表头 / 3 文本数据(带边框) /
# 4 说明大标题 / 5 说明分节标题 / 6 说明正文(自动换行) / 7 说明首列(加粗浅底)
STYLES_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="3">
<font><sz val="11"/><name val="宋体"/><charset val="134"/></font>
<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="宋体"/><charset val="134"/></font>
<font><b/><sz val="14"/><color rgb="FFC00000"/><name val="宋体"/><charset val="134"/></font>
</fonts>
<fills count="4">
<fill><patternFill patternType="none"/></fill>
<fill><patternFill patternType="gray125"/></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFC00000"/><bgColor indexed="64"/></patternFill></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFD9E1F2"/><bgColor indexed="64"/></patternFill></fill>
</fills>
<borders count="2">
<border><left/><right/><top/><bottom/><diagonal/></border>
<border><left style="thin"><color rgb="FFBFBFBF"/></left><right style="thin"><color rgb="FFBFBFBF"/></right><top style="thin"><color rgb="FFBFBFBF"/></top><bottom style="thin"><color rgb="FFBFBFBF"/></bottom><diagonal/></border>
</border>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="8">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/>
<xf numFmtId="49" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1" applyNumberFormat="1"/>
<xf numFmtId="0" fontId="0" fillId="3" borderId="0" xfId="0" applyFill="1" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
</cellXfs>
<cellStyles count="1"><cellStyle name="常规" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""

CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<bookViews><workbookView windowWidth="20000" windowHeight="12000"/></bookViews>
<sheets><sheet name="{sheet1}" sheetId="1" r:id="rId1"/><sheet name="导入说明" sheetId="2" r:id="rId2"/></sheets>
</workbook>"""

WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""


def col_letter(idx):
    """1 -> A, 27 -> AA。"""
    s = ""
    while idx > 0:
        idx, r = divmod(idx - 1, 26)
        s = chr(65 + r) + s
    return s


def esc(text):
    return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace('"', "&quot;"))


def inline_cell(ref, text, style):
    return '<c r="%s" s="%d" t="inlineStr"><is><t xml:space="preserve">%s</t></is></c>' % (
        ref, style, esc(text))


def build_sheet1(headers, required_flags):
    """headers: [(标题, 列宽)]；required_flags: True=必填(红)。"""
    cols_xml = "".join(
        '<col min="%d" max="%d" width="%s" customWidth="1"/>' % (i + 1, i + 1, w)
        for i, (_, w) in enumerate(headers))
    cells = []
    for i, ((title, _), required) in enumerate(zip(headers, required_flags)):
        # 必填靠红底白字表头区分，标题不加 * 后缀（前端 fieldMap 按表头全名精确匹配）
        cells.append(inline_cell("%s1" % col_letter(i + 1), title, 1 if required else 2))
    last = col_letter(len(headers))
    return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetPr/><dimension ref="A1:%s1"/><sheetViews><sheetView tabSelected="1" workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/><selection pane="bottomLeft" activeCell="A2" sqref="A2"/></sheetView></sheetViews>
<sheetFormatPr defaultRowHeight="15"/>
<cols>%s</cols>
<sheetData><row r="1" ht="30" customHeight="1" spans="1:%d">%s</row></sheetData>
<autoFilter ref="A1:%s1"/>
<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>
</worksheet>""" % (last, cols_xml, len(headers), "".join(cells), last)


def build_sheet2(sections, intro):
    """sections: [(分节标题, [说明行...])]；首行大标题 intro。"""
    rows = []
    r = 1
    rows.append('<row r="%d" ht="26" customHeight="1"><c r="A%d" s="4" t="inlineStr"><is><t xml:space="preserve">%s</t></is></c></row>' % (r, r, esc(intro)))
    for title, lines in sections:
        r += 1
        rows.append('<row r="%d" ht="20" customHeight="1"><c r="A%d" s="5" t="inlineStr"><is><t xml:space="preserve">%s</t></is></c></row>' % (r, r, esc(title)))
        for line in lines:
            r += 1
            # 估算行高：每行约 55 个全角字符
            est = max(1, (len(line) + 54) // 55)
            ht = 15 * est + 4
            rows.append('<row r="%d" ht="%d" customHeight="1"><c r="A%d" s="6" t="inlineStr"><is><t xml:space="preserve">%s</t></is></c></row>' % (r, ht, r, esc(line)))
    return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetPr/><dimension ref="A1:A%d"/><sheetViews><sheetView workbookViewId="0"/></sheetViews>
<sheetFormatPr defaultRowHeight="15"/>
<cols><col min="1" max="1" width="110" customWidth="1"/></cols>
<sheetData>%s</sheetData>
<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>
</worksheet>""" % (r, "".join(rows))


COMMON_SECTIONS = [
    ("通用填写规则", [
        "1. 第 1 行为表头（红底=必填列），请勿修改或删除；数据从第 2 行开始填写，一次可导多行。",
        "2. 编码列必须与系统档案完全一致（区分字母大小写）；名称列可不填，导入后以系统档案的标准名称为准，名称列仅用于人工核对，不参与匹配。",
        "3. 日期一律使用 yyyy-MM-dd 格式（如 2026-09-15）。",
        "4. 数字不要加千分位、货币符号；金额/数量直接填数字。",
        "5. 导入后数据先进入页面「暂存区」，可在页面手工新增、编辑、删除行；全部行为有效后点「期初建账」才写入正式账表。",
        "6. 建账前提：本模块尚未建账、系统无任何业务日结记录、总账尚未启用。首次业务日结之前如需调整，可在页面反建账后重新导入。",
    ]),
]

STOCK_BATCH_HEADERS = [
    ("商品编码", 16), ("商品名称", 22), ("仓库编码", 14), ("仓库名称", 16),
    ("批号", 16), ("生产日期", 14), ("效期日期", 14),
    ("数量", 12), ("成本单价(可填0)", 16), ("备注", 24),
]
STOCK_BATCH_REQUIRED = [True, False, True, False, False, False, False, True, True, False]
STOCK_BATCH_SECTIONS = COMMON_SECTIONS + [
    ("库存期初（按批次）填写规则", [
        "1. 必填列：商品编码、仓库编码、数量、成本单价。",
        "2. 数量必须大于 0，最多 4 位小数；成本单价必须 ≥ 0，最多 6 位小数。允许填 0：0 成本行可正常建账，但页面会给出警示，首次采购入库后系统自动重算加权平均成本。",
        "3. 批号规则：批号列手填时以手填为准；批号留空但有生产日期时，自动按生产日期 yyyyMMdd 生成批号（如 20260915）；两者都空则为无批次库存。",
        "4. 效期日期留空时，按商品档案保质期自动推算（生产日期 + 保质期天数）；商品无保质期则效期为空。效期日期不得早于生产日期。",
        "5. 同一文件内「商品+仓库+批号」只能出现一行（无批号时同一商品+仓库只能一行），多批次、多仓库请拆多行；需要分行请先在表内合并数量。",
        "6. 本模板用于【未启用 WMS 库位管理】的账套；启用库位管理请改用「按库位」模板。同一商品+仓库+批号不能两种模板混用（不能只给部分数量定位库位）。",
        "7. 建账时若该商品在该仓库已有库存数量，整批建账将被拒绝，必须先把现有库存清零再导入期初。",
    ]),
]

STOCK_BIN_HEADERS = [
    ("商品编码", 16), ("商品名称", 22), ("仓库编码", 14), ("仓库名称", 16),
    ("库位编码", 14), ("容器编码", 14),
    ("批号", 16), ("生产日期", 14), ("效期日期", 14),
    ("数量", 12), ("成本单价(可填0)", 16), ("备注", 24),
]
STOCK_BIN_REQUIRED = [True, False, True, False, True, False, False, False, False, True, True, False]
STOCK_BIN_SECTIONS = COMMON_SECTIONS + [
    ("库存期初（按库位）填写规则", [
        "1. 必填列：商品编码、仓库编码、库位编码、数量、成本单价。",
        "2. 库位编码必须已在 WMS 库位档案中维护，且状态为正常、未冻结；仓库名称必须与库位所属仓库一致。容器编码可留空。",
        "3. 数量必须大于 0，库位库存最多支持 3 位小数；成本单价 ≥ 0、最多 6 位小数，允许填 0（页面警示，不阻断建账）。",
        "4. 批号规则：批号列手填时以手填为准；留空但有生产日期时自动按 yyyyMMdd 生成；都空则为无批次。效期留空时按生产日期+保质期推算。",
        "5. 同一文件内「商品+仓库+批号+库位+容器」只能出现一行；同一批号放在多个库位时拆多行填写。",
        "6. 过账时除写入财务库存三账外，同时按行写入 WMS 库位库存并增加库位占用量、留存库位流水（来源号 QTRK- 开头，可溯源）。",
        "7. 同一商品+仓库+批号不能与「按批次」模板混用；建账时该商品在该仓库已有数量余额同样整批拒绝，必须先清零。",
    ]),
]

AR_HEADERS = [
    ("客户编码", 16), ("客户名称", 22), ("原单据号", 20), ("原单据日期", 14),
    ("应收金额", 14), ("业务员", 14), ("备注", 24),
]
AR_REQUIRED = [True, False, False, False, True, False, False]
AR_SECTIONS = COMMON_SECTIONS + [
    ("客户应收期初填写规则", [
        "1. 必填列：客户编码、应收金额。客户编码必须是客户档案中已存在的编码。",
        "2. 应收金额为客户截至建账日的未收款余额（含税口径），必须大于 0，最多 2 位小数；系统只导入未达余额，不编造历史收款与核销记录。",
        "3. 原单据号、原单据日期仅留存备查，不进入正式往来账：账龄统一从建账日起算，到期日统一为建账日 + 30 天，模板中没有也不需要填到期日。",
        "4. 同一文件内「客户+原单据号」不能重复；原单据号为空时同一客户只能出现一行，请先在表内合并金额。",
        "5. 业务员可填员工姓名，仅记录不参与分摊；备注可填摘要说明。",
        "6. 反建账限制：行生成的应收单一旦被收款核销或产生核销记录，则不能反建账。",
    ]),
]

AP_HEADERS = [
    ("供应商编码", 16), ("供应商名称", 22), ("原单据号", 20), ("原单据日期", 14),
    ("应付金额", 14), ("备注", 24),
]
AP_REQUIRED = [True, False, False, False, True, False]
AP_SECTIONS = COMMON_SECTIONS + [
    ("供应商应付期初填写规则", [
        "1. 必填列：供应商编码、应付金额。供应商编码必须是供应商档案中已存在的编码。",
        "2. 应付金额为截至建账日的未付款余额（含税口径），必须大于 0，最多 2 位小数；系统只导入未达余额，不编造历史付款与核销记录。",
        "3. 原单据号、原单据日期仅留存备查，不进入正式往来账：账龄统一从建账日起算，到期日统一为建账日 + 30 天。",
        "4. 同一文件内「供应商+原单据号」不能重复；原单据号为空时同一供应商只能出现一行，请先在表内合并金额。",
        "5. 反建账限制：行生成的应付单一旦被付款核销或产生核销记录，则不能反建账。",
    ]),
]

TEMPLATES = [
    ("init-stock-batch-template.xlsx", "期初导入（按批次）",
     STOCK_BATCH_HEADERS, STOCK_BATCH_REQUIRED, STOCK_BATCH_SECTIONS, "库存期初导入模板（按批次，未启用 WMS）"),
    ("init-stock-bin-template.xlsx", "期初导入（按库位）",
     STOCK_BIN_HEADERS, STOCK_BIN_REQUIRED, STOCK_BIN_SECTIONS, "库存期初导入模板（按库位，启用 WMS）"),
    ("init-ar-template.xlsx", "期初导入",
     AR_HEADERS, AR_REQUIRED, AR_SECTIONS, "客户应收期初导入模板"),
    ("init-ap-template.xlsx", "期初导入",
     AP_HEADERS, AP_REQUIRED, AP_SECTIONS, "供应商应付期初导入模板"),
]


def write_template(filename, sheet1_name, headers, required, sections, intro):
    out = os.path.normpath(os.path.join(OUT_DIR, filename))
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", CONTENT_TYPES)
        z.writestr("_rels/.rels", RELS)
        z.writestr("xl/workbook.xml", WORKBOOK.format(sheet1=esc(sheet1_name)))
        z.writestr("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
        z.writestr("xl/styles.xml", STYLES_XML)
        z.writestr("xl/worksheets/sheet1.xml", build_sheet1(headers, required))
        z.writestr("xl/worksheets/sheet2.xml", build_sheet2(sections, intro))
    print("written:", out)


if __name__ == "__main__":
    for t in TEMPLATES:
        write_template(*t)
