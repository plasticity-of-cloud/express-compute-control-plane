# Contributing to Express Compute

Thank you for your interest in contributing! This document explains
how to set up your development environment, run the test suite, and submit changes.

---

## Contributor License Agreement (CLA)

Before we can merge your Pull Request, you must sign our Contributor License Agreement.
This is a one-time, frictionless process:

1. Open a Pull Request.
2. The CLA Assistant bot will post a comment with a link.
3. Click the link, sign in with GitHub, and accept the agreement.
4. The bot marks your PR as ready — takes about 5 seconds.

The CLA grants Plasticity.Cloud Limited and CoDriverLabs Limited a non-exclusive, perpetual, worldwide,
royalty-free license to use, modify, and distribute your contribution in all versions of
Express Compute (Community and PRO). You retain full copyright ownership of your code.

---

## Prerequisites

- **Java 25** (GraalVM CE or Mandrel for native builds)
- **Maven 3.9+**
- **Docker** (for native container builds and integration tests)
- **AWS CLI** + credentials (for deployment)
- **CDK CLI** (`npm install -g aws-cdk`)

---

## Repository Structure

```
ecp-credential-service/   Lambda: credential exchange (hot path, SnapStart)
ecp-mgmt-service/         Lambda: cluster/association CRUD
ecp-tenant-service/       Lambda: tenant provisioning + lifecycle
ecp-auth-proxy/           In-cluster proxy (TokenReview + forwarding)
ecp-workload-identity-webhook/ Admission webhook (env + volume injection)
ecp-karpenter-support/    EC2NodeClass webhook + reconciler
ecp-cli/                  Native CLI (create-cluster, delete-cluster, etc.)
ecp-model/                Shared library (TokenClaims, CallerIdentity)
infra/                       CDK stack (Java, primary deployment path)
docs/                        Architecture, design, user guides
```

---

## Development Workflow

### 1. Fork and clone

```bash
git clone https://github.com/<your-fork>/express-compute-control-plane.git
cd express-compute-control-plane
```

### 2. Create a feature branch

```bash
git checkout -b feature/my-change    # new features
git checkout -b fix/my-fix           # bug fixes
```

### 3. Build

```bash
# All modules, JVM mode (fast)
./build-local.sh --skip-tests

# Single module
./build-local.sh --only tenant --skip-tests

# Native CLI
./build-local.sh --only cli --native
```

### 4. Run tests

```bash
# All unit tests
mvn test

# Integration tests (requires DynamoDB Local)
docker run -d -p 18000:8000 public.ecr.aws/aws-dynamodb-local/aws-dynamodb-local:latest
mvn test -Dintegration.dynamodb=true
```

All tests must pass before submitting a PR.

### 5. Verify build gate

```bash
./build-local.sh --skip-tests   # all modules compile
mvn test                         # all tests pass
```

---

## Code Style

- **Java**: Follow existing patterns — Quarkus CDI, record types, constants in `TenantNaming`
- **Formatting**: Standard Java conventions (4-space indent, no tabs)
- **Commits**: Use [Conventional Commits](https://www.conventionalcommits.org/) prefixes:
  - `feat:` — new feature
  - `fix:` — bug fix
  - `chore:` — maintenance (deps, CI, docs tooling)
  - `docs:` — documentation only
  - `test:` — test additions or fixes
  - `refactor:` — code restructuring without behavior change
- **One logical change per PR** — keep PRs focused and reviewable

---

## What We Accept

- Bug fixes with a test that reproduces the issue
- Performance improvements with benchmarks or before/after metrics
- Documentation improvements (typos, clarity, new examples)
- New features that align with the project roadmap (open an issue first to discuss)
- Additional integration or E2E tests

---

## What Requires Discussion First

Open a GitHub Issue before investing significant effort on:

- Changes to the DynamoDB schema or API surface
- New AWS service integrations
- New external dependencies
- Architectural changes (new modules, different frameworks)
- Features that overlap with the PRO roadmap

---

## Pull Request Checklist

- [ ] CLA signed (bot will prompt you)
- [ ] Branch is up-to-date with `main`
- [ ] `./build-local.sh --skip-tests && mvn test` passes
- [ ] Commit messages follow Conventional Commits
- [ ] New/changed behavior is covered by tests
- [ ] Documentation updated if user-facing behavior changed

---

## Reporting Security Issues

Do **not** open a public GitHub issue for security vulnerabilities.
Email **support@codriverlabs.ai** with subject `[Security]`. We will respond within 48 hours.

---

## Getting Help

- **Community**: ecosystem@plasticity.cloud
- **Discussions**: GitHub Discussions
- **Bug Reports**: GitHub Issues

Thank you for making Express Compute better!
