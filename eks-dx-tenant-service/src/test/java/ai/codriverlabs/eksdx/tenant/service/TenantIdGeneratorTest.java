package ai.codriverlabs.eksdx.tenant.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantIdGeneratorTest {

    private final TenantIdGenerator gen = new TenantIdGenerator();

    @Test
    void generate_returns8CharLowercaseHex() {
        String id = gen.generate("user@example.com", "2026-06-26T10:00:00Z");
        assertEquals(8, id.length());
        assertTrue(id.matches("[0-9a-f]{8}"), "Expected lowercase hex, got: " + id);
    }

    @Test
    void generateExtended_returns9Chars() {
        String id = gen.generateExtended("user@example.com", "2026-06-26T10:00:00Z");
        assertEquals(9, id.length());
        assertTrue(id.matches("[0-9a-f]{9}"));
    }

    @Test
    void generate_isDeterministic() {
        String a = gen.generate("user@example.com", "2026-06-26T10:00:00Z");
        String b = gen.generate("user@example.com", "2026-06-26T10:00:00Z");
        assertEquals(a, b);
    }

    @Test
    void generate_extendedSharesPrefix() {
        String base = gen.generate("user@example.com", "2026-06-26T10:00:00Z");
        String ext  = gen.generateExtended("user@example.com", "2026-06-26T10:00:00Z");
        assertTrue(ext.startsWith(base), "Extended ID must start with base ID");
    }

    @Test
    void generate_differentForDifferentUsers() {
        String a = gen.generate("alice@example.com", "2026-06-26T10:00:00Z");
        String b = gen.generate("bob@example.com",   "2026-06-26T10:00:00Z");
        assertNotEquals(a, b);
    }

    @Test
    void generate_differentForSameUserDifferentTimestamps() {
        String a = gen.generate("user@example.com", "2026-06-26T10:00:00Z");
        String b = gen.generate("user@example.com", "2026-06-26T10:00:01Z");
        assertNotEquals(a, b);
    }

    @Test
    void generate_rejectsBlankUserId() {
        assertThrows(IllegalArgumentException.class, () -> gen.generate("", "2026-06-26T10:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> gen.generate(null, "2026-06-26T10:00:00Z"));
    }

    @Test
    void generate_rejectsBlankCreatedAt() {
        assertThrows(IllegalArgumentException.class, () -> gen.generate("user@example.com", ""));
        assertThrows(IllegalArgumentException.class, () -> gen.generate("user@example.com", null));
    }
}
