# Feature: `ecp get-cluster-access` Command

## Motivation

After `ecp create-cluster <name> --wait` completes, the SSH key is saved locally
to `~/.express-compute/tenants/{region}/{tenantId}.pem` and the public IP is printed once.
If the user later needs to reconnect — after a terminal restart, on a different machine,
or after losing the `.pem` file — there is no command to retrieve the connection details
on demand. They must look up the tenant ID separately and dig through DynamoDB or Lambda
logs.

`ecp get-cluster-access <name>` solves this with a single, ergonomic command.

## Proposed UX

```
$ ecp get-cluster-access my-cluster
  Cluster:    my-cluster
  Public IP:  54.12.34.56
  SSH key:    ~/.express-compute/tenants/us-east-1/a1b2c3d4.pem

  Connect:
  ssh -i ~/.express-compute/tenants/us-east-1/a1b2c3d4.pem ec2-user@54.12.34.56
```

### Flags

| Flag | Effect |
|------|--------|
| `--print-key` | Re-fetch the private key from Secrets Manager and print it to stdout (useful when `.pem` file is lost or on a new machine) |
| `--save-key` | Re-fetch the key from Secrets Manager and save/overwrite the local `.pem` file |
| `--output json` | Emit JSON with `publicIp`, `sshKeyPath`, `tenantId` |

### Error cases

| Condition | Message |
|-----------|---------|
| Cluster not found | `Error: cluster 'X' not found` |
| Cluster not managed (self-managed / no EC2) | `Error: cluster 'X' is self-managed and has no SSH access` |
| Cluster not ready (still provisioning) | `Error: cluster 'X' is not ready yet (state: provisioning, 42%). Use --wait or check back later` |
| Cluster stopped / hibernating | `Error: cluster 'X' is stopped. Resume it first: ecp resume-cluster X` |
| No public IP recorded | `Error: cluster 'X' has no public IP — it may still be booting` |

## Architecture Fit

No backend changes are required. All data is already present:

| Data needed | Source |
|-------------|--------|
| `publicIp` | `TenantItem.publicIp()` — written to DynamoDB when EIP is associated |
| `tenantId` | Returned by `GET /clusters/{name}` (via `ClusterResource.getCluster()`) |
| SSH key path | `EcpConfig.tenantSshKeyPath(region, tenantId)` — already used by `create-cluster --wait` |
| SSH private key | Secrets Manager via `TenantItem.sshKeySecretArn()` (already read by `TenantProvisioningService.getProgress()`) |
| Cluster state | `TenantItem.state()` — ready / provisioning / stopped / failed |

### Call sequence

```
ecp get-cluster-access my-cluster
  │
  ├── GET /clusters/my-cluster   (provisioning Function URL, SigV4)
  │     → returns TenantItem: tenantId, publicIp, state, managed, sshKeySecretArn
  │
  ├── Validate: managed=true, state=ready, publicIp present
  │
  ├── Check local key:  ~/.express-compute/tenants/{region}/{tenantId}.pem
  │     exists? → use it
  │     missing + --save-key / --print-key? → fetch from Secrets Manager
  │
  └── Print connection info
```

The `GET /clusters/{name}` endpoint already exists on `ClusterResource` (tenant-service).
It returns the full `TenantItem` record including `publicIp`, `sshKeySecretArn`, `state`,
and `managed` — everything the command needs.

For `--print-key` / `--save-key`, the CLI calls Secrets Manager directly using the
caller's own AWS credentials (same pattern as other SDK calls in the codebase) — the
`sshKeySecretArn` from the tenant record is the only input needed.

## Implementation Notes

- New file: `ecp-cli/.../cluster/GetClusterAccessCommand.java`
- Registered in `EcpCommand.java` subcommand list
- Follows existing pattern: `@Parameters(index = "0") String name`, inject `EcpApiClient`
- Secrets Manager call uses AWS SDK v2 `SecretsManagerClient` — already a dependency in
  the CLI module (verify before adding); if not, use the SigV4 HTTP client pattern from
  `AwsSigV4Signer` to keep the native binary footprint small
- Key save path: `EcpConfig.tenantSshKeyPath(region, tenantId)` with `rw-------` permissions

## Out of Scope

- Launching the SSH session automatically (`ssh -i ...` exec) — too platform-specific,
  printing the ready-to-run command is sufficient
- kubeconfig generation — separate feature
- Support for self-managed clusters — they have no EC2 instance managed by this system;
  return a clear error
