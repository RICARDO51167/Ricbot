package ricbot.testsupport;

import ricbot.domain.security.ApprovalRequest;
import ricbot.domain.security.ApprovalRequestStore;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryApprovalRequestStore implements ApprovalRequestStore {
    private final ConcurrentHashMap<String, ApprovalRequest> requests = new ConcurrentHashMap<>();

    @Override public ApprovalRequest save(ApprovalRequest request) {
        requests.put(request.requestId(), request);
        return request;
    }

    @Override public Optional<ApprovalRequest> load(String requestId) {
        return Optional.ofNullable(requests.get(requestId));
    }

    @Override public List<ApprovalRequest> list() {
        return List.copyOf(requests.values());
    }
}
