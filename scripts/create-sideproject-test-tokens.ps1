[CmdletBinding()]
param(
    [string]$ApiBaseUrl = 'http://localhost/api',
    [string]$SellerEmail,
    [string]$BuyerEmail,
    [string]$Password,
    [string]$SellerName = 'ARL Test Seller',
    [string]$BuyerName = 'ARL Test Buyer',
    [string]$BusinessName = 'ARL Test Store',
    [string]$BusinessNumber = '123-45-67890',
    [string]$BankAccount = '123456789012',
    [string]$BankCode = '004'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function New-TestSuffix {
    $timestamp = Get-Date -Format 'yyyyMMddHHmmss'
    $random = Get-Random -Minimum 100000 -Maximum 999999
    return "$timestamp$random"
}

function Invoke-ApiJson {
    param(
        [Parameter(Mandatory)]
        [string]$Path,
        [Parameter(Mandatory)]
        [hashtable]$Body
    )

    try {
        return Invoke-RestMethod `
            -Method Post `
            -Uri "$($script:normalizedApiBaseUrl)$Path" `
            -ContentType 'application/json' `
            -Body ($Body | ConvertTo-Json -Compress) `
            -TimeoutSec 15
    } catch {
        $statusCode = $_.Exception.Response.StatusCode.value__
        if ($statusCode) {
            throw "POST $Path failed with HTTP $statusCode. Check that SideProject is running and that the API contract accepts the supplied test-account values."
        }
        throw "POST $Path failed. Check that SideProject is reachable at $script:normalizedApiBaseUrl."
    }
}

function Register-AndLogin {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('seller', 'user')]
        [string]$Role,
        [Parameter(Mandatory)]
        [string]$Email,
        [Parameter(Mandatory)]
        [string]$DisplayName
    )

    $signup = @{
        email = $Email
        password = $script:passwordToUse
        name = $DisplayName
    }
    if ($Role -eq 'seller') {
        $signup.businessName = $script:BusinessName
        $signup.businessNumber = $script:BusinessNumber
        $signup.bankAccount = $script:BankAccount
        $signup.bankCode = $script:BankCode
    }

    [void](Invoke-ApiJson -Path "/auth/signup/$Role" -Body $signup)
    $login = Invoke-ApiJson -Path "/auth/login/$Role" -Body @{
        email = $Email
        password = $script:passwordToUse
    }
    if ([string]::IsNullOrWhiteSpace($login.accessToken)) {
        throw "POST /auth/login/$Role returned no accessToken."
    }
    return $login.accessToken
}

$normalizedApiBaseUrl = $ApiBaseUrl.TrimEnd('/')
$suffix = New-TestSuffix
if ([string]::IsNullOrWhiteSpace($SellerEmail)) { $SellerEmail = "arl.seller.$suffix@example.test" }
if ([string]::IsNullOrWhiteSpace($BuyerEmail)) { $BuyerEmail = "arl.buyer.$suffix@example.test" }
if ([string]::IsNullOrWhiteSpace($Password)) { $Password = "Arl!$suffix" }
$passwordToUse = $Password

$sellerToken = Register-AndLogin -Role seller -Email $SellerEmail -DisplayName $SellerName
$buyerToken = Register-AndLogin -Role user -Email $BuyerEmail -DisplayName $BuyerName

$env:ARL_SPEC_AUTH_SIDEPROJECT_LOCAL_SELLER = "Bearer $sellerToken"
$env:ARL_SPEC_AUTH_SIDEPROJECT_LOCAL_BUYER = "Bearer $buyerToken"

Write-Host '판매자·구매자 테스트 계정을 만들고 로그인했습니다.'
Write-Host '현재 PowerShell 세션에 다음 ARL Runner 환경변수를 설정했습니다:'
Write-Host '  ARL_SPEC_AUTH_SIDEPROJECT_LOCAL_SELLER'
Write-Host '  ARL_SPEC_AUTH_SIDEPROJECT_LOCAL_BUYER'
Write-Host '토큰과 비밀번호는 출력하거나 파일에 저장하지 않았습니다.'
Write-Host '같은 PowerShell 창에서 .\start.ps1 -SkipBuild 를 실행해 ARL 컨테이너를 재생성하세요.'
