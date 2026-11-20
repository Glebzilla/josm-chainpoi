Add-Type -AssemblyName System.Drawing

# JOSM resolves images by prefix:
#   ToggleDialog  -> images/dialogs/<name>.png
#   JosmAction    -> images/<name>.png
# So the same 16x16 icon is written to both places.

function New-ChainPoiIcon([string]$path) {
    $dir = Split-Path -Parent $path
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    $bmp = New-Object System.Drawing.Bitmap 16, 16
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 224, 58, 36))
    $g.FillRectangle($brush, 1, 1, 14, 14)

    $font = New-Object System.Drawing.Font('Arial', 9, [System.Drawing.FontStyle]::Bold)
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $sf.LineAlignment = [System.Drawing.StringAlignment]::Center
    $rect = New-Object System.Drawing.RectangleF 0, 0, 16, 16
    $g.DrawString('M', $font, [System.Drawing.Brushes]::White, $rect, $sf)

    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()

    Write-Output ("icon: $path  " + (Get-Item -LiteralPath $path).Length + " bytes")
}

function New-PanoramaIcon([string]$path) {
    $dir = Split-Path -Parent $path
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    $bmp = New-Object System.Drawing.Bitmap 16, 16
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    # рамка
    $frame = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 21, 101, 192))
    $g.FillRectangle($frame, 1, 3, 14, 10)

    # небо
    $sky = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 187, 222, 251))
    $g.FillRectangle($sky, 2, 4, 12, 8)

    # солнце
    $sun = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 255, 193, 7))
    $g.FillEllipse($sun, 3, 5, 3, 3)

    # холмы
    $grass = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 67, 160, 71))
    $points = @(
        (New-Object System.Drawing.Point 2, 12),
        (New-Object System.Drawing.Point 5, 7),
        (New-Object System.Drawing.Point 8, 10),
        (New-Object System.Drawing.Point 11, 7),
        (New-Object System.Drawing.Point 14, 12)
    )
    $g.FillPolygon($grass, $points)

    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()

    Write-Output ("icon: $path  " + (Get-Item -LiteralPath $path).Length + " bytes")
}

function New-Badge([string]$path, [string]$color, [string]$text, [int]$fontSize) {
    $dir = Split-Path -Parent $path
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    $bmp = New-Object System.Drawing.Bitmap 16, 16
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.ColorTranslator]::FromHtml($color))
    $g.FillRectangle($brush, 1, 1, 14, 14)

    $font = New-Object System.Drawing.Font('Arial', $fontSize, [System.Drawing.FontStyle]::Bold)
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $sf.LineAlignment = [System.Drawing.StringAlignment]::Center
    $rect = New-Object System.Drawing.RectangleF 0, 0, 16, 16
    $g.DrawString($text, $font, [System.Drawing.Brushes]::White, $rect, $sf)

    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()

    Write-Output ("badge: $path  " + (Get-Item -LiteralPath $path).Length + " bytes")
}

$images = Join-Path $PSScriptRoot 'resources\images'
New-ChainPoiIcon (Join-Path $images 'dialogs\chainpoi.png')
New-ChainPoiIcon (Join-Path $images 'chainpoi.png')
New-PanoramaIcon (Join-Path $images 'panoramas.png')
New-Badge (Join-Path $images 'chain-magnit.png') '#E03A24' 'М' 9
New-Badge (Join-Path $images 'chain-kosmetik.png') '#E91E63' 'МК' 7
New-Badge (Join-Path $images 'chain-pyaterochka.png') '#2E7D32' '5' 10
New-Badge (Join-Path $images 'chain-ozon.png') '#005BFF' 'O' 9
New-Badge (Join-Path $images 'chain-ozon-box.png') '#0D47A1' 'O' 9
New-Badge (Join-Path $images 'chain-wildberries.png') '#7B1FA2' 'W' 9
