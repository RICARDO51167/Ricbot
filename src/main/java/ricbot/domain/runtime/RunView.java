package ricbot.domain.runtime;

public record RunView(RunState state, long lastCommit, String projectionDigest) { }
