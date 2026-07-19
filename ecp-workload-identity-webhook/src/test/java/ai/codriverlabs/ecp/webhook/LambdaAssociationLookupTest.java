package ai.codriverlabs.ecp.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class LambdaAssociationLookupTest {

    LambdaAssociationLookup lookup;

    @BeforeEach
    void setUp() {
        lookup = new LambdaAssociationLookup();
        lookup.endpoint = "http://localhost:9999";
        lookup.clusterName = "test-cluster";
    }

    @Test
    void hasAssociation_returnsFalse_whenEndpointUnreachable() {
        // Should not throw, just return false
        assertFalse(lookup.hasAssociation("default", "my-sa"));
    }

    @Test
    void readToken_returnsNull_whenFileDoesNotExist() {
        assertNull(lookup.readToken());
    }
}
