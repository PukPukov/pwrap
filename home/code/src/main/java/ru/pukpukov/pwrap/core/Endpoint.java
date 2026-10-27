package ru.pukpukov.pwrap.core;

import module java.base;
import static ru.pukpukov.pwrap.core.Json.require;

/** Valid endpoints, not interchangeable strings and integers. No DNS lookup on parsing. */
public final class Endpoint {
    
    public record Port(int value) implements Comparable<Port> {
        public static final Port DNS = new Port(53), DOT = new Port(853);
        public Port { require(value >= 1 && value <= 65535, "Invalid endpoint port: " + value); }
        public int compareTo(Port other) { return Integer.compare(value, other.value); }
        public String toString() { return Integer.toString(value); }
    }
    
    public record Address(String literal) {
        public Address { literal = canonical(literal); }
        public boolean ipv6() { return literal.contains(":"); }
        public String toString() { return literal; }
    }
    
    private static String canonical(String literal) {
        require(!literal.contains("%") && !literal.contains("[") && !literal.contains("]"), "Invalid IP literal: " + literal);
        if (!literal.contains(":")) {
            require(literal.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"), "Invalid IP literal: " + literal);
            for (String part : literal.split("\\.")) require(Integer.parseInt(part) <= 255, "Invalid IP literal: " + literal);
            return literal;
        }
        byte[] bytes = InetAddress.ofLiteral(literal).getAddress();
        if (bytes.length == 4) {
            byte[] mapped = new byte[16]; mapped[10] = -1; mapped[11] = -1;
            System.arraycopy(bytes, 0, mapped, 12, 4); bytes = mapped;
        }
        int[] groups = new int[8]; for (int i = 0; i < 8; i++) groups[i] = (bytes[i * 2] & 255) * 256 + (bytes[i * 2 + 1] & 255);
        int start = -1, length = 1;
        for (int i = 0; i < 8;) {
            if (groups[i] != 0) { i++; continue; }
            int j = i; while (j < 8 && groups[j] == 0) j++;
            if (j - i > length) { start = i; length = j - i; }
            i = j;
        }
        List<String> left = new ArrayList<>(), right = new ArrayList<>();
        if (start < 0) return Arrays.stream(groups).mapToObj(Integer::toHexString).collect(Collectors.joining(":"));
        for (int i = 0; i < start; i++) left.add(Integer.toHexString(groups[i]));
        for (int i = start + length; i < 8; i++) right.add(Integer.toHexString(groups[i]));
        return String.join(":", left) + "::" + String.join(":", right);
    }
    
}