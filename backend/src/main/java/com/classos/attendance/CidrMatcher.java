package com.classos.attendance;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks whether an IP address falls inside allowed ranges such as "10.20.0.0/16"
 * (the campus Wi-Fi). No ranges configured = the check is switched off.
 * Plain Java, no Spring, so it can be unit-tested on its own.
 */
public final class CidrMatcher {

    private record Block(byte[] network, int prefix) {}

    private final List<Block> blocks = new ArrayList<>();

    public CidrMatcher(List<String> cidrs) {
        if (cidrs == null) return;
        for (String raw : cidrs) {
            String c = raw == null ? "" : raw.trim();
            if (c.isEmpty()) continue;
            String[] parts = c.split("/", 2);
            byte[] addr = literal(parts[0]);
            if (addr == null) throw new IllegalArgumentException("not an IP range: " + c);
            int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : addr.length * 8;
            if (prefix < 0 || prefix > addr.length * 8) throw new IllegalArgumentException("bad prefix in " + c);
            blocks.add(new Block(addr, prefix));
        }
    }

    public boolean enabled() { return !blocks.isEmpty(); }

    public boolean allows(String ip) {
        if (!enabled()) return true;
        byte[] a = literal(ip);
        if (a == null) return false;
        for (Block b : blocks) {
            if (b.network().length == a.length && samePrefix(b.network(), a, b.prefix())) return true;
        }
        return false;
    }

    private static boolean samePrefix(byte[] x, byte[] y, int bits) {
        int full = bits / 8, rest = bits % 8;
        for (int i = 0; i < full; i++) if (x[i] != y[i]) return false;
        if (rest == 0) return true;
        int mask = (0xff << (8 - rest)) & 0xff;
        return (x[full] & mask) == (y[full] & mask);
    }

    /** Parses only IP literals, never host names (which would trigger a DNS lookup). */
    private static byte[] literal(String s) {
        if (s == null || s.isBlank() || !s.matches("[0-9a-fA-F:.]+")) return null;
        try {
            return InetAddress.getByName(s).getAddress();  // IPv4-mapped IPv6 comes back as IPv4
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
