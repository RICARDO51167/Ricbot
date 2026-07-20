package ricbot.integration.api.console;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JsonlConsoleEventStore implements ConsoleEventStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path eventFile;

    public JsonlConsoleEventStore(Path workspace) {
        Path root = (workspace != null ? workspace : Path.of(".")).toAbsolutePath().normalize();
        this.eventFile = root.resolve(".ricbot").resolve("console-events.jsonl");
    }

    @Override
    public synchronized void append(ConsoleEvent event) {
        if (event == null) {
            return;
        }
        try {
            Files.createDirectories(eventFile.getParent());
            Files.writeString(
                    eventFile,
                    MAPPER.writeValueAsString(event.toMap()) + "\n",
                    StandardCharsets.UTF_8,
                    Files.exists(eventFile)
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE
            );
        } catch (Exception ignored) {
            // Console events must not break normal API behavior.
        }
    }

    @Override
    public List<ConsoleEvent> listBySession(String sessionId, String category, String after, int limit) {
        return filter(sessionId, "", category, after, limit);
    }

    @Override
    public List<ConsoleEvent> listByRun(String runId, String category, String after, int limit) {
        return filter("", runId, category, after, limit);
    }

    @Override
    public ConsoleEvent findById(String eventId) {
        String target = clean(eventId);
        if (target.isBlank()) {
            return null;
        }
        for (ConsoleEvent event : readAll()) {
            if (target.equals(event.id())) {
                return event;
            }
        }
        return null;
    }

    @Override
    public List<ConsoleEvent> listAll(int limit) {
        List<ConsoleEvent> all = readAll();
        if (limit <= 0 || all.size() <= limit) {
            return all;
        }
        return new ArrayList<>(all.subList(Math.max(0, all.size() - limit), all.size()));
    }

    public Path eventFile() {
        return eventFile;
    }

    private List<ConsoleEvent> filter(String sessionId, String runId, String category, String after, int limit) {
        String safeSessionId = clean(sessionId);
        String safeRunId = clean(runId);
        String safeCategory = clean(category).toLowerCase(java.util.Locale.ROOT);
        String cursor = clean(after);
        List<ConsoleEvent> out = new ArrayList<>();
        boolean pastCursor = cursor.isBlank();
        for (ConsoleEvent event : readAll()) {
            if (!safeSessionId.isBlank() && !safeSessionId.equals(event.sessionId())) {
                continue;
            }
            if (!safeRunId.isBlank() && !safeRunId.equals(event.runId())) {
                continue;
            }
            if (!safeCategory.isBlank() && !"all".equals(safeCategory) && !safeCategory.equals(event.category())) {
                continue;
            }
            if (!pastCursor) {
                if (cursor.equals(event.id())) {
                    pastCursor = true;
                }
                continue;
            }
            out.add(event);
            if (limit > 0 && out.size() >= limit) {
                break;
            }
        }
        if (!cursor.isBlank() && !pastCursor) {
            out.clear();
            for (ConsoleEvent event : readAll()) {
                if (!safeSessionId.isBlank() && !safeSessionId.equals(event.sessionId())) {
                    continue;
                }
                if (!safeRunId.isBlank() && !safeRunId.equals(event.runId())) {
                    continue;
                }
                if (!safeCategory.isBlank() && !"all".equals(safeCategory) && !safeCategory.equals(event.category())) {
                    continue;
                }
                out.add(event);
                if (limit > 0 && out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    private synchronized List<ConsoleEvent> readAll() {
        if (!Files.isRegularFile(eventFile)) {
            return List.of();
        }
        List<ConsoleEvent> out = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(eventFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isBlank()) {
                    continue;
                }
                try {
                    ConsoleEvent event = ConsoleEvent.fromMap(MAPPER.readValue(trimmed, MAP_TYPE));
                    if (event != null) {
                        out.add(event);
                    }
                } catch (Exception ignored) {
                    // Skip malformed legacy/corrupted lines.
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return out;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
