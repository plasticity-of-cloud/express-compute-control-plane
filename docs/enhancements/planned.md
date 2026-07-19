# Planned Enhancements

## API Gateway Throttling (Usage Plan)

**Priority:** Medium — prevents cost amplification from abuse
**Cost:** Free (built into API Gateway)

Add a usage plan with throttling to limit request rates per API key or globally.

```yaml
# CDK stack addition
EcpUsagePlan:
  Type: AWS::ApiGateway::UsagePlan
  DependsOn: EcpApiprodStage
  Properties:
    UsagePlanName: ecp-default
    Throttle:
      BurstLimit: 50      # max concurrent requests
      RateLimit: 20       # requests per second
    Quota:
      Limit: 100000       # requests per month
      Period: MONTH
    ApiStages:
      - ApiId: !Ref EcpApi
        Stage: prod
```

Considerations:
- The `/assets` endpoint handles credential exchange for all pods — set rate limits high enough for peak pod churn
- Management endpoints (`/clusters`, `/jwks`) are low-traffic — could use a separate, tighter usage plan
- API keys can be issued per-cluster for per-tenant throttling

## IP-Based Resource Policy

**Priority:** Low — defense-in-depth, not strictly required given IAM + token auth
**Cost:** Free

Restrict API Gateway access to known CIDR ranges using a resource policy.

```yaml
# CDK stack addition
EcpApi:
  Type: AWS::Serverless::Api
  Properties:
    # ... existing config ...
    Auth:
      ResourcePolicy:
        IpRangeWhitelist:
          - "203.0.113.0/24"    # Office / VPN
          - "10.0.0.0/8"        # VPC (if using VPC endpoint)
          # Add k3s/microk8s node NAT gateway IPs
```

Considerations:
- Requires knowing all source IPs upfront — breaks if nodes have dynamic public IPs
- Not needed if clusters use a VPN or static NAT gateway
- Can be combined with IAM auth for layered security

## VPC Endpoint (Private API)

**Priority:** Low — for production workloads requiring zero public exposure
**Cost:** ~$14.60/month (2 AZs)

Convert the API Gateway to a private endpoint accessible only from within a VPC.

Considerations:
- Requires all callers (proxy, webhook, CLI) to be in or connected to the VPC
- CLI would need VPN or bastion access
- Best for enterprise deployments where all clusters are VPC-connected

## WAF Rate Limiting

**Priority:** Low — alternative to usage plans with more granular rules
**Cost:** ~$5/month base + $1/rule/month

AWS WAF can apply rate-based rules (e.g., block IPs exceeding 100 req/5min) and geo-restrictions.

Considerations:
- More flexible than usage plans (per-IP rate limiting, geo-blocking)
- Adds latency (~1ms)
- Overkill for current scale

## Recommended Implementation Order

1. **Usage plan with throttling** — free, prevents runaway costs
2. **IP-based resource policy** — free, add when node IPs are stable
3. **WAF** — when you need per-IP rate limiting or geo-blocking
4. **VPC endpoint** — when going fully private for production
