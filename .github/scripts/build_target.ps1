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

# 下载并安装官方 FML 服务端，返回 -P<loader>26ServerDir 参数
function Install-FmlRuntime {
    param([string] $Loader, [string] $Game)
    $targets = Get-Content 'gradle/compatibility-targets.properties' -Raw | ConvertFrom-StringData
    $version = $targets["fml.runtime.$Loader.$Game"]
    if (!$version) { throw "Missing installer version for $Loader $Game" }
    $repo = if ($Loader -eq 'forge') {
        'https://maven.minecraftforge.net/net/minecraftforge/forge'
    } else {
        'https://maven.neoforged.net/releases/net/neoforged/neoforge'
    }
    $runtime = New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) "$Loader-$([guid]::NewGuid())")
    $filename = "$Loader-$version-installer.jar"
    $installer = Join-Path $runtime.FullName $filename
    curl --fail --location --retry 3 --silent --show-error --user-agent 'Mozilla/5.0 (GitHub Actions)' --output $installer "$repo/$version/$filename"
    if ($LASTEXITCODE -ne 0) { throw "Could not download the official $Loader $version installer" }
    $patched = if ($Loader -eq 'forge') {
        "libraries/net/minecraftforge/forge/$version/forge-$version-server.jar"
    } elseif ($Game -eq '1.21.1') {
        "libraries/net/neoforged/neoforge/$version/neoforge-$version-server.jar"
    } else {
        "libraries/net/neoforged/minecraft-server-patched/$version/minecraft-server-patched-$version.jar"
    }
    Push-Location -LiteralPath $runtime.FullName
    # 安装器日志只输出到控制台，不能混入函数返回值；stderr 也不应当作错误
    $PSNativeCommandUseErrorActionPreference = $false
    try {
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            & java '-Djava.awt.headless=true' -jar $installer --installServer 2>&1 | Out-Host
            if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $patched -PathType Leaf)) { break }
            if ($attempt -eq 3) {
                throw "Failed to prepare $patched with the official $Loader $version installer"
            }
            Write-Warning "Retrying the $Loader $version installer after attempt $attempt failed"
        }
    } finally {
        Pop-Location
    }
    return "-P${Loader}26ServerDir=$($runtime.FullName)"
}

$flags = @('--console=plain', '-PstrictReleaseNotices=true', '-Porg.gradle.java.installations.fromEnv=JAVA17_HOME,JAVA21_HOME,JAVA25_HOME', "-PgameVersion=$game")
switch ($loader) {
    'agent' { $flags += @('-PjavaAgent=true', '-Ploaders=agent') }
    { $_ -in 'fabric','quilt' } { $flags += @("-Ploaders=$loader", "-PdistributionLoader=$loader") }
    { $_ -in 'forge','neoforge' } { $flags += @("-Ploaders=$loader", (Install-FmlRuntime $loader $game)) }
    'universal' {
        $platforms = @($job.platforms)
        $flags += @('-PmergeJars=true', "-Ploaders=$($platforms -join ',')")
        foreach ($platform in $platforms) {
            if ($platform -in 'forge','neoforge') { $flags += (Install-FmlRuntime $platform $game) }
        }
    }
    default { throw "Unsupported build target: $loader" }
}
$wrapper = if ($IsWindows) { './gradlew.bat' } else { './gradlew' }
if (!$IsWindows) { chmod +x $wrapper }
$tasks = @('buildProducts', ':engine:core:check', ':engine:gitignore:check', ':backends:zstd:check', ':api:host:check')
if ($loader -eq 'agent') { $tasks += ':platforms:javaagent:check' }
if ($loader -eq 'universal') { $tasks += @('mergeJars', 'mergeApiJars') }
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
