# Closes JOSM gracefully: sends WM_CLOSE to the main window and waits.
# JOSM saves preferences and does not leave a crash/autosave state.
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/close-josm.ps1
# Exit 0 - closed, 1 - did not close (probably waiting in a dialog: close it by hand).

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
    Write-Output 'JOSM is not running'
    exit 0
}

[void][JosmWindow]::PostMessage($proc.MainWindowHandle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)  # WM_CLOSE

for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 500
    if (-not (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue)) {
        Write-Output 'JOSM closed gracefully'
        exit 0
    }
}

Write-Output 'JOSM did not close in 20 s - probably a dialog is waiting'
exit 1
