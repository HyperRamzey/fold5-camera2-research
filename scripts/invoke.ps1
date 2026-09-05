#!/usr/bin/env pwsh
# Read-only adb helper: invoke.sh "<shell command to run as root on device>"
# Usage: .\invoke.sh 'pm list packages'
param([Parameter(Mandatory=$true)][string]$Command, [string]$OutFile = "")
$ErrorActionPreference = 'Stop'
$dev = 'RFCWC0G1Z1J'
# Base64-encode the command to survive quoting layers
$bytes = [System.Text.Encoding]::UTF8.GetBytes($Command)
$b64 = [Convert]::ToBase64String($bytes)
$full = "su -c 'echo $b64 | base64 -d | sh'"
if ($OutFile -ne "") {
    adb -s $dev shell $full | Out-File -FilePath $OutFile -Encoding utf8
} else {
    adb -s $dev shell $full
}