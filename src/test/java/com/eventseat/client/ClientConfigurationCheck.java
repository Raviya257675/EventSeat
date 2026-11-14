package com.eventseat.client;

/** Standalone checks, matching the project's existing regression-test style. */
public final class ClientConfigurationCheck {
    public static void main(String[] args) {
        check("localhost", ClientConfiguration.resolveServerHost(null));
        check("localhost", ClientConfiguration.resolveServerHost(""));
        check("localhost", ClientConfiguration.resolveServerHost("  \t"));
        check("192.168.1.10", ClientConfiguration.resolveServerHost("192.168.1.10"));
        check("eventseat-server", ClientConfiguration.resolveServerHost("eventseat-server"));
        check("192.168.1.10", ClientConfiguration.resolveServerHost(" 192.168.1.10 "));
        if (args.length != 1) throw new AssertionError("Pass expected environment host");
        check(args[0], ClientConfiguration.serverHost());
        System.out.println("PASS: host fallback, custom host, whitespace, and environment lookup");
    }

    private static void check(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
