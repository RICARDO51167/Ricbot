package ricbot.infra.runtime;

import java.nio.file.Path;

/** Raised when an execution-capable store is pointed at a pre-v2 database. */
public final class LegacyRuntimeDatabaseException extends IllegalStateException {
    private final Path database;

    public LegacyRuntimeDatabaseException(Path database, int version) {
        super("runtime database schema v" + version + " is archived-only; run the v2 archive migration first: "
                + database);
        this.database = database;
    }

    public Path database() { return database; }
}
