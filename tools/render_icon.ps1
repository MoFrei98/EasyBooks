# Rendert das App-Icon der README aus der SVG-Vorlage von tools/make_icon.py (mit Microsoft Edge).
#   docs/icon.png   Icon in der README (192 px, abgerundet)
# Aufruf: pwsh tools/render_icon.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$edge = "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
$tmp = Join-Path ([IO.Path]::GetTempPath()) "easybooks-icon"
New-Item -ItemType Directory -Force $tmp | Out-Null

function Render($svg, $png, $width, $height) {
    $page = Join-Path $tmp ([IO.Path]::GetFileNameWithoutExtension($png) + '.html')
    $uri = ([Uri](Join-Path $root $svg)).AbsoluteUri
    Set-Content -Encoding utf8 $page "<html><body style='margin:0;background:transparent'><img src='$uri' style='width:${width}px;height:${height}px;display:block'></body></html>"
    $out = Join-Path $root $png
    New-Item -ItemType Directory -Force (Split-Path -Parent $out) | Out-Null
    & $edge --headless=new --disable-gpu --hide-scrollbars --default-background-color=00000000 `
        "--window-size=$width,$height" "--screenshot=$out" ([Uri]$page).AbsoluteUri 2>$null | Out-Null
    Write-Host "geschrieben: $png"
}

Render 'tools/icon/easybooks-icon.svg' 'docs/icon.png' 192 192
