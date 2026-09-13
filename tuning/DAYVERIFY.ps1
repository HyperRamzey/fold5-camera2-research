# tune\DAYVERIFY.ps1 - daylight paired ON/OFF re-verification of drift-caveated keeps
# Per key: ON shot (current kept state) -> REMOVE key -> OFF shot (auto-diff vs ON via -RefJpg) -> restore key.
# Pair legs ~90s apart = drift-resistant. Run: .\DAYVERIFY.ps1 -Pair <id> -Dev <ip:port>
param(
  [string]$Dev = $null,
  [string]$Pair = $null,
  [switch]$List
)
if ($null -eq $Dev) { $Dev = '' }
if ($null -eq $Pair) { $Pair = '' }
$pref = "/data/data/com.samsung.android.ruler/shared_prefs/com.samsung.android.ruler_preferences.xml"
$shotDir = "G:\projects\fold5-camera2-research\tune\shots"
$runner = "G:\projects\fold5-camera2-research\tune\ab_runner.ps1"

$pairs = @(
  @{ Id='sharpB';  Key='lib_sharpness_b_key';           Kind='string'; Value='0.5';   Why='sharpness_b 0.5 (morning: sharp +9.4% w/ drift)' },
  @{ Id='lumaB';   Key='lib_luma_b_key';                Kind='string'; Value='0.5';   Why='luma_b 0.5' },
  @{ Id='chromaA'; Key='lib_chroma_a_key';              Kind='string'; Value='0.5';   Why='chroma_a 0.5' },
  @{ Id='spatA';   Key='lib_spatial_a_key';             Kind='string'; Value='0.5';   Why='spatial_a 0.5' },
  @{ Id='spatB';   Key='lib_spatial_b_key';             Kind='string'; Value='0.5';   Why='spatial_b 0.5' },
  @{ Id='angl';    Key='camera.anglerfish_enabled';     Kind='bool';   Value='true';  Why='anglerfish (27-min-gap keep)' },
  @{ Id='ark';     Key='camera.ark_enabled';            Kind='bool';   Value='true';  Why='ark (drift wash)' },
  @{ Id='behold';  Key='camera.beholder_force_opt_in';  Kind='bool';   Value='true';  Why='beholder opt-in (noise win w/ drift)' },
  @{ Id='falcFF';  Key='camera.falcon_force_fusion';    Kind='bool';   Value='true';  Why='falcon force_fusion (+13% drift pair)' },
  @{ Id='anglR';   Key='camera.force_anglerfish.RESTART'; Kind='bool'; Value='true';  Why='force_anglerfish.RESTART (+7% drift)' },
  @{ Id='cuttle';  Key='camera.force_cuttle.extended';  Kind='bool';   Value='true';  Why='cuttle extended (-5% drift)' },
  @{ Id='hawkFF';  Key='camera.hawk_force_fusion';      Kind='bool';   Value='true';  Why='hawk force_fusion (+4% drift)' },
  @{ Id='hawkTPU'; Key='camera.hawk_tpu_enabled';       Kind='bool';   Value='true';  Why='hawk tpu (cloud-pass pair)' }
)

if ($List) { $pairs | ForEach-Object { Write-Host ("  {0,-8} {1,-40} =>{2,-6} {3}" -f $_.Id, $_.Key, $_.Value, $_.Why) }; return }
if (-not $Dev -or -not $Pair) { Write-Host "use -List / -Pair <id> -Dev <ip:port>"; return }
$p = $pairs | Where-Object { $_.Id -eq $Pair }
if (-not $p) { throw "no pair $Pair" }

function Push-Script([string]$s, [string]$dst) {
  [IO.File]::WriteAllText("$env:TEMP\dayv_tmp.sh", ($s -replace "`r`n","`n"))
  & adb.exe -s $Dev push "$env:TEMP\dayv_tmp.sh" $dst 2>&1 | Out-Null
}

Write-Host "=== DAY-VERIFY [$($p.Id)] $($p.Key) : $($p.Why) ==="

# 1) ON leg (current kept state; saturn no-op rewrite)
& powershell -NoProfile -ExecutionPolicy Bypass -File $runner @("-Dev",$Dev,"-TestId",("D$($p.Id)on"),"-AuxKey","0","-LensId","56","-PrefKind","bool_true","-PrefKey","camera.enable_saturn","-PrefValue","true","-Label",("day-pair ON: "+$p.Key))
$onShot = Get-ChildItem "$shotDir\D$($p.Id)on_*.jpg" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $onShot) { throw "ON shot not found" }

# 2) remove key (tight window: OFF leg right after)
& adb.exe -s $Dev shell "su 0 am force-stop com.samsung.android.ruler" 2>$null | Out-Null
Start-Sleep -Seconds 2
Push-Script ("P=$pref`nsed -i '/name=`"$($p.Key)`"/d' `$P`ngrep -ac `"$($p.Key)`" `$P || echo removed-ok") "/data/local/tmp/dayv_rm.sh"
$rmOut = & adb.exe -s $Dev shell "su 0 sh /data/local/tmp/dayv_rm.sh" 2>&1
Write-Host "remove: $rmOut"

# 3) OFF leg, auto-diff vs the ON shot
& powershell -NoProfile -ExecutionPolicy Bypass -File $runner @("-Dev",$Dev,"-TestId",("D$($p.Id)off"),"-AuxKey","0","-LensId","56","-PrefKind","bool_true","-PrefKey","camera.enable_saturn","-PrefValue","true","-Label",("day-pair OFF: "+$p.Key),"-RefJpg",$onShot.FullName)

# 4) restore key (insert before </map>; entry is absent after removal)
$entry = if ($p.Kind -eq 'bool') { "<boolean name=`"$($p.Key)`" value=`"$($p.Value)`"/>" } else { "<string name=`"$($p.Key)`">$($p.Value)</string>" }
Push-Script ("P=$pref`nsed -i 's|</map>|    $entry\n</map>|' `$P`ngrep -a name=`"$($p.Key)`" `$P") "/data/local/tmp/dayv_ins.sh"
$insOut = & adb.exe -s $Dev shell "su 0 sh /data/local/tmp/dayv_ins.sh" 2>&1
Write-Host "restore: $($insOut | Select-Object -First 1)"
& adb.exe -s $Dev shell "su 0 rm -f /data/local/tmp/dayv_*.sh" 2>$null | Out-Null
Write-Host "=== pair [$($p.Id)] complete - OFF-vs-ON diff above is the daylight-isolated signal ==="
