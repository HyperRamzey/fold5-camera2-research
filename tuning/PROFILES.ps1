# tune\PROFILES.ps1 - Day/Night profile switching (primary = GUI, this = PC-side quick switch)
#
# GUI-SELECTABLE PROFILES (proven 2026-09-13, user-requested):
#   In the AGC app: Settings gear > "Configs, LUT, Libraries, AWB, NoiseModel file" > Import >
#   /Download/AGC.9.2/configs/ >
#     fold5_day_sun.agc    - DAY tune (slot 3 active: E-bank stock 4s/30/25 - fast burst in sun)
#     fold5_night_full.agc - NIGHT tune (slot 4 active: full bare tune, E-bank 8s/60/50)
#   Both verified visible in the app's picker + import round-trip tested (2026-09-13).
#   To save an updated snapshot after future tuning: same screen > Save (writes current state
#   to a new .agc in that folder), then rename to keep.
#
# Mechanism (proven): lib_patch_profile_key=N selects patch slot N; lib_*_key_pN_M overrides
# apply when present, missing keys fall back to BARE keys. Slot 3 = day overrides; slot 4 = empty
# (pure bare = full night tune). NOTE: simultaneous p0/p3 writes of the same key wedge the
# camera HAL (reboot required) - never leave both present; day profile files keep them separate.
#
# This script = one-command PC-side switch (equivalent effect, no GUI taps needed):
param(
  [string]$Set = "",
  [string]$Dev = ""
)
$pref = "/data/data/com.samsung.android.ruler/shared_prefs/com.samsung.android.ruler_preferences.xml"
if ($Set -ne 'day' -and $Set -ne 'night') { Write-Host "use -Set day|night -Dev <ip:port>  (GUI: Configs > Import > fold5_day_sun.agc / fold5_night_full.agc)"; return }
if (-not $Dev) { Write-Host "need -Dev <ip:port>"; return }
$slot = if ($Set -eq 'day') { '3' } else { '4' }
$s = "P=$pref`nsed -i 's|<string name=`"lib_patch_profile_key`">[0-9]*</string>|<string name=`"lib_patch_profile_key`">$slot</string>|' `$P`ngrep -a 'lib_patch_profile_key' `$P"
[IO.File]::WriteAllText("$env:TEMP\prof.sh", ($s -replace "`r`n","`n"))
& adb.exe -s $Dev push "$env:TEMP\prof.sh" /data/local/tmp/prof.sh 2>&1 | Out-Null
$out = & adb.exe -s $Dev shell "su 0 sh /data/local/tmp/prof.sh" 2>&1
Write-Host "profile -> $Set (slot $slot): $out"
& adb.exe -s $Dev shell "su 0 am force-stop com.samsung.android.ruler" 2>$null | Out-Null
Start-Sleep -Seconds 2
& adb.exe -s $Dev shell "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard" 2>$null | Out-Null
& adb.exe -s $Dev shell "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.samsung.android.ruler/com.android.camera.CameraLauncher" 2>$null | Out-Null
Write-Host "camera cold-booted on $Set profile"
& adb.exe -s $Dev shell "su 0 rm -f /data/local/tmp/prof.sh" 2>$null | Out-Null
