package ai.codriverlabs.eksdx.tenant.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice;
import software.amazon.awssdk.services.ec2.model.AllocateAddressRequest;
import software.amazon.awssdk.services.ec2.model.AssociateAddressRequest;import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.InstanceMetadataOptionsRequest;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.util.Base64;

/**
 * Launches the tenant EC2 instance with user data, tags, and optional Elastic IP.
 */
@ApplicationScoped
public class TenantEc2Service {

    private static final Logger LOG = Logger.getLogger(TenantEc2Service.class);

    private final Ec2Client ec2 = Ec2Client.create();
    private final SsmClient ssm = SsmClient.create();

    public record Ec2Result(String instanceId, String elasticIp, String eipAllocationId) {}

    public Ec2Result launchInstance(String tenantId, String clusterName, String launchTemplateId,
                                   String subnetId, String securityGroupId, String instanceProfileName,
                                   String keyName, String region, String k8sVersion,
                                   boolean assignElasticIp, int diskSizeGb, String arch,
                                   String privateIp, String accountId, String vpcCidr,
                                   String publicSubnetId, String privateSubnetId,
                                   TenantProvisioningService.ProvisionedResources created) {

        String amiId = ssm.getParameter(GetParameterRequest.builder()
            .name("/eks-d-xpress/infra/ami/" + arch + "/" + k8sVersion)
            .build()).parameter().value();

        String userData = Base64.getEncoder().encodeToString(userDataScript(
            tenantId, clusterName, region, k8sVersion, privateIp, accountId, arch, vpcCidr,
            publicSubnetId, privateSubnetId, securityGroupId).getBytes());

        var runRequest = RunInstancesRequest.builder()
            .imageId(amiId)
            .metadataOptions(InstanceMetadataOptionsRequest.builder()
                .httpEndpoint("enabled")
                .httpTokens("required")
                .httpPutResponseHopLimit(2)
                .build())
            .launchTemplate(LaunchTemplateSpecification.builder()
                .launchTemplateId(launchTemplateId).build())
            .subnetId(subnetId)
            .securityGroupIds(securityGroupId)
            .privateIpAddress(privateIp)
            .keyName(keyName)
            .iamInstanceProfile(IamInstanceProfileSpecification.builder()
                .name(instanceProfileName).build())
            .userData(userData)
            .blockDeviceMappings(BlockDeviceMapping.builder()
                .deviceName("/dev/xvda")
                .ebs(EbsBlockDevice.builder().volumeSize(diskSizeGb).build())
                .build())
            .minCount(1).maxCount(1)
            .tagSpecifications(TagSpecification.builder()
                .resourceType(ResourceType.INSTANCE)
                .tags(
                    Tag.builder().key("Name").value(clusterName).build(),
                    Tag.builder().key("eks-dx-tenant").value(tenantId).build(),
                    Tag.builder().key("kubernetes.io/cluster/" + clusterName).value("owned").build(),
                    Tag.builder().key("ebs.csi.aws.com/cluster-name").value(clusterName).build(),
                    Tag.builder().key("Platform").value("eks-dx").build())
                .build())
            .build();

        // IAM instance profiles are eventually consistent — retry up to 30s
        RunInstancesResponse runResp = null;
        for (int attempt = 1; attempt <= 6; attempt++) {
            try {
                runResp = ec2.runInstances(runRequest);
                break;
            } catch (software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
                if (attempt < 6 && e.getMessage().contains("Invalid IAM Instance Profile")) {
                    LOG.infof("Instance profile not yet propagated, retrying (%d/6)...", attempt);
                    try { Thread.sleep(5_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
                } else {
                    throw e;
                }
            }
        }

        String instanceId = runResp.instances().getFirst().instanceId();
        LOG.infof("Launched EC2 instance %s for tenant %s", instanceId, tenantId);

        // Pre-allocate EIP (association happens in stream Phase 1 after instance is running)
        var allocResp = ec2.allocateAddress(AllocateAddressRequest.builder()
            .domain("vpc")
            .tagSpecifications(TagSpecification.builder()
                .resourceType(ResourceType.ELASTIC_IP)
                .tags(Tag.builder().key("Name").value(clusterName).build(),
                      Tag.builder().key("eks-dx-tenant").value(tenantId).build(),
                      Tag.builder().key("eks-dx-eip-persistent").value(String.valueOf(assignElasticIp)).build(),
                      Tag.builder().key("project").value("eks-dx").build())
                .build())
            .build());
        if (created != null) created.eipAllocationId = allocResp.allocationId();

        return new Ec2Result(instanceId, null, allocResp.allocationId());
    }

    /** Associates the pre-allocated EIP once the instance is running. Called from stream Phase 1. */
    public String associateEip(String instanceId, String eipAllocationId) {
        ec2.associateAddress(AssociateAddressRequest.builder()
            .instanceId(instanceId)
            .allocationId(eipAllocationId)
            .build());
        var addrResp = ec2.describeAddresses(r -> r.allocationIds(eipAllocationId));
        String publicIp = addrResp.addresses().getFirst().publicIp();
        LOG.infof("Associated EIP %s to instance %s", publicIp, instanceId);
        return publicIp;
    }

    private String userDataScript(String tenantId, String clusterName,
                                  String region, String k8sVersion, String nodeIp,
                                  String accountId, String arch, String vpcCidr,
                                  String publicSubnetId, String privateSubnetId,
                                  String securityGroupId) {
        String nodeRoleArn = "arn:aws:iam::" + accountId + ":role/eks-dx-t-" + tenantId + "-ir";
        return """
            #!/bin/bash
            mkdir -p /opt/eks-d
            EKS_DX_ENDPOINT=$(aws ssm get-parameter \
              --name /eks-d-xpress/control-plane/api/endpoint \
              --region %s \
              --query Parameter.Value \
              --output text 2>/dev/null || echo "")
            cat > /opt/eks-d/cluster.env <<CONF
            TENANT_ID="%s"
            CLUSTER_NAME="%s"
            NODE_IP="%s"
            AWS_ACCOUNT_ID="%s"
            AWS_REGION="%s"
            NODE_ROLE_ARN="%s"
            CLUSTER_ENDPOINT="https://%s:6443"
            POD_SUBNET="%s"
            PUBLIC_SUBNET_ID="%s"
            PRIVATE_SUBNET_ID="%s"
            SECURITY_GROUP_ID="%s"
            EKS_DX_ENDPOINT="${EKS_DX_ENDPOINT}"
            EKS_DX_API_URL="${EKS_DX_ENDPOINT}/clusters/%s/assets"
            K8S_VERSION="%s"
            CONF
            """.formatted(region, tenantId, clusterName, nodeIp, accountId, region,
                         nodeRoleArn, nodeIp, vpcCidr, publicSubnetId, privateSubnetId,
                         securityGroupId, clusterName, k8sVersion);
    }
}
