package ricbot.domain.task;

public record PatchApplyResult(String taskId, String digest, Status status, String evidence) {
    public enum Status { APPLIED, ALREADY_APPLIED, CONFLICT, EMPTY }
}
