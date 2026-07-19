# Supply Chain, Licensing & SBOM

This document describes the software supply chain, dependency licensing, and artifact provenance for the Express Compute project.

---

## License

Express Compute is licensed under the **Elastic License 2.0 (ELv2)**.

- You may use, copy, distribute, and create derivative works freely.
- You may **not** provide it as a hosted/managed service to third parties.
- See [LICENSE.md](../LICENSE.md) for full terms.

---

## Dependency Licensing Summary

All runtime dependencies are compatible with ELv2 distribution:

| Category | License | Examples |
|----------|---------|----------|
| Quarkus framework | Apache 2.0 | `io.quarkus:*` |
| AWS SDK v2 | Apache 2.0 | `software.amazon.awssdk:*` |
| Jackson (JSON) | Apache 2.0 | `com.fasterxml.jackson.*` |
| jose4j (JWT/JWKS) | Apache 2.0 | `org.bitbucket.b_c:jose4j` |
| Fabric8 (K8s client) | Apache 2.0 | `io.fabric8:kubernetes-client` |
| SmallRye (MicroProfile) | Apache 2.0 | `io.smallrye:*` |
| Vert.x | Apache 2.0 / EPL 2.0 | `io.vertx:*` |
| Netty | Apache 2.0 | `io.netty:*` |
| SLF4J / JBoss Logging | MIT / Apache 2.0 | Logging facades |
| PicoCLI | Apache 2.0 | CLI framework |
| Micrometer | Apache 2.0 | Metrics |

**No GPL-only dependencies** are included in runtime artifacts. OpenJDK itself uses GPLv2 with Classpath Exception, which explicitly permits linking and distribution.

---

## Container Base Images

| Image | Used For | License | Redistributable |
|-------|----------|---------|-----------------|
| `quay.io/quarkus/quarkus-distroless-image:2.0` | Native binaries (auth-proxy, webhook, karpenter-support) | UBI EULA (freely redistributable) | ✓ |
| `registry.access.redhat.com/ubi9/openjdk-25-runtime:1.24` | JVM containers (fallback) | UBI EULA + GPLv2+CE (OpenJDK) | ✓ |
| `quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25` | Native compilation (build-time only) | UBI EULA | N/A (not shipped) |

Red Hat Universal Base Images (UBI) are [explicitly freely redistributable](https://developers.redhat.com/articles/ubi-faq) without a Red Hat subscription. Distribution via any registry (GHCR, ECR, DockerHub) is permitted.

---

## Build-Time Tools (Not Shipped)

These are used during compilation and CI but are **not** included in distributed artifacts:

| Tool | License | Purpose |
|------|---------|---------|
| GraalVM / Mandrel 25 | GPLv2+CE | Native image compilation |
| Maven 3.9+ | Apache 2.0 | Build orchestration |
| AWS CDK (Java) | Apache 2.0 | Infrastructure synthesis |
| Docker / containerd | Apache 2.0 | Container image builds |
| GitHub Actions | Proprietary (SaaS) | CI/CD pipeline |

---

## SBOM (Software Bill of Materials)

### Generation

SBOMs are generated automatically during the release pipeline using Quarkus built-in support:

```properties
# Enabled in application.properties for each module
quarkus.package.sbom.enabled=true
quarkus.package.sbom.format=cyclonedx
```

Each release artifact includes:
- `*-sbom.json` — CycloneDX 1.5 format SBOM
- Embedded in native images via `--enable-sbom` GraalVM flag

### Manual Generation

```bash
# Generate SBOM for a specific module
mvn org.cyclonedx:cyclonedx-maven-plugin:makeBom -pl ecp-tenant-service

# Output: target/bom.json (CycloneDX format)
```

### License Report

```bash
# Generate third-party license report
mvn license:third-party-report
# Output: target/site/third-party-report.html
```

---

## Artifact Provenance

### Release Artifacts

Each tagged release (`v*`) produces:

| Artifact | Format | Signed | SBOM |
|----------|--------|--------|------|
| CLI binary (linux/arm64, linux/amd64) | Native executable | SHA256 checksum | Embedded |
| Container images (GHCR) | OCI multi-arch manifest | Signed digest | Included |
| Lambda zips (credential, mgmt, tenant) | ZIP | SHA256 checksum | Included |
| Helm charts (OCI) | tar.gz | SHA256 checksum | Included |
| CDK deployment bundle | tar.gz | SHA256 checksum | Included |

### Checksums

All release artifacts include `checksums.sha256` for integrity verification:

```bash
sha256sum -c checksums.sha256
```

### Container Image Signing (Planned)

Future releases will include cosign signatures for container images:

```bash
cosign verify ghcr.io/plasticity-of-cloud/express-compute-auth-proxy:1.0.3
```

---

## Vulnerability Management

### Dependency Scanning

- **Dependabot**: Monitors all Maven dependencies for known CVEs
- **GitHub Security Advisories**: Automatic alerts for critical vulnerabilities
- **Native image analysis**: GraalVM dead-code elimination reduces attack surface

### Update Policy

- Critical CVEs: Patched within 72 hours
- High CVEs: Patched in next release (4-6 week cycle)
- Medium/Low: Batched in regular dependency updates

---

## Compliance Checklist (GA)

- [x] License: Elastic License 2.0 (ELv2)
- [x] All dependencies: Apache 2.0 / MIT / EPL 2.0 compatible
- [x] Base images: UBI (freely redistributable)
- [x] No GPL-only runtime dependencies
- [x] SBOM generation: CycloneDX format
- [x] Checksums: SHA256 for all release artifacts
- [x] CLA: Required for external contributors
- [ ] Container image signing: cosign (planned)
- [ ] Reproducible builds: Mandrel container ensures determinism (in progress)
- [ ] Third-party notices: Aggregated license texts (generate with `mvn license:aggregate-third-party-report`)

---

## For Enterprise Customers

ELv2 allows you to:
- ✓ Use in production (any scale)
- ✓ Modify and create derivative works
- ✓ Bundle with your own products
- ✓ Use internally without restriction

ELv2 does **not** allow:
- ✗ Offering Express Compute as a hosted/managed service to third parties

For managed service rights or custom licensing: support@codriverlabs.ai
