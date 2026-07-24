<#
.SYNOPSIS
    Obtains a Firebase ID token (JWT) for a test user, for calling this backend by hand.

.DESCRIPTION
    The backend never mints tokens — it only verifies them against Firebase's public JWKS.
    Tokens come from Firebase's Identity Toolkit REST API, which is what a real client SDK
    calls underneath:

        sign up  POST https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=API_KEY
        sign in  POST https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=API_KEY
        refresh  POST https://securetoken.googleapis.com/v1/token?key=API_KEY

    The `key` is the project's **Web API key**, not the service account. It is a public
    client identifier — it is embedded in every web/mobile app and is safe to share. It
    grants nothing on its own; the password still has to be right.

        Firebase Console -> Project settings -> General -> "Web API Key"
        or: firebase apps:sdkconfig WEB --project packing-3d-6a9e6

    Email/password sign-in must be enabled under Authentication -> Sign-in method, or every
    call returns OPERATION_NOT_ALLOWED.

    The ID token is the value for `Authorization: Bearer <token>`. It expires after one
    hour; the refresh token does not, so re-run with -RefreshToken to get a fresh one.

.PARAMETER Provision
    Also calls GET /api/v1/users/me on the backend. A Firebase account is not yet a user of
    this system: the local `users` row is created just-in-time on the first /me call. Until
    it exists the file endpoints reject the caller — and because an unknown owner and a
    deleted one deliberately return the same 404, a missing profile looks identical to a
    missing file. Provisioning up front removes that confusion.

.EXAMPLE
    # First run for a brand-new account: create it, provision the profile, export $env:ID_TOKEN
    .\scripts\Get-FirebaseToken.ps1 -Email dev@example.com -Password 'Passw0rd!' -SignUp -Provision -SetEnv

.EXAMPLE
    # Later runs
    .\scripts\Get-FirebaseToken.ps1 -Email dev@example.com -Password 'Passw0rd!' -SetEnv
    curl.exe -H "Authorization: Bearer $env:ID_TOKEN" -F "file=@cube.stl" http://localhost:8080/api/v1/files

.EXAMPLE
    # Just the token, for piping or capturing
    $t = .\scripts\Get-FirebaseToken.ps1 -Email dev@example.com -Password 'Passw0rd!' -Raw
#>
[CmdletBinding(DefaultParameterSetName = 'Password')]
param(
    [Parameter(ParameterSetName = 'Password', Mandatory, Position = 0)]
    [string] $Email,

    # Omit to be prompted without the password landing in PSReadLine history.
    [Parameter(ParameterSetName = 'Password', Position = 1)]
    [string] $Password,

    # Exchange a refresh token for a new ID token. No password needed.
    [Parameter(ParameterSetName = 'Refresh', Mandatory)]
    [string] $RefreshToken,

    # Defaults to $env:FIREBASE_WEB_API_KEY so it can be set once per shell.
    [string] $ApiKey = $env:FIREBASE_WEB_API_KEY,

    # Create the account instead of signing in. Fails with EMAIL_EXISTS if it is already there.
    [Parameter(ParameterSetName = 'Password')]
    [switch] $SignUp,

    # Call GET /api/v1/users/me to create the local profile row.
    [switch] $Provision,

    [string] $BackendUrl = 'http://localhost:8080',

    # Emit only the raw token on stdout — nothing else, so it can be captured.
    [switch] $Raw,

    # Set $env:ID_TOKEN (and $env:REFRESH_TOKEN) in the calling shell.
    [switch] $SetEnv
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    throw @'
No Web API key. Pass -ApiKey, or set it once for the shell:

    $env:FIREBASE_WEB_API_KEY = 'AIza...'

Find it in Firebase Console -> Project settings -> General -> "Web API Key",
or run: firebase apps:sdkconfig WEB --project packing-3d-6a9e6
'@
}

# Base64url differs from base64 in two ways: the 62/63 characters, and the dropped padding.
function ConvertFrom-Base64Url {
    param([string] $Value)
    $s = $Value.Replace('-', '+').Replace('_', '/')
    switch ($s.Length % 4) {
        2 { $s += '==' }
        3 { $s += '=' }
    }
    [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($s))
}

# Firebase reports failures as 400 with a JSON body that carries the actual reason. That
# body is the useful part, and Invoke-RestMethod throws it away unless we dig it out.
function Invoke-FirebaseApi {
    param(
        [string] $Uri,
        [hashtable] $Body,
        [string] $ContentType = 'application/json; charset=utf-8'
    )

    $payload = if ($ContentType -like 'application/json*') { $Body | ConvertTo-Json -Compress } else { $Body }

    try {
        return Invoke-RestMethod -Method Post -Uri $Uri -Body $payload -ContentType $ContentType
    }
    catch {
        $detail = $null
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $detail = $_.ErrorDetails.Message
        }
        elseif ($_.Exception.PSObject.Properties['Response'] -and $_.Exception.Response) {
            # Windows PowerShell 5.1 leaves the body on the stream instead.
            $reader = [IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
            try { $detail = $reader.ReadToEnd() } finally { $reader.Dispose() }
        }

        $code = 'UNKNOWN'
        if ($detail) {
            try { $code = ($detail | ConvertFrom-Json).error.message } catch { $code = $detail }
        }

        $hint = switch -Wildcard ($code) {
            'INVALID_LOGIN_CREDENTIALS*' { 'Wrong email or password. (Projects with email-enumeration protection return this one code for both, on purpose.) Add -SignUp if the account does not exist yet.' }
            'EMAIL_NOT_FOUND*'           { 'No such user. Add -SignUp to create the account.' }
            'INVALID_PASSWORD*'          { 'Wrong password.' }
            'USER_DISABLED*'             { 'The Firebase account is disabled. Re-enable it in the console.' }
            'EMAIL_EXISTS*'              { 'Account already exists — drop -SignUp and just sign in.' }
            'WEAK_PASSWORD*'             { 'Firebase requires at least 6 characters.' }
            'OPERATION_NOT_ALLOWED*'     { 'Email/password sign-in is off. Firebase Console -> Authentication -> Sign-in method -> Email/Password -> Enable.' }
            'TOO_MANY_ATTEMPTS*'         { 'Rate-limited after repeated failures. Wait, or reset the password.' }
            'API key not valid*'         { 'That is not the Web API key. It starts with AIza and lives under Project settings -> General — it is not the service account.' }
            'TOKEN_EXPIRED*'             { 'The refresh token was revoked. Sign in with a password again.' }
            default                      { $null }
        }

        $message = "Firebase rejected the request: $code"
        if ($hint) { $message += "`n$hint" }
        throw $message
    }
}

if ($PSCmdlet.ParameterSetName -eq 'Refresh') {
    # Note the snake_case here — the secure-token endpoint predates the Identity Toolkit
    # naming and returns id_token / refresh_token / user_id, not idToken / refreshToken.
    $response = Invoke-FirebaseApi `
        -Uri "https://securetoken.googleapis.com/v1/token?key=$ApiKey" `
        -Body @{ grant_type = 'refresh_token'; refresh_token = $RefreshToken } `
        -ContentType 'application/x-www-form-urlencoded'

    $idToken      = $response.id_token
    $refreshToken = $response.refresh_token
}
else {
    if ([string]::IsNullOrEmpty($Password)) {
        $secure   = Read-Host -AsSecureString "Password for $Email"
        $Password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(
            [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
    }

    $endpoint = if ($SignUp) { 'accounts:signUp' } else { 'accounts:signInWithPassword' }

    $response = Invoke-FirebaseApi `
        -Uri "https://identitytoolkit.googleapis.com/v1/$($endpoint)?key=$ApiKey" `
        -Body @{ email = $Email; password = $Password; returnSecureToken = $true }

    $idToken      = $response.idToken
    $refreshToken = $response.refreshToken
}

if ($Raw) {
    Write-Output $idToken
    if ($SetEnv) {
        $env:ID_TOKEN      = $idToken
        $env:REFRESH_TOKEN = $refreshToken
    }
    return
}

$claims  = (ConvertFrom-Base64Url $idToken.Split('.')[1]) | ConvertFrom-Json
$expires = [DateTimeOffset]::FromUnixTimeSeconds($claims.exp).ToLocalTime()

Write-Host ''
Write-Host 'Firebase ID token' -ForegroundColor Green
Write-Host "  subject (firebase_uid) : $($claims.sub)"
Write-Host "  email                  : $($claims.email)"
Write-Host "  issuer                 : $($claims.iss)"
Write-Host "  expires                : $expires  (in $([int](($expires - [DateTimeOffset]::Now).TotalMinutes)) min)"

# The backend reads users.role from the database on every request; this claim is only an
# advisory mirror for clients, and a stale one at that — it is frozen at the moment the
# token was minted. Shown to make that divergence visible rather than surprising.
$roles = if ($claims.PSObject.Properties['roles']) { $claims.roles -join ', ' } else { '(none yet — advisory mirror only; the backend reads users.role from the DB)' }
Write-Host "  roles claim            : $roles"
Write-Host ''
Write-Host $idToken
Write-Host ''

if ($Provision) {
    Write-Host "Provisioning local profile via GET $BackendUrl/api/v1/users/me ..." -ForegroundColor Cyan
    try {
        $me = Invoke-RestMethod -Method Get -Uri "$BackendUrl/api/v1/users/me" `
                                -Headers @{ Authorization = "Bearer $idToken" }
        Write-Host "  user id : $($me.id)"
        Write-Host "  role    : $($me.role)"
        Write-Host "  status  : $($me.status)"
    }
    catch {
        Write-Warning "Could not reach the backend at $BackendUrl. Is it running? ($($_.Exception.Message))"
    }
    Write-Host ''
}

if ($SetEnv) {
    $env:ID_TOKEN      = $idToken
    $env:REFRESH_TOKEN = $refreshToken
    Write-Host 'Exported $env:ID_TOKEN and $env:REFRESH_TOKEN for this shell.' -ForegroundColor Green
    Write-Host ''
    Write-Host '  curl.exe -H "Authorization: Bearer $env:ID_TOKEN" -F "file=@cube.stl" http://localhost:8080/api/v1/files'
    Write-Host '  curl.exe -H "Authorization: Bearer $env:ID_TOKEN" http://localhost:8080/api/v1/files'
    Write-Host ''
}
else {
    Write-Host 'Tip: add -SetEnv to export $env:ID_TOKEN, or -Raw to capture just the token.' -ForegroundColor DarkGray
}
