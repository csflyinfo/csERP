$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$BASE = "http://localhost:8080/api"
$body = '{"username":"admin","password":"admin123"}'
$loginRes = Invoke-WebRequest -Uri "$BASE/auth/login" -Method POST -Body $body -ContentType "application/json" -UseBasicParsing
$token = ($loginRes.Content | ConvertFrom-Json).data.token
$headers = @{ Authorization = "Bearer $token"; "Content-Type" = "application/json; charset=utf-8" }

function Call($url, $bodyStr) {
    $b = [System.Text.Encoding]::UTF8.GetBytes($bodyStr)
    $r = Invoke-WebRequest -Uri "$BASE$url" -Method POST -Body $b -Headers $headers -UseBasicParsing -TimeoutSec 15
    return $r.Content | ConvertFrom-Json
}

Write-Host "======= Category unique-code retest ======="
$rnd = Get-Random -Minimum 10 -Maximum 99
$catBody = '{"parentId":"","parentCode":"","categoryCode":"' + $rnd + '","categoryName":"UniqueCat","defaultTaxRate":"13%"}'
$catRes = Call "/base/category/create" $catBody
Write-Host ("category.create code=" + $catRes.code + " message=" + $catRes.message)
if ($catRes.data) { Write-Host ("  -> categoryCode=" + $catRes.data.categoryCode + " id=" + $catRes.data.categoryId) }

Write-Host "`n======= Persistence: verify created records still in list ======="
$goodsList = Call "/base/goods/page" '{"pageNo":1,"pageSize":50,"keyword":"GDT"}'
Write-Host ("goods with keyword=GDT: total=" + $goodsList.data.total + " rows=" + $goodsList.data.records.Count)
if ($goodsList.data.records.Count -gt 0) {
    Write-Host "  Sample: " ($goodsList.data.records[0] | ConvertTo-Json -Compress -Depth 3).Substring(0, 200)
}

$brandList = Call "/base/brand/page" '{"pageNo":1,"pageSize":50}'
Write-Host ("brand total after create: " + $brandList.data.total)

$custList = Call "/base/customer/page" '{"pageNo":1,"pageSize":50,"keyword":"CUT"}'
Write-Host ("customer keyword=CUT total: " + $custList.data.total)

$supList = Call "/base/supplier/page" '{"pageNo":1,"pageSize":50,"keyword":"SPT"}'
Write-Host ("supplier keyword=SPT total: " + $supList.data.total)

Write-Host "`n======= Category duplicate should fail with 400 ======="
$catDup = Call "/base/category/create" $catBody
Write-Host ("Duplicate result code=" + $catDup.code + " message=" + $catDup.message)

Write-Host "`n======= Update check on created customer ======="
$custDetail = Call "/base/customer/page" ('{"pageNo":1,"pageSize":10,"keyword":"CUT"}')
if ($custDetail.data.records.Count -gt 0) {
    $rec = $custDetail.data.records[0]
    Write-Host ("customer.name=" + $rec.customerName + " status=" + $rec.status)
}
