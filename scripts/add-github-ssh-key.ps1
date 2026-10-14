# Upload local SSH public key to GitHub via api.github.com (works when github.com:443 is blocked).
# 1) On phone / VPN: https://github.com/settings/tokens → Generate (classic) → enable "admin:public_key"
#    (or fine-grained: Account permissions → SSH keys → Read and write)
# 2) Run:  powershell -ExecutionPolicy Bypass -File scripts\add-github-ssh-key.ps1
# 3) Paste the token when prompted, then: git push -u origin main

$ErrorActionPreference = "Stop"
$pubPath = Join-Path $env:USERPROFILE ".ssh\id_ed25519_yuhuo.pub"
if (-not (Test-Path $pubPath)) {
    throw "Missing public key: $pubPath"
}
$pub = (Get-Content -Raw $pubPath).Trim()
Write-Host "Public key:`n$pub`n"

$secure = Read-Host "Paste GitHub Personal Access Token (input hidden)" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $token = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}
if ([string]::IsNullOrWhiteSpace($token)) { throw "Empty token" }

$body = @{
    title = "yuhuo-desktop-$(hostname)-$(Get-Date -Format yyyyMMdd)"
    key   = $pub
} | ConvertTo-Json

$headers = @{
    Authorization          = "Bearer $token"
    Accept                 = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent"           = "yuhuo-ssh-setup"
}

try {
    $resp = Invoke-RestMethod -Method Post -Uri "https://api.github.com/user/keys" -Headers $headers -Body $body -ContentType "application/json"
    Write-Host "OK: key id=$($resp.id) title=$($resp.title)"
} catch {
    $msg = $_.ErrorDetails.Message
    if ($msg -match "already exists|key is already in use") {
        Write-Host "Key already on account (OK)."
    } else {
        throw $_
    }
}

Write-Host "`nTesting SSH..."
ssh -o BatchMode=yes -o ConnectTimeout=15 -T git@github.com
Write-Host "`nIf you see Hi <username>!, run:`n  git push -u origin main"
