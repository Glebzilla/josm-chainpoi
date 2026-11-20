# Закрывает JOSM аккуратно: посылает WM_CLOSE главному окну и ждёт выхода.
# Так JOSM успевает сохранить настройки и не оставляет «аварийных» данных.
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/close-josm.ps1
# Код возврата 0 — закрыт, 1 — не закрылся (скорее всего, висит диалог
# сохранения: тогда закройте его вручную).

$ErrorActionPreference = 'Stop'

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class JosmWindow {
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr w, IntPtr l);
}
"@

$proc = Get-Process -Name java, javaw -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $proc) {
    Write-Output 'JOSM не запущен'
    exit 0
}

[void][JosmWindow]::PostMessage($proc.MainWindowHandle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)  # WM_CLOSE

for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 500
    if (-not (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue)) {
        Write-Output 'JOSM закрыт аккуратно'
        exit 0
    }
}

Write-Output 'JOSM не закрылся за 20 с — вероятно, ждёт ответа в диалоге'
exit 1
