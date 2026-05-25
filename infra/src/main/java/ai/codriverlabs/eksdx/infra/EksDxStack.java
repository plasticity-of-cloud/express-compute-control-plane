package ai.codriverlabs.eksdx.infra;

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
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.CfnFunction;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionUrl;
import software.amazon.awscdk.services.lambda.FunctionUrlAuthType;
import software.amazon.awscdk.services.lambda.FunctionUrlOptions;
import software.amazon.awscdk.services.lambda.InvokeMode;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.amazon.awscdk.CfnParameter;
import software.constructs.Construct;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * CDK stack for EKS-DX — at parity with sam.yaml.
 *
 * Three Lambda functions:
 *   credentialFn  — hot path, SnapStart, JVM
 *   mgmtFn        — cluster/association CRUD, JVM
 *   tenantFn      — async provisioning + SSE stream, GraalVM native arm64, Function URL
 */
public class EksDxStack extends Stack {

    public EksDxStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);

        // Resolve asset paths: works whether invoked from project root (mvn -pl infra)
        // or from infra/ directory (cdk synth)
        String root = Path.of("eks-dx-credential-service").toFile().isDirectory()
            ? "" : "../";

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
            .tableName("eks-dx-clusters")
            .partitionKey(Attribute.builder().name("clusterName").type(AttributeType.STRING).build())
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .removalPolicy(RemovalPolicy.RETAIN)
            .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder().pointInTimeRecoveryEnabled(true).build())
            .build();

        Table associationsTable = Table.Builder.create(this, "AssociationsTable")
            .tableName("eks-dx-associations")
            .partitionKey(Attribute.builder().name("PK").type(AttributeType.STRING).build())
            .sortKey(Attribute.builder().name("SK").type(AttributeType.STRING).build())
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .removalPolicy(RemovalPolicy.RETAIN)
            .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder().pointInTimeRecoveryEnabled(true).build())
            .build();

        Table tenantsTable = Table.Builder.create(this, "TenantsTable")
            .tableName("eks-dx-tenants")
            .partitionKey(Attribute.builder().name("tenantId").type(AttributeType.STRING).build())
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .removalPolicy(RemovalPolicy.RETAIN)
            .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder().pointInTimeRecoveryEnabled(true).build())
            .build();

        // -----------------------------------------------------------------------
        // SSM Parameter lookups (written by Terraform in eks-dx-infra)
        // -----------------------------------------------------------------------
        String launchTemplateId = StringParameter.valueForStringParameter(
            this, "/eks-dx/tenant/launch-template-id");
        String subnetId = StringParameter.valueForStringParameter(
            this, "/eks-dx/tenant/subnet-id");

        // -----------------------------------------------------------------------
        // CloudWatch Log Group for API Gateway access logs
        // -----------------------------------------------------------------------
        LogGroup apiAccessLogGroup = LogGroup.Builder.create(this, "ApiAccessLogGroup")
            .logGroupName("/aws/apigateway/eks-dx")
            .retention(RetentionDays.ONE_MONTH)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();

        // -----------------------------------------------------------------------
        // REST API — IAM auth by default, per-route overrides below
        // -----------------------------------------------------------------------
        RestApi api = RestApi.Builder.create(this, "EksDxApi")
            .restApiName("eks-dx")
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
        // Lambda: credential service  (hot path — SnapStart)
        // -----------------------------------------------------------------------
        Function credentialFn = Function.Builder.create(this, "EksDxCredentialFunction")
            .functionName("eks-dx-credential-service")
            .runtime(Runtime.JAVA_25)
            .handler("io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest")
            .code(Code.fromAsset(root + "eks-dx-credential-service/target/function.zip"))
            .memorySize(512)
            .timeout(Duration.seconds(30))
            .environment(Map.of(
                "EKS_DX_CLUSTERS_TABLE", clustersTable.getTableName(),
                "EKS_DX_ASSOCIATIONS_TABLE", associationsTable.getTableName()))
            .build();

        // SnapStart on published versions
        CfnFunction cfnCredential = (CfnFunction) credentialFn.getNode().getDefaultChild();
        cfnCredential.addPropertyOverride("SnapStart", Map.of("ApplyOn", "PublishedVersions"));

        // IAM: GetItem only (not full CRUD) — credential path is read-only on DynamoDB
        credentialFn.addToRolePolicy(PolicyStatement.Builder.create()
            .actions(List.of("dynamodb:GetItem"))
            .resources(List.of(clustersTable.getTableArn(), associationsTable.getTableArn()))
            .build());
        credentialFn.addToRolePolicy(PolicyStatement.Builder.create()
            .actions(List.of("sts:AssumeRole", "sts:TagSession"))
            .resources(List.of("arn:aws:iam::*:role/eks-dx-pod-*"))
            .build());

        // -----------------------------------------------------------------------
        // Lambda: mgmt service  (cluster/association CRUD)
        // -----------------------------------------------------------------------
        Function mgmtFn = Function.Builder.create(this, "EksDxMgmtFunction")
            .functionName("eks-dx-mgmt-service")
            .runtime(Runtime.JAVA_25)
            .handler("io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest")
            .code(Code.fromAsset(root + "eks-dx-mgmt-service/target/function.zip"))
            .memorySize(256)
            .timeout(Duration.seconds(30))
            .environment(Map.of(
                "EKS_DX_CLUSTERS_TABLE", clustersTable.getTableName(),
                "EKS_DX_ASSOCIATIONS_TABLE", associationsTable.getTableName()))
            .build();

        clustersTable.grantReadWriteData(mgmtFn);
        associationsTable.grantReadWriteData(mgmtFn);
        mgmtFn.addToRolePolicy(PolicyStatement.Builder.create()
            .actions(List.of("iam:GetRole"))
            .resources(List.of("arn:aws:iam::*:role/*"))
            .build());

        // -----------------------------------------------------------------------
        // Lambda: tenant service  (GraalVM native, arm64, SSE via Function URL)
        // -----------------------------------------------------------------------
        // Context flags:
        //   -c nativeArch=x86   → GraalVM native on x86_64 (build without -Pnative on arm64)
        //   -c jvmTenant=true   → JVM mode on x86_64 (skip native build entirely)
        //   (default)           → GraalVM native on arm64 (prod)
        boolean jvmMode = "true".equals(this.getNode().tryGetContext("jvmTenant"));
        boolean x86Native = "x86".equals(this.getNode().tryGetContext("nativeArch"));
        Runtime tenantRuntime = jvmMode ? Runtime.JAVA_25 : Runtime.PROVIDED_AL2023;
        Architecture tenantArch = (jvmMode || x86Native) ? Architecture.X86_64 : Architecture.ARM_64;
        Function tenantFn = Function.Builder.create(this, "EksDxTenantFunction")
            .functionName("eks-dx-tenant-service")
            .runtime(tenantRuntime)
            .architecture(tenantArch)
            .handler("io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest")
            .code(Code.fromAsset(root + "eks-dx-tenant-service/target/function.zip"))
            .memorySize(128)
            .timeout(Duration.seconds(900))
            .environment(Map.of(
                "EKS_DX_TENANTS_TABLE", tenantsTable.getTableName(),
                "EKS_DX_CLUSTERS_TABLE", clustersTable.getTableName(),
                "EKS_DX_LAUNCH_TEMPLATE_ID", launchTemplateId,
                "EKS_DX_SUBNET_ID", subnetId))
            .build();

        // Function URL for SSE /stream endpoint (not via API Gateway)
        FunctionUrl tenantFunctionUrl = tenantFn.addFunctionUrl(FunctionUrlOptions.builder()
            .authType(FunctionUrlAuthType.AWS_IAM)
            .invokeMode(InvokeMode.RESPONSE_STREAM)
            .build());

        tenantsTable.grantReadWriteData(tenantFn);
        tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
            .actions(List.of("dynamodb:DeleteItem"))
            .resources(List.of(clustersTable.getTableArn()))
            .build());
        tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
            .actions(List.of(
                "ec2:RunInstances", "ec2:TerminateInstances",
                "ec2:CreateKeyPair", "ec2:DeleteKeyPair",
                "ec2:DescribeInstances", "ec2:CreateTags"))
            .resources(List.of("*"))
            .build());
        tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
            .actions(List.of(
                "iam:CreateRole", "iam:DeleteRole",
                "iam:PutRolePolicy", "iam:DeleteRolePolicy", "iam:PassRole"))
            .resources(List.of("arn:aws:iam::*:role/eks-dx-tenant-*"))
            .build());
        tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
            .actions(List.of(
                "secretsmanager:CreateSecret",
                "secretsmanager:DeleteSecret",
                "secretsmanager:GetSecretValue"))
            .resources(List.of("arn:aws:secretsmanager:*:*:secret:eks-dx/tenant/*"))
            .build());
        tenantFn.addToRolePolicy(PolicyStatement.Builder.create()
            .actions(List.of("sts:GetCallerIdentity"))
            .resources(List.of("*"))
            .build());

        // -----------------------------------------------------------------------
        // API routes — explicit methods with correct auth per route
        // -----------------------------------------------------------------------
        LambdaIntegration credentialInteg = new LambdaIntegration(credentialFn);
        LambdaIntegration mgmtInteg = new LambdaIntegration(mgmtFn);
        LambdaIntegration tenantInteg = new LambdaIntegration(tenantFn);

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

        // /clusters/{name}/pod-identity-associations
        IResource associations = clusterByName.addResource("pod-identity-associations");
        associations.addMethod("GET", mgmtInteg, noAuth);
        associations.addMethod("POST", mgmtInteg, iamAuth);

        // /clusters/{name}/pod-identity-associations/{id}
        IResource associationById = associations.addResource("{id}");
        associationById.addMethod("GET", mgmtInteg, noAuth);
        associationById.addMethod("DELETE", mgmtInteg, iamAuth);

        // /tenants
        IResource tenants = api.getRoot().addResource("tenants");
        tenants.addMethod("POST", tenantInteg, iamAuth);

        // /tenants/{id}
        IResource tenantById = tenants.addResource("{id}");
        tenantById.addMethod("GET", tenantInteg, iamAuth);
        tenantById.addMethod("DELETE", tenantInteg, iamAuth);

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
            .alarmName("eks-dx-credential-errors")
            .metric(credentialFn.metricErrors(MetricOptions.builder().period(Duration.minutes(5)).statistic("Sum").build()))
            .threshold(5).evaluationPeriods(1)
            .treatMissingData(TreatMissingData.NOT_BREACHING).build();

        Alarm.Builder.create(this, "CredentialDurationAlarm")
            .alarmName("eks-dx-credential-p99-duration")
            .metric(credentialFn.metricDuration(MetricOptions.builder().period(Duration.minutes(5)).statistic("p99").build()))
            .threshold(5000).evaluationPeriods(3)
            .treatMissingData(TreatMissingData.NOT_BREACHING).build();

        Alarm.Builder.create(this, "MgmtErrorAlarm")
            .alarmName("eks-dx-mgmt-errors")
            .metric(mgmtFn.metricErrors(MetricOptions.builder().period(Duration.minutes(5)).statistic("Sum").build()))
            .threshold(5).evaluationPeriods(1)
            .treatMissingData(TreatMissingData.NOT_BREACHING).build();

        // -----------------------------------------------------------------------
        // Outputs
        // -----------------------------------------------------------------------
        CfnOutput.Builder.create(this, "Endpoint")
            .description("EKS-DX API endpoint")
            .value(api.getUrl()).build();

        CfnOutput.Builder.create(this, "CustomEndpoint")
            .description("Custom domain endpoint")
            .value(Fn.conditionIf(hasCustomDomain.getLogicalId(),
                "https://" + domainNameParam.getValueAsString(),
                Aws.NO_VALUE).toString())
            .build();

        CfnOutput.Builder.create(this, "TenantStreamFunctionUrl")
            .description("Lambda Function URL for SSE tenant progress stream")
            .value(tenantFunctionUrl.getUrl()).build();

        CfnOutput.Builder.create(this, "ClustersTableName")
            .value(clustersTable.getTableName()).build();

        CfnOutput.Builder.create(this, "AssociationsTableName")
            .value(associationsTable.getTableName()).build();

        CfnOutput.Builder.create(this, "TenantsTableName")
            .value(tenantsTable.getTableName()).build();

        CfnOutput.Builder.create(this, "CredentialFunctionName")
            .value(credentialFn.getFunctionName()).build();

        CfnOutput.Builder.create(this, "MgmtFunctionName")
            .value(mgmtFn.getFunctionName()).build();

        CfnOutput.Builder.create(this, "TenantFunctionName")
            .value(tenantFn.getFunctionName()).build();

        CfnOutput.Builder.create(this, "EksDxCliPolicyResource")
            .description("IAM resource ARN for CLI execute-api:Invoke policy")
            .value(String.format("arn:aws:execute-api:%s:%s:%s/prod/*/*",
                Stack.of(this).getRegion(),
                Stack.of(this).getAccount(),
                api.getRestApiId()))
            .build();
    }
}
