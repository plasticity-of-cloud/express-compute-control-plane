# Express Compute CLI UAT

Robot Framework acceptance tests for every `ecp` CLI command.

## Quick start

```bash
# Install deps + build CLI + run all mock-mode tests
pip3 install -r tests/uat/requirements.txt
./tests/uat/run-uat.sh

# Run a single suite
robot --variable CLI_JAR:$(ls ecp-cli/target/*-runner.jar) \
  tests/uat/suites/04_get_cluster_access.robot

# Run live tests against a deployed stack
UAT_LIVE=true AWS_REGION=us-east-1 UAT_ROLE_ARN=arn:aws:iam::123:role/uat \
  ./tests/uat/run-uat.sh --suite live
```

## Structure

```
suites/
  01_help_and_version.robot     all --help flags + unknown command
  03_create_cluster.robot       validation, 409, 429, happy path, self-managed
  04_get_cluster_access.robot   all guard conditions + happy path + --save-key
  05_list_describe_cluster.robot
  06_stop_resume_cluster.robot
  07_associations.robot
  live/
    01_full_lifecycle.robot     end-to-end (UAT_LIVE=true only)
```

## Mode

Mock-mode (default): a Python stdlib HTTP server intercepts all CLI calls.
No AWS credentials required. Completes in ~30 seconds.

Live-mode (`UAT_LIVE=true`): runs against a real deployed stack.
See `suites/live/README.md`.
