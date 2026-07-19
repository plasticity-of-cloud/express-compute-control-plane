package ai.codriverlabs.ecp.tenant.service;

import ai.codriverlabs.ecp.tenant.TenantNaming;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AssociateRouteTableRequest;
import software.amazon.awssdk.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.CreateSubnetRequest;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeRouteTablesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVpcsRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.UserIdGroupPair;

import java.util.List;

/**
 * Creates per-tenant network isolation: dedicated subnets + security group.
 *
 * Subnet CIDR allocation:
 *   Public:  10.0.{index}.0/24
 *   Private: 10.0.{100+index}.0/24
 *
 * Index is auto-calculated from existing tenant subnets (SubnetIndex tag).
 */
@ApplicationScoped
public class TenantNetworkService {

    private static final Logger LOG = Logger.getLogger(TenantNetworkService.class);

    private final Ec2Client ec2 = Ec2Client.create();

    public record NetworkResult(String publicSubnetId, String privateSubnetId, String securityGroupId, String controlPlaneIp, String vpcCidr) {}

    /**
     * Subnet IP layout (per /24 tenant subnet):
     *   .0-.3  — AWS reserved (network, router, DNS, future)
     *   .4     — reserved for tenant workstation / CI runner
     *   .5     — control-plane node (static, used as --node-ip and controlPlaneEndpoint)
     *   .6+    — DHCP pool (VPC CNI secondary IPs, future worker nodes)
     */
    private static final int CONTROL_PLANE_HOST_OFFSET = 5;

    /**
     * Creates per-tenant public subnet, private subnet, and security group.
     * On partial failure, cleans up any resources created before the error.
     */
    public NetworkResult createTenantNetwork(String tenantId, String clusterName, String vpcId, String availabilityZone, String sshCidr) {
        String vpcCidr = ec2.describeVpcs(DescribeVpcsRequest.builder()
            .vpcIds(vpcId).build()).vpcs().getFirst().cidrBlock();

        int subnetIndex = nextAvailableSubnetIndex(vpcId);

        String publicSubnetId = null;
        String privateSubnetId = null;
        String sgId = null;

        try {
            // Public subnet
            String publicCidr = "10.0." + subnetIndex + ".0/24";
            publicSubnetId = ec2.createSubnet(CreateSubnetRequest.builder()
                .vpcId(vpcId)
                .cidrBlock(publicCidr)
                .availabilityZone(availabilityZone)
                .tagSpecifications(software.amazon.awssdk.services.ec2.model.TagSpecification.builder()
                    .resourceType(software.amazon.awssdk.services.ec2.model.ResourceType.SUBNET)
                    .tags(
                        Tag.builder().key("Name").value(tenantId + "-public-subnet").build(),
                        Tag.builder().key("SubnetIndex").value(String.valueOf(subnetIndex)).build(),
                        Tag.builder().key("SubnetType").value("Public").build(),
                        Tag.builder().key("ecp-tenant").value(tenantId).build(),
                        Tag.builder().key("kubernetes.io/cluster/" + clusterName).value("owned").build(),
                        Tag.builder().key("kubernetes.io/role/elb").value("1").build(),
                        Tag.builder().key("Platform").value("express-compute").build())
                    .build())
                .build()).subnet().subnetId();
            LOG.infof("Created public subnet %s (%s) for tenant %s", publicSubnetId, publicCidr, tenantId);

            // Private subnet
            String privateCidr = "10.0." + (100 + subnetIndex) + ".0/24";
            privateSubnetId = ec2.createSubnet(CreateSubnetRequest.builder()
                .vpcId(vpcId)
                .cidrBlock(privateCidr)
                .availabilityZone(availabilityZone)
                .tagSpecifications(software.amazon.awssdk.services.ec2.model.TagSpecification.builder()
                    .resourceType(software.amazon.awssdk.services.ec2.model.ResourceType.SUBNET)
                    .tags(
                        Tag.builder().key("Name").value(tenantId + "-private-subnet").build(),
                        Tag.builder().key("SubnetType").value("Private").build(),
                        Tag.builder().key("ecp-tenant").value(tenantId).build(),
                        Tag.builder().key("kubernetes.io/cluster/" + clusterName).value("owned").build(),
                        Tag.builder().key("kubernetes.io/role/internal-elb").value("1").build(),
                        Tag.builder().key("Platform").value("express-compute").build())
                    .build())
                .build()).subnet().subnetId();
            LOG.infof("Created private subnet %s (%s) for tenant %s", privateSubnetId, privateCidr, tenantId);

            // Route table associations
            String publicRtId = findRouteTable(vpcId, "ecp-managed-k8s-infra-public-rt");
            String privateRtId = findRouteTable(vpcId, "ecp-managed-k8s-infra-private-rt");
            ec2.associateRouteTable(AssociateRouteTableRequest.builder()
                .subnetId(publicSubnetId).routeTableId(publicRtId).build());
            ec2.associateRouteTable(AssociateRouteTableRequest.builder()
                .subnetId(privateSubnetId).routeTableId(privateRtId).build());
            LOG.infof("Associated route tables for tenant %s", tenantId);

            // Security group
            sgId = createTenantSecurityGroup(tenantId, clusterName, vpcId, vpcCidr, sshCidr);

            // Static control-plane IP: <subnet-base>.5
            String controlPlaneIp = "10.0." + subnetIndex + "." + CONTROL_PLANE_HOST_OFFSET;

            return new NetworkResult(publicSubnetId, privateSubnetId, sgId, controlPlaneIp, vpcCidr);
        } catch (Exception e) {
            // Clean up partially created resources
            LOG.warnf("Network creation failed for tenant %s, cleaning up: %s", tenantId, e.getMessage());
            if (sgId != null) {
                String sg = sgId;
                try { ec2.deleteSecurityGroup(r -> r.groupId(sg)); } catch (Exception ex) { /* best effort */ }
            }
            if (privateSubnetId != null) {
                String priv = privateSubnetId;
                try { ec2.deleteSubnet(r -> r.subnetId(priv)); } catch (Exception ex) { /* best effort */ }
            }
            if (publicSubnetId != null) {
                String pub = publicSubnetId;
                try { ec2.deleteSubnet(r -> r.subnetId(pub)); } catch (Exception ex) { /* best effort */ }
            }
            throw e;
        }
    }

    private String createTenantSecurityGroup(String tenantId, String clusterName, String vpcId, String vpcCidr, String sshCidr) {
        String sgName = TenantNaming.securityGroupName(tenantId);
        String sgId = ec2.createSecurityGroup(CreateSecurityGroupRequest.builder()
            .groupName(sgName)
            .description("EKS-D tenant: SSH, Kubernetes API, kubelet, pod networking")
            .vpcId(vpcId)
            .tagSpecifications(software.amazon.awssdk.services.ec2.model.TagSpecification.builder()
                .resourceType(software.amazon.awssdk.services.ec2.model.ResourceType.SECURITY_GROUP)
                .tags(
                    Tag.builder().key("Name").value(sgName).build(),
                    Tag.builder().key("ecp-tenant").value(tenantId).build(),
                    Tag.builder().key("kubernetes.io/cluster/" + clusterName).value("owned").build(),
                    Tag.builder().key("Platform").value("express-compute").build())
                .build())
            .build()).groupId();

        // SSH from VPC
        ec2.authorizeSecurityGroupIngress(AuthorizeSecurityGroupIngressRequest.builder()
            .groupId(sgId)
            .ipPermissions(
                // SSH from caller CIDR
                IpPermission.builder()
                    .ipProtocol("tcp").fromPort(22).toPort(22)
                    .ipRanges(IpRange.builder().cidrIp(sshCidr).description("SSH").build())
                    .build(),
                // Kubernetes API server (6443) from VPC
                IpPermission.builder()
                    .ipProtocol("tcp").fromPort(6443).toPort(6443)
                    .ipRanges(IpRange.builder().cidrIp(vpcCidr).description("Kubernetes API").build())
                    .build(),
                // Kubelet (10250) from VPC
                IpPermission.builder()
                    .ipProtocol("tcp").fromPort(10250).toPort(10250)
                    .ipRanges(IpRange.builder().cidrIp(vpcCidr).description("Kubelet API").build())
                    .build(),
                // All traffic within SG (pod networking between nodes)
                IpPermission.builder()
                    .ipProtocol("-1")
                    .userIdGroupPairs(UserIdGroupPair.builder().groupId(sgId).description("Pod networking").build())
                    .build())
            .build());

        LOG.infof("Created security group %s (%s) for tenant %s", sgId, sgName, tenantId);
        return sgId;
    }

    private String findRouteTable(String vpcId, String nameTag) {
        return ec2.describeRouteTables(DescribeRouteTablesRequest.builder()
            .filters(
                Filter.builder().name("vpc-id").values(vpcId).build(),
                Filter.builder().name("tag:Name").values(nameTag).build())
            .build()).routeTables().getFirst().routeTableId();
    }

    /**
     * Find next available subnet index by scanning existing SubnetIndex tags.
     */
    private int nextAvailableSubnetIndex(String vpcId) {
        List<Subnet> subnets = ec2.describeSubnets(DescribeSubnetsRequest.builder()
            .filters(
                Filter.builder().name("vpc-id").values(vpcId).build(),
                Filter.builder().name("tag:SubnetType").values("Public").build(),
                Filter.builder().name("tag:ecp-tenant").values("*").build())
            .build()).subnets();

        int maxIndex = 0;
        for (Subnet subnet : subnets) {
            for (var tag : subnet.tags()) {
                if ("SubnetIndex".equals(tag.key())) {
                    int idx = Integer.parseInt(tag.value());
                    if (idx > maxIndex) maxIndex = idx;
                }
            }
        }
        return maxIndex + 1;
    }

    /**
     * Best-effort cleanup of tenant network resources.
     */
    public void deleteTenantNetwork(NetworkResult network) {
        if (network.securityGroupId() != null) {
            ec2.deleteSecurityGroup(software.amazon.awssdk.services.ec2.model.DeleteSecurityGroupRequest.builder()
                .groupId(network.securityGroupId()).build());
        }
        if (network.publicSubnetId() != null) {
            ec2.deleteSubnet(software.amazon.awssdk.services.ec2.model.DeleteSubnetRequest.builder()
                .subnetId(network.publicSubnetId()).build());
        }
        if (network.privateSubnetId() != null) {
            ec2.deleteSubnet(software.amazon.awssdk.services.ec2.model.DeleteSubnetRequest.builder()
                .subnetId(network.privateSubnetId()).build());
        }
    }
}
