# Roadmap — EKS-DX as an MCP Server

## Status: Planned

## Vision

The rise of autonomous AI agents (coding agents, CI agents, research agents) creates a new class of infrastructure consumer: agents that need **ephemeral, isolated Kubernetes environments** to execute work — and then discard them.

eks-dx is already well-positioned for this:

- **API-driven** — every operation (provision, register, associate, terminate) is a REST call
- **Fast lifecycle** — tenant clusters provision in minutes, terminate in seconds via a single API call
- **Pod Identity** — agents running workloads inside the cluster get scoped AWS credentials automatically, no static key distribution
- **Serverless backend** — no ops overhead; scales to zero between uses

Exposing eks-dx as an [MCP (Model Context Protocol)](https://modelcontextprotocol.io) server lets any MCP-compatible AI agent (Claude, Cursor, Kiro, custom agents) provision and manage clusters as native tool calls — the same way a human uses the CLI.

## The Branch/PR Mental Model

The analogy to Git branches is direct:

```
git checkout -b feature/my-work     →  eks-dx create-tenant
                                        eks-dx create-association
                                        <agent works in isolation>
git push && open PR                 →  agent validates workloads, runs tests
git merge && delete branch          →  eks-dx delete-tenant
```

Each agent task gets its own cluster. Clusters are cheap (single EC2 instance), isolated (dedicated VPC subnets, IAM role, SG), and disposable. Agents never share state or credentials across tasks.

## Proposed MCP Server: `eks-dx-mcp`

A new module (or a thin layer over the existing CLI) that exposes eks-dx operations as MCP tools.

### Tools

| MCP Tool | Maps to | Description |
|----------|---------|-------------|
| `create_tenant` | `POST /tenants` | Provision a new EKS-D cluster |
| `get_tenant` | `GET /tenants/{id}` | Poll provisioning status |
| `delete_tenant` | `DELETE /tenants/{id}` | Terminate and clean up |
| `register_cluster` | `POST /clusters` | Register cluster with JWKS |
| `create_association` | `POST /clusters/{name}/pod-identity-associations` | Map SA → IAM role |
| `list_associations` | `GET /clusters/{name}/pod-identity-associations` | List current mappings |
| `delete_association` | `DELETE /clusters/{name}/pod-identity-associations/{id}` | Remove mapping |
| `delete_cluster` | `DELETE /clusters/{name}` | Deregister cluster |
| `stream_provisioning` | `GET /tenants/{id}/stream` | SSE progress stream during provisioning |

### Resources

MCP resources let agents read structured state without a tool call:

| MCP Resource | Content |
|-------------|---------|
| `tenant://{id}` | Tenant state, EC2 IP, kubeconfig endpoint |
| `cluster://{name}` | Cluster registration, JWKS, issuer |
| `association://{cluster}/{ns}/{sa}` | Pod identity binding |

### Prompts

Pre-built prompt templates for common agentic workflows:

- `provision-and-test` — spin up cluster, deploy workload, validate, terminate
- `debug-pod-identity` — troubleshoot credential flow for a given SA
- `rotate-jwks` — guided JWKS rotation workflow

## Example Agent Workflow

An autonomous coding agent working on a feature that touches S3 permissions:

```
1. Agent calls create_tenant(arch="arm64", k8sVersion="1.31")
   → polls get_tenant() until status=RUNNING

2. Agent calls register_cluster(name="agent-task-42", kubeconfig=...)
   → cluster registered in DynamoDB with JWKS

3. Agent calls create_association(cluster="agent-task-42",
     namespace="default", serviceAccount="workload",
     roleArn="arn:aws:iam::123456789012:role/eks-dx-pod-s3-reader")

4. Agent deploys its workload to the cluster via kubectl
   → Pod Identity Agent + eks-dx-auth-proxy provide real AWS credentials
   → Agent validates S3 access works as expected

5. Agent calls delete_tenant(id=...) → all resources cleaned up
   Agent calls delete_cluster(name="agent-task-42") → DynamoDB entry removed
```

The agent has full AWS credential isolation — its workload assumed `eks-dx-pod-s3-reader`, which only has the permissions it needs, for the duration of the task.

## Why This Matters

**Isolation by default** — each agent task runs in a dedicated cluster with its own subnets, SG, and IAM role. A compromised or buggy agent workload cannot affect other tasks.

**Auditable** — every credential exchange goes through STS AssumeRole with session tags (`clusterName`, `namespace`, `serviceAccount`). CloudTrail shows exactly what each agent task accessed.

**Cost-aligned** — clusters exist only while the agent needs them. An agent that provisions at task start and terminates at task end pays only for the compute it actually used.

**No credential sprawl** — agents never receive static AWS keys. Credentials are short-lived (1h STS sessions) and scoped to a role the agent does not control.

**Composable** — MCP tools compose with other tools in an agent's toolkit. An agent can provision a cluster, run `kubectl` via a separate MCP server, inspect CloudWatch logs, and tear down — all in a single reasoning loop.

## Implementation

The MCP server can be implemented as a thin Java/Quarkus module using the [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk):

```
eks-dx-mcp/
├── src/main/java/.../
│   ├── EksDxMcpServer.java          # MCP server entry point
│   ├── tools/
│   │   ├── TenantTools.java         # create/get/delete tenant
│   │   ├── ClusterTools.java        # register/delete cluster
│   │   └── AssociationTools.java    # create/list/delete associations
│   └── client/
│       └── EksDxApiClient.java      # reuse AwsSigV4Signer from CLI
```

The MCP server talks to the existing API Gateway endpoints using SigV4 signing — the same mechanism as the CLI. No new backend Lambda is needed.

**Transport options:**
- `stdio` — for local agent use (Claude Desktop, Cursor, Kiro)
- `HTTP/SSE` — for remote agents or multi-user deployments

**Native binary** — built with GraalVM (same as CLI) for fast startup. MCP servers on `stdio` transport are invoked per-session; cold start matters.

## Agentic Safety Considerations

- MCP tools should require explicit confirmation for destructive operations (`delete_tenant`, `delete_cluster`) unless the agent is running in an automated pipeline mode
- Tenant quotas per API caller should be enforced (e.g., max 3 concurrent tenants) to prevent runaway agents from accumulating resources
- Session tag conditions on trust policies remain the security boundary — agents cannot escalate beyond what their target IAM roles allow

## Related Documents

- `docs/roadmap/security-hardening/control-plane-managed-oidc-jwks.md` — pre-registration enables agents to get a kubeconfig before the cluster boots
- `docs/roadmap/security-hardening/ssm-only-access.md` — SSM-only access removes the need for agents to manage SSH keys
- `archived (see eks-d-xpress-internal-docs)` — proxy design that agents could use to interact with the cluster API via Lambda/CloudFront without public exposure
