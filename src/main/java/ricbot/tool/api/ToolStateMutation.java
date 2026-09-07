package ricbot.tool.api;

import java.util.Map;
import java.util.Set;

public sealed interface ToolStateMutation permits ToolStateMutation.SetToolExposure,
        ToolStateMutation.RecordFileReadReceipt, ToolStateMutation.InvalidateFileReadReceipts {
    record SetToolExposure(Set<ToolGroup> activeGroups) implements ToolStateMutation {
        public SetToolExposure { activeGroups = Set.copyOf(activeGroups != null ? activeGroups : Set.of(ToolGroup.BASIC)); }
    }
    record RecordFileReadReceipt(String key, Map<String, Object> receipt) implements ToolStateMutation {
        public RecordFileReadReceipt { key = key != null ? key : ""; receipt = Map.copyOf(receipt != null ? receipt : Map.of()); }
    }
    record InvalidateFileReadReceipts(Set<String> keys, String reason) implements ToolStateMutation {
        public InvalidateFileReadReceipts { keys = Set.copyOf(keys != null ? keys : Set.of()); reason = reason != null ? reason : ""; }
    }
}
