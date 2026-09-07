package ricbot.domain.runtime;

/** The fixed v6 graph. WAIT is the common suspended state. */
public enum RuntimePhase {
    INGEST, CONTEXT, MODEL, TOOLS, WAIT, COMPACT, DELEGATE, TERMINAL
}
