param([string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path)

$base = Join-Path $RepoRoot 'app/src/main/java/io/github/offlineglass/hook/adapters'
$specFiles = Get-ChildItem -LiteralPath $base -Recurse -Filter 'TargetSpec.kt' -File
$keys = @()
foreach ($file in $specFiles) {
    $key = $file.Directory.Name
    $text = Get-Content -LiteralPath $file.FullName -Raw -Encoding utf8
    if ($text -notmatch ('TargetSpec\("' + [regex]::Escape($key) + '"')) {
        throw "TargetSpec key and directory disagree: $($file.FullName)"
    }
    $keys += $key
}
if ($keys.Count -eq 0 -or ($keys | Sort-Object -Unique).Count -ne $keys.Count) {
    throw "Expected unique app directories, found $($keys.Count)."
}

$registry = Get-Content -LiteralPath (Join-Path $RepoRoot 'app/src/main/java/io/github/offlineglass/targets/TargetSpecRegistry.kt') -Raw -Encoding utf8
$registryKeys = [regex]::Matches($registry, 'hook\.adapters\.([a-z0-9_]+)\.targetSpec') |
    ForEach-Object { $_.Groups[1].Value }
$sortedRegistryKeys = ($registryKeys | Sort-Object) -join ','
$sortedDirectoryKeys = ($keys | Sort-Object) -join ','
if ($sortedRegistryKeys -ne $sortedDirectoryKeys) {
    throw 'TargetSpecRegistry and app directories disagree.'
}

$signalRegistry = Get-Content -LiteralPath (Join-Path $base 'TargetHookSignalsRegistry.kt') -Raw -Encoding utf8
$signalKeys = [regex]::Matches($signalRegistry, 'hook\.adapters\.([a-z0-9_]+)\.hookSignals') |
    ForEach-Object { $_.Groups[1].Value }
foreach ($key in $signalKeys) {
    if (-not (Test-Path -LiteralPath (Join-Path $base "$key/HookSignals.kt"))) {
        throw "Missing HookSignals.kt for $key"
    }
}
if (($signalKeys | Sort-Object -Unique).Count -ne $signalKeys.Count) {
    throw 'Duplicate hook-signal keys.'
}

$catalog = Get-Content -LiteralPath (Join-Path $RepoRoot 'app/src/main/java/io/github/offlineglass/targets/AppCatalog.kt') -Raw -Encoding utf8
$archiveBlock = [regex]::Match($catalog, 'archivedKeys\s*=\s*setOf\((.*?)\)', [System.Text.RegularExpressions.RegexOptions]::Singleline)
if (-not $archiveBlock.Success) {
    throw 'Could not read AppCatalog archived keys.'
}
$archivedKeys = [regex]::Matches($archiveBlock.Groups[1].Value, '"([a-z0-9_]+)"') |
    ForEach-Object { $_.Groups[1].Value }
$activeKeys = $registryKeys | Where-Object { $_ -notin $archivedKeys }
$expectedPackages = @()
foreach ($key in $activeKeys) {
    $targetSpec = Join-Path $base "$key/TargetSpec.kt"
    $targetText = Get-Content -LiteralPath $targetSpec -Raw -Encoding utf8
    $packageMatch = [regex]::Match($targetText, 'TargetSpec\("' + [regex]::Escape($key) + '",\s*"([^"]+)"')
    if (-not $packageMatch.Success) {
        throw "Could not read target package for $key."
    }
    $expectedPackages += $packageMatch.Groups[1].Value
}
$scopeFile = Join-Path $RepoRoot 'app/src/main/assets/xposed_scope'
$scopePackages = @(Get-Content -LiteralPath $scopeFile -Encoding utf8 | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() })
if (@($expectedPackages | Sort-Object -Unique).Count -ne $expectedPackages.Count) {
    throw 'Duplicate target package names.'
}
$missingPackages = @($expectedPackages | Where-Object { $_ -notin $scopePackages })
$extraPackages = @($scopePackages | Where-Object { $_ -notin $expectedPackages })
$duplicatePackages = @($scopePackages | Group-Object | Where-Object Count -gt 1)
if ($missingPackages.Count -or $extraPackages.Count -or $duplicatePackages.Count) {
    throw "LSPosed scope mismatch. Missing=[$($missingPackages -join ',')], extra=[$($extraPackages -join ',')], duplicates=[$(($duplicatePackages | ForEach-Object Name) -join ',')]."
}
Write-Output "PASS: $($keys.Count) app directories, $($activeKeys.Count) active apps in scope, $($signalKeys.Count) app-owned hook signal sets."
