# tune\build_config.ps1 - build fold5_tuned_v66.agc from fold5_best baseline + kept settings
# Usage:
#   .\build_config.ps1 -KeptFile .\KEPT.tsv            (build from results file)
#   .\build_config.ps1 -Inline "lib_hardmerge_key_p0_0=1;gcam.zsl_buffer_size=8"  (quick manual)
#
# KeptFile format (TSV, produced by ab_runner's AB_RESULTS.tsv + your KEEP annotations):
#   TestId <tab> Label <tab> PrefKey <tab> PrefValue <tab> Lens <tab> ... <tab> Verdict
#   Only rows whose Verdict is KEEP (or FUNC_OK + PASS decided manually) are applied.
#   A row with Lens=ALL replicates the per-lens suffix key to _p0_0,_p0_1,_p0_2,_p0_3 (main,wide,tele,main2)
#   A row with a full per-lens key (already suffixed) is written as-is.
#   A row whose key starts with camera./gcam./SABRE/etc. (no lib_ prefix) is a GLOBAL flag: boolean/int written once.
#
# The builder:
#   1. Reads fold5_best.agc (baseline: CFA fix included).
#   2. Verifies baseline CFA fix + guards (fails if missing).
#   3. Applies kept entries (string for lib_*, boolean/int for flags).
#   4. Writes fold5_tuned_v66.agc.
#   5. Prints a diff summary vs fold5_best + a device-sync checklist.
param(
  [string]$KeptFile = "",
  [string]$Inline = "",
  [string]$Baseline = "G:\projects\fold5-camera2-research\fold5_best.agc",
  [string]$Out = "G:\projects\fold5-camera2-research\fold5_tuned_v66.agc"
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Baseline)) { throw "baseline missing: $Baseline" }
$lines = Get-Content $Baseline

# ---- 2. baseline guards ----
$guards = [ordered]@{
  "CFA fix (main)"     = '<string name="pref_sensor_color_filter_key_0">0</string>'
  "patch profile 3"    = '<string name="lib_patch_profile_key">3</string>'
  "saturn=true"        = '<boolean name="camera.enable_saturn" value="true" />'
  "HDR+ main on"       = '<string name="pref_camera_hdr_plus_override_key_p0_0">on</string>'
}
foreach ($g in $guards.Keys) {
  if (-not ($lines | Select-String -SimpleMatch $guards[$g])) { throw "baseline guard FAILED: $g missing" }
}
Write-Host "baseline guards: OK (CFA fix, patch profile, saturn, HDR+)"

# ---- collect kept entries ----
$entries = @()   # @{Key,Value,Kind}  Kind: string|boolean|int
function Add-Entry($k, $v) {
  $kind = "string"
  if ($k -match '^(camera\.|gcam\.|SABRE|camcorder\.|beholder)') {
    # GoogleX flags: boolean unless key suggests int (count/size/mode/threshold/ms/frames)
    if ($k -match '(count|size|mode|threshold|_ms|frames|wb_source|height)') { $kind = "int" } else { $kind = "boolean" }
  }
  $script:entries += [PSCustomObject]@{Key=$k; Value=$v; Kind=$kind}
}

if ($KeptFile -and (Test-Path $KeptFile)) {
  $rows = Import-Csv -Path $KeptFile -Delimiter "`t" -Header TestId,Label,PrefKey,PrefValue,Lens,Saved,Dims,Metrics,Verdict
  foreach ($r in $rows) {
    if ($r.Verdict -notmatch 'KEEP|PASS') { continue }
    if (-not $r.PrefKey -or -not $r.PrefValue) { continue }
    if ($r.Lens -eq "ALL" -and $r.PrefKey -match '^lib_') {
      # replicate base lib key to per-lens slots: strip any existing suffix first
      $base = $r.PrefKey -replace '_p\d+_\d+$',''
      foreach ($suf in @("_p0_0","_p0_1","_p0_2","_p0_3")) { Add-Entry "$base$suf" $r.PrefValue }
    } else {
      Add-Entry $r.PrefKey $r.PrefValue
    }
  }
}
if ($Inline) {
  foreach ($pair in ($Inline -split ';')) {
    if ($pair -match '^\s*([^=]+)=(.*)$') { Add-Entry $matches[1].Trim() $matches[2].Trim() }
  }
}
if ($entries.Count -eq 0) { Write-Host "no kept entries given - output = baseline copy (still written)"; }

# ---- 3. apply entries ----
$applied = 0; $inserted = 0
foreach ($e in $entries) {
  $found = $false
  for ($i=0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match ('name="' + [regex]::Escape($e.Key) + '"')) {
      switch ($e.Kind) {
        "string"  { $lines[$i] = "    <string name=`"$($e.Key)`">$($e.Value)</string>" }
        "boolean" { $lines[$i] = "    <boolean name=`"$($e.Key)`" value=`"$($e.Value)`" />" }
        "int"     { $lines[$i] = "    <int name=`"$($e.Key)`" value=`"$($e.Value)`" />" }
      }
      $found = $true; $applied++; break
    }
  }
  if (-not $found) {
    # insert before </map>
    $idx = [array]::IndexOf($lines, "</map>")
    if ($idx -lt 0) { throw "no </map> in baseline!" }
    switch ($e.Kind) {
      "string"  { $newLine = "    <string name=`"$($e.Key)`">$($e.Value)</string>" }
      "boolean" { $newLine = "    <boolean name=`"$($e.Key)`" value=`"$($e.Value)`" />" }
      "int"     { $newLine = "    <int name=`"$($e.Key)`" value=`"$($e.Value)`" />" }
    }
    $lines = $lines[0..($idx-1)] + $newLine + $lines[$idx..($lines.Count-1)]
    $inserted++
  }
}

# ---- 4. write output ----
$lines | Set-Content $Out -Encoding UTF8
Write-Host ""
Write-Host "built: $Out"
Write-Host "  entries applied to existing keys : $applied"
Write-Host "  entries inserted (new keys)     : $inserted"

# ---- 5. diff summary vs baseline ----
Write-Host ""
Write-Host "=== DIFF vs fold5_best.agc ==="
$baseMap = @{}
foreach ($l in (Get-Content $Baseline)) { if ($l -match 'name="([^"]+)"') { $baseMap[$matches[1]] = $l.Trim() } }
$outMap = @{}
foreach ($l in $lines) { if ($l -match 'name="([^"]+)"') { $outMap[$matches[1]] = $l.Trim() } }
$changes = 0
foreach ($k in ($outMap.Keys | Sort-Object)) {
  if ($baseMap.ContainsKey($k)) {
    if ($outMap[$k] -ne $baseMap[$k]) { Write-Host "  CHANGED $k"; Write-Host "    was: $($baseMap[$k])"; Write-Host "    now: $($outMap[$k])"; $changes++ }
  } else {
    Write-Host "  ADDED   $k : $($outMap[$k])"; $changes++
  }
}
if ($changes -eq 0) { Write-Host "  (identical to baseline)" }

# ---- device-sync checklist ----
Write-Host ""
Write-Host "=== DEVICE SYNC CHECKLIST (run at device time) ==="
Write-Host "1. force-stop app; push this file's CHANGED/ADDED keys to device prefs (same sed patterns as ab_runner)"
Write-Host "2. cold-boot camera on lens 56; take 2 shots; measure both vs neutral ref (R/G 1.22 B/G 0.86 +-10%)"
Write-Host "3. repeat on wide 58 (aux 1) and tele 52 (aux 2): >=2 shots each, dims 4000x3000 / 2784x2080"
Write-Host "4. verify guards: aux=0, lens list, CFA=0, saturn=true, patch=3, no 50MP keys"
Write-Host "5. grep-verify all 6 v6.6 patches in gcam_decompiled (unchanged by this work)"
