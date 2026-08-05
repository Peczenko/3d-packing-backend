<#
.SYNOPSIS
    End-to-end smoke test of the packing pipeline: create a job, let a worker pack it,
    download the result.

.DESCRIPTION
    Creates a packing job through the API, optionally starts the one-shot workers, polls
    the job until it reaches a terminal state, then follows the result redirect and asserts
    the downloaded bytes are non-empty.

    The workers are started *after* the POST on purpose. Each container takes at most one
    dispatch and exits, so a worker started before the job exists spends its whole receive
    window on an empty queue.

    Neither the token nor the project id has a default. Both are secrets of a sort — the
    token is a bearer credential, the project id names a resource the caller has access to —
    and this file is committed.

.PARAMETER ApiBaseUrl
    Backend base URL. Defaults to the Compose-published port.

.PARAMETER FirebaseToken
    A Firebase ID token for a user who is already a member of -ProjectId with WRITE.
    Obtain one with scripts\Get-FirebaseToken.ps1.

.PARAMETER ProjectId
    An existing project's id. The job is created under it, and a non-member deliberately
    gets the same 404 as a project that does not exist.

.PARAMETER StartWorkers
    Run `docker compose --profile packing up` with five workers straight after the POST.
    Omit if workers are already running or are started by hand.

.EXAMPLE
    .\scripts\Test-PackingPipeline.ps1 -FirebaseToken $env:ID_TOKEN -ProjectId $projectId -StartWorkers
#>
[CmdletBinding()]
param(
    [string] $ApiBaseUrl = 'http://localhost:8080',

    [Parameter(Mandatory)]
    [string] $FirebaseToken,

    [Parameter(Mandatory)]
    [string] $ProjectId,

    [switch] $StartWorkers
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# [CmdletBinding()] makes -Debug a valid parameter, and -Debug makes the web cmdlets write
# every request header to the debug stream — including "Authorization: Bearer <id token>",
# and including the SAS query string on the download. Someone debugging a 401 under
# Start-Transcript would write a live credential to disk. -Debug sets this preference in
# script scope before the body runs, so assigning it here is what overrides it.
$DebugPreference = 'SilentlyContinue'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$pollIntervalSeconds = 2
$pollTimeoutSeconds = 90
$workerCount = 5

$headers = @{ Authorization = "Bearer $FirebaseToken" }
$jobsUrl = "$ApiBaseUrl/api/v1/projects/$ProjectId/packing-jobs"

# ConvertTo-Json defaults to a depth of 2, which silently renders a nested object as its
# type name. The spec is one level deep here, but Depth is set so a richer spec does not
# quietly become "System.Collections.Hashtable".
$body = @{
    maxRuntimeSeconds = 60
    spec              = @{ testField = 'smoke' }
} | ConvertTo-Json -Depth 10 -Compress

Write-Host "POST $jobsUrl" -ForegroundColor Cyan
$created = Invoke-RestMethod -Method Post -Uri $jobsUrl -Headers $headers `
                             -ContentType 'application/json; charset=utf-8' -Body $body
$jobId = $created.id
Write-Host "  job $jobId is $($created.status)" -ForegroundColor Green

if ($StartWorkers) {
    Write-Host "Starting $workerCount workers ..." -ForegroundColor Cyan
    Push-Location $repositoryRoot
    try {
        # --no-deps and the explicit service are load-bearing, not tidiness. The
        # servicebus service builds from a dockerfile_inline, so a bare --build
        # rebuilds the emulator image, and Compose then recreates the container —
        # which drops every message the broker is holding, including the dispatch
        # this run just created. Scoping the build to the worker keeps the broker
        # and its queue contents alone.
        & docker compose --profile packing up -d --build --no-deps `
            --scale "packing-worker=$workerCount" packing-worker
        if ($LASTEXITCODE -ne 0) {
            throw "Worker startup failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}

Write-Host "Polling $jobsUrl/$jobId every ${pollIntervalSeconds}s for up to ${pollTimeoutSeconds}s ..." -ForegroundColor Cyan
$deadline = [DateTimeOffset]::UtcNow.AddSeconds($pollTimeoutSeconds)
$job = $null
$lastStatus = ''

while ([DateTimeOffset]::UtcNow -lt $deadline) {
    $job = Invoke-RestMethod -Method Get -Uri "$jobsUrl/$jobId" -Headers $headers
    if ($job.status -ne $lastStatus) {
        Write-Host "  $($job.status)"
        $lastStatus = $job.status
    }
    if ($job.status -eq 'FAILED') {
        throw "Packing job $jobId FAILED: $($job.failureReason)"
    }
    if ($job.status -eq 'SUCCEEDED') {
        break
    }
    Start-Sleep -Seconds $pollIntervalSeconds
}

if ($null -eq $job -or $job.status -ne 'SUCCEEDED') {
    $observed = if ($null -eq $job) { '(never polled)' } else { $job.status }
    throw "Packing job $jobId did not succeed within ${pollTimeoutSeconds}s; last status $observed."
}

Write-Host "  engine   : $($job.engineVersion)"
Write-Host "  result   : $($job.resultFileName) ($($job.resultSizeBytes) bytes, $($job.resultContentType))"
Write-Host "  checksum : $($job.resultChecksum)"

# The endpoint answers 302 to a short-lived SAS URL, and the 302 itself is the assertion.
# HttpClient rather than Invoke-WebRequest, because -MaximumRedirection 0 raises the
# redirect as a terminating error instead of handing back the response, and
# -SkipHttpErrorCheck does not cover it.
Write-Host "GET $jobsUrl/$jobId/result" -ForegroundColor Cyan
$handler = [Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$client = [Net.Http.HttpClient]::new($handler)
try {
    $client.DefaultRequestHeaders.Authorization =
        [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $FirebaseToken)
    $redirect = $client.GetAsync("$jobsUrl/$jobId/result").GetAwaiter().GetResult()
    $statusCode = [int]$redirect.StatusCode
    if ($statusCode -ne 302) {
        throw "Expected 302 from the result endpoint, got $statusCode."
    }
    $downloadUrl = $redirect.Headers.Location
}
finally {
    $client.Dispose()
    $handler.Dispose()
}
if ($null -eq $downloadUrl) {
    throw 'The result endpoint returned 302 with no Location header.'
}

# Against the local stack the API mints a SAS URL on http://azurite:10000 — the Compose
# service name, which resolves inside that network and nowhere else. The port is published
# on the host, and the signature covers the account and blob path rather than the
# authority, so swapping only the host leaves a genuine SAS URL. Resolvability is probed
# rather than inferred from an exception message, which is localised.
$downloadUri = [Uri] $downloadUrl
try {
    [Net.Dns]::GetHostEntry($downloadUri.Host) | Out-Null
}
catch {
    $rewritten = [UriBuilder]::new($downloadUri)
    $rewritten.Host = 'localhost'
    Write-Host "  $($downloadUri.Host) does not resolve here; using localhost:$($downloadUri.Port)" -ForegroundColor DarkGray
    $downloadUri = $rewritten.Uri
}

# Deliberately without $headers: the SAS URL carries its own credential, is on another
# host, and the Firebase token has no business being sent there.
$outputPath = Join-Path ([IO.Path]::GetTempPath()) "packing-result-$jobId.bin"
Invoke-WebRequest -Method Get -Uri $downloadUri -OutFile $outputPath | Out-Null
$downloaded = Get-Item -LiteralPath $outputPath
if ($downloaded.Length -le 0) {
    throw "Downloaded result $outputPath is empty."
}

# Not merely non-empty: these are the size and checksum the worker computed over the bytes
# it uploaded, carried across the wire in the succeeded event and persisted by the backend.
# Checking the download against both is what turns "something arrived" into "the bytes the
# packer produced arrived", and it is the one assertion that spans every hop at once.
if ($downloaded.Length -ne $job.resultSizeBytes) {
    throw "Downloaded $($downloaded.Length) bytes, but the job reports $($job.resultSizeBytes)."
}
# Get-FileHash returns upper-case hex and the backend stores lower-case, so both sides are
# normalised rather than left to PowerShell's case-insensitive -ne, which is easy to read as
# a case-sensitive comparison that happens to pass.
$actualChecksum = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash.ToLowerInvariant()
$expectedChecksum = "$($job.resultChecksum)".ToLowerInvariant()
if ($actualChecksum -cne $expectedChecksum) {
    throw "Downloaded result hashes to $actualChecksum, but the job reports $expectedChecksum."
}

Write-Host ''
Write-Host "PASS  job $jobId  ->  $outputPath" -ForegroundColor Green
Write-Host "      $($downloaded.Length) bytes, sha256 $actualChecksum — matches the job record" -ForegroundColor Green
