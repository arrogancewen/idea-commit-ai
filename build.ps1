#requires -Version 7
<#
.SYNOPSIS
    Offline build for the "AI Commit Message" IntelliJ plugin.

.DESCRIPTION
    Compiles the plugin sources against a locally installed IntelliJ IDEA and packs an
    installable plugin jar. No Gradle and no network access are needed, because the
    IntelliJ Platform classes are taken straight from the IDE installation.

    IDEA and the JDK are auto-detected. If detection fails, pass -IdeaHome / -JdkHome.

.PARAMETER IdeaHome
    Path to the IntelliJ IDEA installation (the folder containing `lib`).
    Windows example: 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3'
    macOS example:   '/Applications/IntelliJ IDEA.app/Contents'

.PARAMETER JdkHome
    Path to a JDK 21+ home. Falls back to $env:JAVA_HOME, then to javac on PATH.

.PARAMETER Version
    Version stamped into the produced jar file name.

.EXAMPLE
    pwsh -File build.ps1

.EXAMPLE
    pwsh -File build.ps1 -IdeaHome '/Applications/IntelliJ IDEA.app/Contents' -JdkHome "$env:JAVA_HOME"
#>
[CmdletBinding()]
param(
    [string]$IdeaHome,
    [string]$JdkHome,
    [string]$Version = '1.2.0'
)

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$srcDir = Join-Path $root 'src/main/java'
$resDir = Join-Path $root 'src/main/resources'
$buildDir = Join-Path $root 'build'
$classesDir = Join-Path $buildDir 'classes'
$distDir = Join-Path $buildDir 'dist'

$pathSeparator = [System.IO.Path]::PathSeparator

function Resolve-JdkHome {
    param([string]$Explicit)
    if ($Explicit) { return $Explicit }
    if ($env:JAVA_HOME) { return $env:JAVA_HOME }
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) { return (Split-Path (Split-Path $javac.Source -Parent) -Parent) }
    return $null
}

function Test-IdeaRoot {
    param([string]$Path)
    if (-not $Path -or -not (Test-Path $Path)) { return $false }
    $lib = Join-Path $Path 'lib'
    if (-not (Test-Path $lib)) { return $false }
    return @(Get-ChildItem $lib -Filter '*.jar' -ErrorAction SilentlyContinue).Count -gt 0
}

function Resolve-IdeaHome {
    param([string]$Explicit)
    if ($Explicit) { return $Explicit }
    if ($env:IDEA_HOME) { return $env:IDEA_HOME }

    $candidates = @()

    # 1. JetBrains' own tooling writes toolboxes.json / .home files next to the IDE.
    #    This finds installations on any drive, including custom folders.
    $toolboxRoots = @(
        "$env:LOCALAPPDATA\JetBrains\Toolbox\apps",
        "$env:APPDATA\JetBrains\Toolbox\apps",
        "$env:HOME/.local/share/JetBrains/Toolbox/apps"
    )
    foreach ($toolboxRoot in $toolboxRoots) {
        if (-not (Test-Path $toolboxRoot)) { continue }
        $candidates += @(Get-ChildItem $toolboxRoot -Directory -Recurse -Depth 3 -ErrorAction SilentlyContinue |
                         Where-Object { $_.Name -match 'IntelliJ|IDEA' } |
                         Select-Object -ExpandProperty FullName)
    }

    # 2. Standard install locations per platform.
    if ($IsWindows) {
        $probe = @("$env:ProgramFiles\JetBrains", "${env:ProgramFiles(x86)}\JetBrains", "$env:LOCALAPPDATA\Programs")
        foreach ($dir in $probe) {
            $candidates += @(Get-ChildItem $dir -Directory -Filter '*IntelliJ*' -ErrorAction SilentlyContinue |
                             Select-Object -ExpandProperty FullName)
        }
    } elseif ($IsMacOS) {
        $candidates += @(Get-ChildItem '/Applications' -Directory -Filter 'IntelliJ IDEA*.app' -ErrorAction SilentlyContinue |
                         ForEach-Object { Join-Path $_.FullName 'Contents' })
    } else {
        $candidates += @(Get-ChildItem '/opt' -Directory -Filter '*idea*' -ErrorAction SilentlyContinue |
                         Select-Object -ExpandProperty FullName)
    }

    # 3. Last resort: look for an "idea64.exe" / "idea.sh" launcher and walk up from it.
    $launchers = if ($IsWindows) { @('idea64.exe', 'idea.exe') }
                 elseif ($IsMacOS) { @() }
                 else { @('idea.sh') }
    $searchRoots = @($env:ProgramFiles, "${env:ProgramFiles(x86)}", "$env:LOCALAPPDATA\Programs", 'D:\app', '/opt', "$env:HOME/apps") |
                   Where-Object { $_ -and (Test-Path $_) } |
                   Select-Object -Unique
    foreach ($searchRoot in $searchRoots) {
        foreach ($launcher in $launchers) {
            $found = Get-ChildItem $searchRoot -Recurse -Depth 4 -Filter $launcher -File -ErrorAction SilentlyContinue |
                     Select-Object -First 3
            foreach ($exe in $found) {
                $candidates += (Split-Path $exe.FullName -Parent | Split-Path -Parent)
            }
        }
    }

    foreach ($candidate in $candidates) {
        if (Test-IdeaRoot $candidate) { return $candidate }
    }
    return $null
}

$jdk = Resolve-JdkHome $JdkHome
$idea = Resolve-IdeaHome $IdeaHome

if (-not $idea) {
    throw @"
Could not find an IntelliJ IDEA installation.

Pass it explicitly, pointing at the folder that contains 'lib':
    pwsh -File build.ps1 -IdeaHome 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3'
    pwsh -File build.ps1 -IdeaHome '/Applications/IntelliJ IDEA.app/Contents'

Or set the IDEA_HOME environment variable.
"@
}
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin'))) {
    throw "Could not find a JDK. Pass -JdkHome <JDK 21+ home> or set JAVA_HOME."
}

$javac = Join-Path $jdk 'bin/javac'
$jarExe = Join-Path $jdk 'bin/jar'
if ($IsWindows) { $javac += '.exe'; $jarExe += '.exe' }

if (-not (Test-Path $javac)) { throw "javac not found at $javac" }
if (-not (Test-Path $jarExe)) { throw "jar not found at $jarExe" }
if (-not (Test-IdeaRoot $idea)) { throw "Not a usable IntelliJ IDEA installation (no jars in lib): $idea" }

Write-Host "IDEA : $idea"
Write-Host "JDK  : $jdk"

if (Test-Path $classesDir) { Remove-Item $classesDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classesDir, $distDir | Out-Null

# Platform classes live in lib/*.jar (and lib/modules/*.jar on IDEA 2024.1+).
$classpath = @(
    (Join-Path (Join-Path $idea 'lib') '*'),
    (Join-Path (Join-Path $idea 'lib') 'modules/*')
) -join $pathSeparator

$sources = @(Get-ChildItem $srcDir -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
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
Write-Host "Install : IntelliJ IDEA > Settings > Plugins > gear icon > Install Plugin from Disk..."
Write-Host "Configure: Settings > Tools > AI Commit Message"
