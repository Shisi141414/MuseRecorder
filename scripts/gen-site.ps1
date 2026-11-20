# 生成单文件网站（scripts/site-template.html + docs/images/*.jpg → docs/index.html）
# 真机截图 base64 内嵌：中文页显示 zh 四张、英文页显示 en 四张，四图并排一行。
# 截图来源：工作区 graphs/ 目录（真机四页截图，中英各四张），
# 先运行 resize-shots.ps1 生成 docs/images/{zh,en}-{1..4}.jpg。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$templatePath = Join-Path $root "scripts/site-template.html"
$imgDir = Join-Path $root "docs/images"

if (-not (Test-Path $templatePath)) { throw "template not found: $templatePath" }
$html = [IO.File]::ReadAllText($templatePath, [Text.UTF8Encoding]::new($false))
for ($i = 1; $i -le 4; $i++) {
    foreach ($lang in @("zh", "en")) {
        $jpg = Join-Path $imgDir "$lang-$i.jpg"
        if (Test-Path $jpg) {
            $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jpg))
            $html = $html.Replace("{{IMG_" + $lang.ToUpper() + "_$i}}", "data:image/jpeg;base64,$b64")
        } else {
            Write-Output "warn: missing $jpg"
        }
    }
}
[IO.File]::WriteAllText((Join-Path $root "docs/index.html"), $html, [Text.UTF8Encoding]::new($false))
Write-Output "docs/index.html generated (8 inline JPEG screenshots, per-language 4-up row)"
