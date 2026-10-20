# AWS deployment guide

This guide builds and deploys the supplied personal demonstration. No AWS resources were created during generation.

## Architecture

The public ALB terminates TLS, redirects HTTP and routes `/api/*` to backend port 8080; other requests reach Angular assets served by Nginx on port 80. Each Fargate task contains both containers. The ECS service runs at least two tasks across private application subnets in two AZs and scales to four on CPU. It uses two target groups with health checks. RDS PostgreSQL is Multi AZ, encrypted and private in isolated subnets. Clients use the single writer endpoint, never the standby directly. Sessions, accounts, ledger, idempotency and outbox state reside in PostgreSQL.

There are two NAT gateways, one per AZ, for private task access to ECR, Secrets Manager and CloudWatch. The database subnets have no internet default route. WAF is associated with the ALB. Route 53 aliases the provided hostname to the ALB. ACM must provide a validated certificate in the same AWS Region. The repository image tags are immutable. RDS connections use `sslmode=verify-full` and the bundled AWS trust store.

## Prerequisites

- AWS account with permission to create the resources in both templates, including IAM roles and the required service linked roles.
- AWS CLI v2, Docker with AMD64 build support, and a shell for `scripts/build-push.sh`.
- A public Route 53 hosted zone and hostname, plus an issued ACM certificate covering it in the deployment Region.
- Budget approval for ALB, WAF, Fargate, CloudWatch, RDS Multi AZ, NAT gateways, ECR, Secrets Manager and Route 53. This topology is intentionally not a free-tier deployment.

## Deploy

From the project root, select your account and Region using your normal AWS credential workflow. For example, set `AWS_PROFILE` if you use named profiles, then `AWS_REGION=us-east-1`.

```bash
aws sts get-caller-identity
aws cloudformation deploy --region "$AWS_REGION" --stack-name banking-registry --template-file infra/registry.yaml
```

Choose an unused image tag; ECR will reject overwriting an existing tag. Use a git commit SHA or release identifier.

```bash
export TAG=demo-v1
bash scripts/build-push.sh
```

Record the BackendImage and FrontendImage values printed by the script. Create a private JSON file outside the repository with parameter values for your environment:

```json
[
 {"ParameterKey":"BackendImage","ParameterValue":"ACCOUNT.dkr.ecr.REGION.amazonaws.com/BACKEND:TAG"},
 {"ParameterKey":"FrontendImage","ParameterValue":"ACCOUNT.dkr.ecr.REGION.amazonaws.com/FRONTEND:TAG"},
 {"ParameterKey":"CertificateArn","ParameterValue":"arn:aws:acm:REGION:ACCOUNT:certificate/ID"},
 {"ParameterKey":"DomainName","ParameterValue":"banking-demo.example.com"},
 {"ParameterKey":"HostedZoneId","ParameterValue":"YOUR_HOSTED_ZONE_ID"},
 {"ParameterKey":"DemoPassword","ParameterValue":"YOUR_UNIQUE_DISPOSABLE_DEMO_PASSWORD"}
]
```

The placeholders above are configuration instructions, not hardcoded application values. Keep that file private and do not commit it. The template stores the demo password in Secrets Manager. Then:

```bash
aws cloudformation create-stack --region "$AWS_REGION" --stack-name banking-demo --template-body file://infra/cloudformation.yaml --parameters file:///absolute/private/parameters.json --capabilities CAPABILITY_IAM
aws cloudformation wait stack-create-complete --region "$AWS_REGION" --stack-name banking-demo
aws cloudformation describe-stacks --region "$AWS_REGION" --stack-name banking-demo --query 'Stacks[0].Outputs'
```

Open the HTTPS Url output. Initial provisioning and database creation can take several minutes. If creation fails, inspect CloudFormation events and ECS container logs. The CloudFormation syntax was linted, but account quotas, regional feature support and a live deployment have not been tested. Review the selected default PostgreSQL engine in the target Region and freeze a supported `EngineVersion` for any controlled environment.

## Updates

Build a new immutable image tag. Use a CloudFormation change set to update BackendImage and FrontendImage while preserving other parameters. Review and execute the change set. The service uses a deployment circuit breaker. Changes to the database need backward-compatible migrations and an explicit restore plan.

The stack uses RDS managed master credentials as a demo simplification. RDS rotation changes the secret; ECS injects secrets only when a task starts. Coordinate rotation with an ECS forced deployment or implement dynamic credential refresh. Do not rotate credentials without a refresh plan. Existing user password hashes do not change when DemoPassword changes.

For a forced task restart, use the ClusterName and ServiceName outputs:

```bash
aws ecs update-service --region "$AWS_REGION" --cluster CLUSTER_OUTPUT --service SERVICE_OUTPUT --force-new-deployment
```

## Operations and cleanup

CloudWatch captures container logs and an ALB 5xx alarm. The template does not create an alarm notification recipient. Subscribe an approved operations destination separately if required. Application reconciliation emits `LEDGER_RECONCILIATION_MISMATCH` when account and ledger totals differ.

RDS has seven day backups, deletion protection and snapshot retention. The deletion protection setting must be explicitly disabled before deleting the stack. Delete only your demo resources, verify snapshot requirements and review the change set first. ECR repositories are retained by their stack and must be cleaned up separately if no longer needed. NAT gateways, logs, snapshots and retained ECR images may continue to incur charges while present.

This stack seeds demo credentials and simulated balances. Do not use it to store real banking data. A real deployment needs separate migration/runtime database roles, user lifecycle and recovery, MFA, secret rotation automation, security review, retention decisions, restore exercises and measured capacity testing.

## Primary references

- https://docs.aws.amazon.com/AmazonECS/latest/developerguide/networking-outbound.html
- https://docs.aws.amazon.com/vpc/latest/userguide/vpc-example-private-subnets-nat.html
- https://docs.aws.amazon.com/AmazonECS/latest/developerguide/specifying-sensitive-data.html
- https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html
- https://angular.dev/reference/versions
- AWS RDS trust bundle: https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
