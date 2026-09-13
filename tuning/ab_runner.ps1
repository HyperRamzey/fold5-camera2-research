# tune\ab_runner.ps1 - one-variable-at-a-time A/B harness
# PROTOCOL (user-specified): turn a setting on/change it, take a pic, diff to the last one.
#   if it works and isn't broken -> keep; else -> revert and log.
# Applies ONE pref change per run, cold-boots camera on target lens (verified by camera ID),
# takes ONE shot, pulls it, diffs against the PREVIOUS shot (auto-detected or explicit -RefJpg).
#
# Usage examples:
#   .\ab_runner.ps1 -Dev 192.168.1.14:46275 -TestId B1a -AuxKey 0 -LensId 56 `
#     -PrefKind lib_string -PrefKey lib_hardmerge_key_p0_0 -PrefValue "1" -Label "hardmerge sabre"
#   .\ab_runner.ps1 -Dev ... -TestId A8 -AuxKey 0 -LensId 56 -PrefKind bool_true -PrefKey gcam.zsl_buffer_size ... (int)
#   .\ab_runner.ps1 -Dev ... -TestId REVERT-B1 -AuxKey 0 -LensId 56 -PrefKind revert -PrefKey lib_hardmerge_key_p0_0 -PrefValue "3" -Label revert
#
# PrefKind: lib_string (sed-safe string value) | bool_true | bool_false | int
#   bool_* / int create-or-update <boolean>/<int> entries; lib_string updates <string>.
# REVERT writes back the ORIGINAL value you pass in -PrefValue.
param(
  [Parameter(Mandatory=$true)][string]$Dev,
  [Parameter(Mandatory=$true)][string]$TestId,
  [Parameter(Mandatory=$true)][int]$AuxKey,       # position: 0=main56 1=wide58 2=tele52 3=main56 4=U73
  [Parameter(Mandatory=$true)][string]$LensId,    # expected camera id string e.g. "56"
  [Parameter(Mandatory=$true)][string]$PrefKind,
  [Parameter(Mandatory=$true)][string]$PrefKey,
  [Parameter(Mandatory=$true)][string]$PrefValue,
  [string]$Label,                                   # display label (no default here; normalized below)
  [string]$RefJpg,                                  # explicit reference jpg (no default here; normalized below)
  [int]$WaitBootSec = 22
)

$ErrorActionPreference = 'Continue'
$pref = "/data/data/com.samsung.android.ruler/shared_prefs/com.samsung.android.ruler_preferences.xml"
$shotDir = "G:\projects\fold5-camera2-research\tune\shots"
$logFile = "G:\projects\fold5-camera2-research\tune\AB_RESULTS.tsv"
New-Item -ItemType Directory -Path $shotDir -Force | Out-Null
if (-not (Test-Path $logFile)) { "TestId`tLabel`tPrefKey`tValue`tLens`tSaved`tDims`tLuma`tRG`tBG`tNoise`tSharp`tVerdict" | Set-Content $logFile }

function Adb([string]$cmd) { & adb.exe -s $Dev shell $cmd 2>$null }

Write-Host "=== A/B [$TestId] $Label : $PrefKey = $PrefValue (lens $LensId aux $AuxKey) ==="

# 1) snapshot the pref file before change (for forensics/rollback)
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
& adb.exe -s $Dev shell "su 0 cp $pref /data/local/tmp/pref_backup_$stamp.xml" 2>$null | Out-Null

# 2) force-stop, apply ONE pref change
Adb "su 0 am force-stop com.samsung.android.ruler" | Out-Null
Start-Sleep -Seconds 2
$scriptPath = "$env:TEMP\ab_${TestId}.sh"

# Build the on-device shell script with SINGLE-quoted here-strings and token replacement
# (a double-quoted here-string would expand $P at generation time and corrupt the script).
function New-ApplyScript([string]$kind, [string]$key, [string]$val) {
  $tpl = @'
P=__PREF__
grep -q 'name="__KEY__"' $P && __SED__ $P || __INS__ $P
grep -a 'name="__KEY__"' $P
'@
  switch ($kind) {
    "lib_string" {
      $sed = 'sed -i ''s|<string name="{0}">[^<]*</string>|<string name="{0}">{1}</string>|''' -f $key, $val
      $ins = 'sed -i ''s|</map>|    <string name="{0}">{1}</string>\n</map>|''' -f $key, $val
    }
    "bool_true" {
      $sed = 'sed -i ''s|<boolean name="{0}" value="[^"]*" */>|<boolean name="{0}" value="true"/>|''' -f $key
      $ins = 'sed -i ''s|</map>|    <boolean name="{0}" value="true"/>\n</map>|''' -f $key
    }
    "bool_false" {
      $sed = 'sed -i ''s|<boolean name="{0}" value="[^"]*" */>|<boolean name="{0}" value="false"/>|''' -f $key
      $ins = 'sed -i ''s|</map>|    <boolean name="{0}" value="false"/>\n</map>|''' -f $key
    }
    "int" {
      $sed = 'sed -i ''s|<int name="{0}" value="[^"]*" */>|<int name="{0}" value="{1}"/>|''' -f $key, $val
      $ins = 'sed -i ''s|</map>|    <int name="{0}" value="{1}"/>\n</map>|''' -f $key, $val
    }
    "revert" {
      $sed = 'sed -i ''s|<string name="{0}">[^<]*</string>|<string name="{0}">{1}</string>|''' -f $key, $val
      $ins = "true"
    }
    default { throw "unknown PrefKind $kind" }
  }
  $tpl -replace "__PREF__", $pref -replace "__KEY__", $key -replace "__SED__", $sed -replace "__INS__", $ins
}
$s = New-ApplyScript $PrefKind $PrefKey $PrefValue
# safety: the generated script must contain the pref path and no stray backslash-dollar
if ($s -notmatch "P=/data/" -or $s -match '\\\$') { throw "generated script corrupted: `n$s" }
[IO.File]::WriteAllText($scriptPath, ($s -replace "`r`n","`n"))
$localLen = if (Test-Path $scriptPath) { (Get-Item $scriptPath).Length } else { -1 }
Write-Host "apply-script: $scriptPath ($localLen bytes)"
$pushOut = & adb.exe -s $Dev push $scriptPath /data/local/tmp/ab_step.sh 2>&1
Write-Host "push: $pushOut"
$applyOut = & adb.exe -s $Dev shell "su 0 sh /data/local/tmp/ab_step.sh" 2>&1
if (-not $applyOut) { Write-Host "APPLY FAILED (no grep output) - ABORT"; Write-Host "raw apply output: [$applyOut]"; exit 1 }
Write-Host "applied: $($applyOut | Select-Object -First 1)"

# 3) cold-boot on target lens (set aux, launch, VERIFY camera id) - single-quoted template, token-replaced
# NOTE: force-stop teardown ASYNCHRONOUSLY rewrites pref_aux_key with the lens the app was on; wait it out
# BEFORE overwriting, or the app's write lands after ours and boots the wrong lens.
Adb "su 0 am force-stop com.samsung.android.ruler" | Out-Null
Start-Sleep -Seconds 5
$auxTpl = @'
P=__PREF__
sed -i 's|<string name="pref_aux_key">[0-9]*</string>|<string name="pref_aux_key">__AUX__</string>|' $P
grep -a 'name="pref_aux_key"' $P
'@
$auxScript = $auxTpl -replace "__PREF__", $pref -replace "__AUX__", $AuxKey
[IO.File]::WriteAllText("$env:TEMP\ab_aux.sh", ($auxScript -replace "`r`n","`n"))
& adb.exe -s $Dev push "$env:TEMP\ab_aux.sh" /data/local/tmp/ab_aux.sh 2>&1 | Out-Null
# set aux + VERIFY it stuck (the killed app asynchronously rewrites pref_aux_key during teardown)
$auxOut = Adb "su 0 sh /data/local/tmp/ab_aux.sh"
$wantAux = "<string name=`"pref_aux_key`">$AuxKey</string>"
$guard = 0
while (-not ($auxOut -match [regex]::Escape($wantAux)) -and $guard -lt 5) {
  Start-Sleep -Seconds 2
  $auxOut = Adb "su 0 sh /data/local/tmp/ab_aux.sh"
  $guard++
}
if ($auxOut) { Write-Host "aux: $($auxOut | Select-Object -First 1)" }
# wake screen + dismiss keyguard FIRST (camera UI needs screen-on; device dozes aggressively)
& adb.exe -s $Dev shell "input keyevent KEYCODE_WAKEUP" 2>$null | Out-Null
Start-Sleep -Seconds 2
& adb.exe -s $Dev shell "wm dismiss-keyguard" 2>$null | Out-Null
Start-Sleep -Seconds 1
& adb.exe -s $Dev shell "logcat -c" 2>$null | Out-Null
& adb.exe -s $Dev shell "logcat -b crash -c" 2>$null | Out-Null
& adb.exe -s $Dev shell "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.samsung.android.ruler/com.android.camera.CameraLauncher" 2>$null | Out-Null
Start-Sleep -Seconds $WaitBootSec
$camLine = ""
$camOk = $false
for ($try=1; $try -le 4; $try++) {
  $camLine = (& adb.exe -s $Dev shell "dumpsys media.camera" 2>$null | Select-String -Pattern "Camera ID: \d+, Cost" | Select-Object -First 1).Line
  if ($camLine -match "Camera ID: $LensId,") { $camOk = $true; break }
  if ($camLine) { break }   # some camera open, but wrong lens -> genuine mismatch, no retry needed
  # no camera yet: nudge wake + wait (doze race)
  & adb.exe -s $Dev shell "input keyevent KEYCODE_WAKEUP" 2>$null | Out-Null
  Start-Sleep -Seconds 8
}
Write-Host "camera: $camLine"
if (-not $camOk) { Write-Host "CAMERA ID MISMATCH (expected $LensId) - ABORT"; exit 1 }

# 4) take ONE shot
$pid0 = (Adb "pidof com.samsung.android.ruler").Trim()
$before = (Adb "ls -t /storage/emulated/0/DCIM/Camera/" | Select-Object -First 1); if ($before) { $before = $before.Trim() } else { $before = "" }
Adb "input keyevent 27" | Out-Null
$new = $false; $after = $before
for ($i=0; $i -lt 15; $i++) {
  Start-Sleep -Seconds 4
  $after = (Adb "ls -t /storage/emulated/0/DCIM/Camera/" | Select-Object -First 1); if ($after) { $after = $after.Trim() } else { $after = "" }
  if ($after -ne $before) { $new = $true; break }
}
$exif = (& adb.exe -s $Dev shell "logcat -d" 2>$null | Select-String -Pattern "imageExif" | Select-Object -Last 1).Line
Write-Host "shot: $after (new=$new)  $(if($exif){$exif.Trim()})"

# 5) functional gates
$funcOk = $new
$dims = ""
if ($new) {
  $local = "$shotDir\${TestId}_$after"
  & adb.exe -s $Dev pull "/storage/emulated/0/DCIM/Camera/$after" $local 2>$null | Out-Null
  Add-Type -AssemblyName System.Drawing
  $img = [System.Drawing.Image]::FromFile($local)
  $dims = "$($img.Width)x$($img.Height)"
  $img.Dispose()
}
$fatal = & adb.exe -s $Dev shell "logcat -d -b crash -t 50" 2>$null | Select-String -Pattern "FATAL"
$pid1 = (Adb "pidof com.samsung.android.ruler").Trim()
$camStill = $null -ne ((& adb.exe -s $Dev shell "dumpsys media.camera" 2>$null | Select-String -Pattern "Camera ID: $LensId" | Select-Object -First 1).Line)
if (-not $funcOk) { Write-Host "FUNC FAIL: no new file"; $verdict="FUNC_FAIL_NOSAVE" }
elseif ($fatal) { Write-Host "FUNC FAIL: crash"; $fatal | Select-Object -First 2 | ForEach-Object { Write-Host "  $($_.Line.Trim().Substring(0,[Math]::Min(90,$_.Line.Trim().Length)))" }; $verdict="FUNC_FAIL_CRASH" }
elseif (-not $camStill) { Write-Host "FUNC FAIL: camera closed after shot"; $verdict="FUNC_FAIL_DEAD_SESSION" }
elseif ($pid0 -ne $pid1) { Write-Host "FUNC FAIL: pid changed ($pid0 -> $pid1)"; $verdict="FUNC_FAIL_PID" }
else { $verdict="FUNC_OK"; Write-Host "FUNC OK: saved $dims, camera open, pid stable" }

# 6) quality diff vs reference (only if func ok and a ref exists)
$metrics = ""
if ($verdict -eq "FUNC_OK") {
  if (-not $RefJpg) {
    $refCandidates = Get-ChildItem $shotDir -Filter "*.jpg" | Sort-Object LastWriteTime -Descending
    if ($refCandidates.Count -ge 2) { $RefJpg = $refCandidates[1].FullName }  # [0] is the shot we just took
    elseif ($refCandidates.Count -eq 1) { $RefJpg = $refCandidates[0].FullName }
  }
  if ($RefJpg -and (Test-Path $RefJpg)) {
    Write-Host "ref: $(Split-Path $RefJpg -Leaf)"
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File "G:\projects\fold5-camera2-research\tune\measure.ps1" $RefJpg $local
    $out | ForEach-Object { Write-Host "  $_" }
    $dline = $out | Select-String -Pattern "^NEW"
    if ($dline -match "luma=([\d.]+) R/G=([\d.]+) B/G=([\d.]+).*noise=([\d.]+) sharp=([\d.]+)") {
      $metrics = "$($matches[1])/$($matches[2])/$($matches[3])/$($matches[4])/$($matches[5])"
    }
    $verdict = ($out | Select-String -Pattern "^VERDICT").Line
    if (-not $verdict) { $verdict = "MEASURE_INCOMPLETE" }
  } else {
    $verdict = "REF_BASELINE (no previous shot - this becomes the reference)"
  }
}

# 7) log row
"$TestId`t$Label`t$PrefKey`t$PrefValue`t$LensId`t$new`t$dims`t$metrics`t$verdict" | Add-Content $logFile
Write-Host "logged -> AB_RESULTS.tsv [$verdict]"
Write-Host "NOTE: decide KEEP/REVERT manually (protocol: works + not broken = keep)."
Write-Host "      To revert: re-run with -PrefKind revert -PrefValue `<original`>"
