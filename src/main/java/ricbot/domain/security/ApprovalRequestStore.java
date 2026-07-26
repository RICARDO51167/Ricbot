package ricbot.domain.security;

import java.util.List;
import java.util.Optional;

public interface ApprovalRequestStore {
    ApprovalRequest save(ApprovalRequest request);
    Optional<ApprovalRequest> load(String requestId);
    List<ApprovalRequest> list();
}
