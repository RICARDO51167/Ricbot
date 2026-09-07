package ricbot.infra.config;

import java.nio.file.Path;

/** Stable startup failure for an explicitly selected or malformed configuration. */
public final class ConfigLoadException extends IllegalArgumentException {
    private final String code;
    private final Path path;

    public ConfigLoadException(String code, Path path, String message, Throwable cause) {
        super(code + ": " + message + " (" + path + ")", cause);
        this.code = code;
        this.path = path;
    }

    public String code() { return code; }
    public Path path() { return path; }
}
