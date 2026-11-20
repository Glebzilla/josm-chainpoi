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

function New-Badge([string]$path, [string]$color, [string]$glyph) {
    $dir = Split-Path -Parent $path
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    $bmp = New-Object System.Drawing.Bitmap 16, 16
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.ColorTranslator]::FromHtml($color))
    $gp = New-Object System.Drawing.Drawing2D.GraphicsPath
    $gp.AddArc(1, 1, 5, 5, 180, 90)
    $gp.AddArc(10, 1, 5, 5, 270, 90)
    $gp.AddArc(10, 10, 5, 5, 0, 90)
    $gp.AddArc(1, 10, 5, 5, 90, 90)
    $gp.CloseFigure()
    $g.FillPath($brush, $gp)

    # Буквы рисуем линиями: шрифт в 16 px обрезается и плывёт.
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White), 1.7
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round

    switch ($glyph) {
        'M' {
            $pts = @(
                (New-Object System.Drawing.PointF 4.5, 11.5),
                (New-Object System.Drawing.PointF 4.5, 5.0),
                (New-Object System.Drawing.PointF 8.0, 9.0),
                (New-Object System.Drawing.PointF 11.5, 5.0),
                (New-Object System.Drawing.PointF 11.5, 11.5)
            )
            $g.DrawLines($pen, $pts)
        }
        'K' {
            $g.DrawLine($pen, 5.5, 5.0, 5.5, 11.5)
            $pts = @(
                (New-Object System.Drawing.PointF 11.5, 5.0),
                (New-Object System.Drawing.PointF 5.5, 8.4),
                (New-Object System.Drawing.PointF 11.5, 11.5)
            )
            $g.DrawLines($pen, $pts)
        }
        'W' {
            $pts = @(
                (New-Object System.Drawing.PointF 4.0, 5.0),
                (New-Object System.Drawing.PointF 6.2, 11.5),
                (New-Object System.Drawing.PointF 8.0, 8.0),
                (New-Object System.Drawing.PointF 9.8, 11.5),
                (New-Object System.Drawing.PointF 12.0, 5.0)
            )
            $g.DrawLines($pen, $pts)
        }
        'O' {
            $g.DrawEllipse($pen, 4.6, 4.6, 6.8, 6.8)
        }
        '5' {
            $pts = @(
                (New-Object System.Drawing.PointF 11.0, 5.2),
                (New-Object System.Drawing.PointF 6.0, 5.2),
                (New-Object System.Drawing.PointF 6.0, 8.0),
                (New-Object System.Drawing.PointF 9.4, 8.0),
                (New-Object System.Drawing.PointF 11.0, 9.2),
                (New-Object System.Drawing.PointF 11.0, 10.4),
                (New-Object System.Drawing.PointF 9.0, 11.6),
                (New-Object System.Drawing.PointF 6.0, 11.6)
            )
            $g.DrawLines($pen, $pts)
        }
    }

    $pen.Dispose()
    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()

    Write-Output ("badge: $path  " + (Get-Item -LiteralPath $path).Length + " bytes")
}
$images = Join-Path $PSScriptRoot 'resources\images'
New-ChainPoiIcon (Join-Path $images 'dialogs\chainpoi.png')
New-ChainPoiIcon (Join-Path $images 'chainpoi.png')
New-PanoramaIcon (Join-Path $images 'panoramas.png')
New-Badge (Join-Path $images 'chain-magnit.png') '#E03A24' 'M'
New-Badge (Join-Path $images 'chain-kosmetik.png') '#E91E63' 'K'
New-Badge (Join-Path $images 'chain-pyaterochka.png') '#2E7D32' '5'
New-Badge (Join-Path $images 'chain-ozon.png') '#005BFF' 'O'
New-Badge (Join-Path $images 'chain-ozon-box.png') '#0D47A1' 'O'
New-Badge (Join-Path $images 'chain-wildberries.png') '#7B1FA2' 'W'
