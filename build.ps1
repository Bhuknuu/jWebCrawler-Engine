# build.ps1 — jWebCrawler-Engine Build Script
# Usage: .\build.ps1          (compile + run)
#        .\build.ps1 -compile (compile only)
param([switch]$compile)

$ErrorActionPreference = 'Stop'

$ProjectRoot = $PSScriptRoot
$SrcDir      = Join-Path $ProjectRoot "Project\src"
$OutDir      = Join-Path $SrcDir "out"

# Ensure output directory exists
if (-not (Test-Path $OutDir)) {
    New-Item -ItemType Directory -Path $OutDir | Out-Null
}

Write-Host "[BUILD] Compiling Java sources..." -ForegroundColor Cyan
$Sources = Get-ChildItem -Path $SrcDir -Filter "*.java" | ForEach-Object { $_.FullName }

javac -d $OutDir @Sources
if ($LASTEXITCODE -ne 0) {
    Write-Host "[BUILD] COMPILATION FAILED" -ForegroundColor Red
    exit 1
}
Write-Host "[BUILD] Compilation successful." -ForegroundColor Green

if ($compile) { exit 0 }

Write-Host "[RUN] Starting jWebCrawler Dashboard..." -ForegroundColor Cyan
Write-Host "[RUN] Browser will open automatically to http://localhost:8080" -ForegroundColor Yellow
Write-Host "[RUN] Press Ctrl+C to stop." -ForegroundColor Gray

# Run from project root so dashboard/ is accessible at ./dashboard/
# (Main.java also resolves it from class location, this is belt-and-suspenders)
Set-Location $ProjectRoot
java -cp $OutDir Main
