param([string]$Path)
Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap($Path)
$points = @(
    @{n='top-left';x=96;y=96},
    @{n='top-right';x=1824;y=96},
    @{n='bottom-left';x=96;y=1824},
    @{n='bottom-right';x=1824;y=1824},
    @{n='center';x=960;y=960},
    @{n='upper-center';x=960;y=300},
    @{n='lower-center';x=960;y=1700},
    @{n='mid-left';x=400;y=960},
    @{n='mid-right';x=1520;y=960}
)
foreach ($p in $points) {
    $c = $bmp.GetPixel($p.x, $p.y)
    Write-Host ($p.n + ' = R' + $c.R + ',G' + $c.G + ',B' + $c.B)
}
$bmp.Dispose()
