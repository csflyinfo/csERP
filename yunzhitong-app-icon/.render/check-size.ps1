param([string]$Path)
Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile($Path)
Write-Host ('Width=' + $img.Width)
Write-Host ('Height=' + $img.Height)
$img.Dispose()
