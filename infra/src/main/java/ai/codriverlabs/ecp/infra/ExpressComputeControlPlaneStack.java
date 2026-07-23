package ai.codriverlabs.ecp.infra;

import software.amazon.awscdk.Aws;
import software.amazon.awscdk.CfnCondition;
import software.amazon.awscdk.CfnConditionProps;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Fn;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.AccessLogFormat;
import software.amazon.awscdk.services.apigateway.AuthorizationType;
import software.amazon.awscdk.services.apigateway.CfnBasePathMapping;
import software.amazon.awscdk.services.apigateway.CfnDomainName;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.LogGroupLogDestination;
import software.amazon.awscdk.services.apigateway.MethodLoggingLevel;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.apigateway.IResource;
import software.amazon.awscdk.services.apigateway.StageOptions;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.MetricOptions;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.PointInTimeRecoverySpecification;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.CfnFunction;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionUrl;
import software.amazon.awscdk.services.lambda.FunctionUrlAuthType;
import software.amazon.awscdk.services.lambda.FunctionUrlOptions;
import software.amazon.awscdk.services.lambda.InvokeMode;
import software.amazon.awscdk.services.lambda.LayerVersion;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.amazon.awscdk.CfnParameter;
import software.constructs.Construct;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * CDK stack for Express Compute — at parity with sam.yaml.
 *
 * Deployment modes (CDK context: deploymentMode):
 *   self-managed — credential-service + mgmt-service only (no infra stack dependency)
 *   managed      — adds tenant-service (requires express-compute-infra stack)
 *   hybrid       — same as managed, both flows enabled (default)
 *
 * Lambda functions:
 *   credentialFn  — hot path, SnapStart, JVM (always deployed)
 *   mgmtFn        — cluster/association CRUD, JVM (always deployed)
 *   tenantFn      — async provisioning + SSE stream, GraalVM native arm64, Function URL (managed/hybrid only)
 */
public class ExpressComputeControlPlaneStack extends Stack {

    public ExpressComputeControlPlaneStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);

        // -----------------------------------------------------------------------
        // Stack-level tags
        // -----------------------------------------------------------------------
        Tags.of(this).add("Project", "express-compute-control-plane");

        // -----------------------------------------------------------------------
        // Deployment mode: self-managed | managed | hybrid (default)
        // -----------------------------------------------------------------------
        String deploymentMode = (String) this.getNode().tryGetContext("deploymentMode");
        if (deploymentMode == null || deploymentMode.isBlank()) {
            deploymentMode = "hybrid";
        }
        if (!List.of("self-managed", "managed", "hybrid").contains(deploymentMode)) {
            throw new IllegalArgumentException(
                "Invalid deploymentMode: " + deploymentMode + ". Must be one of: self-managed, managed, hybrid");
        }
        boolean deployTenantService = !deploymentMode.equals("self-managed");
        final String effectiveDeploymentMode = deploymentMode;

        // Resolve asset paths: release bundle uses ../assets/ by default.
        // Development builds use --context development=true to resolve from target/ dirs.
        boolean development = "true".equals(this.getNode().tryGetContext("development"));
        String credentialZip, mgmtZip, tenantZip;
        if (development) {
            String root = Path.of("ecp-credential-service").toFile().isDirectory() ? "" : "../";
            credentialZip = root + "ecp-credential-service/target/function.zip";
            mgmtZip = root + "ecp-mgmt-service/target/function.zip";
            tenantZip = root + "ecp-tenant-service/target/function.zip";
        } else {
            String root = Path.of("assets").toFile().isDirectory() ? "" : "../";
            credentialZip = root + "assets/credential-service.zip";
            mgmtZip = root + "assets/mgmt-service.zip";
            tenantZip = root + "assets/tenant-service.zip";
        }

        // -----------------------------------------------------------------------
        // Parameters
        // -----------------------------------------------------------------------
        CfnParameter domainNameParam = CfnParameter.Builder.create(this, "DomainName")
            .type("String").defaultValue("")
            .description("Custom domain name (leave empty to skip)").build();

        CfnParameter certArnParam = CfnParameter.Builder.create(this, "CertificateArn")
            .type("String").defaultValue("")
            .description("ACM certificate ARN for custom domain").build();

        CfnCondition hasCustomDomain = new CfnCondition(this, "HasCustomDomain",
            CfnConditionProps.builder()
                .expression(Fn.conditionNot(
                    Fn.conditionEquals(domainNameParam.getValueAsString(), "")))
                .build());

        // -----------------------------------------------------------------------
        // DynamoDB Tables
        // -----------------------------------------------------------------------
        Table clustersTable = Table.Builder.create(this, "ClustersTable")
            .tableName("express-compute-clusters")
            .partitionKey(Attribute.builder().name("clusterName").type(AttributeType.STRING).build())
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .removalPolicy(RemovalPolicy.DESTROY)
            .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder().pointInTimeRecoveryEnabled(true).build())
            .build();

        Table associationsTable = Table.Builder.create(this, "AssociationsTable")
            .tableName("express-compute-associations")
            .partitionKey(Attribute.builder().name("PK").type(AttributeType.STRING).build())
            .sortKey(Attribute.builder().name("SK").type(AttributeType.STRING).build())
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .removalPolicy(RemovalPolicy.DESTROY)
            .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder().pointInTimeRecoveryEnabled(true).build())
            .build();

        Table tenantsTable = null;
        if (deployTenantService) {
            tenantsTable = Table.Builder.create(this, "TenantsTable")
                .tableName("express-compute-tenants")
                .partitionKey(Attribute.builder().name("tenantId").type(AttributeType.STRING).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .removalPolicy(RemovalPolicy.DESTROY)
                .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder().pointInTimeRecoveryEnabled(true).build())
                .build();
        }

        // -----------------------------------------------------------------------
        // SSM Parameter lookups (written by express-compute/infra stack)
        // Only required for managed/hybrid modes (tenant provisioning needs VPC + launch templates)
        // -----------------------------------------------------------------------
        String ltArm64Ondemand = null, ltArm64Spot = null, ltX86Ondemand = null, ltX86Spot = null, vpcId = null;
        if (deployTenantService) {
            ltArm64Ondemand = StringParameter.valueForStringParameter(
                this, "/express-compute/infra/launch-template/arm64/ondemand");
            ltArm64Spot = StringParameter.valueForStringParameter(
                this, "/express-compute/infra/launch-template/arm64/spot");
            ltX86Ondemand = StringParameter.valueForStringParameter(
                this, "/express-compute/infra/launch-template/x86_64/ondemand");
            ltX86Spot = StringParameter.valueForStringParameter(
                this, "/express-compute/infra/launch-template/x86_64/spot");
            vpcId = StringParameter.valueForStringParameter(
                this, "/express-compute/infra/network/vpc-id");
        }

        // -----------------------------------------------------------------------
        // CloudWatch Log Group for API Gateway access logs
        // -----------------------------------------------------------------------
        LogGroup apiAccessLogGroup = LogGroup.Builder.create(this, "ApiAccessLogGroup")
            .logGroupName("/aws/apigateway/express-compute")
            .retention(RetentionDays.ONE_MONTH)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();

        // -----------------------------------------------------------------------
        // REST API — IAM auth by default, per-route overrides below
        // -----------------------------------------------------------------------
        RestApi api = RestApi.Builder.create(this, "EcpApi")
            .restApiName("express-compute")
            .deployOptions(StageOptions.builder()
                .stageName("prod")
                .accessLogDestination(new LogGroupLogDestination(apiAccessLogGroup))
                .accessLogFormat(AccessLogFormat.jsonWithStandardFields())
                .loggingLevel(MethodLoggingLevel.INFO)
                .build())
            .defaultMethodOptions(MethodOptions.builder()
                .authorizationType(AuthorizationType.IAM)
                .build())
            .build();

        // -----------------------------------------------------------------------
        // Lambda: credential service  (hot path — SnapStart in JVM, native in PRO)
        // -----------------------------------------------------------------------
        // Context: -c nativeAll=true → credential + mgmt services deploy as native arm64
        boolean nativeAll = "true".equals(this.getNode().tryGetContext("nativeAll"));

        LogGroup credentialLogGroup = LogGroup.Builder.create(this, "CredentialFnLogGroup")
            .logGroupName("/aws/lambda/express-compute-credential-service")
            .retention(RetentionDays.ONE_MONTH)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();

        // Fixed-name broker role — trust anchor for Workload Identity-compatible trust policies
        Role credentialBrokerRole = Role.Builder.create(this, "ECPCredentialBrokerRole")
            .roleName("ECPCredentialBroker-" + Stack.of(this).getRegion())
            .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
            .build();

        Function credentialFn = Function.Builder.create(this, "EcpCredentialFunction")
            .functionName("express-compute-credential-service")
            .runtime(nativeAll ? Runtime.PROVIDED_AL2023 : Runtime.JAVA_25)
            .architecture(nativeAll ? Architecture.ARM_64 : Architecture.X86_64)
            .handler(nativeAll ? "bootstrap" : "io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest")
            .code(Code.fromAsset(credentialZip))
            .memorySize(nativeAll ? 128 : 512)
            .timeout(Duration.seconds(30))
            .role(credentialBrokerRole)
            .environment(Map.of(
                "ECP_CLUSTERS_TABLE", clustersTable.getTableName(),
                "ECP_ASSOCIATIONS_TABLE", associationsTable.getTableName(),
                "AWS_ACCOUNT_ID", Aws.ACCOUNT_ID))
            .logGroup(credentialLogGroup)
            .build();

        // SnapStart on published versions (JVM mode only — native doesn't need it)
        if (!nativeAll) {
            CfnFunction cfnCredential = (CfnFunction) credentialFn.getNode().getDefaultChild();
            cfnCredential.addPropertyOverride("SnapStart", Map.of("ApplyOn", "PublishedVersions"));
        }

        // IAM: GetItem only (not full CRUD) — credential path is read-only on DynamoDB
        credentialFn.addToRolePolicy(PolicyStatement.Builder.create()
            .actions(List.of("dynamodb:GetItem"))
            .resources(List.of(clustersTable.getTableArn(), associationsTable.getTableArn()))
            .build());
        // Workload Identity-compatible broker: AssumeRole + TagSession + SetSourceIdentity
        // No role-name prefix constraint — scoping is via session tag conditions on target trust policies
        credentialFn.addToRolePolicy(PolicyStatement.Builder.create()
            .actions(List.of("sts:AssumeRole", "sts:TagSession", "sts:SetSourceIdentity"))
            .resources(List.of("*"))
            .build());
        // CloudWatch Logs (required since we provide explicit role)
        credentialFn.addToRolePolicy(PolicyStatement.Builder.create()
            .actions(List.of("logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"))
            .resources(List.of("*"))
            .build());

        // -----------------------------------------------------------------------
        // Lambda: mgmt service  (cluster/association CRUD)
        // -----------------------------------------------------------------------
        LogGroup mgmtLogGroup = LogGroup.Builder.create(this, "MgmtFnLogGroup")
            .logGroupName("/aws/lambda/express-compute-mgmt-service")
            .retention(RetentionDays.ONE_MONTH)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();

        Function mgmtFn = Function.Builder.create(this, "EcpMgmtFunction")
            .functionName("express-compute-mgmt-service")
            .runtime(nativeAll ? Runtime.PROVIDED_AL2023 : Runtime.JAVA_25)
            .architecture(nativeAll ? Architecture.ARM_64 : Architecture.X86_64)
            .handler(nativeAll ? "bootstrap" : "io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest")
            .code(Code.fromAsset(mgmtZip))
            .memorySize(nativeAll ? 128 : 256)
            .timeout(Duration.seconds(30))
            .environment(Map.of(
                "ECP_CLUSTERS_TABLE", clustersTable.getTableName(),
                "ECP_ASSOCIATIONS_TABLE", associationsTable.getTableName(),
                "AWS_ACCOUNT_ID", Aws.ACCOUNT_ID))
            .logGroup(mgmtLogGroup)
            .build();

        clustersTable.grantReadWriteData(mgmtFn);
        associationsTable.grantReadWriteData(mgmtFn);
        mgmtFn.addToRolePolicy(PolicyStatement.Builder.create()
            .actions(List.of("iam:GetRole", "iam:ListRoleTags"))
            .resources(List.of("arn:aws:iam::*:role/*"))
            .build());
        // Trust policy management — scoped to roles tagged ecp-managed=true
        mgmtFn.addToRolePolicy(PolicyStatement.Builder.create()
            .actions(List.of("iam:UpdateAssumeRolePolicy"))
            .resources(List.of(String.format("arn:aws:iam::%s:role/*", Aws.ACCOUNT_ID)))
            .conditions(Map.of(
                "StringEquals", Map.of("iam:ResourceTag/ecp-managed", "true")))
            .build());

        // -----------------------------------------------------------------------
        // KMS + Tenant Service (managed/hybrid modes only)
        // -----------------------------------------------------------------------
        Function tenantFn = null;
        FunctionUrl tenantFunctionUrl = null;
        if (deployTenantService) {
            var caSigningKey = software.amazon.awscdk.services.kms.Key.Builder.create(this, "CaSigningKey")
                .alias("express-compute/control-plane/ca-signing-key")
                .description("Shared asymmetric key for signing tenant CA certificates")
                .keySpec(software.amazon.awscdk.services.kms.KeySpec.RSA_2048)
                .keyUsage(software.amazon.awscdk.services.kms.KeyUsage.SIGN_VERIFY)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

            // Context flags:
            //   -c nativeArch=x86   → GraalVM native on x86_64 (build without -Pnative on arm64)
            //   -c jvmTenant=true   → JVM mode on x86_64 (skip native build entirely)
            //   (default)           → GraalVM native on arm64 (prod)
            boolean jvmMode = "true".equals(this.getNode().tryGetContext("jvmTenant"));
            boolean x86Native = "x86".equals(this.getNode().tryGetContext("nativeArch"));
            boolean dryRun = "true".equals(this.getNode().tryGetContext("dryRun"));
            Architecture tenantArch = (jvmMode || x86Native) ? Architecture.X86_64 : Architecture.ARM_64;

            // Write the API endpoint to SSM so EC2 instances can read it at boot
            String endpointParamName = "/express-compute/control-plane/api/endpoint";
            StringParameter.Builder.create(this, "ApiEndpointParam")
                .parameterName(endpointParamName)
                .stringValue(api.getUrl())
                .description("Express Compute API Gateway endpoint — read by EC2 instances at boot")
                .build();

            // Quota: max tenants per caller identity (ownership isolation)
            var maxTenantsPerCaller = StringParameter.Builder.create(this, "MaxTenantsPerCallerParam")
                .parameterName("/express-compute/control-plane/quota/max-tenants-per-caller")
                .stringValue("1")
                .description("Maximum tenants a single IAM identity can provision")
                .build();

            LogGroup tenantLogGroup = LogGroup.Builder.create(this, "TenantFnLogGroup")
                .logGroupName("/aws/lambda/express-compute-tenant-service")
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

            // Lambda Web Adapter layer — bridges HTTP server to Lambda streaming Runtime API
            String region = this.getRegion();
            String webAdapterLayerArn = tenantArch == Architecture.ARM_64
                ? "arn:aws:lambda:" + region + ":753240598075:layer:LambdaAdapterLayerArm64:25"
                : "arn:aws:lambda:" + region + ":753240598075:layer:LambdaAdapterLayerX86:25";
            var webAdapterLayer = LayerVersion.fromLayerVersionArn(this, "LambdaWebAdapter", webAdapterLayerArn);

            tenantFn = Function.Builder.create(this, "EcpTenantFunction")
                .functionName("express-compute-tenant-service")
                .runtime(jvmMode ? Runtime.JAVA_25 : Runtime.PROVIDED_AL2023)
                .architecture(tenantArch)
                .handler(jvmMode ? "run.sh" : "bootstrap")
                .code(Code.fromAsset(tenantZip))
                .layers(List.of(webAdapterLayer))
                .memorySize(jvmMode ? 512 : 256)
                .timeout(Duration.seconds(900))
                .environment(java.util.Map.ofEntries(
                    Map.entry("EXPRESS_COMPUTE_TENANTS_TABLE", tenantsTable.getTableName()),
                    Map.entry("EXPRESS_COMPUTE_CLUSTERS_TABLE", clustersTable.getTableName()),
                    Map.entry("EXPRESS_COMPUTE_LT_ARM64_ONDEMAND", ltArm64Ondemand),
                    Map.entry("EXPRESS_COMPUTE_LT_ARM64_SPOT", ltArm64Spot),
                    Map.entry("EXPRESS_COMPUTE_LT_X86_ONDEMAND", ltX86Ondemand),
                    Map.entry("EXPRESS_COMPUTE_LT_X86_SPOT", ltX86Spot),
                    Map.entry("EXPRESS_COMPUTE_VPC_ID", vpcId),
                    Map.entry("EXPRESS_COMPUTE_KMS_CA_KEY_ID", caSigningKey.getKeyId()),
                    Map.entry("EXPRESS_COMPUTE_AVAILABILITY_ZONE", "auto"),
                    Map.entry("EXPRESS_COMPUTE_DRY_RUN", String.valueOf(dryRun)),
                    Map.entry("EXPRESS_COMPUTE_DEPLOYMENT_MODE", effectiveDeploymentMode),
                    Map.entry("EXPRESS_COMPUTE_MAX_TENANTS_PER_CALLER", maxTenantsPerCaller.getStringValue()),
                    Map.entry("AWS_LWA_INVOKE_MODE", "response_stream"),
                    Map.entry("AWS_LAMBDA_EXEC_WRAPPER", "/opt/bootstrap"),
                    Map.entry("READINESS_CHECK_PATH", "/q/health/ready"),
                    Map.entry("PORT", "8080")))
                .logGroup(tenantLogGroup)
                .build();

            // Single Function URL for both SSE stream and provisioning
            tenantFunctionUrl = tenantFn.addFunctionUrl(FunctionUrlOptions.builder()
                .authType(FunctionUrlAuthType.AWS_IAM)
                .invokeMode(InvokeMode.RESPONSE_STREAM)
                .build());

            StringParameter.Builder.create(this, "TenantStreamUrlParam")
                .parameterName("/express-compute/control-plane/api/stream-url")
                .stringValue(tenantFunctionUrl.getUrl())
                .description("Express Compute tenant Function URL — used for both SSE stream and provisioning")
                .build();

            StringParameter.Builder.create(this, "TenantProvisioningUrlParam")
                .parameterName("/express-compute/control-plane/api/provisioning-url")
                .stringValue(tenantFunctionUrl.getUrl())
                .description("Express Compute tenant provisioning Function URL — same as stream URL")
                .build();

            tenantsTable.grantReadWriteData(tenantFn);
            clustersTable.grantReadWriteData(tenantFn);
            // EC2: read-only on shared VPC infrastructure
            tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                    "ec2:DescribeVpcs", "ec2:DescribeSubnets", "ec2:DescribeRouteTables",
                    "ec2:DescribeSecurityGroups", "ec2:DescribeAddresses", "ec2:DescribeSnapshots"))
                .resources(List.of("*"))
                .build());
            // EC2: mutating actions scoped to shared VPC
            String vpcArn = String.format("arn:aws:ec2:%s:%s:vpc/%s",
                Stack.of(this).getRegion(), Stack.of(this).getAccount(), vpcId);
            tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                    "ec2:CreateSubnet", "ec2:DeleteSubnet",
                    "ec2:CreateSecurityGroup", "ec2:DeleteSecurityGroup",
                    "ec2:AuthorizeSecurityGroupIngress", "ec2:AssociateRouteTable"))
                .resources(List.of(vpcArn,
                    String.format("arn:aws:ec2:%s:%s:subnet/*", Stack.of(this).getRegion(), Stack.of(this).getAccount()),
                    String.format("arn:aws:ec2:%s:%s:security-group/*", Stack.of(this).getRegion(), Stack.of(this).getAccount()),
                    String.format("arn:aws:ec2:%s:%s:route-table/*", Stack.of(this).getRegion(), Stack.of(this).getAccount())))
                .build());
            // EC2: instance lifecycle + EIP/key creation
            tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                    "ec2:RunInstances", "ec2:TerminateInstances",
                    "ec2:StopInstances", "ec2:StartInstances",
                    "ec2:CreateKeyPair",
                    "ec2:DescribeInstances", "ec2:CreateTags",
                    "ec2:AllocateAddress", "ec2:AssociateAddress",
                    "ec2:CancelSpotInstanceRequests", "ec2:DescribeSpotInstanceRequests"))
                .resources(List.of("*"))
                .build());
            // EC2: EIP release/disassociate — scoped to EIPs tagged project=express-compute
            tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("ec2:ReleaseAddress", "ec2:DisassociateAddress"))
                .resources(List.of(String.format("arn:aws:ec2:%s:%s:elastic-ip/*",
                    Stack.of(this).getRegion(), Stack.of(this).getAccount())))
                .conditions(Map.of("StringEquals", Map.of("aws:ResourceTag/project", "express-compute")))
                .build());
            // EC2: key pair deletion — scoped to key pairs tagged project=express-compute
            tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("ec2:DeleteKeyPair"))
                .resources(List.of(String.format("arn:aws:ec2:%s:%s:key-pair/*",
                    Stack.of(this).getRegion(), Stack.of(this).getAccount())))
                .conditions(Map.of("StringEquals", Map.of("aws:ResourceTag/project", "express-compute")))
                .build());
            // EC2: snapshot deletion — scoped to snapshots tagged Platform=express-compute
            tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("ec2:DeleteSnapshot"))
                .resources(List.of(String.format("arn:aws:ec2:%s::snapshot/*",
                    Stack.of(this).getRegion())))
                .conditions(Map.of("StringEquals", Map.of("aws:ResourceTag/Platform", "express-compute")))
                .build());
            tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                    "iam:CreateRole", "iam:DeleteRole",
                    "iam:PutRolePolicy", "iam:DeleteRolePolicy", "iam:PassRole", "iam:TagRole",
                    "iam:AttachRolePolicy", "iam:DetachRolePolicy",
                    "iam:ListRolePolicies", "iam:ListAttachedRolePolicies",
                    "iam:CreateInstanceProfile", "iam:DeleteInstanceProfile",
                    "iam:AddRoleToInstanceProfile", "iam:RemoveRoleFromInstanceProfile"))
                .resources(List.of("arn:aws:iam::*:role/ecp-tenant-*",
                    "arn:aws:iam::*:instance-profile/ecp-tenant-*"))
                .build());
            // DLM: etcd backup lifecycle policies
            tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                    "dlm:CreateLifecyclePolicy",
                    "dlm:DeleteLifecyclePolicy",
                    "dlm:GetLifecyclePolicies",
                    "dlm:TagResource"))
                .resources(List.of("*"))
                .build());
            // SQS: Karpenter interruption queue + per-tenant progress FIFO queue
            tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                    "sqs:CreateQueue", "sqs:DeleteQueue", "sqs:GetQueueUrl",
                    "sqs:TagQueue", "sqs:ReceiveMessage", "sqs:DeleteMessage"))
                .resources(List.of(String.format("arn:aws:sqs:%s:%s:ecp-tenant-*",
                    Stack.of(this).getRegion(), Stack.of(this).getAccount())))
                .build());
            // EventBridge: spot interruption rules
            tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                    "events:PutRule", "events:PutTargets",
                    "events:RemoveTargets", "events:DeleteRule"))
                .resources(List.of(String.format("arn:aws:events:%s:%s:rule/ecp-tenant-*",
                    Stack.of(this).getRegion(), Stack.of(this).getAccount())))
                .build());
            tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                    "secretsmanager:CreateSecret",
                    "secretsmanager:DeleteSecret",
                    "secretsmanager:GetSecretValue"))
                .resources(List.of(
                    "arn:aws:secretsmanager:*:*:secret:ecp/tenant/*"))
                .build());
            // KMS: sign tenant CA certificates
            caSigningKey.grant(tenantFn, "kms:Sign");
            tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("sts:GetCallerIdentity"))
                .resources(List.of("*"))
                .build());
            tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("ssm:GetParameter"))
                .resources(List.of(
                    String.format("arn:aws:ssm:%s:%s:parameter%s",
                        Stack.of(this).getRegion(), Stack.of(this).getAccount(), endpointParamName),
                    String.format("arn:aws:ssm:%s:%s:parameter/express-compute/infra/ami/*",
                        Stack.of(this).getRegion(), Stack.of(this).getAccount())))
                .build());
        } // end deployTenantService

        // -----------------------------------------------------------------------
        // API routes — explicit methods with correct auth per route
        // -----------------------------------------------------------------------
        LambdaIntegration credentialInteg = new LambdaIntegration(credentialFn);
        LambdaIntegration mgmtInteg = new LambdaIntegration(mgmtFn);

        MethodOptions iamAuth = MethodOptions.builder().authorizationType(AuthorizationType.IAM).build();
        MethodOptions noAuth = MethodOptions.builder().authorizationType(AuthorizationType.NONE).build();

        // /clusters
        IResource clusters = api.getRoot().addResource("clusters");
        clusters.addMethod("GET", mgmtInteg, iamAuth);
        clusters.addMethod("POST", mgmtInteg, iamAuth);

        // /clusters/{name}
        IResource clusterByName = clusters.addResource("{name}");
        clusterByName.addMethod("GET", mgmtInteg, iamAuth);
        clusterByName.addMethod("DELETE", mgmtInteg, iamAuth);

        // /clusters/{name}/jwks
        clusterByName.addResource("jwks").addMethod("PUT", mgmtInteg, iamAuth);

        // /clusters/{name}/assets  — OPEN (token validated by Lambda)
        clusterByName.addResource("assets").addMethod("POST", credentialInteg, noAuth);

        // /clusters/{name}/workload-identities
        IResource associations = clusterByName.addResource("workload-identities");
        associations.addMethod("GET", mgmtInteg, noAuth);
        associations.addMethod("POST", mgmtInteg, iamAuth);

        // /clusters/{name}/workload-identities/{id}
        IResource associationById = associations.addResource("{id}");
        associationById.addMethod("GET", mgmtInteg, noAuth);
        associationById.addMethod("DELETE", mgmtInteg, iamAuth);

        // -----------------------------------------------------------------------
        // Custom domain (conditional)
        // -----------------------------------------------------------------------
        CfnDomainName cfnDomain = CfnDomainName.Builder.create(this, "ApiDomainName")
            .domainName(domainNameParam.getValueAsString())
            .regionalCertificateArn(certArnParam.getValueAsString())
            .endpointConfiguration(CfnDomainName.EndpointConfigurationProperty.builder()
                .types(List.of("REGIONAL")).build())
            .build();
        cfnDomain.getCfnOptions().setCondition(hasCustomDomain);

        CfnBasePathMapping cfnMapping = CfnBasePathMapping.Builder.create(this, "ApiMapping")
            .domainName(domainNameParam.getValueAsString())
            .restApiId(api.getRestApiId())
            .stage("prod")
            .build();
        cfnMapping.getCfnOptions().setCondition(hasCustomDomain);
        cfnMapping.addDependency(cfnDomain);

        // -----------------------------------------------------------------------
        // CloudWatch Alarms — named to match SAM
        // -----------------------------------------------------------------------
        Alarm.Builder.create(this, "CredentialErrorAlarm")
            .alarmName("express-compute-credential-errors")
            .metric(credentialFn.metricErrors(MetricOptions.builder().period(Duration.minutes(5)).statistic("Sum").build()))
            .threshold(5).evaluationPeriods(1)
            .treatMissingData(TreatMissingData.NOT_BREACHING).build();

        Alarm.Builder.create(this, "CredentialDurationAlarm")
            .alarmName("express-compute-credential-p99-duration")
            .metric(credentialFn.metricDuration(MetricOptions.builder().period(Duration.minutes(5)).statistic("p99").build()))
            .threshold(5000).evaluationPeriods(3)
            .treatMissingData(TreatMissingData.NOT_BREACHING).build();

        Alarm.Builder.create(this, "MgmtErrorAlarm")
            .alarmName("express-compute-mgmt-errors")
            .metric(mgmtFn.metricErrors(MetricOptions.builder().period(Duration.minutes(5)).statistic("Sum").build()))
            .threshold(5).evaluationPeriods(1)
            .treatMissingData(TreatMissingData.NOT_BREACHING).build();

        // -----------------------------------------------------------------------
        // Outputs
        // -----------------------------------------------------------------------
        CfnOutput.Builder.create(this, "Endpoint")
            .description("Express Compute API endpoint")
            .value(api.getUrl()).build();

        CfnOutput.Builder.create(this, "CustomEndpoint")
            .description("Custom domain endpoint")
            .value("https://" + domainNameParam.getValueAsString())
            .condition(hasCustomDomain)
            .build();

        if (deployTenantService) {
            CfnOutput.Builder.create(this, "TenantStreamFunctionUrl")
                .description("Lambda Function URL for SSE tenant progress stream")
                .value(tenantFunctionUrl.getUrl()).build();
        }

        CfnOutput.Builder.create(this, "ClustersTableName")
            .value(clustersTable.getTableName()).build();

        CfnOutput.Builder.create(this, "AssociationsTableName")
            .value(associationsTable.getTableName()).build();

        if (deployTenantService) {
            CfnOutput.Builder.create(this, "TenantsTableName")
                .value(tenantsTable.getTableName()).build();
        }

        CfnOutput.Builder.create(this, "CredentialFunctionName")
            .value(credentialFn.getFunctionName()).build();

        CfnOutput.Builder.create(this, "MgmtFunctionName")
            .value(mgmtFn.getFunctionName()).build();

        if (deployTenantService) {
            CfnOutput.Builder.create(this, "TenantFunctionName")
                .value(tenantFn.getFunctionName()).build();
        }

        CfnOutput.Builder.create(this, "DeploymentMode")
            .description("Current deployment mode (self-managed, managed, or hybrid)")
            .value(deploymentMode).build();

        CfnOutput.Builder.create(this, "EcpCliPolicyResource")
            .description("IAM resource ARN for CLI execute-api:Invoke policy")
            .value(String.format("arn:aws:execute-api:%s:%s:%s/prod/*/*",
                Stack.of(this).getRegion(),
                Stack.of(this).getAccount(),
                api.getRestApiId()))
            .build();
    }
}
