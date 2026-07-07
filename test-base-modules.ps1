$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$BASE = "http://localhost:8080/api"
$body = '{"username":"admin","password":"admin123"}'
$loginRes = Invoke-WebRequest -Uri "$BASE/auth/login" -Method POST -Body $body -ContentType "application/json" -UseBasicParsing
$loginJson = $loginRes.Content | ConvertFrom-Json
$token = $loginJson.data.token
Write-Host "[LOGIN] token acquired: $($token.Substring(0,20))..."

$headers = @{ Authorization = "Bearer $token"; "Content-Type" = "application/json; charset=utf-8" }

$results = @()

function Test-Endpoint($name, $url, $bodyStr) {
    try {
        $b = [System.Text.Encoding]::UTF8.GetBytes($bodyStr)
        $r = Invoke-WebRequest -Uri "$BASE$url" -Method POST -Body $b -Headers $headers -UseBasicParsing -TimeoutSec 15
        $j = $r.Content | ConvertFrom-Json
        $ok = ($j.code -eq "0" -or $j.code -eq 0)
        $rowCount = 0
        if ($j.data.records) { $rowCount = $j.data.records.Count } elseif ($j.data -is [array]) { $rowCount = $j.data.Count }
        $script:results += [PSCustomObject]@{
            Module = $name; Url = $url; Status = $r.StatusCode; Code = $j.code;
            OK = $ok; Rows = $rowCount; Message = $j.message
        }
        Write-Host ("[{0,-30}] {1,-40} status={2} code={3} rows={4}" -f $name, $url, $r.StatusCode, $j.code, $rowCount)
        return $j
    } catch {
        $script:results += [PSCustomObject]@{
            Module = $name; Url = $url; Status = "ERR"; Code = "ERR"; OK = $false; Rows = 0; Message = $_.Exception.Message
        }
        Write-Host ("[{0,-30}] {1,-40} ERROR: {2}" -f $name, $url, $_.Exception.Message)
        return $null
    }
}

Write-Host "`n=============== PAGE (LIST) TESTS ==============="
$pageBody = '{"pageNo":1,"pageSize":10}'
Test-Endpoint "goods.page"       "/base/goods/page"       $pageBody | Out-Null
Test-Endpoint "category.page"    "/base/category/page"    $pageBody | Out-Null
Test-Endpoint "brand.page"       "/base/brand/page"       $pageBody | Out-Null
Test-Endpoint "unit.page"        "/base/unit/page"        $pageBody | Out-Null
Test-Endpoint "warehouse.page"   "/base/warehouse/page"   $pageBody | Out-Null
Test-Endpoint "customer.page"    "/base/customer/page"    $pageBody | Out-Null
Test-Endpoint "supplier.page"    "/base/supplier/page"    $pageBody | Out-Null
Test-Endpoint "priceGroup.page"  "/base/master/price-group/page"  $pageBody | Out-Null
Test-Endpoint "counterparty"     "/base/master/counterparty/page" $pageBody | Out-Null
Test-Endpoint "fundAccount"      "/base/master/fund-account/page" $pageBody | Out-Null
Test-Endpoint "expenseType"      "/base/master/expense-type/page" $pageBody | Out-Null
Test-Endpoint "territory"        "/base/master/territory/page"    $pageBody | Out-Null
Test-Endpoint "routeLine"        "/base/master/route-line/page"   $pageBody | Out-Null
Test-Endpoint "employee"         "/base/master/employee/page"     $pageBody | Out-Null
Test-Endpoint "department"       "/base/master/department/page"   $pageBody | Out-Null
Test-Endpoint "owner"            "/base/master/owner/page"        $pageBody | Out-Null

Write-Host "`n=============== CREATE TESTS ==============="
$ts = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()

$brandBody = '{"brandCode":"BRT' + $ts + '","brandName":"TestBrand' + $ts + '"}'
Test-Endpoint "brand.create"     "/base/brand/create"     $brandBody | Out-Null

$unitBody = '{"unitCode":"UTT' + $ts + '","unitName":"TestUnit' + $ts + '"}'
Test-Endpoint "unit.create"      "/base/unit/create"      $unitBody | Out-Null

$catBody = '{"parentId":"","parentCode":"","categoryCode":"99","categoryName":"TestCat' + $ts + '","defaultTaxRate":"13%"}'
Test-Endpoint "category.create" "/base/category/create" $catBody | Out-Null

$whBody = '{"warehouseCode":"WHT' + $ts + '","warehouseName":"TestWH' + $ts + '","warehouseType":"Normal","inventoryType":"Main","costGroup":"CG01","managerName":"TestManager"}'
Test-Endpoint "warehouse.create" "/base/warehouse/create" $whBody | Out-Null

$goodsBody = '{"goodsCode":"GDT' + $ts + '","goodsName":"TestGoods' + $ts + '","goodsType":"Normal","spec":"500ml","baseUnit":"BOTTLE","barcode":"6901234' + $ts + '","standardPrice":9.9,"canSale":true,"canPurchase":true}'
Test-Endpoint "goods.create" "/base/goods/create" $goodsBody | Out-Null

$custBody = '{"customerCode":"CUT' + $ts + '","customerName":"TestCust' + $ts + '","channelType":"Retail","contactName":"TestContact","mobile":"13800138000","customerLevel":"Normal"}'
Test-Endpoint "customer.create" "/base/customer/create" $custBody | Out-Null

$supBody = '{"supplierCode":"SPT' + $ts + '","supplierName":"TestSup' + $ts + '","supplierType":"Normal","contactName":"TestContact","phone":"021-12345678","settlementMethod":"Monthly"}'
Test-Endpoint "supplier.create" "/base/supplier/create" $supBody | Out-Null

Write-Host "`n=============== UPDATE TESTS ==============="

$catUpdBody = '{"categoryCode":"99","categoryName":"TestCat' + $ts + '-Upd","defaultTaxRate":"9%"}'
Test-Endpoint "category.update" "/base/category/update" $catUpdBody | Out-Null

$goodsUpdBody = '{"goodsCode":"GDT' + $ts + '","goodsName":"TestGoods' + $ts + '-Upd","standardPrice":19.9}'
Test-Endpoint "goods.update" "/base/goods/update" $goodsUpdBody | Out-Null

$custUpdBody = '{"customerCode":"CUT' + $ts + '","customerName":"TestCust' + $ts + '-Upd","status":"NORMAL"}'
Test-Endpoint "customer.update" "/base/customer/update" $custUpdBody | Out-Null

$supUpdBody = '{"supplierCode":"SPT' + $ts + '","supplierName":"TestSup' + $ts + '-Upd","phone":"021-99999999"}'
Test-Endpoint "supplier.update" "/base/supplier/update" $supUpdBody | Out-Null

Write-Host "`n=============== DELETE / STOP TESTS ==============="

$stopGoods = '{"goodsCode":"GDT' + $ts + '"}'
Test-Endpoint "goods.stop" "/base/goods/stop" $stopGoods | Out-Null

$stopCust = '{"moduleCode":"customer","bizId":"CUT' + $ts + '"}'
Test-Endpoint "customer.stop-master" "/base/master/stop" $stopCust | Out-Null

$stopSup = '{"moduleCode":"supplier","bizId":"SPT' + $ts + '"}'
Test-Endpoint "supplier.stop-master" "/base/master/stop" $stopSup | Out-Null

$saveMaster = '{"moduleCode":"customer","customerCode":"CMT' + $ts + '","customerName":"MasterSave' + $ts + '"}'
Test-Endpoint "master.save-customer" "/base/master/save" $saveMaster | Out-Null

Write-Host "`n=============== SUMMARY ==============="
$success = ($results | Where-Object { $_.OK }).Count
$fail    = ($results | Where-Object { -not $_.OK }).Count
Write-Host "Total: $($results.Count)   Success: $success   Failed: $fail"
if ($fail -gt 0) {
    Write-Host "`n[FAILED CASES]"
    $results | Where-Object { -not $_.OK } | Format-Table Module, Url, Status, Code, Message -AutoSize
}
Write-Host "`n[ALL RESULTS]"
$results | Format-Table Module, Status, Code, Rows -AutoSize
