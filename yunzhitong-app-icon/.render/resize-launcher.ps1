# 由 1024x1024 母图生成 Android 各密度 ic_launcher.png。
# 用 HighQualityBicubic 而非默认缩放，否则图标里「智速达」三个字在 mdpi(48px) 下会糊成一团。
Add-Type -AssemblyName System.Drawing

$src = 'e:\我的工作项目\erp-wms-tms\yunzhitong-app-icon\assets\zhishuda-icon.png'
$resRoot = 'e:\我的工作项目\erp-wms-tms\tms_driver_app\android\app\src\main\res'

# Android launcher 图标标准尺寸：mdpi 48px 为基准，逐级 1.5/2/3/4 倍
$targets = @{
    'mipmap-mdpi'    = 48
    'mipmap-hdpi'    = 72
    'mipmap-xhdpi'   = 96
    'mipmap-xxhdpi'  = 144
    'mipmap-xxxhdpi' = 192
}

$origin = [System.Drawing.Image]::FromFile($src)
try {
    foreach ($dir in $targets.Keys) {
        $size = $targets[$dir]
        $outDir = Join-Path $resRoot $dir
        if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }
        $out = Join-Path $outDir 'ic_launcher.png'

        $bmp = New-Object System.Drawing.Bitmap $size, $size
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        try {
            $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
            $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
            $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $g.DrawImage($origin, 0, 0, $size, $size)
        } finally {
            $g.Dispose()
        }
        $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
        Write-Host ("{0,-16} {1}x{1}" -f $dir, $size)
    }
} finally {
    $origin.Dispose()
}
Write-Host 'launcher icons generated'
