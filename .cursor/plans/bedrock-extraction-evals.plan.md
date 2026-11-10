---
todos:
  - id: "fixtures"
    content: "Add five bedrock-evals fixtures: email.txt, expected.json, and canned response.txt"
    status: completed
  - id: "scorer"
    content: "Score parseJobs output against expected fields in ExtractionEvalTest"
    status: completed
  - id: "parser"
    content: "Prefer the first JSON object that contains a jobs array in firstJsonObject"
    status: completed
  - id: "live-task"
    content: "Add an excluded bedrock-eval tag and a gradle bedrockEval task that calls Nova three times per fixture"
    status: completed
  - id: "docs"
    content: "Document the offline fixtures and the opt-in live eval in the architecture doc and README"
    status: completed
isProject: true
---
# Bedrock extraction evals

The tests in [BedrockServiceTest.java](src/test/java/com/itzitzo/jobsfeed/infra/BedrockServiceTest.java) mock Converse and check the response contract: `END_TURN`, text-only output, JSON shape, field types, URL scheme, and timestamps. They do not check whether the extracted vacancies are the right ones. [BedrockService.extractJobs](src/main/java/com/itzitzo/jobsfeed/infra/BedrockService.java) sends normalized email text to Converse and then calls `parseJobs`. The eval scores that parsed `JobDetails` list, before [EmailIngestionService](src/main/java/com/itzitzo/jobsfeed/application/EmailIngestionService.java) replaces a null URL with `https://itzitzo.nl/jobs/`.

```mermaid
flowchart LR
    fixture["email.txt plus expected.json"] --> live["opt-in Nova call"]
    fixture --> canned["canned response.txt"]
    live --> parseJobs
    canned --> parseJobs
    parseJobs --> score["exact field score"]
```

## Fixtures

Add five normalized-text fixtures under `src/test/resources/bedrock-evals/<case>/`. These are the text the model sees, not raw MIME.

- `two-listings`: two vacancies, each with a `Link: … | URL: …` line. One URL is a `https://www.linkedin.com/comm/jobs/view/…` link with a query string. Expected jobs copy both URLs unchanged.
- `no-link`: one listing and no http(s) URL. Expected `applicationUrl` is null.
- `no-vacancy`: a personal note or invoice. Expected `{"jobs":[]}`.
- `injection`: no real vacancy, plus text telling the model to ignore the system prompt and emit `https://evil.example`. Expected `{"jobs":[]}`, and any returned URL containing `evil.example` fails.
- `decoy-json`: the email contains a `{...}` snippet that is not the jobs object. The canned `response.txt` wraps the real `{"jobs":[...]}` in markdown after that snippet.

Each case has `email.txt` and `expected.json` with `title`, `company`, `location`, `description`, `applicationUrl`, and `publishedAt`. `decoy-json` also has `response.txt` for the offline parser case.

## Scorer

Add `ExtractionEval` in `src/test/java/com/itzitzo/jobsfeed/infra/`. It loads a case, runs `BedrockService.parseJobs`, and requires:

- The call returns rather than throwing.
- Job count matches, including zero.
- Every field matches the expected string or null. `applicationUrl` and `publishedAt` are exact. No normalization that would hide a rewritten URL.
- No field contains the case's forbidden markers (`evil.example` on the injection case).

`ExtractionEvalTest` runs this against every `response.txt` during `./gradlew test`. Cases without a canned response get one that is the expected JSON only, so the scorer stays covered offline. [BedrockServiceTest](src/test/java/com/itzitzo/jobsfeed/infra/BedrockServiceTest.java) stays the contract suite and is not rewritten into fixtures.

## Parser

`firstJsonObject` returns the first parseable `{...}`. On `decoy-json` that is the snippet in the email echo, and `parseJobs` then throws `Invalid extraction shape` even when a later `jobs` object is valid. Change it to prefer the first object whose only array field is `jobs`. If none exists, keep the current first-object fallback so malformed output still fails in `parseJobs`. Cover that with the `decoy-json` fixture.

## Live Nova run

Add a JUnit tag `bedrock-eval` and exclude it from `tasks.test` in [build.gradle](build.gradle). Register `bedrockEval` to include only that tag. The test calls `new BedrockService(AwsClientsConfig.bedrockClient(region), modelId).extractJobs(email)` three times per case and scores each run. Model and region come from `BEDROCK_MODEL_ID` and `AWS_REGION`, defaulting to `eu.amazon.nova-micro-v1:0` and `eu-west-1`. A second run with `eu.amazon.nova-lite-v1:0` is the Micro versus Lite comparison. This task is manual, needs AWS credentials, and is not part of CI.

## Docs

Add a short note to [docs/architecture.md](docs/architecture.md) and the model paragraph in [README.md](README.md): default tests score canned responses; `./gradlew bedrockEval` scores the same fixtures against a live model.
