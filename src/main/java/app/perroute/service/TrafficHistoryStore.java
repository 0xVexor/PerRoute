package app.perroute.service;

import app.perroute.util.Json;
import app.perroute.model.ApplicationIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TrafficHistoryStore implements AutoCloseable {
    private final Path file;
    private final Map<String, Map<String, Counters>> days = new LinkedHashMap<>();
    private boolean dirty;
    private long lastSaveMillis;

    public TrafficHistoryStore(Path file) {
        this.file = file;
        load();
    }

    public synchronized void record(String applicationPath, long downloadBytes, long uploadBytes) {
        if (applicationPath == null || applicationPath.isBlank() || downloadBytes + uploadBytes <= 0) return;
        String day = LocalDate.now().toString();
        Counters counters = days.computeIfAbsent(day, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(applicationPath, ignored -> new Counters());
        counters.down += Math.max(0, downloadBytes);
        counters.up += Math.max(0, uploadBytes);
        dirty = true;
        if (System.currentTimeMillis() - lastSaveMillis >= 10_000) saveQuietly();
    }

    public synchronized Snapshot snapshot() {
        String today = LocalDate.now().toString();
        String month = YearMonth.now().toString();
        long todayDown = 0, todayUp = 0, monthDown = 0, monthUp = 0;
        Map<String, Counters> combined = new LinkedHashMap<>();
        for (var day : days.entrySet()) {
            boolean currentMonth = day.getKey().startsWith(month);
            for (var app : day.getValue().entrySet()) {
                if (day.getKey().equals(today)) { todayDown += app.getValue().down; todayUp += app.getValue().up; }
                if (currentMonth) {
                    monthDown += app.getValue().down; monthUp += app.getValue().up;
                    Counters target = combined.computeIfAbsent(app.getKey(), ignored -> new Counters());
                    target.down += app.getValue().down; target.up += app.getValue().up;
                }
            }
        }
        List<Entry> applications = combined.entrySet().stream()
                .map(item -> new Entry(item.getKey(), item.getValue().down, item.getValue().up))
                .sorted(Comparator.comparingLong((Entry item) -> item.downloadBytes + item.uploadBytes).reversed())
                .toList();
        return new Snapshot(todayDown, todayUp, monthDown, monthUp, applications);
    }

    public synchronized Report report(String applicationFilter) {
        String filter = applicationFilter == null ? "" : ApplicationIdentity.isMac(applicationFilter) ? applicationFilter : applicationFilter.trim();
        String today = LocalDate.now().toString();
        String month = YearMonth.now().toString();
        long todayDown = 0, todayUp = 0, monthDown = 0, monthUp = 0;
        Map<String, Counters> todayApps = new LinkedHashMap<>();
        Map<String, Counters> monthApps = new LinkedHashMap<>();
        List<Day> dayTotals = new ArrayList<>();
        for (var day : days.entrySet()) {
            if (!day.getKey().startsWith(month)) continue;
            long dayDown = 0, dayUp = 0;
            for (var app : day.getValue().entrySet()) {
                Counters monthTarget = monthApps.computeIfAbsent(app.getKey(), ignored -> new Counters());
                monthTarget.down += app.getValue().down; monthTarget.up += app.getValue().up;
                if (day.getKey().equals(today)) {
                    Counters todayTarget = todayApps.computeIfAbsent(app.getKey(), ignored -> new Counters());
                    todayTarget.down += app.getValue().down; todayTarget.up += app.getValue().up;
                }
                if (!filter.isEmpty() && !ApplicationIdentity.same(app.getKey(), filter)) continue;
                dayDown += app.getValue().down; dayUp += app.getValue().up;
            }
            monthDown += dayDown; monthUp += dayUp;
            if (day.getKey().equals(today)) { todayDown = dayDown; todayUp = dayUp; }
            if (dayDown + dayUp > 0) dayTotals.add(new Day(day.getKey(), dayDown, dayUp));
        }
        dayTotals.sort(Comparator.comparing(Day::date).reversed());
        return new Report(todayDown, todayUp, monthDown, monthUp, entries(todayApps), entries(monthApps), List.copyOf(dayTotals));
    }

    private static List<Entry> entries(Map<String, Counters> source) {
        return source.entrySet().stream()
                .map(item -> new Entry(item.getKey(), item.getValue().down, item.getValue().up))
                .sorted(Comparator.comparingLong((Entry item) -> item.downloadBytes + item.uploadBytes).reversed())
                .toList();
    }

    public synchronized void clear() {
        days.clear();
        dirty = true;
        saveQuietly();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            Map<String, Object> root = Json.object(Files.readString(file, StandardCharsets.UTF_8));
            Object rawDays = root.get("days");
            if (!(rawDays instanceof Map<?, ?> dayMap)) return;
            for (var rawDay : dayMap.entrySet()) {
                if (!(rawDay.getValue() instanceof Map<?, ?> apps)) continue;
                Map<String, Counters> targetDay = new LinkedHashMap<>();
                for (var rawApp : apps.entrySet()) {
                    if (!(rawApp.getValue() instanceof Map<?, ?> values)) continue;
                    Counters counters = new Counters();
                    counters.down = number(values.get("down")); counters.up = number(values.get("up"));
                    targetDay.put(String.valueOf(rawApp.getKey()), counters);
                }
                days.put(String.valueOf(rawDay.getKey()), targetDay);
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized void saveQuietly() {
        if (!dirty) return;
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            Map<String, Object> encodedDays = new LinkedHashMap<>();
            for (var day : days.entrySet()) {
                Map<String, Object> apps = new LinkedHashMap<>();
                for (var app : day.getValue().entrySet()) {
                    apps.put(app.getKey(), Map.of("down", app.getValue().down, "up", app.getValue().up));
                }
                encodedDays.put(day.getKey(), apps);
            }
            root.put("days", encodedDays);
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, Json.stringify(root), StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            dirty = false;
            lastSaveMillis = System.currentTimeMillis();
        } catch (IOException ignored) {}
    }

    private static long number(Object value) { return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L; }
    @Override public synchronized void close() { saveQuietly(); }
    private static final class Counters { long down; long up; }
    public record Entry(String applicationPath, long downloadBytes, long uploadBytes) {}
    public record Day(String date, long downloadBytes, long uploadBytes) {}
    public record Report(long todayDownloadBytes, long todayUploadBytes, long monthDownloadBytes, long monthUploadBytes,
                         List<Entry> todayApplications, List<Entry> monthApplications, List<Day> days) {}
    public record Snapshot(long todayDownloadBytes, long todayUploadBytes, long monthDownloadBytes,
                           long monthUploadBytes, List<Entry> applications) {}
}
