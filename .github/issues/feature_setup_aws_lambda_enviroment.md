# Feature setup: AWS Lambda environment (Java + Gradle)

This guide sets up this project as a **Gradle-based Java AWS Lambda** service, prepared for:
- **AWS Bedrock integration**
- **Amazon DynamoDB integration**
- **Terraform provisioning with GitHub Actions deployment automation**

---

## 1) Prerequisites

- Java **17** (LTS) or newer
- Gradle **8+** (or use Gradle Wrapper)
- AWS CLI v2 configured (`aws configure`)
- Terraform **1.6+**
- AWS account with permissions for:
  - Lambda
  - IAM
  - CloudWatch Logs
  - Bedrock (model invocation)
  - DynamoDB
- GitHub repository with permission to configure:
  - GitHub Actions
  - Repository secrets
  - OIDC trust to AWS IAM role

---

## 2) Initialize/structure the Gradle project

If this repository is not already a Gradle project:

```bash
gradle init --type java-application
```

Recommended structure:

```text
.
├── build.gradle
├── settings.gradle
└── src
    └── main
        └── java
            └── com
                └── itzitzo
                    └── jobsfeed
                        ├── LambdaHandler.java
                        ├── config
                        │   └── AwsClientsConfig.java
                        └── service
                            ├── BedrockService.java
                            └── DynamoDbService.java
```

---

## 3) `build.gradle` baseline (Lambda + Bedrock + DynamoDB)

Use AWS SDK v2 and create a deployable fat JAR:

```groovy
plugins {
    id 'java'
    id 'com.github.johnrengelman.shadow' version '8.1.1'
}

group = 'com.itzitzo'
version = '0.1.0'

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    // Lambda runtime
    implementation 'com.amazonaws:aws-lambda-java-core:1.2.3'
    implementation 'com.amazonaws:aws-lambda-java-events:3.11.5'

    // AWS SDK v2
    implementation platform('software.amazon.awssdk:bom:2.30.13')
    implementation 'software.amazon.awssdk:bedrockruntime'
    implementation 'software.amazon.awssdk:dynamodb'

    // Optional logging facade
    implementation 'org.slf4j:slf4j-api:2.0.16'
    runtimeOnly 'org.slf4j:slf4j-simple:2.0.16'

    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.3'
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set('')
    mergeServiceFiles()
}
```

Then build:

```bash
./gradlew clean shadowJar
```

Output artifact:

```text
build/libs/<project-name>-0.1.0.jar
```

---

## 4) Lambda handler setup

Create a handler class implementing `RequestHandler`:

```java
package com.itzitzo.jobsfeed;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.Map;

public class LambdaHandler implements RequestHandler<Map<String, Object>, String> {
    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        context.getLogger().log("Lambda invocation received");
        return "ok";
    }
}
```

Lambda handler value:

```text
com.itzitzo.jobsfeed.LambdaHandler::handleRequest
```

---

## 5) Prepare Bedrock integration

Create a central client factory:

```java
package com.itzitzo.jobsfeed.config;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public final class AwsClientsConfig {
    private AwsClientsConfig() {}

    public static BedrockRuntimeClient bedrockClient(String region) {
        return BedrockRuntimeClient.builder()
            .region(Region.of(region))
            .build();
    }

    public static DynamoDbClient dynamoDbClient(String region) {
        return DynamoDbClient.builder()
            .region(Region.of(region))
            .build();
    }
}
```

Bedrock environment variables to define in Lambda:

- `AWS_REGION` (for example `eu-west-1`)
- `BEDROCK_MODEL_ID` (for example `amazon.titan-text-lite-v1`)

IAM permissions (minimum):

- `bedrock:InvokeModel`
- `bedrock:InvokeModelWithResponseStream` (if streaming is needed)

---

## 6) Prepare DynamoDB integration

DynamoDB environment variables:

- `DYNAMODB_TABLE_NAME`
- `AWS_REGION`

IAM permissions (minimum):

- `dynamodb:GetItem`
- `dynamodb:PutItem`
- `dynamodb:UpdateItem`
- `dynamodb:Query`

Suggested table starting point:

- Partition key: `pk` (String)
- Sort key: `sk` (String, optional but recommended for evolution)
- Enable on-demand capacity at first

---

## 7) Deploy Lambda (quick path)

1. Create IAM role for Lambda with:
   - `AWSLambdaBasicExecutionRole`
   - custom policy for Bedrock + DynamoDB actions listed above
2. Create Lambda function:
   - Runtime: `java17`
   - Handler: `com.itzitzo.jobsfeed.LambdaHandler::handleRequest`
   - Upload JAR from `build/libs/`
3. Configure environment variables:
   - `AWS_REGION`
   - `BEDROCK_MODEL_ID`
   - `DYNAMODB_TABLE_NAME`
4. Set timeout/memory:
   - Start with `30s` timeout and `1024 MB` memory for Bedrock calls
5. Test with a sample event in Lambda console or AWS CLI

---

## 8) Provision infrastructure with Terraform

Provisioning should be done through Terraform (not manual console setup) so environments are reproducible.

Recommended layout:

```text
infra/
├── versions.tf
├── providers.tf
├── variables.tf
├── main.tf
├── iam.tf
├── lambda.tf
├── dynamodb.tf
├── outputs.tf
└── envs/
    ├── dev.tfvars
    └── prod.tfvars
```

Terraform should manage:

- Lambda function (`java17`, handler, memory, timeout, env vars)
- IAM role and least-privilege policies for:
  - CloudWatch logs
  - Bedrock invoke actions
  - DynamoDB table access
- DynamoDB table (on-demand mode)
- CloudWatch log group and retention

Core Lambda environment variables provisioned by Terraform:

- `AWS_REGION`
- `BEDROCK_MODEL_ID`
- `DYNAMODB_TABLE_NAME`

Example Terraform flow:

```bash
cd infra
terraform init
terraform plan -var-file=envs/dev.tfvars
terraform apply -var-file=envs/dev.tfvars
```

---

## 9) Automate provisioning and deployment with GitHub Actions

Use GitHub Actions to:

1. Build the Java Lambda JAR with Gradle.
2. Run Terraform plan/apply for infrastructure changes.
3. Update Lambda code after successful infra provisioning.

Suggested workflow file:

```text
.github/workflows/deploy-lambda.yml
```

Workflow requirements:

- Use GitHub OIDC federation to AWS (avoid long-lived AWS keys).
- Store configuration in repo secrets/variables:
  - `AWS_ROLE_ARN`
  - `AWS_REGION`
  - `TF_STATE_BUCKET` (if using remote state)
- Separate jobs/stages for `plan` and `apply` (with branch/environment protection).
- Run `./gradlew clean test shadowJar` before deployment.

Minimal job sequence:

1. Checkout code
2. Setup Java
3. Build and test artifact
4. Configure AWS credentials via OIDC
5. Terraform `init` + `plan`
6. Terraform `apply` (protected branches/environments only)
7. Publish Lambda code package

---

## 10) Recommended next implementation steps

1. Add `BedrockService` with prompt/request builder and response parser.
2. Add `DynamoDbService` with repository-style methods (`saveJob`, `getJob`, `listJobs`).
3. Inject services into `LambdaHandler` and wire real request flow.
4. Add unit tests for handler and service layers.
5. Add integration tests (localstack or AWS test environment).

---

## 11) Security and operations checklist

- Never hardcode secrets; use environment variables or AWS Secrets Manager
- Restrict IAM policies to exact table ARN and required Bedrock actions only
- Log request IDs and key business events to CloudWatch
- Add CloudWatch alarms for error count and duration
- Use separate AWS accounts/environments for dev, staging, and prod
