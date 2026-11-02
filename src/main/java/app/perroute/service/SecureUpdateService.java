package app.perroute.service;

import app.perroute.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public final class SecureUpdateService {
    public static final String CURRENT_VERSION = BuildIdentity.version();
    public static final String OFFICIAL_FEED = "https://github.com/0xVexor/PerRoute/releases/latest/download/update.json";
    private static final String UPDATE_PUBLIC_KEY =
            "MCowBQYDK2VwAyEAJHclwauxFVQNKkuskeYcPWLr4eSC9emFIsm5fLqkiVw=";
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final long MAX_INSTALLER_BYTES = 250L * 1024L * 1024L;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public UpdateInfo check(String feedUrl) throws Exception {
        URI feed = secureUri(feedUrl, "Update feed");
        HttpRequest request = HttpRequest.newBuilder(feed).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "PerRoute/" + CURRENT_VERSION).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        requireSuccess(response.statusCode(), "Update check");
        if (response.body().length == 0 || response.body().length > MAX_MANIFEST_BYTES) {
            throw new IOException("The update manifest has an unexpected size");
        }
        return parseAndVerify(response.body());
    }

    public UpdateInfo parseAndVerify(byte[] manifestBytes) throws Exception {
        if (manifestBytes == null || manifestBytes.length == 0 || manifestBytes.length > MAX_MANIFEST_BYTES) {
            throw new IOException("The update manifest has an unexpected size");
        }
        Map<String, Object> json = Json.object(new String(manifestBytes, StandardCharsets.UTF_8));
        String version = string(json, "version");
        URI download = secureUri(string(json, "url"), "Update download");
        String sha256 = string(json, "sha256").toLowerCase(Locale.ROOT);
        String signature = string(json, "signature");
        long size = number(json, "size");
        if (!sha256.matches("[0-9a-f]{64}")) throw new IOException("The update checksum is invalid");
        if (size <= 0 || size > MAX_INSTALLER_BYTES) throw new IOException("The update size is invalid");
        verifySignature(version, download.toString(), sha256, size, signature);
        return new UpdateInfo(version, download, sha256, size, compareVersions(version, CURRENT_VERSION) > 0);
    }

    public Path download(UpdateInfo update, Consumer<Integer> progress) throws Exception {
        if (update == null || !update.newer()) throw new IOException("No newer update is available");
        HttpRequest request = HttpRequest.newBuilder(update.url()).timeout(Duration.ofMinutes(10))
                .header("User-Agent", "PerRoute/" + CURRENT_VERSION).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        requireSuccess(response.statusCode(), "Update download");
        Path directory = AppDirectories.dataDirectory().resolve("updates");
        Files.createDirectories(directory);
        Path target = directory.resolve("PerRoute-Setup-" + safeVersion(update.version()) + ".exe");
        Path temporary = target.resolveSibling(target.getFileName() + ".download");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long copied = 0;
        try (InputStream input = response.body(); var output = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                copied += read;
                if (copied > update.size() || copied > MAX_INSTALLER_BYTES) throw new IOException("The update is larger than its signed manifest");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                progress.accept((int)Math.min(100, copied * 100 / update.size()));
            }
        } catch (Throwable error) {
            Files.deleteIfExists(temporary);
            throw error;
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (copied != update.size() || !MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII), update.sha256().getBytes(StandardCharsets.US_ASCII))) {
            Files.deleteIfExists(temporary);
            throw new IOException("The downloaded update failed its signed checksum verification");
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        progress.accept(100);
        return target;
    }

    private static void verifySignature(String version, String url, String sha256, long size, String signatureText) throws Exception {
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(UPDATE_PUBLIC_KEY)));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update((version + "\n" + url + "\n" + sha256 + "\n" + size).getBytes(StandardCharsets.UTF_8));
        byte[] signature;
        try { signature = Base64.getDecoder().decode(signatureText); }
        catch (IllegalArgumentException error) { throw new IOException("The update signature is invalid", error); }
        if (!verifier.verify(signature)) throw new IOException("The update manifest signature is not trusted");
    }

    private static URI secureUri(String value, String label) throws IOException {
        URI uri;
        try { uri = URI.create(value == null ? "" : value.trim()); }
        catch (IllegalArgumentException error) { throw new IOException(label + " URL is invalid", error); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IOException(label + " must use HTTPS");
        }
        return uri;
    }

    private static String string(Map<String, Object> json, String key) throws IOException {
        Object value = json.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IOException("Update manifest is missing " + key);
        return text.trim();
    }

    private static long number(Map<String, Object> json, String key) throws IOException {
        Object value = json.get(key);
        if (!(value instanceof Number number)) throw new IOException("Update manifest is missing " + key);
        return number.longValue();
    }

    private static void requireSuccess(int status, String operation) throws IOException {
        if (status < 200 || status >= 300) throw new IOException(operation + " failed with HTTP " + status);
    }

    public static int compareVersions(String left, String right) {
        String[] a = left.split("[-+]", 2)[0].split("\\.");
        String[] b = right.split("[-+]", 2)[0].split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? parsePart(a[i]) : 0;
            int bv = i < b.length ? parsePart(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int parsePart(String value) {
        try { return Integer.parseInt(value.replaceAll("[^0-9].*$", "")); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String safeVersion(String version) { return version.replaceAll("[^0-9A-Za-z._-]", "_"); }

    public record UpdateInfo(String version, URI url, String sha256, long size, boolean newer) {}
}
