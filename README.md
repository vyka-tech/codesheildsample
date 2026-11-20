# codesheildsample

Intentionally vulnerable Java 17 AWS Lambda, used as a test target for CodeShield AI.

> ⚠️ The dependency versions in `pom.xml` are deliberately outdated and have known CVEs.
> Do not reuse them. The function has no public URL and can only be invoked with AWS credentials.

## Vulnerable dependencies

| Dependency | Version | Example CVE | Fixed in |
|---|---|---|---|
| log4j-core / log4j-api | 2.14.1 | CVE-2021-44228 (Log4Shell) | 2.17.1 |
| jackson-databind | 2.9.10.1 | CVE-2020-36518 | 2.12.7.1 |
| commons-text | 1.9 | CVE-2022-42889 (Text4Shell) | 1.10.0 |
| commons-collections | 3.2.1 | CVE-2015-7501 | 3.2.2 |
| snakeyaml | 1.26 | CVE-2022-1471 | 2.0 |
| guava | 29.0-jre | CVE-2023-2976 | 32.0.0-jre |

## How it fits CodeShield AI

- Deployed as a **Lambda container image** in ECR repo `codesheildsample`. Amazon Inspector scans the image
  and reports findings against that ECR repo, which is how CodeShield finds them.
- `docker build .` runs the full Maven build, which is the command CodeShield uses to validate fixes.

## Build and deploy

Requires Docker, the AWS CLI and a profile with ECR, Lambda and IAM access.

```powershell
.\deploy.ps1 -Profile yogiltd          # build, push to ECR, create/update Lambda, smoke test
```

Invoke manually:

```powershell
aws lambda invoke --function-name codesheildsample --payload '{\"name\":\"jane\"}' --cli-binary-format raw-in-base64-out out.json --region eu-west-1 --profile yogiltd
```
