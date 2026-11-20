<#
.SYNOPSIS
  Build the sample as a Lambda container image, push it to ECR and create/update the Lambda.

.EXAMPLE
  .\deploy.ps1 -Profile yogiltd
#>
param(
    [string]$Profile      = "yogiltd",
    [string]$Region       = "eu-west-1",
    [string]$Name         = "codesheildsample",   # ECR repo + Lambda function name (must match the GitHub repo name for CodeShield)
    [string]$Tag          = "latest"
)

# "Continue" because docker/aws write normal progress to stderr, which Windows PowerShell 5.1
# would otherwise treat as a terminating error. Failures are detected via exit codes instead.
$ErrorActionPreference = "Continue"
$aws = @("--region", $Region, "--profile", $Profile)

function Invoke-Checked([scriptblock]$Cmd, [string]$What) {
    & $Cmd
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit $LASTEXITCODE)" }
}

# Runs an AWS CLI "describe/get" call and returns $true if the resource exists.
function Test-AwsResource([scriptblock]$Cmd) {
    & $Cmd *> $null
    return ($LASTEXITCODE -eq 0)
}

$account  = (aws sts get-caller-identity @aws --query Account --output text).Trim()
if ($LASTEXITCODE -ne 0 -or -not $account) { throw "AWS credentials for profile '$Profile' are not valid" }
$registry = "$account.dkr.ecr.$Region.amazonaws.com"
$imageUri = "$registry/${Name}:$Tag"
Write-Host "Account $account | Region $Region | Image $imageUri"

# 1. ECR repository
if (-not (Test-AwsResource { aws ecr describe-repositories --repository-names $Name @aws })) {
    Write-Host "Creating ECR repository $Name"
    Invoke-Checked { aws ecr create-repository --repository-name $Name @aws | Out-Null } "ECR create"
}

# 2. Build and push. --provenance=false keeps a single-platform manifest, which Lambda requires.
Invoke-Checked { docker build --platform linux/amd64 --provenance=false -t $imageUri $PSScriptRoot } "docker build"
$password = aws ecr get-login-password @aws
$password | docker login --username AWS --password-stdin $registry | Out-Null
if ($LASTEXITCODE -ne 0) { throw "ECR login failed" }
Invoke-Checked { docker push $imageUri } "docker push"

# 3. Execution role (CloudWatch Logs only)
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

# 4. Lambda function (container image). No Function URL is created: invoke only with AWS credentials.
if (Test-AwsResource { aws lambda get-function --function-name $Name @aws }) {
    Write-Host "Updating Lambda $Name"
    Invoke-Checked { aws lambda update-function-code --function-name $Name --image-uri $imageUri @aws | Out-Null } "Lambda update"
} else {
    Write-Host "Creating Lambda $Name"
    Invoke-Checked { aws lambda create-function --function-name $Name --package-type Image --code "ImageUri=$imageUri" `
        --role $roleArn --timeout 30 --memory-size 512 --architectures x86_64 @aws | Out-Null } "Lambda create"
}
Invoke-Checked { aws lambda wait function-updated-v2 --function-name $Name @aws } "Lambda wait"

# 5. Smoke test
$payloadFile = Join-Path $env:TEMP "$Name-payload.json"
Set-Content -Path $payloadFile -Value '{"name":"codeshield tester"}' -Encoding ascii
$outFile = Join-Path $PSScriptRoot "response.json"
Invoke-Checked { aws lambda invoke --function-name $Name --payload "fileb://$payloadFile" $outFile @aws | Out-Null } "Lambda invoke"
Remove-Item $payloadFile
Write-Host "Lambda response:"; Get-Content $outFile
Remove-Item $outFile
