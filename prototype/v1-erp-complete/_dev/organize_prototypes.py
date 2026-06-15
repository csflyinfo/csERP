from pathlib import Path
import shutil

root = Path(r"E:\我的工作项目\erp-wms-tms\prototype\v1-erp-complete")
for d in ["00-main", "01-base-data", "02-sales", "03-purchase", "04-inventory", "05-finance", "06-system", "assets", "_dev"]:
    (root / d).mkdir(exist_ok=True)

moves = {
    "product-archive.html": "01-base-data",
    "customer-archive.html": "01-base-data",
    "supplier-archive.html": "01-base-data",
    "master-data-combined.html": "01-base-data",
    "sales-quick-order.html": "02-sales",
    "sales-order.html": "02-sales",
    "sales-outbound.html": "02-sales",
    "sales-receipt.html": "02-sales",
    "purchase-order.html": "03-purchase",
    "purchase-inbound.html": "03-purchase",
    "purchase-receipt.html": "03-purchase",
    "purchase-expense.html": "03-purchase",
    "inventory-balance.html": "04-inventory",
    "other-inbound.html": "04-inventory",
    "other-outbound.html": "04-inventory",
    "transfer-order.html": "04-inventory",
    "damage-order.html": "04-inventory",
    "cost-adjust.html": "04-inventory",
    "ar-ap-account.html": "05-finance",
    "receipt-payment.html": "05-finance",
    "init-wizard.html": "06-system",
    "system-prototype.html": "00-main",
    "modules.html": "00-main",
}

for name, folder in moves.items():
    src = root / name
    dst = root / folder / name
    if src.exists():
        if dst.exists():
            dst.unlink()
        shutil.move(str(src), str(dst))

css_src = root / "module-style.css"
css_dst = root / "assets" / "module-style.css"
if css_src.exists():
    if css_dst.exists():
        css_dst.unlink()
    shutil.move(str(css_src), str(css_dst))

for html in root.glob("*/*.html"):
    if html.parent.name == "00-main":
        css_rel = "../assets/module-style.css"
    else:
        css_rel = "../assets/module-style.css"
    text = html.read_text(encoding="utf-8")
    text = text.replace('href="module-style.css"', f'href="{css_rel}"')
    html.write_text(text, encoding="utf-8")

# fix cross-links in module index pages
modules = root / "00-main" / "modules.html"
if modules.exists():
    text = modules.read_text(encoding="utf-8")
    replacements = {
        'href="product-archive.html"': 'href="../01-base-data/product-archive.html"',
        'href="customer-archive.html"': 'href="../01-base-data/customer-archive.html"',
        'href="supplier-archive.html"': 'href="../01-base-data/supplier-archive.html"',
        'href="master-data-combined.html"': 'href="../01-base-data/master-data-combined.html"',
        'href="sales-quick-order.html"': 'href="../02-sales/sales-quick-order.html"',
        'href="sales-order.html"': 'href="../02-sales/sales-order.html"',
        'href="purchase-order.html"': 'href="../03-purchase/purchase-order.html"',
        'href="purchase-expense.html"': 'href="../03-purchase/purchase-expense.html"',
        'href="inventory-balance.html"': 'href="../04-inventory/inventory-balance.html"',
        'href="ar-settlement.html"': 'href="../05-finance/ar-ap-account.html"',
        'href="ap-settlement.html"': 'href="../05-finance/ar-ap-account.html"',
        'href="damage-order.html"': 'href="../04-inventory/damage-order.html"',
        'href="cost-adjust.html"': 'href="../04-inventory/cost-adjust.html"',
    }
    for a, b in replacements.items():
        text = text.replace(a, b)
    modules.write_text(text, encoding="utf-8")

system = root / "00-main" / "system-prototype.html"
if system.exists():
    text = system.read_text(encoding="utf-8")
    replacements = {
        'product-archive.html': '../01-base-data/product-archive.html',
        'customer-archive.html': '../01-base-data/customer-archive.html',
        'supplier-archive.html': '../01-base-data/supplier-archive.html',
        'master-data-combined.html': '../01-base-data/master-data-combined.html',
        'sales-quick-order.html': '../02-sales/sales-quick-order.html',
        'index.html': '../index.html',
    }
    for a, b in replacements.items():
        text = text.replace(a, b)
    system.write_text(text, encoding="utf-8")

# move scratch scripts/check files to _dev, except this script already there
for f in root.iterdir():
    if f.is_file() and (f.name.startswith("_") or f.suffix in {".py", ".js"}):
        dst = root / "_dev" / f.name
        if f.resolve() != dst.resolve():
            if dst.exists():
                dst.unlink()
            shutil.move(str(f), str(dst))

print("organized")
