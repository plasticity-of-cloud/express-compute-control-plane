package ai.codriverlabs.ecp.karpenter.model;

import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * Fabric8 model for Karpenter's EC2NodeClass CRD (karpenter.k8s.aws/v1).
 * Cluster-scoped — intentionally does NOT implement Namespaced.
 *
 * @Kind("EC2NodeClass") is required: Fabric8 defaults to the Java class name "Ec2NodeClass",
 * but the actual CRD kind is "EC2NodeClass". Without it, KubernetesDeserializer cannot match
 * incoming admission review or watch events to this class and falls back to GenericKubernetesResource.
 */
@Group("karpenter.k8s.aws")
@Version("v1")
@Kind("EC2NodeClass")
@Singular("ec2nodeclass")
@Plural("ec2nodeclasses")
@ShortNames("ec2nc")
public class Ec2NodeClass extends CustomResource<Ec2NodeClassSpec, Ec2NodeClassStatus> {}
