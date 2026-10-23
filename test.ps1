#requires -Version 7
<#
.SYNOPSIS
    Compiles and runs the offline regression tests for the plugin.

.DESCRIPTION
    The tests need no IDE and no network: they compile against the plugin classes plus the
    local IntelliJ Platform jars, then exercise the parts that are easy to get wrong
    (JSON parsing, response extraction, commit message shaping and the adaptive budget).

.EXAMPLE
    pwsh -File test.ps1
#>
[CmdletBinding()]
param(
    [string]$IdeaHome = 'D:\app\idea\IntelliJ IDEA',
    [string]$JdkHome = 'C:\Program Files\Zulu\zulu-25'
)

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$classesDir = Join-Path $root 'build\classes'
$testSrcDir = Join-Path $root 'test\java'
$testOutDir = Join-Path $root 'build\test-classes'

$javac = Join-Path $JdkHome 'bin\javac.exe'
$java = Join-Path $JdkHome 'bin\java.exe'

if (-not (Test-Path $javac)) { throw "javac not found: $javac (pass -JdkHome <JDK 17+>)" }
if (-not (Test-Path $classesDir)) { throw "Plugin classes not built yet. Run build.ps1 first." }

$classpath = @(
    $classesDir,
    (Join-Path $IdeaHome 'lib\*'),
    (Join-Path $IdeaHome 'lib\modules\*')
) -join ';'

New-Item -ItemType Directory -Force -Path $testOutDir | Out-Null
$sources = @(Get-ChildItem $testSrcDir -Recurse -Filter *.java | Select-Object -ExpandProperty FullName)
if ($sources.Count -eq 0) { throw "No test sources found under $testSrcDir" }

Write-Host "Compiling $($sources.Count) test file(s)..."
& $javac -encoding UTF-8 -nowarn -d $testOutDir -cp $classpath @sources
if ($LASTEXITCODE -ne 0) { throw "Test compilation failed." }

$runCp = "$classpath;$testOutDir"
$failed = 0
foreach ($test in @('FormatterSmokeTest', 'JsonSmokeTest')) {
    Write-Host ""
    Write-Host "=== $test ==="
    & $java "-Dfile.encoding=UTF-8" -cp $runCp $test
    if ($LASTEXITCODE -ne 0) { $failed++ }
}

Write-Host ""
if ($failed -eq 0) {
    Write-Host "All test suites passed." -ForegroundColor Green
} else {
    throw "$failed test suite(s) failed."
}
