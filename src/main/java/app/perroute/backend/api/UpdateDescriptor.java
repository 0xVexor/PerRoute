package app.perroute.backend.api;

public record UpdateDescriptor(String version, java.net.URI url, String sha256, long size, boolean newer) {}
