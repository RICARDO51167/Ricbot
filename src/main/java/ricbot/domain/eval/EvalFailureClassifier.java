package ricbot.domain.eval;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class EvalFailureClassifier {
    private EvalFailureClassifier() {
    }

    static Failure classify(EvalCaseResult result, Exception exception) {
        if (exception != null) {
            String message = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("timeout")) {
                return new Failure("timeout", message);
            }
            if (lower.contains("replay_exhausted")) {
                return new Failure("replay_exhausted", message);
            }
            if (lower.contains("replay_artifact_invalid")) {
                return new Failure("replay_artifact_invalid", message);
            }
            if (lower.contains("api") || lower.contains("model") || lower.contains("provider")) {
                return new Failure("model_error", message);
            }
            return new Failure("harness_error", message);
        }

        if (result == null) {
            return new Failure("harness_error", "missing result");
        }

        List<String> replayErrors = result.getReplayErrors();
        if (replayErrors != null && !replayErrors.isEmpty()) {
            return new Failure("replay_mismatch", replayErrors.get(0));
        }

        List<String> fixtureErrors = result.getFixtureErrors();
        if (fixtureErrors != null && !fixtureErrors.isEmpty()) {
            return new Failure("fixture_error", fixtureErrors.get(0));
        }

        List<String> workspaceRestoreErrors = result.getWorkspaceRestoreErrors();
        if (workspaceRestoreErrors != null && !workspaceRestoreErrors.isEmpty()) {
            return new Failure("workspace_restore_error", workspaceRestoreErrors.get(0));
        }

        List<String> sessionRestoreErrors = result.getSessionRestoreErrors();
        if (sessionRestoreErrors != null && !sessionRestoreErrors.isEmpty()) {
            return new Failure("session_restore_error", sessionRestoreErrors.get(0));
        }

        Map<String, Object> runTrace = result.getRunTrace();
        if (runTrace != null) {
            String stopReason = String.valueOf(runTrace.getOrDefault("stop_reason", ""));
            String error = String.valueOf(runTrace.getOrDefault("error", ""));
            if (!stopReason.isBlank() && !"stop".equals(stopReason)) {
                if ("error".equals(stopReason)) {
                    return new Failure("model_error", !error.isBlank() ? error : "model call failed");
                }
                if (stopReason.contains("tool")) {
                    return new Failure("tool_error", !error.isBlank() ? error : stopReason);
                }
                if (stopReason.contains("iteration") || stopReason.contains("loop")) {
                    return new Failure("not_converged", !error.isBlank() ? error : stopReason);
                }
                return new Failure("agent_incomplete", !error.isBlank() ? error : stopReason);
            }
        }

        List<String> sideEffectViolations = result.getSideEffectViolations();
        if (sideEffectViolations != null && !sideEffectViolations.isEmpty()) {
            return new Failure("side_effect_violation", sideEffectViolations.get(0));
        }

        List<String> assertionErrors = result.getAssertionErrors();
        if (assertionErrors != null && !assertionErrors.isEmpty()) {
            return new Failure("assertion_failed", assertionErrors.get(0));
        }

        if (result.getResponse() == null || result.getResponse().isBlank()) {
            return new Failure("blank_response", "agent produced an empty response");
        }

        if (result.getMaxDurationExceededDetail() != null) {
            return new Failure("latency_budget_exceeded", result.getMaxDurationExceededDetail());
        }

        return null;
    }

    record Failure(String kind, String detail) {
    }
}
