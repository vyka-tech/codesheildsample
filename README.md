# codesheildsample

A small greeting web service on AWS Lambda (Java 17): type your name and it greets you, and its status page shows the library versions it runs with a live self-test for each. It is a deliberately vulnerable demo target for CodeShield AI scanning and fixing.

**Service URL:** <https://4oz5xf7m5zb65cgby22wcnwzxy0gcdcv.lambda-url.eu-west-1.on.aws/>

> ⚠️ Some dependency versions in `pom.xml` are deliberately outdated and have known CVEs. Do not reuse them.

## What it does

- `GET /` shows a page with a greeting form ("Hello, Jane Doe!"), the service status, and a table of the libraries
  running in this deployment with a pass/fail self-test for each.
- `GET /api/hello?name=jane` returns the greeting as JSON.
- `GET /api/health` returns `UP` plus every library version and self-test result (HTTP 503 if any fails).

Try it: [greet CodeShield](https://4oz5xf7m5zb65cgby22wcnwzxy0gcdcv.lambda-url.eu-west-1.on.aws/api/hello?name=CodeShield) ·
[health](https://4oz5xf7m5zb65cgby22wcnwzxy0gcdcv.lambda-url.eu-west-1.on.aws/api/health)

## Dependencies

| Dependency | Version | Status | Example CVE | Fixed in |
|---|---|---|---|---|
| log4j-core / log4j-api | 2.14.1 | ❌ vulnerable | CVE-2021-44228 (Log4Shell) | 2.17.1 |
| jackson-databind | 2.18.11 | ✅ fixed by CodeShield (PR #1) | CVE-2020-36518 | 2.12.7.1 |
| commons-text | 1.9 | ❌ vulnerable | CVE-2022-42889 (Text4Shell) | 1.10.0 |
| commons-collections | 3.2.1 | ❌ vulnerable | CVE-2015-7501 | 3.2.2 |
| snakeyaml | 2.0 | ✅ fixed by CodeShield (PR #2) | CVE-2022-1471 | 2.0 |
| guava | 29.0-jre | ❌ vulnerable | CVE-2023-2976 | 32.0.0-jre |

## How it fits CodeShield AI

- Deployed as a **ZIP Lambda** (`java17`). `mvn package` builds `target/codesheildsample.zip` with classes at the
  root and each dependency jar in `lib/`.
- **Amazon Inspector Lambda standard scanning** scans those jars and reports findings against the function name.
  In CodeShield, register this repo and pick the `codesheildsample` Lambda function as its scan target.
- The `Dockerfile` is a **build check only**: `docker build .` runs the Maven build, which is how CodeShield
  validates a fix. It is not used for deployment.

## Endpoint details

Public HTTPS via a Lambda Function URL (see **Service URL** above).

| Route | Returns |
|---|---|
| `GET /` | HTML status page: greeting form, the library versions actually running, and a self-test per library |
| `GET /api/hello?name=jane` | `{"message":"Hello, Jane!", ...}` |
| `GET /api/health` | `{"status":"UP","dependencies":[{"library":"jackson-databind","version":"2.18.11","ok":true}, ...]}` (503 if any self-test fails) |

Use it to show that a CodeShield fix kept the service working: after the PR deploys, refresh the page and the
upgraded library shows its new version with its self-test still passing. Only `GET` is accepted, caller input is
never logged or passed to the vulnerable library APIs, and it is HTML-escaped before being echoed.

## CI/CD (GitHub Actions)

| Workflow | Trigger | What it does |
|---|---|---|
| `CI` (`.github/workflows/ci.yml`) | Every PR to `main`, incl. CodeShield fix PRs | `mvn package` and checks the zip layout |
| `Deploy` (`.github/workflows/deploy.yml`) | Push to `main` (i.e. after merging a PR), or manual | Builds the zip, updates the Lambda, runs a smoke test |

Flow: CodeShield raises a PR → `CI` must pass → a reviewer merges → `Deploy` ships it → Inspector rescans the
function and the fixed CVEs drop off the CodeShield dashboard.

AWS access uses GitHub OIDC, so no AWS keys are stored in GitHub. The role is defined in
`infra/github-deploy-role.yml`: only `main` of this repo can assume it, and it can only update, read and invoke
the `codesheildsample` function. One-time setup (already done for account `024265813171`):

```powershell
aws cloudformation deploy --stack-name codesheildsample-github-deploy --template-file infra/github-deploy-role.yml --capabilities CAPABILITY_NAMED_IAM --region eu-west-1 --profile yogiltd
```

Recommended: protect `main` and make the `Build Lambda zip` check required.

## Manual build and deploy

Requires the AWS CLI, plus Maven or Docker.

```powershell
.\deploy.ps1 -Profile yogiltd          # build the zip, create/update the Lambda, smoke test
```

Invoke manually:

```powershell
aws lambda invoke --function-name codesheildsample --payload '{\"name\":\"jane\"}' --cli-binary-format raw-in-base64-out out.json --region eu-west-1 --profile yogiltd
```
