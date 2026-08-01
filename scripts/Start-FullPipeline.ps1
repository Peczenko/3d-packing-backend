<#
.SYNOPSIS
    Starts the complete local API, storage, database, and messaging pipeline.

.DESCRIPTION
    Enables packing messaging in the repository .env file, configures the API container
    to address the Service Bus emulator by its Compose service name, then starts the app
    and messaging profiles. Existing unrelated .env entries are preserved.

.PARAMETER EnvFile
    Overrides the .env path. Relative paths are resolved from the repository root.

.PARAMETER ConfigureOnly
    Updates the .env file without starting Docker Compose.

.EXAMPLE
    .\scripts\Start-FullPipeline.ps1
#>
[CmdletBinding()]
param(
    [string] $EnvFile,
    [switch] $ConfigureOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot '.env'
}
elseif (-not [IO.Path]::IsPathRooted($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot $EnvFile
}

function Set-DotEnvValue {
    param(
        [Parameter(Mandatory)]
        [string] $Path,
        [Parameter(Mandatory)]
        [string] $Name,
        [Parameter(Mandatory)]
        [string] $Value
    )

    $lines = if (Test-Path -LiteralPath $Path) {
        [IO.File]::ReadAllLines($Path)
    }
    else {
        @()
    }

    $updated = [Collections.Generic.List[string]]::new()
    $pattern = '^\s*' + [Regex]::Escape($Name) + '\s*='
    $written = $false

    foreach ($line in $lines) {
        if ($line -match $pattern) {
            if (-not $written) {
                $updated.Add("$Name=$Value")
                $written = $true
            }
            continue
        }
        $updated.Add($line)
    }

    if (-not $written) {
        $updated.Add("$Name=$Value")
    }

    [IO.File]::WriteAllLines($Path, $updated, [Text.UTF8Encoding]::new($false))
}

$emulatorConnectionString = 'Endpoint=sb://servicebus;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;'
Set-DotEnvValue -Path $EnvFile -Name 'PACKING_MESSAGING_ENABLED' -Value 'true'
Set-DotEnvValue -Path $EnvFile -Name 'SERVICE_BUS_CONNECTION_STRING' -Value $emulatorConnectionString
$env:PACKING_MESSAGING_ENABLED = 'true'
$env:SERVICE_BUS_CONNECTION_STRING = $emulatorConnectionString

Write-Host "Configured packing messaging in $EnvFile" -ForegroundColor Green

if (-not $ConfigureOnly) {
    Push-Location $repositoryRoot
    try {
        & docker compose --profile messaging up -d --build --wait servicebus-sql servicebus
        if ($LASTEXITCODE -ne 0) {
            throw "Service Bus startup failed with exit code $LASTEXITCODE."
        }

        $wrapperName = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { 'gradlew.bat' } else { 'gradlew' }
        & (Join-Path $repositoryRoot $wrapperName) :app:bootJar
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle bootJar failed with exit code $LASTEXITCODE."
        }

        & docker compose --profile app up -d --build app
        if ($LASTEXITCODE -ne 0) {
            throw "Application startup failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}
