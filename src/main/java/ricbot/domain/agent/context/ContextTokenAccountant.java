package ricbot.domain.agent.context;

/** Pluggable token SPI. Providers may supply an exact implementation. */
@FunctionalInterface
public interface ContextTokenAccountant {
    TokenEstimate count(ModelRequestShape request);
}
