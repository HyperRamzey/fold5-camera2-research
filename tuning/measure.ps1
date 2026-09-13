# tune\measure.ps1 - image quality metrics for A/B diffing
# Usage: .\measure.ps1 <jpg1> [jpg2] [-Grid 40] [-CropCenter 1600x1200]
# Outputs: luma mean, R/G, B/G (full-frame grid, 100px margin, N-px step),
#          noise proxy (high-pass energy), sharpness proxy (Laplacian energy) on center crop.
# If jpg2 given: prints deltas vs jpg1 and PASS/FAIL verdict per the CANDIDATES.md gates.
param(
  [Parameter(Mandatory=$true, Position=0)][string]$Jpg1,
  [Parameter(Position=1)][string]$Jpg2,
  [int]$Grid = 40,
  [int]$CropW = 1600,
  [int]$CropH = 1200
)

Add-Type -AssemblyName System.Drawing

function Measure-Image([string]$path) {
  if (-not (Test-Path $path)) { throw "missing: $path" }
  $img = [System.Drawing.Image]::FromFile($path)
  $bmp = New-Object System.Drawing.Bitmap($img)
  try {
    # --- full-frame color grid (established method: 100px margin, $Grid step) ---
    $mr=0.0; $mg=0.0; $mb=0.0; $n=0
    for ($y=100; $y -lt ($bmp.Height-100); $y+=$Grid) {
      for ($x=100; $x -lt ($bmp.Width-100); $x+=$Grid) {
        $c = $bmp.GetPixel($x,$y); $mr+=$c.R; $mg+=$c.G; $mb+=$c.B; $n++
      }
    }
    $R = $mr/$n; $G = $mg/$n; $B = $mb/$n
    # --- center crop for noise/sharpness (luma channel) ---
    $x0 = [int](($bmp.Width - $CropW)/2);  if ($x0 -lt 0) { $x0 = 0 }
    $y0 = [int](($bmp.Height - $CropH)/2); if ($y0 -lt 0) { $y0 = 0 }
    $cw = [Math]::Min($CropW, $bmp.Width); $ch = [Math]::Min($CropH, $bmp.Height)
    $lum = New-Object 'double[,]' ($cw+1),($ch+1)
    for ($y=0; $y -lt $ch; $y++) {
      for ($x=0; $x -lt $cw; $x++) {
        $c = $bmp.GetPixel(($x0+$x),($y0+$y))
        $lum[$x,$y] = 0.299*$c.R + 0.587*$c.G + 0.114*$c.B
      }
    }
    # noise proxy: mean abs high-pass (3x3 ring minus center), 2px step
    $hp=0.0; $hn=0
    for ($y=2; $y -lt ($ch-2); $y+=2) {
      for ($x=2; $x -lt ($cw-2); $x+=2) {
        $c0 = $lum[$x,$y]
        $avg = ($lum[($x-2),$y] + $lum[($x+2),$y] + $lum[$x,($y-2)] + $lum[$x,($y+2)] + $lum[($x-2),($y-2)] + $lum[($x+2),($y-2)] + $lum[($x-2),($y+2)] + $lum[($x+2),($y+2)])/8.0
        $hp += [Math]::Abs($c0 - $avg); $hn++
      }
    }
    # sharpness proxy: mean abs Laplacian, 2px step
    $lap=0.0; $ln=0
    for ($y=1; $y -lt ($ch-1); $y+=2) {
      for ($x=1; $x -lt ($cw-1); $x+=2) {
        $c0 = $lum[$x,$y]
        $l = -4*$c0 + $lum[($x-1),$y] + $lum[($x+1),$y] + $lum[$x,($y-1)] + $lum[$x,($y+1)]
        $lap += [Math]::Abs($l); $ln++
      }
    }
    [PSCustomObject]@{
      Path = $path
      Dims = "$($bmp.Width)x$($bmp.Height)"
      Luma = [Math]::Round((0.299*$R + 0.587*$G + 0.114*$B),1)
      RG   = [Math]::Round($R/$G,3)
      BG   = [Math]::Round($B/$G,3)
      MeansR = [Math]::Round($R,1); MeansG = [Math]::Round($G,1); MeansB = [Math]::Round($B,1)
      Noise = [Math]::Round($hp/$hn,3)
      Sharp = [Math]::Round($lap/$ln,3)
    }
  } finally { $bmp.Dispose(); $img.Dispose() }
}

$m1 = Measure-Image $Jpg1
Write-Host ("REF  {0}  {1}  luma={2} R/G={3} B/G={4} (R={5} G={6} B={7}) noise={8} sharp={9}" -f `
  (Split-Path $Jpg1 -Leaf), $m1.Dims, $m1.Luma, $m1.RG, $m1.BG, $m1.MeansR, $m1.MeansG, $m1.MeansB, $m1.Noise, $m1.Sharp)

if ($Jpg2) {
  $m2 = Measure-Image $Jpg2
  Write-Host ("NEW  {0}  {1}  luma={2} R/G={3} B/G={4} (R={5} G={6} B={7}) noise={8} sharp={9}" -f `
    (Split-Path $Jpg2 -Leaf), $m2.Dims, $m2.Luma, $m2.RG, $m2.BG, $m2.MeansR, $m2.MeansG, $m2.MeansB, $m2.Noise, $m2.Sharp)
  # deltas
  $dl = if ($m1.Luma -ne 0) { [Math]::Round(100*($m2.Luma-$m1.Luma)/$m1.Luma,1) } else { 0 }
  $drg = if ($m1.RG -ne 0) { [Math]::Round(100*($m2.RG-$m1.RG)/$m1.RG,1) } else { 0 }
  $dbg = if ($m1.BG -ne 0) { [Math]::Round(100*($m2.BG-$m1.BG)/$m1.BG,1) } else { 0 }
  $dn = if ($m1.Noise -ne 0) { [Math]::Round(100*($m2.Noise-$m1.Noise)/$m1.Noise,1) } else { 0 }
  $ds = if ($m1.Sharp -ne 0) { [Math]::Round(100*($m2.Sharp-$m1.Sharp)/$m1.Sharp,1) } else { 0 }
  Write-Host ("DELTA luma={0}% R/G={1}% B/G={2}% noise={3}% sharp={4}%" -f $dl,$drg,$dbg,$dn,$ds)
  # gates (scene drift first)
  if ([Math]::Abs($dl) -gt 20) {
    Write-Host "SCENE DRIFT >20% luma - re-shoot reference before judging color/noise!" -ForegroundColor Yellow
  } else {
    $colorOk = ([Math]::Abs($drg) -le 10) -and ([Math]::Abs($dbg) -le 10)
    $qualityOk = ($dn -le 0) -or ($ds -ge 0)   # noise not worse OR sharpness better
    if ($colorOk -and $qualityOk) { Write-Host "VERDICT: PASS (color ok; noise/sharp not degraded)" -ForegroundColor Green }
    elseif (-not $colorOk) { Write-Host "VERDICT: FAIL (color shifted >10%)" -ForegroundColor Red }
    else { Write-Host "VERDICT: FAIL (noise up AND sharp down)" -ForegroundColor Red }
  }
}
