package ai.codriverlabs.ecp.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.junit.jupiter.api.Assertions.*;

class ClusterTypeTest {

    @Test
    void shouldHaveFourValues() {
        assertEquals(4, ClusterType.values().length);
    }

    @ParameterizedTest
    @CsvSource({
        "MANAGED, MANAGED",
        "SELF_MANAGED, SELF_MANAGED",
        "EKS_NATIVE, EKS_NATIVE",
        "ECS, ECS",
        "managed, MANAGED",
        "self_managed, SELF_MANAGED",
        "self-managed, SELF_MANAGED",
        "eks_native, EKS_NATIVE",
        "eks-native, EKS_NATIVE",
        "ecs, ECS",
        "Managed, MANAGED"
    })
    void fromStringShouldParseValidValues(String input, ClusterType expected) {
        assertEquals(expected, ClusterType.fromString(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void fromStringShouldDefaultToManagedForNullOrEmpty(String input) {
        assertEquals(ClusterType.MANAGED, ClusterType.fromString(input));
    }

    @Test
    void fromStringShouldDefaultToManagedForUnknownValue() {
        assertEquals(ClusterType.MANAGED, ClusterType.fromString("UNKNOWN"));
        assertEquals(ClusterType.MANAGED, ClusterType.fromString("lambda"));
        assertEquals(ClusterType.MANAGED, ClusterType.fromString("invalid-type"));
    }
}
