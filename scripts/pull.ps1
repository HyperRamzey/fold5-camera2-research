#!/usr/bin/env pwsh
# Read-only binary pull: root-reads file, base64 on device, decode locally. No device writes.
# Usage: .\pull.ps1 <remote-path> <local-path>
param([Parameter(Mandatory=$true)][string]$Remote, [Parameter(Mandatory=$true)][string]$Local)
$ErrorActionPreference = 'Stop'
$dev = 'RFCWC0G1Z1J'
$cmd = "su -c 'base64 $Remote'"
# adb shell raw output; strip CR artifacts from pty
$raw = adb -s $dev shell $cmd
$text = ($raw -join "`n") -replace "`r",""
# base64 -w0 not guaranteed; GNU base64 on toybox wraps at 76 cols by default with newlines which we've joined
$clean = $text -replace "\s",""
[IO.File]::WriteAllBytes($Local, [Convert]::FromBase64String($clean))
$len = (Get-Item $Local).Length
Write-Output ("Pulled {0} -> {1} ({2} bytes)" -f $Remote, $Local, $len)
# verify size against device
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("stat -c %s $Remote"))
$devSize = (adb -s $dev shell "su -c 'echo $b64 | base64 -d | sh'").Trim() -replace "`r",""
Write-Output ("Device size: {0} local size: {1} match: {2}" -f $devSize, $len, ($devSize -eq "$len"))