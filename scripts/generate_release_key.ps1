param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [string]$Keytool = "keytool"
)

$ErrorActionPreference = "Stop"

function New-RandomSecret {
    $bytes = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    } finally {
        $generator.Dispose()
    }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null

$keystorePath = [IO.Path]::Combine($outputRoot, "lt-phone-public-release.jks")
$credentialsPath = [IO.Path]::Combine($outputRoot, "lt-phone-public-release.properties")

if ([IO.File]::Exists($keystorePath) -or [IO.File]::Exists($credentialsPath)) {
    throw "Refusing to overwrite an existing public release key or credentials file."
}

$storePassword = New-RandomSecret
$keyPassword = New-RandomSecret
$alias = "lt-phone-public"

$env:LT_PUBLIC_STORE_PASS = $storePassword
$env:LT_PUBLIC_KEY_PASS = $keyPassword
try {
    & $Keytool -genkeypair `
        -keystore $keystorePath `
        -storetype JKS `
        -storepass:env LT_PUBLIC_STORE_PASS `
        -keypass:env LT_PUBLIC_KEY_PASS `
        -alias $alias `
        -keyalg RSA `
        -keysize 4096 `
        -validity 10000 `
        -dname "CN=LT Phone Public Release, O=LT Phone" `
        -noprompt
    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed with exit code $LASTEXITCODE"
    }
} finally {
    Remove-Item Env:LT_PUBLIC_STORE_PASS -ErrorAction SilentlyContinue
    Remove-Item Env:LT_PUBLIC_KEY_PASS -ErrorAction SilentlyContinue
}

$propertyLines = @(
    "LT_KEYSTORE=$($keystorePath.Replace('\', '/'))",
    "LT_STORE_PASS=$storePassword",
    "LT_KEY_ALIAS=$alias",
    "LT_KEY_PASS=$keyPassword"
)
[IO.File]::WriteAllText(
    $credentialsPath,
    (($propertyLines -join "`n") + "`n"),
    [Text.UTF8Encoding]::new($false)
)

if ($IsWindows -or $env:OS -eq "Windows_NT") {
    & icacls.exe $keystorePath /inheritance:r /grant:r "${env:USERNAME}:(R,W)" | Out-Null
    & icacls.exe $credentialsPath /inheritance:r /grant:r "${env:USERNAME}:(R,W)" | Out-Null
}

Write-Output "Created public release keystore: $keystorePath"
Write-Output "Created local credentials file: $credentialsPath"
Write-Output "No password values were printed."
