package com.eventseat.client;

/** TCP endpoint configuration; database configuration belongs only to the server. */
final class ClientConfiguration {
    private ClientConfiguration() { }

    static String serverHost() {
        return resolveServerHost(System.getenv("EVENTSEAT_SERVER_HOST"));
    }

    static String resolveServerHost(String value) {
        return value == null || value.isBlank() ? "localhost" : value.trim();
    }
}
