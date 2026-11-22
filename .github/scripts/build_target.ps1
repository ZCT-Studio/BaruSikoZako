# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio

param(
    [Parameter(Mandatory)][string] $Target,
    [Parameter(Mandatory)][string] $Plan,
    [Parameter(Mandatory)][string] $Output
)

$ErrorActionPreference = 'Stop'
$ErrorView = 'NormalView'
$jobs = (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json).build_jobs
$job = @($jobs | Where-Object { $_.target -ceq $Target })
if ($job.Length -ne 1) { throw "No unique build plan for $Target" }
$job = $job[0]
$game = $job.game
$loader = $job.loader
if ($env:JAVA_HOME_17_X64) { $env:JAVA17_HOME = $env:JAVA_HOME_17_X64 }
if ($env:JAVA_HOME_25_X64) { $env:JAVA25_HOME = $env:JAVA_HOME_25_X64 }
$flags = @('--console=plain', '-PstrictReleaseNotices=true', '-Porg.gradle.java.installations.fromEnv=JAVA17_HOME,JAVA21_HOME,JAVA25_HOME', "-PgameVersion=$game")
switch ($loader) {
    'agent' { $flags += @('-PjavaAgent=true', '-Ploaders=agent') }
    { $_ -in 'fabric','quilt' } { $flags += @("-Ploaders=$loader", "-PdistributionLoader=$loader") }
    { $_ -in 'forge','neoforge' } {
        $targets = Get-Content 'gradle/compatibility-targets.properties' -Raw | ConvertFrom-StringData
        $version = $targets["fml.runtime.$loader.$game"]
        $checksum = $targets["fml.installer.sha256.$loader.$game"]
        if (!$version -or $checksum -notmatch '^[0-9a-f]{64}$') {
            throw "Missing pinned installer for $loader $game"
        }
        $repo = if ($loader -eq 'forge') {
            'https://maven.minecraftforge.net/net/minecraftforge/forge'
        } else {
            'https://maven.neoforged.net/releases/net/neoforged/neoforge'
        }
        $runtime = New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) "$Target-$([guid]::NewGuid())")
        $filename = "$loader-$version-installer.jar"
        $installer = Join-Path $runtime.FullName $filename
        curl --fail --location --retry 3 --silent --show-error --user-agent 'Mozilla/5.0 (GitHub Actions)' --output $installer "$repo/$version/$filename"
        if ($LASTEXITCODE -ne 0) { throw "Could not download the official $loader $version installer" }
        if ((Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash -ine $checksum) {
            throw "SHA-256 mismatch for $filename"
        }
        $patched = if ($loader -eq 'forge') {
            "libraries/net/minecraftforge/forge/$version/forge-$version-server.jar"
        } elseif ($game -eq '1.21.1') {
            "libraries/net/neoforged/neoforge/$version/neoforge-$version-server.jar"
        } else {
            "libraries/net/neoforged/minecraft-server-patched/$version/minecraft-server-patched-$version.jar"
        }
        Push-Location -LiteralPath $runtime.FullName
        try {
            for ($attempt = 1; $attempt -le 3; $attempt++) {
                & java '-Djava.awt.headless=true' -jar $installer --installServer
                if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $patched -PathType Leaf)) { break }
                if ($attempt -eq 3) {
                    throw "Failed to prepare $patched with the official $loader $version installer"
                }
                Write-Warning "Retrying the $loader $version installer after attempt $attempt failed"
            }
        } finally {
            Pop-Location
        }
        $flags += @("-Ploaders=$loader", "-P${loader}26ServerDir=$($runtime.FullName)")
    }
    default { throw "Unsupported build target: $loader" }
}
$wrapper = if ($IsWindows) { './gradlew.bat' } else { './gradlew' }
if (!$IsWindows) { chmod +x $wrapper }
$tasks = @('buildProducts', ':engine:core:check', ':engine:gitignore:check', ':backends:zstd:check', ':api:host:check')
if ($loader -eq 'agent') { $tasks += ':platforms:javaagent:check' }
# 仅对仓库限流重试，普通编译和测试失败直接返回
$PSNativeCommandUseErrorActionPreference = $false
for ($attempt = 1; $attempt -le 3; $attempt++) {
    $rateLimited = $false
    & $wrapper @flags @tasks 2>&1 | ForEach-Object {
        if ("$_" -match 'Received status code 429 from server|HTTP(?: status)?[ :]+429\b') {
            $rateLimited = $true
        }
        Write-Output $_
    }
    $buildExit = $LASTEXITCODE
    if ($buildExit -eq 0) { break }
    if (!$rateLimited -or $attempt -eq 3) { exit $buildExit }
    $delay = 20 * $attempt + (Get-Random -Minimum 0 -Maximum 11)
    Write-Warning "Gradle dependency download was rate limited. Retrying in $delay seconds."
    Start-Sleep -Seconds $delay
}

foreach ($asset in $job.assets) {
    foreach ($name in $asset.paths) {
        $source = Join-Path 'build_product' $name
        $destination = Join-Path $Output $name
        New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination
    }
}
