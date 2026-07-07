$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$BASE = "http://localhost:8080/api"
$loginRes = Invoke-WebRequest -Uri "$BASE/auth/login" -Method POST -Body '{"username":"admin","password":"admin123"}' -ContentType "application/json" -UseBasicParsing
$token = ($loginRes.Content | ConvertFrom-Json).data.token
Write-Host "[LOGIN] OK"
$headers = @{ Authorization = "Bearer $token"; "Content-Type" = "application/json; charset=utf-8" }

$results = @()
$ts = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()

function TestBtn($module, $btn, $url, $bodyStr, $method = "POST") {
    try {
        if ($method -eq "GET") {
            $r = Invoke-WebRequest -Uri "$BASE$url" -Method GET -Headers $headers -UseBasicParsing -TimeoutSec 15
        } else {
            $b = [System.Text.Encoding]::UTF8.GetBytes($bodyStr)
            $r = Invoke-WebRequest -Uri "$BASE$url" -Method $method -Body $b -Headers $headers -UseBasicParsing -TimeoutSec 15
        }
        $ok = ($r.StatusCode -eq 200)
        $codeVal = "N/A"
        try { $codeVal = ($r.Content | ConvertFrom-Json).code } catch { }
        if ($null -ne $codeVal -and $codeVal -ne "0" -and $codeVal -ne 0 -and $codeVal -ne "N/A") {
            $ok = $false
        }
        $script:results += [PSCustomObject]@{
            Module = $module; Button = $btn; Url = $url; HTTP = $r.StatusCode; Code = $codeVal; OK = $ok; Err = ""
        }
        Write-Host ("[{0,-20}] {1,-16} {2,-45} HTTP={3} code={4}" -f $module, $btn, $url, $r.StatusCode, $codeVal)
    } catch {
        $msg = $_.Exception.Message
        $status = "ERR"
        try { $status = $_.Exception.Response.StatusCode.value__ } catch { }
        $script:results += [PSCustomObject]@{
            Module = $module; Button = $btn; Url = $url; HTTP = $status; Code = "-"; OK = $false; Err = $msg
        }
        Write-Host ("[{0,-20}] {1,-16} {2,-45} ERROR: {3}" -f $module, $btn, $url, $msg)
    }
}

$pageBody = '{"pageNo":1,"pageSize":10}'

Write-Host "`n===== 1. GOODS module buttons ====="
TestBtn "goods" "query/refresh" "/base/goods/page" $pageBody
$goodsCreate = '{"goodsCode":"GDBTN' + $ts + '","goodsName":"BtnGoods","goodsType":"Normal","spec":"1L","baseUnit":"BOTTLE","barcode":"690' + $ts + '","standardPrice":10}'
TestBtn "goods" "create" "/base/goods/create" $goodsCreate
$goodsUpdate = '{"goodsCode":"GDBTN' + $ts + '","goodsName":"BtnGoodsUpd","standardPrice":20}'
TestBtn "goods" "edit" "/base/goods/update" $goodsUpdate
TestBtn "goods" "freeze" "/base/goods/freeze" ('{"goodsCode":"GDBTN' + $ts + '"}')
TestBtn "goods" "stop" "/base/goods/stop" ('{"goodsCode":"GDBTN' + $ts + '"}')
TestBtn "goods" "delete" "/base/goods/delete" ('{"goodsCode":"GDBTN' + $ts + '"}')

Write-Host "`n===== 2. CATEGORY module buttons ====="
TestBtn "category" "query/refresh" "/base/category/page" $pageBody
$catCode = (Get-Random -Minimum 50 -Maximum 99).ToString()
$catCreate = '{"parentId":"","parentCode":"","categoryCode":"' + $catCode + '","categoryName":"BtnCat","defaultTaxRate":"13%"}'
TestBtn "category" "create" "/base/category/create" $catCreate
$catUpdate = '{"categoryCode":"' + $catCode + '","categoryName":"BtnCatUpd","defaultTaxRate":"9%"}'
TestBtn "category" "edit" "/base/category/update" $catUpdate

Write-Host "`n===== 3. BRAND module buttons ====="
TestBtn "brand" "query/refresh" "/base/brand/page" $pageBody
$brandCreate = '{"brandCode":"BRBTN' + $ts + '","brandName":"BtnBrand"}'
TestBtn "brand" "create" "/base/brand/create" $brandCreate

Write-Host "`n===== 4. UNIT module buttons ====="
TestBtn "unit" "query/refresh" "/base/unit/page" $pageBody
$unitCreate = '{"unitCode":"UTBTN' + $ts + '","unitName":"BtnUnit"}'
TestBtn "unit" "create" "/base/unit/create" $unitCreate

Write-Host "`n===== 5. WAREHOUSE module buttons ====="
TestBtn "warehouse" "query/refresh" "/base/warehouse/page" $pageBody
$whCreate = '{"warehouseCode":"WHBTN' + $ts + '","warehouseName":"BtnWH","warehouseType":"Normal","inventoryType":"Main","costGroup":"CG01"}'
TestBtn "warehouse" "create" "/base/warehouse/create" $whCreate

Write-Host "`n===== 6. CUSTOMER module buttons ====="
TestBtn "customer" "query/refresh" "/base/customer/page" $pageBody
$custCreate = '{"customerCode":"CUBTN' + $ts + '","customerName":"BtnCust","channelType":"Retail","mobile":"13800138001"}'
TestBtn "customer" "create" "/base/customer/create" $custCreate
$custUpdate = '{"customerCode":"CUBTN' + $ts + '","customerName":"BtnCustUpd","status":"NORMAL"}'
TestBtn "customer" "edit" "/base/customer/update" $custUpdate
$custFreeze = '{"moduleCode":"customer","bizId":"CUBTN' + $ts + '"}'
TestBtn "customer" "freeze" "/base/master/freeze" $custFreeze
TestBtn "customer" "unfreeze" "/base/master/unfreeze" $custFreeze
TestBtn "customer" "stop" "/base/master/stop" $custFreeze

Write-Host "`n===== 7. SUPPLIER module buttons ====="
TestBtn "supplier" "query/refresh" "/base/supplier/page" $pageBody
$supCreate = '{"supplierCode":"SPBTN' + $ts + '","supplierName":"BtnSup","supplierType":"Normal","phone":"021-1234"}'
TestBtn "supplier" "create" "/base/supplier/create" $supCreate
$supUpdate = '{"supplierCode":"SPBTN' + $ts + '","supplierName":"BtnSupUpd"}'
TestBtn "supplier" "edit" "/base/supplier/update" $supUpdate
$supFreeze = '{"moduleCode":"supplier","bizId":"SPBTN' + $ts + '"}'
TestBtn "supplier" "freeze" "/base/master/freeze" $supFreeze
TestBtn "supplier" "unfreeze" "/base/master/unfreeze" $supFreeze
TestBtn "supplier" "stop" "/base/master/stop" $supFreeze

Write-Host "`n===== 8. MASTER-type modules (create + stop) ====="
$masterModules = @('priceGroup','counterparty','fundAccount','expenseType','territory','routeLine','employee','department','owner')
$masterPagePaths = @{
    priceGroup="/base/master/price-group/page";
    counterparty="/base/master/counterparty/page";
    fundAccount="/base/master/fund-account/page";
    expenseType="/base/master/expense-type/page";
    territory="/base/master/territory/page";
    routeLine="/base/master/route-line/page";
    employee="/base/master/employee/page";
    department="/base/master/department/page";
    owner="/base/master/owner/page"
}
foreach ($m in $masterModules) {
    TestBtn $m "query/refresh" $masterPagePaths[$m] $pageBody
    $sBody = '{"moduleCode":"' + $m + '","code":"CD' + $ts + '","name":"BtnMaster"}'
    TestBtn $m "save" "/base/master/save" $sBody
}

Write-Host "`n===== 9. Excel template download buttons ====="
TestBtn "goods" "template" "/excel/template/goods" $null "GET"
TestBtn "customer" "template" "/excel/template/customer" $null "GET"
TestBtn "supplier" "template" "/excel/template/supplier" $null "GET"
TestBtn "warehouse" "template" "/excel/template/warehouse" $null "GET"

Write-Host "`n===== 10. Customer Price modules ====="
TestBtn "customerPrice" "query" "/base/customer-price-adjust/page" $pageBody
TestBtn "customerPriceQuery" "query" "/base/customer-price/query" $pageBody

Write-Host "`n===== SUMMARY ====="
$success = ($results | Where-Object { $_.OK }).Count
$fail    = ($results | Where-Object { -not $_.OK }).Count
Write-Host ("Total: {0}   Success: {1}   Failed: {2}" -f $results.Count, $success, $fail)

if ($fail -gt 0) {
    Write-Host "`n[FAILED CASES]"
    $results | Where-Object { -not $_.OK } | Format-Table Module, Button, Url, HTTP, Code, Err -AutoSize
}

Write-Host "`n[FULL RESULT]"
$results | Format-Table Module, Button, Url, HTTP, Code, OK -AutoSize
