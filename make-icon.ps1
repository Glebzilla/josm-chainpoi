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

$images = Join-Path $PSScriptRoot 'resources\images'
New-ChainPoiIcon (Join-Path $images 'dialogs\chainpoi.png')
New-ChainPoiIcon (Join-Path $images 'chainpoi.png')
