# tune\QUEUE3.ps1 - Night Sight 2x-exposure ladder (user request 2026-09-13 morning)
# Total NS exposure = frame_count x per-frame_exposure. Double each axis separately, then combine winners.
# Current live: lib_max_exp_ms_key_p0_0=4000 (4s/frame), lib_pref_frame_count_zsl_key_p0_0=30, max_frame_count=25.
# NOTE: scene is brightening (early morning) - deltas vs REF2 baseline carry scene-drift caveat; judge on functional+direction.
param(
  [int]$RunStep = 999,
  [int]$RevertStep = 999,
  [string]$DevAddr = "",
  [switch]$List
)
$Dev = $DevAddr

$queue = @(
  @{ N=1; Id="E1"; Aux=0; Lens="56"; Kind="lib_string"; Key="lib_max_exp_ms_key_p0_0";        Value="8000";  Orig="4000"; Label="per-frame exposure 4s->8s (2x axis A)" },
  @{ N=2; Id="E2"; Aux=0; Lens="56"; Kind="lib_string"; Key="lib_pref_frame_count_zsl_key_p0_0"; Value="60";   Orig="30";   Label="ZSL frame count 30->60 (2x axis B, more shots)" },
  @{ N=3; Id="E3"; Aux=0; Lens="56"; Kind="lib_string"; Key="lib_max_frame_count_key_p0_0";     Value="50";   Orig="25";   Label="max frame count 25->50 (burst ceiling for axis B)" },
  @{ N=4; Id="E4"; Aux=0; Lens="56"; Kind="lib_string"; Key="lib_max_bracketing_frames_key_p0_0"; Value="50";  Orig="25";   Label="max bracketing frames 25->50 (axis B support)" },
  @{ N=5; Id="E5"; Aux=0; Lens="56"; Kind="lib_string"; Key="lib_max_short_frames_key_p0_0";    Value="50";   Orig="25";   Label="max short frames 25->50 (axis B support)" },
  @{ N=6; Id="E6"; Aux=0; Lens="56"; Kind="lib_string"; Key="lib_shasta_max_exp_ms_key_p0_0";  Value="8000";  Orig="4000"; Label="shasta max exp 4s->8s (axis A, shasta path idle but parity)" }
)

if ($List) {
  Write-Host "QUEUE3 ($($queue.Count) steps) - Night Sight 2x exposure:"
  foreach ($q in $queue) { Write-Host ("  [{0}] {1,-4} {2,-36} =>{3,-6} {4}" -f $q.N, $q.Id, $q.Key, $q.Value, $q.Label) }
  return
}

if ($RunStep -ge 0 -and $RunStep -ne 999) {
  $q = $queue | Where-Object { $_.N -eq $RunStep }
  if (-not $q) { throw "no step $RunStep" }
  if (-not $Dev) { throw "need -Dev" }
  & powershell -NoProfile -ExecutionPolicy Bypass -File "G:\projects\fold5-camera2-research\tune\ab_runner.ps1" @("-Dev",$Dev,"-TestId",$q.Id,"-AuxKey",$q.Aux,"-LensId",$q.Lens,"-PrefKind",$q.Kind,"-PrefKey",$q.Key,"-PrefValue",$q.Value,"-Label",$q.Label)
  Write-Host "step $($q.N) done. KEEP/REVERT per protocol. Revert: -RevertStep $RunStep"
  return
}

if ($RevertStep -ge 0 -and $RevertStep -ne 999) {
  $q = $queue | Where-Object { $_.N -eq $RevertStep }
  if (-not $q) { throw "no step $RevertStep" }
  if (-not $Dev) { throw "need -Dev" }
  & powershell -NoProfile -ExecutionPolicy Bypass -File "G:\projects\fold5-camera2-research\tune\ab_runner.ps1" @("-Dev",$Dev,"-TestId",("RV"+$q.Id),"-AuxKey",$q.Aux,"-LensId",$q.Lens,"-PrefKind",$q.Kind,"-PrefKey",$q.Key,"-PrefValue",$q.Orig,"-Label",("revert "+$q.Id))
  return
}

Write-Host "use -List / -RunStep N -Dev <ip:port> / -RevertStep N -Dev <ip:port>"
