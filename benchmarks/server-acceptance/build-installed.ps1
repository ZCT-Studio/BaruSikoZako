# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio

param(
    [Parameter(Mandatory)][string] $RuntimeCache,
    [Parameter(Mandatory)][string[]] $Targets,
    [string] $ProductVersion = '0.1.0-dev'
)

$ErrorActionPreference = 'Stop'
$runtime = (Resolve-Path -LiteralPath $RuntimeCache).Path
$built = @{}
foreach ($target in $Targets) {
    if ($target -notmatch '^(fabric|quilt|forge|neoforge)-(26\.(1\.2|2|3)|1\.(20\.1|21\.(1|10|11)))$') {
        throw "Unknown installed-server target $target"
    }
    $loader = $Matches[1]
    $game = $Matches[2]
    $flags = @("--console=plain", "-PproductVersion=$ProductVersion", "-PstrictReleaseNotices=true")
    if ($game.StartsWith('1.') -and $loader -in @('fabric','quilt')) {
        $key = "fabric-$game"
        $flags += @("-PfabricLegacyMinecraft=$game", ':platform-fabric-pre26:assemble', ':public-api:apiModJar')
    } elseif ($loader -in @('fabric','quilt')) {
        $key = "fabric-$game"
        $flags += @('-Pfabric26=true', "-PfabricRuntimeMinecraft=$game",
                    ':platform-fabric:assemble', ':public-api:apiModJar')
    } else {
        $key = $target
        $install = if ($game.StartsWith('1.')) {
            Join-Path $runtime "pre26-runtime/$target"
        } elseif ($game -eq '26.3') {
            Join-Path $runtime "loader26-smoke/$loader"
        } else {
            Join-Path $runtime "fml-range/$target"
        }
        $flags += @('-Pfml26=true', "-PfmlRuntimeMinecraft=$game",
                    "-P${loader}26ServerDir=$install", ":platform-fml26:${loader}Dist")
    }
    if ($built.ContainsKey($key)) { continue }
    & .\gradlew.bat @flags
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $built[$key] = $true
}
