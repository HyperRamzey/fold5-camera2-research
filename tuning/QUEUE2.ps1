# tune\QUEUE2.ps1 - full-featureset probe queue (phase A: absent camera.* feature flags, phase B: neutral lib keys)
# Generated from libgcastartup.so + DEX string extraction, cross-checked vs live XML 2026-09-13.
# Same protocol as TEST_QUEUE: one change per step, A/B evidence, KEEP/REVERT per "works + not broken = keep".
# Revert for ABSENT keys = REMOVE the pref entirely (restore compiled default).
param(
  [int]$RunStep = 999,
  [int]$RevertStep = 999,
  [string]$DevAddr = "",
  [switch]$List
)
$Dev = $DevAddr

# ---- PHASE A: absent camera.* feature flags (bool probe: absent -> true) ----
# filtered: video/front-cam/UI-only/codec/timer junk removed
$phaseA = @(
  'camera.anglerfish_enabled'
  'camera.ark_enabled'
  'camera.autobahn_options_enabled'
  'camera.beholder_force_opt_in'
  'camera.cyclops_enabled'
  'camera.decepticon_force_run'
  'camera.falcon_always_on'
  'camera.falcon_enabled'
  'camera.falcon_force_fusion'
  'camera.falcon_md_enabled'
  'camera.falcon_tpu_enabled'
  'camera.force_anglerfish.RESTART'
  'camera.force_cuttle.extended'
  'camera.gouda.firefly_enabled'
  'camera.gouda.matting_enabled'
  'camera.gyrfalcon_enabled'
  'camera.hawk_enabled'
  'camera.hawk_force_fusion'
  'camera.hawk_tpu_enabled'
  'camera.ica_in_front_enabled'
  'camera.kepler_enabled'
  'camera.sabre_gcam'
  'camera.shasta_ON'
  'camera.sabre_raw'
)

$queue = New-Object System.Collections.Generic.List[object]
# Orig values captured live 2026-09-13 02:5x: empty strings = absent-effect (remove on revert),
# 'Off (as in library)' = port's explicit neutral (restore verbatim on revert)
$origB = @{
  'lib_sharpness_a_key'=''; 'lib_sharpness_b_key'=''; 'lib_luma_a_key'=''; 'lib_luma_b_key'='';
  'lib_chroma_a_key'=''; 'lib_chroma_b_key'=''; 'lib_spatial_a_key'=''; 'lib_spatial_b_key'='';
  'lib_denoise_key'=''; 'lib_sharp_gain_key'=''; 'lib_gamma_key'='Off (as in library)'; 'lib_tone_key'='Off (as in library)';
  'lib_brightness_key'=''; 'lib_noise_reduction_adjust_key'=''
}
$n = 0
foreach ($f in $phaseA) {
  $n++
  $queue.Add([PSCustomObject]@{ N=$n; Id=("F$n"); Aux=0; Lens="56"; Kind="bool_true"; Key=$f; Value="true"; Orig=""; Phase="A"; Label="absent flag probe: $f"; Note="compiled default (off) -> true; revert REMOVES key" })
}

# ---- PHASE B: neutral lib keys on main lens (value probes) ----
$phaseB = @(
  @{ Key='lib_sharpness_a_key';              Value='0.5';    Why='sharpness A scalar' }
  @{ Key='lib_sharpness_b_key';              Value='0.5';    Why='sharpness B scalar' }
  @{ Key='lib_luma_a_key';                   Value='0.5';    Why='luma A scalar' }
  @{ Key='lib_luma_b_key';                   Value='0.5';    Why='luma B scalar' }
  @{ Key='lib_chroma_a_key';                 Value='0.5';    Why='chroma A scalar' }
  @{ Key='lib_chroma_b_key';                 Value='0.5';    Why='chroma B scalar' }
  @{ Key='lib_spatial_a_key';                Value='0.5';    Why='spatial A scalar' }
  @{ Key='lib_spatial_b_key';                Value='0.5';    Why='spatial B scalar' }
  @{ Key='lib_denoise_key';                  Value='1.0';    Why='global denoise (empty=lib default)' }
  @{ Key='lib_sharp_gain_key';               Value='1.5';    Why='sharp gain (empty)' }
  @{ Key='lib_gamma_key';                    Value='1.0';    Why='gamma (Off=lib)' }
  @{ Key='lib_tone_key';                     Value='1.0';    Why='tone (Off=lib)' }
  @{ Key='lib_brightness_key';               Value='1.0';    Why='brightness (empty)' }
  @{ Key='lib_noise_reduction_adjust_key';   Value='1.0';    Why='NR adjust (empty)' }
)
foreach ($b in $phaseB) {
  $n++
  $queue.Add([PSCustomObject]@{ N=$n; Id=("V$($n-$phaseA.Count)"); Aux=0; Lens="56"; Kind="lib_string"; Key=$b.Key; Value=$b.Value; Orig=$origB[$b.Key]; Phase="B"; Label=$b.Why; Note="empty/Off in XML -> probe value; revert removes (empty) or Off-restores" })
}

if ($List) {
  Write-Host "QUEUE2 ($($queue.Count) steps):"
  foreach ($q in $queue) { Write-Host ("  [{0,2}] {1,-5} {2} {3,-40} =>{4,-6} {5}" -f $q.N, $q.Id, $q.Phase, $q.Key, $q.Value, $q.Label) }
  return
}

if ($RunStep -ge 0 -and $RunStep -ne 999) {
  $q = $queue | Where-Object { $_.N -eq $RunStep }
  if (-not $q) { throw "no step $RunStep" }
  if (-not $Dev) { throw "need -Dev" }
  & powershell -NoProfile -ExecutionPolicy Bypass -File "G:\projects\fold5-camera2-research\tune\ab_runner.ps1" @("-Dev",$Dev,"-TestId",$q.Id,"-AuxKey",$q.Aux,"-LensId",$q.Lens,"-PrefKind",$q.Kind,"-PrefKey",$q.Key,"-PrefValue",$q.Value,"-Label",$q.Label)
  Write-Host "step $($q.N) done. KEEP/REVERT per protocol. Absent-key revert: -RevertStep $RunStep (removes key)."
  return
}

if ($RevertStep -ge 0 -and $RevertStep -ne 999) {
  $q = $queue | Where-Object { $_.N -eq $RevertStep }
  if (-not $q) { throw "no step $RevertStep" }
  if (-not $Dev) { throw "need -Dev" }
  if ($q.Orig -eq "" -or $null -eq $q.Orig) {
    # key was ABSENT/EMPTY originally -> REMOVE it (restore compiled default)
    Write-Host "revert $($q.Id): REMOVE key $($q.Key) (was absent)"
    adb -s $Dev shell "su 0 am force-stop com.samsung.android.ruler" 2>$null | Out-Null
    Start-Sleep -Seconds 2
    $pref='/data/data/com.samsung.android.ruler/shared_prefs/com.samsung.android.ruler_preferences.xml'
    $rm = "P=$pref`nsed -i '/name=`"$($q.Key)`"/d' `$P`ngrep -ac `"$($q.Key)`" `$P || echo removed-ok"
    [IO.File]::WriteAllText("$env:TEMP\rmkey.sh", ($rm -replace "`r`n","`n"))
    adb -s $Dev push "$env:TEMP\rmkey.sh" /data/local/tmp/rmkey.sh 2>&1 | Out-Null
    $out = adb -s $Dev shell "su 0 sh /data/local/tmp/rmkey.sh" 2>&1
    if ($out -match 'removed-ok') { Write-Host "key removed (grep count 0)" } else { Write-Host "verify: $out" }
    return
  }
  # 'Off (as in library)' or other explicit value -> normal value revert
  & powershell -NoProfile -ExecutionPolicy Bypass -File "G:\projects\fold5-camera2-research\tune\ab_runner.ps1" @("-Dev",$Dev,"-TestId",("RV"+$q.Id),"-AuxKey",$q.Aux,"-LensId",$q.Lens,"-PrefKind",$q.Kind,"-PrefKey",$q.Key,"-PrefValue",$q.Orig,"-Label",("revert "+$q.Id))
  return
}

Write-Host "use -List / -RunStep N -Dev <ip:port> / -RevertStep N -Dev <ip:port>"
