#requires -Version 7
<#
.SYNOPSIS
    Offline build for the "AI Commit Message (Custom Model)" IntelliJ plugin.

.DESCRIPTION
    Compiles the plugin sources against a locally installed IntelliJ IDEA and packs an
    installable plugin jar. Needs no Gradle and no network access, because the IntelliJ
    Platform classes are taken straight from the IDE installation.

.EXAMPLE
    pwsh -File build.ps1
    pwsh -File build.ps1 -IdeaHome 'D:\app\idea\IntelliJ IDEA' -JdkHome 'C:\Program Files\Zulu\zulu-25'
#>
[CmdletBinding()]
param(
    [string]$IdeaHome = 'D:\app\idea\IntelliJ IDEA',
    [string]$JdkHome = 'C:\Program Files\Zulu\zulu-25',
    [string]$Version = '1.2.0'
)

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$srcDir = Join-Path $root 'src\main\java'
$resDir = Join-Path $root 'src\main\resources'
$buildDir = Join-Path $root 'build'
$classesDir = Join-Path $buildDir 'classes'
$distDir = Join-Path $buildDir 'dist'

$javac = Join-Path $JdkHome 'bin\javac.exe'
$jarExe = Join-Path $JdkHome 'bin\jar.exe'

if (-not (Test-Path $IdeaHome)) { throw "IntelliJ IDEA installation not found: $IdeaHome" }
if (-not (Test-Path $javac)) { throw "javac not found: $javac (pass -JdkHome <JDK 17+>)" }
if (-not (Test-Path $jarExe)) { throw "jar not found: $jarExe" }

Write-Host "IDEA      : $IdeaHome"
Write-Host "JDK       : $JdkHome"

if (Test-Path $classesDir) { Remove-Item $classesDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classesDir, $distDir | Out-Null

# Platform classes live in lib\*.jar and lib\modules\*.jar on IDEA 2024+.
$classpath = @(
    (Join-Path $IdeaHome 'lib\*'),
    (Join-Path $IdeaHome 'lib\modules\*')
) -join ';'

$sources = @(Get-ChildItem $srcDir -Recurse -Filter *.java | Select-Object -ExpandProperty FullName)
if ($sources.Count -eq 0) { throw "No Java sources found under $srcDir" }

Write-Host "Compiling $($sources.Count) source file(s)..."
& $javac -encoding UTF-8 --release 21 -nowarn -d $classesDir -cp $classpath @sources
if ($LASTEXITCODE -ne 0) { throw "Compilation failed." }

Copy-Item (Join-Path $resDir '*') $classesDir -Recurse -Force

$pluginJar = Join-Path $distDir "idea-commit-ai-$Version.jar"
if (Test-Path $pluginJar) { Remove-Item $pluginJar -Force }

& $jarExe cf $pluginJar -C $classesDir .
if ($LASTEXITCODE -ne 0) { throw "Packaging failed." }

Write-Host ""
Write-Host "Built: $pluginJar" -ForegroundColor Green
Write-Host ""
Write-Host "Install: IntelliJ IDEA > Settings > Plugins > gear icon > Install Plugin from Disk... > pick the jar above, then restart."
Write-Host "Configure: Settings > Tools > AI Commit Message."
