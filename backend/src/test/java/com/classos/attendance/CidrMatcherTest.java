package com.classos.attendance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CidrMatcherTest {

    @Test
    void emptyConfigAllowsEverything() {
        CidrMatcher m = new CidrMatcher(List.of());
        assertFalse(m.enabled());
        assertTrue(m.allows("8.8.8.8"));
        assertTrue(new CidrMatcher(List.of("", "  ")).allows("8.8.8.8"));
    }

    @Test
    void matchesIpv4AndIpv6Ranges() {
        CidrMatcher m = new CidrMatcher(List.of("10.20.0.0/16", "192.168.1.0/25", "fd00::/8"));
        assertTrue(m.allows("10.20.5.9"));
        assertFalse(m.allows("10.21.0.1"));
        assertTrue(m.allows("192.168.1.127"));
        assertFalse(m.allows("192.168.1.128"));
        assertTrue(m.allows("fd12:3456::1"));
        assertTrue(m.allows("::ffff:10.20.1.1"));  // IPv4-mapped IPv6
        assertFalse(m.allows("not-an-ip"));
        assertFalse(m.allows(null));
    }

    @Test
    void rejectsBadConfig() {
        assertThrows(IllegalArgumentException.class, () -> new CidrMatcher(List.of("campus.example.com/16")));
        assertThrows(IllegalArgumentException.class, () -> new CidrMatcher(List.of("10.0.0.0/40")));
    }
}
