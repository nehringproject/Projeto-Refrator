param(
    [Parameter(Mandatory = $true)]
    [string]$AndroidSdkRoot,
    [string]$DevApk = "app\build\outputs\apk\dev\debug\app-dev-debug.apk",
    [string]$PublicApk = "app\build\outputs\apk\public\release\app-public-release.apk"
)

$ErrorActionPreference = "Stop"
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    throw "JAVA_HOME must point to a JDK before running the APK analyzer."
}
$analyzer = Join-Path $AndroidSdkRoot "cmdline-tools\latest\bin\apkanalyzer.bat"
if (-not (Test-Path -LiteralPath $analyzer)) {
    throw "apkanalyzer not found at $analyzer"
}
foreach ($apk in @($DevApk, $PublicApk)) {
    if (-not (Test-Path -LiteralPath $apk)) {
        $unsigned = $apk -replace '\.apk$', '-unsigned.apk'
        if ($apk -eq $PublicApk -and (Test-Path -LiteralPath $unsigned)) {
            $PublicApk = $unsigned
        } else {
            throw "APK not found: $apk"
        }
    }
}

function Read-Apk([string]$apk) {
    $manifest = (& $analyzer manifest print $apk) -join "`n"
    $dex = (& $analyzer dex packages $apk) -join "`n"
    $files = (& $analyzer files list $apk) -join "`n"
    [pscustomobject]@{
        Path = $apk
        Package = ((& $analyzer manifest application-id $apk) -join "").Trim()
        Manifest = $manifest
        Dex = $dex
        Files = $files
        Sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
    }
}

$dev = Read-Apk $DevApk
$public = Read-Apk $PublicApk
$checks = [ordered]@{
    DevPackagePreservesData = $dev.Package -eq "dev.agentworkbench.power"
    PublicPackageIsFinal = $public.Package -eq "com.nehringproject.refrator"
    DevContainsExecutionService = $dev.Manifest -match "AgentExecutionService"
    PublicContainsExecutionService = $public.Manifest -match "AgentExecutionService"
    DevContainsPython = $dev.Manifest -match "PythonRuntimeService" -and $dev.Files -match "libpython3\.13\.so"
    PublicContainsPython = $public.Manifest -match "PythonRuntimeService" -and $public.Files -match "libpython3\.13\.so"
    DevContainsLiteLlm = $dev.Manifest -match "LiteLlmRuntimeService"
    PublicContainsLiteLlm = $public.Manifest -match "LiteLlmRuntimeService"
    PublicIsNotDebuggable = $public.Manifest -notmatch 'android:debuggable="true"'
    PublicHasNoLegacyStandard = $public.Dex -notmatch "DistributionProfile\$Companion\.standard"
    PublicIsArm64Only = $public.Files -match "lib/arm64-v8a/" -and $public.Files -notmatch "lib/x86_64/"
}

$failed = @($checks.GetEnumerator() | Where-Object { -not $_.Value })
$checks.GetEnumerator() | ForEach-Object {
    "{0}: {1}" -f $_.Key, $(if ($_.Value) { "PASS" } else { "FAIL" })
}
"Dev SHA-256: $($dev.Sha256)"
"Public SHA-256: $($public.Sha256)"
if ($failed.Count -gt 0) { throw "Release boundary failed: $($failed.Key -join ', ')" }
