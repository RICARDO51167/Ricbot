package ricbot.domain.agent;

public final class SideEffectConfirmationRequiredException extends IllegalStateException {
    private final String idempotencyKey;

    public SideEffectConfirmationRequiredException(String idempotencyKey) {
        super("side-effect outcome is uncertain; authorize retry for idempotency key " + idempotencyKey);
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() { return idempotencyKey; }
}
