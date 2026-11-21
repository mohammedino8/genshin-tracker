[CmdletBinding()]
param([switch]$AppImageOnly)

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') {
    throw 'Run this script on Windows with a Windows JDK 21.'
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a Windows JDK 21.' }
$jpackage = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
if (-not (Test-Path $jpackage)) { throw 'JAVA_HOME must point to a full JDK 21 containing jpackage.exe.' }
if (-not $AppImageOnly) {
    foreach ($command in @('candle.exe', 'light.exe')) {
        if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
            throw 'Install WiX Toolset 3.14 and add its bin directory to PATH, or pass -AppImageOnly.'
        }
    }
}

Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    $version = '1.0.0'
    & '.\mvnw.cmd' -B clean verify
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed ($LASTEXITCODE)." }

    $inputDir = (New-Item -ItemType Directory 'target\package-input').FullName
    $outputDir = (New-Item -ItemType Directory 'target\windows').FullName
    $icon = (Resolve-Path 'packaging\GenshinTracker.ico').Path
    $jar = "genshin-tracker-$version.jar"
    Copy-Item "target\$jar" $inputDir
    Copy-Item 'target\lib' $inputDir -Recurse
    $javafxJars = @(Get-ChildItem (Join-Path $inputDir 'lib') -Filter 'javafx-*-win.jar')
    if ($javafxJars.Count -ne 3) { throw 'Expected the Windows x64 JavaFX runtime libraries.' }
    $fxModulePath = ($javafxJars | ForEach-Object { '$APPDIR/lib/' + $_.Name }) -join ';'

    $imageArgs = @(
        '--type', 'app-image', '--name', 'Genshin Tracker', '--app-version', $version,
        '--input', $inputDir, '--main-jar', $jar, '--main-class', 'tracker.Main',
        '--icon', $icon, '--dest', $outputDir, '--vendor', 'Genshin Tracker',
        '--add-modules', 'java.se,jdk.unsupported,jdk.crypto.ec,jdk.localedata',
        '--java-options', "--module-path=$fxModulePath", '--java-options', '--add-modules=javafx.controls'
    )
    & $jpackage @imageArgs
    if ($LASTEXITCODE -ne 0) { throw "Windows app-image build failed ($LASTEXITCODE)." }
    if (-not $AppImageOnly) {
        $installerArgs = @(
            '--type', 'msi', '--name', 'Genshin Tracker', '--app-version', $version,
            '--app-image', (Join-Path $outputDir 'Genshin Tracker'), '--dest', $outputDir,
            '--vendor', 'Genshin Tracker', '--icon', $icon, '--win-menu',
            '--win-menu-group', 'Genshin Tracker', '--win-shortcut', '--win-per-user-install',
            '--win-upgrade-uuid', 'dc982af2-0631-40ae-9f7c-10f540d8555c'
        )
        & $jpackage @installerArgs
        if ($LASTEXITCODE -ne 0) { throw "Windows MSI build failed ($LASTEXITCODE)." }
    }
    Write-Output "Windows package output directory is $outputDir"
} finally {
    Pop-Location
}
