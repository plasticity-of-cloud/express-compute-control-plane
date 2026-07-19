# Tenant Self-Registration Security

## Problem

When the provisioner Lambda launches an EC2 instance, it gives that instance an IAM role
so it can call `POST /clusters` to register itself with ecp. Without further scoping,
that role could register **any** cluster name — including overwriting an existing tenant's
JWKS or impersonating another cluster.

## Solution: IAM resource ARN scoped to the tenant's own path

The instance profile role's `execute-api:Invoke` permission is scoped to the exact API
Gateway resource path for that tenant:

```json
{
  "Effect": "Allow",
  "Action": "execute-api:Invoke",
  "Resource": "arn:aws:execute-api:{region}:{accountId}:{apiId}/*/POST/clusters/{tenantId}"
}
```

`{tenantId}` is substituted at role creation time by the provisioner Lambda. The instance
can only call `POST /clusters/acme-staging` — not `POST /clusters/acme-production` or any
other path.

## Full instance role policy

```json
[
  {
    "Effect": "Allow",
    "Action": "secretsmanager:GetSecretValue",
    "Resource": "arn:aws:secretsmanager:{region}:{accountId}:secret:ecp/tenant/{tenantId}/*"
  },
  {
    "Effect": "Allow",
    "Action": "execute-api:Invoke",
    "Resource": "arn:aws:execute-api:{region}:{accountId}:{apiId}/*/POST/clusters/{tenantId}"
  },
  {
    "Effect": "Allow",
    "Action": "dynamodb:UpdateItem",
    "Resource": "arn:aws:dynamodb:{region}:{accountId}:table/ecp-tenants",
    "Condition": {
      "ForAllValues:StringEquals": { "dynamodb:LeadingKeys": ["{tenantId}"] }
    }
  }
]
```

Each permission is scoped to the tenant's own resources:

| Permission | Scope |
|---|---|
| `secretsmanager:GetSecretValue` | Only secrets under `ecp/tenant/{tenantId}/` |
| `execute-api:Invoke` | Only `POST /clusters/{tenantId}` — cannot register any other name |
| `dynamodb:UpdateItem` | Only the tenant's own DynamoDB item (leading key condition) |

## Why not a registration token?

A separate one-time token in Secrets Manager would work but adds complexity:
a new auth path in the mgmt-service, token generation logic, and cleanup logic.
IAM resource scoping achieves the same guarantee with no additional code — the
constraint is enforced by AWS IAM before the request reaches the Lambda.

## First-boot registration flow

```
EC2 instance boots (IAM role: ecp-tenant-{tenantId}-instance-role)
  ↓
1. aws secretsmanager get-secret-value ecp/tenant/{tenantId}/signing-key
   → writes /etc/kubernetes/pki/sa.key + sa.pub
2. kubeadm init --service-account-signing-key-file sa.key \
                --service-account-issuer https://{publicIp}
3. Derive JWKS from sa.pub
4. ecp register-cluster {tenantId} --issuer https://{publicIp} --jwks-file /tmp/jwks.json
   → POST /clusters/{tenantId}  (SigV4, signed by instance profile)
   → IAM allows this call only for this tenantId
5. Install ecp-auth-proxy + webhook (pre-baked in AMI)
6. dynamodb:UpdateItem → state: ready
```

Step 4 is the only outbound AWS API call that registers the cluster. If an attacker
compromised the instance and tried to register a different cluster name, IAM would
reject the call with `AccessDeniedException`.
