# Live UAT Suite

These tests run against a **deployed AWS stack** and require real credentials.

## Prerequisites

- `UAT_LIVE=true` environment variable set
- A deployed `EksDXpressControlPlaneStack` in the target account/region
- AWS credentials with permissions to invoke the Lambda Function URLs
- An IAM role ARN for association tests: `UAT_ROLE_ARN=arn:aws:iam::123:role/uat-role`
- The CLI built: `./build-local.sh --only cli --skip-tests`

## Running

```bash
UAT_LIVE=true \
AWS_REGION=us-east-1 \
UAT_ROLE_ARN=arn:aws:iam::123456789012:role/uat-test-role \
./tests/uat/run-uat.sh --suite live
```

## What is tested

1. `create-cluster uat-test --wait` — provisions a real EC2 cluster (~12 min)
2. `get-cluster-access uat-test` — returns public IP and SSH command
3. `get-cluster-access uat-test --save-key` — writes .pem locally
4. SSH connectivity to the EC2 instance
5. `create-association` / `list-associations` — DynamoDB round-trip
6. `stop-cluster` → `resume-cluster` lifecycle
7. `delete-cluster` — full teardown

## Cleanup

If a test fails mid-way, clean up manually:
```bash
eks-dx delete-cluster uat-test
```
