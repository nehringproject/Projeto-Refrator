param([string]$Apk)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    $required = @(
        "LICENSE",
        "NOTICE",
        "THIRD_PARTY_NOTICES.md",
        "PRIVACY.md",
        "SECURITY.md",
        "local-llm/LICENSE.arm-ai-chat",
        "third_party/llama.cpp/LICENSE"
    )
    $missing = @($required | Where-Object { -not (Test-Path -LiteralPath $_) })
    if ($missing) { throw "Required publication files are missing: $($missing -join ', ')" }

    $publishable = @(git ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw "git ls-files failed." }
    $forbiddenNames = @($publishable | Where-Object {
        $_ -match '(^|/)(local\.properties|work|outputs|build)(/|$)' -or
        $_ -match '\.(apk|aab|apks|idsig|jks|keystore|p12|pfx|pem|hprof|log|pid)$'
    })
    if ($forbiddenNames) { throw "Forbidden generated or private files are tracked: $($forbiddenNames -join ', ')" }

    $textFiles = @($publishable | Where-Object {
        $_ -match '\.(kt|kts|java|xml|json|md|txt|properties|toml|ya?ml|py|c|cc|cpp|h|cmake|gradle|ps1|sh)$' -and
        $_ -notmatch '^third_party/'
    })
    $secretPattern = '(gsk_|nvapi-|kn-)[A-Za-z0-9_-]{12,}|sk-[A-Za-z0-9_-]{20,}|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY'
    $personalPattern = 'C:\\Users\\[A-Za-z0-9._-]+|/Users/[A-Za-z0-9._-]+|/home/[A-Za-z0-9._-]+'
    $violations = [System.Collections.Generic.List[string]]::new()
    foreach ($file in $textFiles) {
        if (-not (Test-Path -LiteralPath $file)) { continue }
        $body = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $file))
        if ($file -eq "app/src/test/kotlin/dev/agentworkbench/RemoteContextRedactorTest.kt") {
            $body = $body.Replace("gsk_TEST_FIXTURE_NOT_A_CREDENTIAL_000000", "<fixture>")
            $body = [regex]::Replace(
                $body,
                '-----BEGIN PRIVATE KEY-----[\s\S]*?-----END PRIVATE KEY-----',
                '<synthetic-private-key-fixture>'
            )
        }
        if ($body -match $secretPattern -and $file -notmatch 'RemoteContextRedactorTest|audit-public-source') {
            $violations.Add("credential-like value: $file")
        }
        if ($body -match $personalPattern) { $violations.Add("developer path: $file") }
    }
    if ($violations.Count) { throw ($violations -join [Environment]::NewLine) }

    if ($Apk) {
        if (-not (Test-Path -LiteralPath $Apk)) { throw "APK not found: $Apk" }
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Apk))
        try {
            $pathLeaks = [System.Collections.Generic.List[string]]::new()
            foreach ($entry in $archive.Entries) {
                if ($entry.Length -eq 0 -or $entry.Length -gt 256MB) { continue }
                $stream = $entry.Open()
                try {
                    $reader = [IO.BinaryReader]::new($stream)
                    $ascii = [Text.Encoding]::ASCII.GetString($reader.ReadBytes([int]$entry.Length))
                    # Block local Windows/macOS developer identities. Common
                    # upstream CI home paths found in signed dependencies
                    # are provenance strings in signed third-party binaries,
                    # not private data owned by this project.
                    if ($ascii -match '(?i)(?:C:[/\\]Users[/\\][A-Za-z0-9._-]{1,64}[/\\]|/Users/[A-Za-z0-9._-]{1,64}/)') {
                        $pathLeaks.Add($entry.FullName)
                    }
                } finally {
                    $stream.Dispose()
                }
            }
            if ($pathLeaks.Count) {
                throw "APK contains absolute developer paths in: $($pathLeaks -join ', ')"
            }
        } finally {
            $archive.Dispose()
        }
    }

    "Public source audit: PASS"
    "Publishable files: $($publishable.Count)"
} finally {
    Pop-Location
}
