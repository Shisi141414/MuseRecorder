# 将 graphs/ 目录的真机截图压缩到 docs/images/（宽 632，JPEG 85%）
param(
    [string]$SrcDir = "../graphs",
    [string]$OutDir = "docs/images"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$src = Resolve-Path (Join-Path $root $SrcDir)
$out = Join-Path $root $OutDir
New-Item -ItemType Directory -Force -Path $out | Out-Null

Add-Type -AssemblyName System.Drawing

function Resize-Shot([string]$path, [string]$dest, [int]$targetW) {
    $img = [System.Drawing.Image]::FromFile($path)
    try {
        $ratio = $targetW / $img.Width
        $h = [int]($img.Height * $ratio)
        $bmp = New-Object System.Drawing.Bitmap($targetW, $h)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.DrawImage($img, 0, 0, $targetW, $h)
        $g.Dispose()
        $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq "image/jpeg" }
        $ep = New-Object System.Drawing.Imaging.EncoderParameters(1)
        $ep.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]85)
        $bmp.Save($dest, $codec, $ep)
        $bmp.Dispose()
    } finally { $img.Dispose() }
}

for ($i = 1; $i -le 4; $i++) {
    Resize-Shot (Join-Path $src "$i.jpg") (Join-Path $out "zh-$i.jpg") 632
    Resize-Shot (Join-Path $src "Eng $i.jpg") (Join-Path $out "en-$i.jpg") 632
}
Get-ChildItem $out -Filter *.jpg | ForEach-Object { Write-Output "  $($_.Name) ($([Math]::Round($_.Length/1KB,0)) KB)" }
