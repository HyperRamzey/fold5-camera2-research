# tune\TEST_QUEUE.ps1 - pre-planned A/B sequence (fill $Dev when device is ready)
# PROTOCOL: one variable at a time. Each step: apply -> cold boot -> verify camera -> shoot -> diff vs previous -> keep/revert.
# KEEP rule (user): works AND isn't broken -> keep. Else revert + document.
# Order: reference shot first (no change), then Tier A flags (config-only, cheap), then Tier B per-lens lib keys.
#
# RUN MODES:
#   .\TEST_QUEUE.ps1 -List              # print the queue
#   .\TEST_QUEUE.ps1 -RunStep N -Dev 192.168.1.14:46275   # run exactly step N
#   .\TEST_QUEUE.ps1 -RevertStep N -Dev ...               # revert step N to its Original value
#
# Every step records to AB_RESULTS.tsv; KEEP/REVERT decisions go in DECISIONS.md.
param(
  [int]$RunStep = 999,
  [int]$RevertStep = 999,
  [string]$DevAddr = "",
  [int]$StepNum = 0,
  [switch]$List
)
$Dev = $DevAddr   # display name kept for messages

# Dev last known: 192.168.1.14:46275 (port rotates - re-discover via: adb mdns services; adb connect <ip:port>)

$queue = @(
  # ---- REFERENCE (no change) ----
  @{ N=0;  Id="REF0";   Aux=0; Lens="56"; Kind="none";       Key="";                                    Value="";        Orig="";        Label="reference shot, current baseline (main)"; Note="becomes diff reference for step 1" },

  # ---- TIER A: GoogleX flags (global, config-only) - cheap, high info ----
  @{ N=1;  Id="A1";    Aux=0; Lens="56"; Kind="bool_true";   Key="SABRE_ALLOWED";                       Value="true";    Orig="true";    Label="verify SABRE_ALLOWED present (already true in best.agc - device may lack it)"; Note="if diff = noise, keep (it protects the sabre path)" },
  @{ N=2;  Id="A5";    Aux=0; Lens="56"; Kind="bool_true";   Key="camera.include_ultra_short_frame";    Value="true";    Orig="false";   Label="HDR+ burst includes ultra-short frames (highlight roll-off)"; Note="expect: highlights cleaner, noise same" },
  @{ N=3;  Id="A6";    Aux=0; Lens="56"; Kind="bool_true";   Key="camera.nonzsl_extended_base_frame_selection"; Value="true"; Orig="false"; Label="extended base-frame selection (non-ZSL)"; Note="expect: sharper base pick" },
  @{ N=4;  Id="A2";    Aux=0; Lens="56"; Kind="bool_true";   Key="camera.shasta.force";                 Value="true";    Orig="false";   Label="force shasta merge path"; Note="if crash/no-save -> FUNC_FAIL, revert immediately" },
  @{ N=5;  Id="A4";    Aux=0; Lens="56"; Kind="bool_true";   Key="camera.spatial_rgb_force";            Value="true";    Orig="false";   Label="force spatial RGB merge"; Note="family of sabre; watch for double-apply with hardmerge=3" },
  @{ N=6;  Id="A8";    Aux=0; Lens="56"; Kind="int";         Key="gcam.zsl_buffer_size";                Value="8";       Orig="";        Label="ZSL ring buffer 8 frames (default ~6)"; Note="more base-frame choices; watch memory" },
  @{ N=7;  Id="A9";    Aux=0; Lens="56"; Kind="int";         Key="gcam.sabre_burst_size";               Value="15";      Orig="";        Label="sabre burst 15 frames"; Note="pairs with A1" },
  @{ N=8;  Id="A21";   Aux=0; Lens="56"; Kind="bool_true";   Key="camera.hdr_memory_reserve";           Value="true";    Orig="false";   Label="reserve memory for HDR merge (OOM guard)"; Note="no image change expected; keep if no harm" },
  @{ N=9;  Id="A18a";  Aux=0; Lens="56"; Kind="int";         Key="gcam.hdrplus_wb_source";              Value="1";       Orig="";        Label="HDR+ WB source = 1 (A/B 0/1/2)"; Note="color-sensitive: must stay in +-10% neutral window" },
  @{ N=10; Id="A18b"; Aux=0; Lens="56"; Kind="int";         Key="gcam.hdrplus_wb_source";              Value="2";       Orig="1";       Label="HDR+ WB source = 2"; Note="same gate" },
  @{ N=11; Id="A20";   Aux=0; Lens="56"; Kind="bool_true";   Key="gcam.eager_simultaneous_merge_and_finish"; Value="true"; Orig="false"; Label="parallel merge+finish (latency)"; Note="keep only if saves stay clean" },

  # ---- TIER B: per-lens lib keys (MAIN, aux 0) - the 43-key block already exists for main ----
  @{ N=12; Id="B1a";  Aux=0; Lens="56"; Kind="lib_string"; Key="lib_hardmerge_key_p0_0";            Value="1";       Orig="3";          Label="merge algo: sabre (vs spatial rgb 3)"; Note="THE big one: detail/noise character" },
  @{ N=13; Id="B1b";  Aux=0; Lens="56"; Kind="lib_string"; Key="lib_hardmerge_key_p0_0";            Value="2";       Orig="3";          Label="merge algo: spatial bayer"; Note="second data point" },
  @{ N=14; Id="B6a";  Aux=0; Lens="56"; Kind="lib_string"; Key="lib_volume_processing_key_p0_0";    Value="29.0";    Orig="29.0";  Label="volume processing 1: 29.0 (JavaSaBr value)"; Note="(*) = non-default tuned already" },
  @{ N=15; Id="B6b";  Aux=0; Lens="56"; Kind="lib_string"; Key="lib_volume_processing_key_p0_0";    Value="25.0";    Orig="29.0";       Label="volume processing 1: 25.0 (midpoint)"; Note="if 29 too strong" },
  @{ N=16; Id="B7";   Aux=0; Lens="56"; Kind="lib_string"; Key="lib_fix_shasta_merge_key_p0_0";     Value="Off (as in library)"; Orig="Off (as in library)"; Label="fix shasta merge: OFF (library default)"; Note="the (*) non-default may be stale" },
  @{ N=17; Id="B2a";  Aux=0; Lens="56"; Kind="lib_string"; Key="lib_sabre_burst_merge_1_key_p0_0";  Value="1.25";    Orig="1.5 (Burst Merge 1)"; Label="sabre burst merge 1.25"; Note="softer merge" },
  @{ N=18; Id="B2b";  Aux=0; Lens="56"; Kind="lib_string"; Key="lib_sabre_burst_merge_1_key_p0_0";  Value="1.75";    Orig="1.5 (Burst Merge 1)"; Label="sabre burst merge 1.75"; Note="stronger" },
  @{ N=19; Id="B3";   Aux=0; Lens="56"; Kind="lib_string"; Key="lib_sabre_1_key_p0_0";              Value="-1.125";  Orig="-1.000(default)"; Label="sabre denoise -1.125 (more aggressive)"; Note="negative = stronger" },
  @{ N=20; Id="B4";   Aux=0; Lens="56"; Kind="lib_string"; Key="lib_sabre_2_key_p0_0";              Value="125";     Orig="100(default)"; Label="sabre_2 125"; Note="" },
  @{ N=21; Id="B5";   Aux=0; Lens="56"; Kind="lib_string"; Key="lib_sabre_3_key_p0_0";              Value="15";      Orig="10 (default)"; Label="sabre_3 15"; Note="" },
  @{ N=22; Id="B12"; Aux=0; Lens="56"; Kind="lib_string"; Key="lib_temporal_radius_key_p0_0";      Value="256 (Default)"; Orig="256 (Default)";  Label="temporal radius 256 (less smear)"; Note="motion fidelity" },
  @{ N=23; Id="B25";  Aux=0; Lens="56"; Kind="lib_string"; Key="lib_better_color_wiener_sabre_key_p0_0"; Value="1.500"; Orig="";  Label="better-color wiener sabre 1.5 (JavaSaBr p5 value)"; Note="absent on main today" },
  @{ N=24; Id="B9a";  Aux=0; Lens="56"; Kind="lib_string"; Key="lib_sabre_detail_key_p0_0";          Value="1.250";   Orig="1.125";      Label="sabre detail 1.25"; Note="" },
  @{ N=25; Id="B9b";  Aux=0; Lens="56"; Kind="lib_string"; Key="lib_sabre_detail_key_p0_0";          Value="1.000";   Orig="1.250";      Label="sabre detail 1.0"; Note="down direction" },
  @{ N=26; Id="B16";  Aux=0; Lens="56"; Kind="lib_string"; Key="lib_fix_sabre_noise_key_p0_0";      Value="512";     Orig="0.000099659 (Default)"; Label="fix sabre noise 512 (strong (*) value)"; Note="aggressive denoise fix" },
  @{ N=27; Id="B24";  Aux=0; Lens="56"; Kind="lib_string"; Key="lib_savannah_merge_key_p0_0";        Value="1.125";   Orig="1.125";      Label="savannah merge 1.125 (chroma denoise up)"; Note="color gate!" },
  @{ N=28; Id="B10";  Aux=0; Lens="56"; Kind="lib_string"; Key="lib_smoothness_key_p0_0";            Value="4.00";    Orig="2.0 (Default)"; Label="smoothness 4.0 (smoother)"; Note="expect noise down, detail down" },
  @{ N=29; Id="B11";  Aux=0; Lens="56"; Kind="lib_string"; Key="lib_smoothing_sabre_key_p0_0";      Value="1.75";    Orig="1.4";        Label="smoothing sabre 1.75"; Note="VERIFY stored value first: baseline '1.4' may not be a real entry" },

  # ---- TIER C: wide/tele frame-count parity (only if main's tuning shows value) ----
  @{ N=30; Id="C1";   Aux=1; Lens="58"; Kind="lib_string"; Key="lib_pref_frame_count_zsl_key_p0_1"; Value="25";      Orig="25";         Label="wide ZSL 25 (match main)"; Note="wide currently 0 = library default" },
  @{ N=31; Id="C2";   Aux=2; Lens="52"; Kind="lib_string"; Key="lib_pref_frame_count_zsl_key_p0_2"; Value="25";      Orig="25";         Label="tele ZSL 25 (match main)"; Note="tele currently 0" }
)

if ($List) {
  Write-Host "TEST QUEUE ($($queue.Count) steps) - run in order, decide KEEP/REVERT after each:"
  foreach ($q in $queue) {
    Write-Host ("  [{0,2}] {1,-6} {2,-32} {3,-10} {4}" -f $q.N, $q.Id, $q.Key, "=>$($q.Value)", $q.Label)
  }
  return
}

if ($RunStep -ge 0 -and $RunStep -ne 999) {
  # RunStep 0 = reference shot (sentinel: default 999 means 'not set')
  $q = $queue | Where-Object { $_.N -eq $RunStep }
  if (-not $q) { throw "no step $RunStep" }
  if (-not $Dev) { throw "need -Dev" }
  $abargs = @("-Dev",$Dev,"-TestId",$q.Id,"-AuxKey",$q.Aux,"-LensId",$q.Lens,"-PrefKind",$q.Kind,"-PrefKey",$q.Key,"-PrefValue",$q.Value,"-Label",$q.Label)
  if ($q.Kind -eq "none") {
    Write-Host "step $($q.N): reference shot (no pref change) - running ab_runner with dummy no-op"
    # reference = shoot with current prefs; ab_runner needs SOME key; use a harmless re-write of current saturn value
    $abargs = @("-Dev",$Dev,"-TestId",$q.Id,"-AuxKey",$q.Aux,"-LensId",$q.Lens,"-PrefKind","bool_true","-PrefKey","camera.enable_saturn","-PrefValue","true","-Label","REF (no-op rewrite saturn=true)")
  }
  & powershell -NoProfile -ExecutionPolicy Bypass -File "G:\projects\fold5-camera2-research\tune\ab_runner.ps1" @abargs
  Write-Host ""
  Write-Host "step $($q.N) done. Decide KEEP/REVERT (protocol: works+not broken=keep). Log in DECISIONS.md."
  if ($q.Note) { Write-Host "note: $($q.Note)" }
  return
}

if ($RevertStep -ge 0 -and $RevertStep -ne 999) {
  $q = $queue | Where-Object { $_.N -eq $RevertStep }
  if (-not $q) { throw "no step $RevertStep" }
  if (-not $Dev) { throw "need -Dev" }
  $orig = $q.Orig
  if ($q.Kind -eq "bool_true") {
    $kind = if ($orig -eq "true") { "bool_true" } else { "bool_false" }
    $val = $orig
  } elseif ($q.Kind -eq "int") {
    $kind = "int"; $val = $orig
  } else {
    $kind = "lib_string"; $val = $orig
  }
  if ($orig -eq "" -and $q.Kind -eq "lib_string") {
    # key was absent originally -> remove it instead of setting
    Write-Host "revert $($q.Id): REMOVE key $($q.Key) (was absent)"
    # (ab_runner doesn't have a remove mode; do it directly:)
    adb -s $Dev shell "su 0 am force-stop com.samsung.android.ruler" 2>$null | Out-Null
    Start-Sleep -Seconds 2
    $s = "P=/data/data/com.samsung.android.ruler/shared_prefs/com.samsung.android.ruler_preferences.xml`nsed -i '/name=`"$($q.Key)`"/d' `$P`ngrep -c `"$($q.Key)`" `$P || echo removed"
    [IO.File]::WriteAllText("$env:TEMP\rmkey.sh", ($s -replace "`r`n","`n"))
    adb -s $Dev push "$env:TEMP\rmkey.sh" /data/local/tmp/rmkey.sh 2>&1 | Out-Null
    adb -s $Dev shell "su 0 sh /data/local/tmp/rmkey.sh"
    return
  }
  & powershell -NoProfile -ExecutionPolicy Bypass -File "G:\projects\fold5-camera2-research\tune\ab_runner.ps1" `
    -Dev $Dev -TestId "RV$($q.Id)" -AuxKey $q.Aux -LensId $q.Lens -PrefKind $kind -PrefKey $q.Key -PrefValue $val -Label "revert $($q.Id)"
  return
}

Write-Host "use -List / -RunStep N -Dev <ip:port> / -RevertStep N -Dev <ip:port>"
