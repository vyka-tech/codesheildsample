<#
.SYNOPSIS
  Build the sample as a Lambda ZIP package and create/update the Lambda (java17 runtime).
  Amazon Inspector (Lambda standard scanning) scans the jars inside the zip.

.EXAMPLE
  .\deploy.ps1 -Profile yogiltd
#>
param(
    [string]$Profile = "yogiltd",
    [string]$Region  = "eu-west-1",
    [string]$Name    = "codesheildsample"   # Lambda function name
)

# "Continue" because docker/aws/mvn write normal progress to stderr, which Windows PowerShell 5.1
# would otherwise treat as a terminating error. Failures are detected via exit codes instead.
$ErrorActionPreference = "Continue"
$aws     = @("--region", $Region, "--profile", $Profile)
$handler = "com.codeshield.sample.Handler::handleRequest"
$zipPath = Join-Path $PSScriptRoot "target\$Name.zip"

function Invoke-Checked([scriptblock]$Cmd, [string]$What) {
    & $Cmd
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit $LASTEXITCODE)" }
}

# Runs an AWS CLI "describe/get" call and returns $true if the resource exists.
function Test-AwsResource([scriptblock]$Cmd) {
    & $Cmd *> $null
    return ($LASTEXITCODE -eq 0)
}

$account = (aws sts get-caller-identity @aws --query Account --output text)
if ($LASTEXITCODE -ne 0 -or -not $account) { throw "AWS credentials for profile '$Profile' are not valid" }
Write-Host "Account $($account.Trim()) | Region $Region | Function $Name"

# 1. Build target/<name>.zip (Maven locally, or inside Docker when Maven is not installed)
if (Get-Command mvn -ErrorAction SilentlyContinue) {
    Push-Location $PSScriptRoot
    try { Invoke-Checked { mvn -q -B clean package -DskipTests } "Maven build" } finally { Pop-Location }
} else {
    Write-Host "Maven not found, building in Docker"
    Invoke-Checked { docker build -t "$Name-build" $PSScriptRoot } "docker build"
    $cid = (docker create "$Name-build").Trim()
    New-Item -ItemType Directory -Force (Split-Path $zipPath) | Out-Null
    try { Invoke-Checked { docker cp "${cid}:/src/target/$Name.zip" $zipPath } "docker cp" } finally { docker rm $cid | Out-Null }
}
if (-not (Test-Path $zipPath)) { throw "Build did not produce $zipPath" }
Write-Host ("Built {0} ({1:N0} KB)" -f $zipPath, ((Get-Item $zipPath).Length / 1KB))

# 2. Execution role (CloudWatch Logs only)
$roleName = "$Name-lambda-role"
if (-not (Test-AwsResource { aws iam get-role --role-name $roleName --profile $Profile })) {
    Write-Host "Creating IAM role $roleName"
    $trust = '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
    $trustFile = Join-Path $env:TEMP "$Name-trust.json"
    Set-Content -Path $trustFile -Value $trust -Encoding ascii
    Invoke-Checked { aws iam create-role --role-name $roleName --assume-role-policy-document "file://$trustFile" --profile $Profile | Out-Null } "IAM create-role"
    Remove-Item $trustFile
    Invoke-Checked { aws iam attach-role-policy --role-name $roleName --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole --profile $Profile } "IAM attach-role-policy"
    Write-Host "Waiting for IAM role to propagate..."
    Start-Sleep -Seconds 15
}
$roleArn = (aws iam get-role --role-name $roleName --profile $Profile --query Role.Arn --output text).Trim()

# 3. Lambda function (zip, java17). No Function URL is created: invoke only with AWS credentials.
#    A function's package type cannot change, so an existing Image-based function is replaced.
$existingType = $null
if (Test-AwsResource { aws lambda get-function --function-name $Name @aws }) {
    $existingType = (aws lambda get-function-configuration --function-name $Name @aws --query PackageType --output text).Trim()
}
if ($existingType -eq "Image") {
    Write-Host "Replacing image-based function $Name with a zip-based one"
    Invoke-Checked { aws lambda delete-function --function-name $Name @aws } "Lambda delete (image)"
    $existingType = $null
}

if ($existingType) {
    Write-Host "Updating Lambda $Name"
    Invoke-Checked { aws lambda update-function-code --function-name $Name --zip-file "fileb://$zipPath" @aws | Out-Null } "Lambda update-code"
    Invoke-Checked { aws lambda wait function-updated-v2 --function-name $Name @aws } "Lambda wait"
    Invoke-Checked { aws lambda update-function-configuration --function-name $Name --runtime java17 --handler $handler @aws | Out-Null } "Lambda update-config"
} else {
    Write-Host "Creating Lambda $Name"
    Invoke-Checked { aws lambda create-function --function-name $Name --package-type Zip --runtime java17 `
        --handler $handler --zip-file "fileb://$zipPath" --role $roleArn --timeout 30 --memory-size 512 `
        --architectures x86_64 @aws | Out-Null } "Lambda create"
}
Invoke-Checked { aws lambda wait function-updated-v2 --function-name $Name @aws } "Lambda wait"

# 4. Public HTTPS endpoint (Lambda Function URL, no auth) so the demo page opens in a browser.
#    The handler only accepts GET, never logs or interpolates caller input, and escapes it before echoing.
if (-not (Test-AwsResource { aws lambda get-function-url-config --function-name $Name @aws })) {
    Write-Host "Creating Function URL"
    Invoke-Checked { aws lambda create-function-url-config --function-name $Name --auth-type NONE @aws | Out-Null } "Function URL create"
}
# Public URLs need both permissions; ignore "already exists" on re-runs
aws lambda add-permission --function-name $Name --statement-id FunctionUrlPublicAccess `
    --action lambda:InvokeFunctionUrl --principal "*" --function-url-auth-type NONE @aws *> $null
$hasInvokeViaUrl = (aws lambda get-policy --function-name $Name @aws --query Policy --output text 2>$null) -match 'InvokedViaFunctionUrl'
if (-not $hasInvokeViaUrl) {
    # --invoked-via-function-url limits this grant to calls made through the URL. Needs a recent AWS CLI.
    aws lambda add-permission --function-name $Name --statement-id FunctionUrlInvokeFunction `
        --action lambda:InvokeFunction --principal "*" --invoked-via-function-url @aws *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Warning ("Could not add the lambda:InvokeFunction permission for the Function URL (the URL will return 403). " +
                       "Upgrade the AWS CLI and re-run, or add it with boto3: add_permission(..., InvokedViaFunctionUrl=True).")
    }
}
# Cap concurrency so a public URL cannot run up cost (skipped if the account's concurrency quota is too low)
aws lambda put-function-concurrency --function-name $Name --reserved-concurrent-executions 5 @aws *> $null
if ($LASTEXITCODE -ne 0) { Write-Host "Note: could not reserve concurrency (account quota too low); continuing" }
$functionUrl = (aws lambda get-function-url-config --function-name $Name @aws --query FunctionUrl --output text).Trim()

# 5. Smoke test
$payloadFile = Join-Path $env:TEMP "$Name-payload.json"
Set-Content -Path $payloadFile -Value '{"name":"codeshield tester"}' -Encoding ascii
$outFile = Join-Path $PSScriptRoot "response.json"
Invoke-Checked { aws lambda invoke --function-name $Name --payload "fileb://$payloadFile" $outFile @aws | Out-Null } "Lambda invoke"
Remove-Item $payloadFile
Write-Host "Lambda response:"; Get-Content $outFile
Remove-Item $outFile

# 6. HTTP smoke test through the public URL
$health = Invoke-RestMethod -Uri ($functionUrl.TrimEnd('/') + "/api/health") -TimeoutSec 60
Write-Host "Health: $($health.status)"
$health.dependencies | ForEach-Object { Write-Host ("  {0,-20} {1,-10} {2}" -f $_.library, $_.version, $(if ($_.ok) { 'ok' } else { "FAIL: $($_.error)" })) }
Write-Host "Open in a browser: $functionUrl"
if ($health.status -ne "UP") { throw "Health check reported $($health.status)" }
