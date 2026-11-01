package app.perroute.backend.core;

import app.perroute.backend.api.RouteObservation;
import app.perroute.service.TunnelHttp;

import javax.naming.directory.InitialDirContext;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Objects;

public final class NetworkDiagnostics {
    @FunctionalInterface
    public interface TextRequest {
        String get(TunnelHttp.Route route, String host, String path, int timeout) throws Exception;
    }

    private final TextRequest request;

    public NetworkDiagnostics() { this(TunnelHttp::getText); }

    public NetworkDiagnostics(TextRequest request) {
        this.request = Objects.requireNonNull(request, "request");
    }

    public RouteObservation lookupPublicAddress(Integer socksPort) {
        var route = socksPort == null ? TunnelHttp.Route.direct() : TunnelHttp.Route.socks(socksPort);
        Exception traceError = null;
        try {
            String body = request.get(route, "www.cloudflare.com", "/cdn-cgi/trace", 6_000);
            String ip = null;
            String country = null;
            for (String line : body.lines().toList()) {
                String[] fields = line.split("=", 2);
                if (fields.length != 2) continue;
                if (fields[0].trim().equals("ip")) ip = fields[1].trim();
                if (fields[0].trim().equals("loc")) country = fields[1].trim();
            }
            if (ip != null && !ip.isBlank()) return new RouteObservation(ip, country, null);
        } catch (Exception error) {
            traceError = error;
        }
        try {
            String ip = request.get(route, "api.ipify.org", "/", 6_000).trim();
            if (!ip.isBlank() && ip.length() <= 45) return new RouteObservation(ip, null, null);
        } catch (Exception error) {
            return failed(error);
        }
        return failed(traceError);
    }

    private RouteObservation failed(Exception error) {
        String message = error == null ? "No answer" : error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return new RouteObservation(null, null, message);
    }

    public List<String> systemDnsServers() {
        try {
            var environment = new Hashtable<String, String>();
            environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            var context = new InitialDirContext(environment);
            try {
                Object provider = context.getEnvironment().get("java.naming.provider.url");
                String urls = provider instanceof String value ? value : "";
                return Arrays.stream(urls.split(" ")).map(url -> url.replaceFirst("^dns://", "")
                        .replaceAll("^[/ ]+|[/ ]+$", "")).filter(url -> !url.isBlank()).distinct().toList();
            } finally {
                context.close();
            }
        } catch (Exception error) {
            return List.of();
        }
    }

    public String publicResolver() {
        try { return InetAddress.getByName("whoami.akamai.net").getHostAddress(); }
        catch (Exception error) { return null; }
    }
}
