# 生成 README / 网站用示意图（中/英各两张：总览-原始视图、总览-光谱图视图）
param(
    [string]$OutDir = "docs/images"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$out = Join-Path $root $OutDir
New-Item -ItemType Directory -Force -Path $out | Out-Null

$labels = @{
    zh = @{
        title = "Muse 记录器"; status = "数据流正常"; signal = "信号 −62 dBm · 优"; worn = "已佩戴"
        elec = @("TP9","AF7","AF8","TP10"); btnMain = "断开连接"; btnRec = "记录"
        wave = "实时脑电"; badge = "原始"; note = "四通道 · 共用量程"
        nav = @("总览","连接","记录","设置"); hint1 = "长按钮连接或断开；红键开始 / 结束录制；"; hint2 = "1–4 事件标注（长按改名，写入 CSV）。"
        badgeSpec = "光谱图"; noteSpec = "最新在顶 · 时间 ↓"
    }
    en = @{
        title = "Muse Recorder"; status = "Streaming"; signal = "Signal −62 dBm · Excellent"; worn = "Worn"
        elec = @("TP9","AF7","AF8","TP10"); btnMain = "Disconnect"; btnRec = "Rec"
        wave = "Live EEG"; badge = "Raw"; note = "4 ch · shared range"
        nav = @("Overview","Connect","Record","Settings"); hint1 = "Long button connects/disconnects; red button toggles recording;"; hint2 = "1–4 insert event markers (long-press to rename)."
        badgeSpec = "Spectrogram"; noteSpec = "Newest on top · time ↓"
    }
}

$colors = @("#2E7DD1", "#2FA84F", "#E8833A", "#9B59D6")
function Sine-Lane([double]$phase, [double]$amp, [int]$y0, [int]$h, [int]$W) {
    $pts = New-Object System.Text.StringBuilder
    $half = $h / 2.0 - 5
    for ($x = 0; $x -le $W; $x += 3) {
        $a = (13 * [Math]::Sin($x / 23.0 + $phase) + 6 * [Math]::Sin($x / 7.0 + $phase * 2) + 3 * [Math]::Sin($x / 3.1)) * $amp
        $y = $y0 + $h / 2.0 - ($a / 70.0) * $half
        [void]$pts.Append("$x," + [Math]::Round($y, 1).ToString() + " ")
    }
    return $pts.ToString()
}
function Heat-Color([double]$t) {
    $stops = @(
        @(0.00, @(235,238,246)), @(0.25, @(120,160,230)), @(0.50, @(60,180,120)),
        @(0.75, @(240,170,60)), @(1.00, @(205,55,55))
    )
    if ($t -lt 0) { $t = 0 }; if ($t -gt 1) { $t = 1 }
    $lo = $stops[0]; $hi = $stops[$stops.Count - 1]
    for ($i = 0; $i -lt $stops.Count - 1; $i++) {
        if ($t -ge $stops[$i][0] -and $t -le $stops[$i + 1][0]) { $lo = $stops[$i]; $hi = $stops[$i + 1]; break }
    }
    $span = [Math]::Max(1e-6, $hi[0] - $lo[0])
    $f = [Math]::Min(1.0, [Math]::Max(0.0, ($t - $lo[0]) / $span))
    $r = [Math]::Round($lo[1][0] + ($hi[1][0] - $lo[1][0]) * $f)
    $g = [Math]::Round($lo[1][1] + ($hi[1][1] - $lo[1][1]) * $f)
    $b = [Math]::Round($lo[1][2] + ($hi[1][2] - $lo[1][2]) * $f)
    return "rgb($r,$g,$b)"
}

function Build-Overview([string]$lang, [bool]$spectrogram) {
    $L = $labels[$lang]
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('<svg xmlns="http://www.w3.org/2000/svg" width="744" height="1540" viewBox="0 0 372 770" font-family="Segoe UI, system-ui, sans-serif">')
    # 底
    [void]$sb.AppendLine('<rect width="372" height="770" rx="28" fill="#F5F6FB"/>')
    [void]$sb.AppendLine('<rect width="372" height="770" rx="28" fill="none" stroke="#D8DCE9" stroke-width="2"/>')
    # 标题栏
    [void]$sb.AppendLine('<path d="M0,28 A28,28 0 0 1 28,0 H344 A28,28 0 0 1 372,28 V64 H0 Z" fill="#4355B9"/>')
    [void]$sb.AppendLine("<text x='20' y='42' font-size='19' font-weight='700' fill='#FFFFFF'>$($L.title)</text>")
    # 状态卡
    [void]$sb.AppendLine('<rect x="14" y="76" width="344" height="130" rx="18" fill="#FFFFFF"/>')
    [void]$sb.AppendLine('<circle cx="28" cy="102" r="6" fill="#2FA84F"/>')
    [void]$sb.AppendLine("<text x='42' y='108' font-size='16' font-weight='700' fill='#1B1F2D'>$($L.status)</text>")
    # 电池（凸点在左、填充锚右、双色数字）
    [void]$sb.AppendLine('<rect x="292" y="96" width="5" height="14" rx="2" fill="#2FA84F"/>')
    [void]$sb.AppendLine('<rect x="299" y="92" width="52" height="22" rx="7" fill="none" stroke="#2FA84F" stroke-width="2.5"/>')
    [void]$sb.AppendLine('<rect x="314" y="95" width="34" height="16" rx="4" fill="#2FA84F"/>')
    [void]$sb.AppendLine("<clipPath id='battClip$lang'><rect x='314' y='95' width='34' height='16'/></clipPath>")
    [void]$sb.AppendLine("<text x='325' y='108' font-size='13' font-weight='800' fill='#2FA84F' text-anchor='middle'>73</text>")
    [void]$sb.AppendLine("<text x='325' y='108' font-size='13' font-weight='800' fill='#FFFFFF' text-anchor='middle' clip-path='url(#battClip$lang)'>73</text>")
    [void]$sb.AppendLine("<text x='28' y='130' font-size='13' fill='#3C4257'>$($L.signal)</text>")
    [void]$sb.AppendLine("<text x='344' y='130' font-size='12' font-weight='700' fill='#2FA84F' text-anchor='end'>✓ $($L.worn)</text>")
    for ($i = 0; $i -lt 4; $i++) {
        $cx = 22 + $i * 84
        [void]$sb.AppendLine("<rect x='$cx' y='142' width='76' height='30' rx='11' fill='#F1F2F9'/>")
        $dotC = @("#2FA84F", "#2FA84F", "#E8A23A", "#D84B4B")[$i]
        [void]$sb.AppendLine("<circle cx='$( $cx + 16 )' cy='157' r='6' fill='$dotC'/>")
        [void]$sb.AppendLine("<text x='$( $cx + 28 )' y='162' font-size='13' font-weight='700' fill='#1B1F2D'>$($L.elec[$i])</text>")
    }
    # 按钮行
    [void]$sb.AppendLine('<rect x="14" y="218" width="234" height="54" rx="16" fill="#4355B9"/>')
    [void]$sb.AppendLine("<text x='131' y='251' font-size='16' font-weight='700' fill='#FFFFFF' text-anchor='middle'>$($L.btnMain)</text>")
    [void]$sb.AppendLine('<rect x="258" y="218" width="100" height="54" rx="16" fill="#FFFFFF" stroke="#D84B4B" stroke-width="2"/>')
    [void]$sb.AppendLine("<text x='308' y='251' font-size='16' font-weight='800' fill='#D84B4B' text-anchor='middle'>$($L.btnRec)</text>")
    # 波形卡
    [void]$sb.AppendLine('<rect x="14" y="282" width="344" height="330" rx="18" fill="#FFFFFF"/>')
    [void]$sb.AppendLine("<text x='26' y='306' font-size='13' font-weight='700' fill='#4A5068'>$($L.wave)</text>")
    $badge = if ($spectrogram) { $L.badgeSpec } else { $L.badge }
    [void]$sb.AppendLine("<rect x='$( 26 + $L.wave.Length * 13 + 8 )' y='292' width='$( $badge.Length * 10 + 16 )' height='18' rx='8' fill='#E7EAF9'/>")
    $bx = 26 + $L.wave.Length * 13 + 16
    [void]$sb.AppendLine("<text x='$( $bx + $badge.Length * 5 + 8 )' y='305' font-size='10' font-weight='700' fill='#4355B9' text-anchor='middle'>$badge</text>")
    [void]$sb.AppendLine("<circle cx='$( $bx + $badge.Length * 10 + 32 )' cy='301' r='11' fill='#E9EBF5'/>")
    $icx = $bx + $badge.Length * 10 + 32
    [void]$sb.AppendLine("<path d='M$( $icx - 5 ),$(301 - 2) a6,6 0 1 1 2,5 M$( $icx + 5 ),303 a6,6 0 1 1 -2,-5' fill='none' stroke='#4355B9' stroke-width='1.6' stroke-linecap='round'/>")
    $note = if ($spectrogram) { $L.noteSpec } else { $L.note }
    [void]$sb.AppendLine("<text x='346' y='305' font-size='10' fill='#8A90A4' text-anchor='end'>$note</text>")

    if ($spectrogram) {
        # 光谱图（频率横轴）
        $plotX0 = 26; $plotX1 = 346; $plotY0 = 316; $axisY = 576; $plotY1 = $axisY - 8
        $unitW = 22; $pw = $plotX1 - $unitW - $plotX0
        $rnd = New-Object System.Random(7)
        for ($x = 0; $x -lt $pw; $x += 4) {
            $hz = 1 + $x / $pw * 59
            for ($y = $plotY0; $y -lt $plotY1; $y += 4) {
                $t = 0.05 + 0.04 * $rnd.NextDouble()
                $t += 0.70 * [Math]::Exp(-[Math]::Pow($hz - 10.5, 2) / 8) * (0.55 + 0.45 * [Math]::Cos($y / 26.0))
                $t += 0.38 * [Math]::Exp(-[Math]::Pow($hz - 21, 2) / 18) * (0.3 + 0.7 * [Math]::Abs([Math]::Sin($y / 44.0)))
                $t += 0.50 * [Math]::Exp(-[Math]::Pow($hz - 2.5, 2) / 4) * (0.35 + 0.65 * [Math]::Abs([Math]::Sin($y / 70.0 + 1)))
                [void]$sb.AppendLine("<rect x='$( $plotX0 + $x )' y='$y' width='4' height='4' fill='$( Heat-Color $t )'/>")
            }
        }
        for ($hz = 5; $hz -le 60; $hz += 5) {
            $x = $plotX0 + [Math]::Round(($hz - 1) / 59.0 * $pw)
            [void]$sb.AppendLine("<line x1='$x' y1='$plotY0' x2='$x' y2='$plotY1' stroke='#C3C7D4' stroke-width='0.8' stroke-dasharray='2,3'/>")
            [void]$sb.AppendLine("<text x='$x' y='$axisY' font-size='10' fill='#6D7386' text-anchor='middle'>$hz</text>")
        }
        [void]$sb.AppendLine("<text x='$plotX0' y='$axisY' font-size='10' fill='#9AA0B5'>1</text>")
        [void]$sb.AppendLine("<text x='346' y='$axisY' font-size='10' font-weight='700' fill='#6D7386' text-anchor='end'>Hz</text>")
    } else {
        # 原始四分框
        $laneY0 = 320; $laneH = 64; $W = 320
        for ($c = 0; $c -lt 4; $c++) {
            $lt = $laneY0 + $c * $laneH
            $cy = $lt + $laneH / 2
            [void]$sb.AppendLine("<line x1='26' y1='$cy' x2='346' y2='$cy' stroke='#C3C7D4' stroke-width='0.8' stroke-dasharray='4,5'/>")
            $phase = 1.7 * $c + 0.5
            $amp = @(0.55, 1.0, 0.8, 0.42)[$c]
            $pts = Sine-Lane $phase $amp $lt $laneH $W
            [void]$sb.AppendLine("<polyline points='$pts' transform='translate(26,0)' fill='none' stroke='$( $colors[$c] )' stroke-width='1.8'/>")
            [void]$sb.AppendLine("<text x='26' y='$( $lt + 14 )' font-size='10.5' font-weight='700' fill='$( $colors[$c] )'>$( $L.elec[$c] )</text>")
        }
        [void]$sb.AppendLine("<text x='346' y='330' font-size='10' fill='#8A90A4' text-anchor='end'>+70µV</text>")
        [void]$sb.AppendLine("<text x='346' y='574' font-size='10' fill='#8A90A4' text-anchor='end'>−70µV</text>")
    }
    # 标注键
    for ($i = 0; $i -lt 4; $i++) {
        $mx = 14 + $i * 88
        [void]$sb.AppendLine("<rect x='$mx' y='622' width='78' height='50' rx='15' fill='#FFFFFF' stroke='#B9BFDC' stroke-width='1.6'/>")
        [void]$sb.AppendLine("<text x='$( $mx + 39 )' y='654' font-size='19' font-weight='800' fill='#333A55' text-anchor='middle'>$($i + 1)</text>")
    }
    [void]$sb.AppendLine("<text x='20' y='694' font-size='10.5' fill='#8A90A4'>$($L.hint1)</text>")
    [void]$sb.AppendLine("<text x='20' y='708' font-size='10.5' fill='#8A90A4'>$($L.hint2)</text>")
    # 底部导航
    [void]$sb.AppendLine('<path d="M0,742 H372 V742 A28,28 0 0 1 344,770 H28 A28,28 0 0 1 0,742 Z" fill="#FFFFFF" transform="translate(0,0)"/>')
    [void]$sb.AppendLine('<rect x="0" y="712" width="372" height="30" fill="#FFFFFF"/>')
    [void]$sb.AppendLine('<path d="M0,700 H372 V742 A28,28 0 0 1 344,770 H28 A28,28 0 0 1 0,742 Z" fill="#FFFFFF"/>')
    for ($i = 0; $i -lt 4; $i++) {
        $nx = 46.5 + $i * 93
        $active = $i -eq 0
        $col = if ($active) { "#4355B9" } else { "#9AA0B5" }
        $wt = if ($active) { "700" } else { "400" }
        [void]$sb.AppendLine("<rect x='$( $nx - 10 )' y='716' width='20' height='20' rx='6' fill='$col' opacity='0.85'/>")
        [void]$sb.AppendLine("<text x='$nx' y='758' font-size='11' font-weight='$wt' fill='$col' text-anchor='middle'>$($L.nav[$i])</text>")
    }
    [void]$sb.AppendLine('</svg>')
    return $sb.ToString()
}

foreach ($lang in @("zh", "en")) {
    [System.IO.File]::WriteAllText((Join-Path $out "overview-$lang.svg"), (Build-Overview $lang $false), [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText((Join-Path $out "spectrogram-$lang.svg"), (Build-Overview $lang $true), [System.Text.UTF8Encoding]::new($false))
}

# 由模板生成单文件网站（SVG 直接内嵌，浏览器面板预览与 Pages 均可显示）
$templatePath = Join-Path $root "scripts/site-template.html"
if (Test-Path $templatePath) {
    $html = [IO.File]::ReadAllText($templatePath, [Text.UTF8Encoding]::new($false))
    $html = $html.Replace("{{SVG_OVERVIEW_ZH}}", (Build-Overview "zh" $false))
    $html = $html.Replace("{{SVG_OVERVIEW_EN}}", (Build-Overview "en" $false))
    $html = $html.Replace("{{SVG_SPEC_ZH}}", (Build-Overview "zh" $true))
    $html = $html.Replace("{{SVG_SPEC_EN}}", (Build-Overview "en" $true))
    [System.IO.File]::WriteAllText((Join-Path $root "docs/index.html"), $html, [Text.UTF8Encoding]::new($false))
    Write-Output "docs/index.html generated (single-file, inline SVG)"
}
Write-Output "SVG generated:"
Get-ChildItem $out | ForEach-Object { Write-Output "  $($_.Name) ($([Math]::Round($_.Length/1KB,1)) KB)" }
