# codesheildsample

Intentionally vulnerable Java 17 AWS Lambda (ZIP package), used as a test target for CodeShield AI.

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

- Deployed as a **ZIP Lambda** (`java17`). `mvn package` builds `target/codesheildsample.zip` with classes at the
  root and each dependency jar in `lib/`.
- **Amazon Inspector Lambda standard scanning** scans those jars and reports findings against the function name.
  In CodeShield, register this repo and pick the `codesheildsample` Lambda function as its scan target.
- The `Dockerfile` is a **build check only**: `docker build .` runs the Maven build, which is how CodeShield
  validates a fix. It is not used for deployment.

## Build and deploy

Requires the AWS CLI, plus Maven or Docker.

```powershell
.\deploy.ps1 -Profile yogiltd          # build the zip, create/update the Lambda, smoke test
```

Invoke manually:

```powershell
aws lambda invoke --function-name codesheildsample --payload '{\"name\":\"jane\"}' --cli-binary-format raw-in-base64-out out.json --region eu-west-1 --profile yogiltd
```
