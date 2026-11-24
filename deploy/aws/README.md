# Deploy the low-cost AWS demo

One EC2 instance runs the full stack (Postgres, Redis, Kafka, the three services, Prometheus, Jaeger)
with the same `docker-compose.yml` used locally. The nightly reconciliation extract is written to a
real S3 bucket. Everything is created by Terraform and removed with one command.

```
 your laptop ──(your IP only, port 8081)──► EC2 t3.large (Amazon Linux 2023, default VPC)
                                             └─ docker compose: postgres · redis · kafka · order · inventory · payment
                                             └─ systemd timer 01:30 UTC ─► reconciliation-batch ─► S3 (KMS-encrypted)
 shell access: AWS SSM Session Manager (no SSH key, no port 22)
```

## What it costs

These are rough on-demand prices for us-east-1. Check the AWS Pricing Calculator for your region.

| Resource | Approximate cost |
|---|---|
| EC2 t3.large, running 24/7 | ~$0.083/hour, about $61/month |
| 30 GB gp3 disk | ~$2.40/month |
| Public IPv4 address | ~$0.005/hour, about $3.60/month |
| S3 + requests | cents |

**About $67/month if left running.** To pay less:
- **Stop the instance** when you're not demoing: `aws ec2 stop-instances --instance-ids <id>`.
  You then pay only for the disk (~$2.40/month). Start it again before the interview; the stack restarts by itself.
- Use `instance_type = "t3.medium"` (~$30/month). It works, but it's tight on memory.
- **Run `terraform destroy` when you're done.** It deletes everything, including the S3 bucket.

## One-time setup on your Mac

```bash
brew install awscli
brew tap hashicorp/tap && brew install hashicorp/tap/terraform
brew install --cask session-manager-plugin
```

Then sign in to AWS. Create an access key for an IAM user (not the root account) in the AWS
console, and run:

```bash
aws configure
```

Check that it works with `aws sts get-caller-identity`.

## Deploy

```bash
cd deploy/aws/terraform
cp terraform.tfvars.example terraform.tfvars
curl -s https://checkip.amazonaws.com      # put this IP + "/32" into allowed_cidr in terraform.tfvars
terraform init
terraform plan                             # review: 1 instance, 1 bucket, 1 role, 1 security group, ...
terraform apply
```

The instance needs **about 10 minutes** after `apply` finishes to install Docker, build the jars and
start the containers. Then open the `swagger_ui` URL from the Terraform output.

To watch the bootstrap:

```bash
aws ssm start-session --target <instance_id>
sudo tail -f /var/log/order-platform-bootstrap.log      # ends with BOOTSTRAP COMPLETE
```

## Try it

```bash
API=$(terraform output -raw order_api)
curl -s -X POST $API -H "Content-Type: application/json" -H "Idempotency-Key: demo-$(date +%s)" \
  -d '{"customerId":"demo","currency":"USD","lines":[{"sku":"SKU-1001","quantity":2,"unitPrice":10.00}]}'
```

Run the reconciliation batch now instead of waiting for 01:30 UTC (from a Session Manager shell):

```bash
cd /opt/order-platform
sudo docker compose -f docker-compose.yml -f deploy/aws/docker-compose.aws.yml --profile batch \
  run --rm reconciliation-batch --businessDate=$(date -u +%F) --rerun
```

Then list the extract and manifest in S3 from your Mac:

```bash
aws s3 ls s3://$(terraform output -raw extract_bucket)/orders/reconciliation/ --recursive
```

Prometheus and Jaeger aren't exposed publicly. Reach them through a Session Manager port forward:

```bash
aws ssm start-session --target <instance_id> --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["16686"],"localPortNumber":["16686"]}'
# then open http://localhost:16686 (Jaeger); use 9090 for Prometheus
```

## Deploy a newer version

The instance clones `git_ref` once, at first boot. After pushing changes, either:

- run `terraform apply -replace=aws_instance.app` to get a fresh instance (simplest), or
- in a Session Manager shell: `cd /opt/order-platform && sudo git pull && sudo ./gradlew build -x test && sudo docker compose -f docker-compose.yml -f deploy/aws/docker-compose.aws.yml up -d --build`

## Tear down

```bash
terraform destroy
```

## Security notes

- Only your IP can reach ports 8081/8082. Nothing else is open, and there's no SSH.
- No AWS keys are stored on the instance. Containers use the instance role through IMDSv2. The role can
  only write to the extract bucket, plus the standard SSM permissions.
- The bucket blocks all public access, is KMS-encrypted and versioned, and expires extracts after 30 days.
- The databases use the local demo passwords from `deploy/local/postgres-init.sql`. They're acceptable
  here only because Postgres isn't reachable from outside the instance. The production design uses RDS
  with credentials from Secrets Manager.

## How this differs from production

| Demo (this) | Production design |
|---|---|
| One EC2 instance, Docker Compose | EKS across 3 AZs, manifests in `deploy/k8s` |
| Postgres in a container | RDS PostgreSQL Multi-AZ + read replica |
| Redis / Kafka in containers | ElastiCache Redis, Amazon MSK |
| Images built on the instance | Jenkins builds once, pushes to ECR, Harness/uDeploy promote |
| Session Manager + your IP | ALB + WAF, private subnets, OAuth2 |
