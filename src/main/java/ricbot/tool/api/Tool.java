package ricbot.tool.api;

import java.util.*;

/**
 * 所有工具的抽象基类
 */
public abstract class Tool {

    public abstract String getName();

    public abstract String getDescription();

    public List<ToolParam> getParams() {
        return List.of();
    }

    public boolean isReadOnly() {
        return false;
    }

    public boolean isExclusive() {
        return false;
    }

    /** Whether this tool can reverse a previously successful side effect. */
    public boolean supportsCompensation() {
        return false;
    }

    /**
     * Reverses a prior result. Implementations must validate that the supplied
     * result belongs to the supplied arguments before mutating external state.
     */
    public Object compensate(
            Map<String, Object> params,
            Object previousResult,
            ToolExecutionContext context
    ) throws Exception {
        throw new UnsupportedOperationException("工具 '" + getName() + "' 不支持补偿。");
    }

    public Map<String, Object> castParams(Map<String, Object> params) {
        return params != null ? params : new LinkedHashMap<>();
    }

    public List<String> validateParams(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> actual = params != null ? params : Collections.emptyMap();

        for (ToolParam param : getParams()) {
            if (param.isRequired() && !actual.containsKey(param.getName())) {
                errors.add("缺少必填参数 '" + param.getName() + "'");
                continue;
            }

            if (!actual.containsKey(param.getName())) {
                continue;
            }

            Object value = actual.get(param.getName());
            String expectedType = param.getType();

            if (!ToolParam.typeMatches(expectedType, value)) {
                errors.add("参数 '" + param.getName() + "' 的类型应为 " + expectedType);
            }
        }

        return errors;
    }

    public Map<String, Object> toSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (ToolParam param : getParams()) {
            properties.put(param.getName(), param.toSchema());
            if (param.isRequired()) {
                required.add(param.getName());
            }
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        if (!required.isEmpty()) {
            parameters.put("required", required);
        }

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", getName());
        function.put("description", getDescription());
        function.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);

        return schema;
    }

    public Object execute(Map<String, Object> params, ToolExecutionContext context) throws Exception {
        return execute(params);
    }

    public Object execute(Map<String, Object> params) throws Exception {
        throw new UnsupportedOperationException("工具 '" + getName() + "' 未实现 execute(Map) 方法。");
    }

    public static final class ToolExecutionContext {
        private static final ThreadLocal<ToolExecutionContext> CURRENT =
                ThreadLocal.withInitial(ToolExecutionContext::normal);

        private final boolean approved;
        private final String approvalId;
        private final String idempotencyKey;

        private ToolExecutionContext(boolean approved, String approvalId, String idempotencyKey) {
            this.approved = approved;
            this.approvalId = approvalId != null ? approvalId.trim() : "";
            this.idempotencyKey = idempotencyKey != null ? idempotencyKey.trim() : "";
        }

        public static ToolExecutionContext normal() {
            return new ToolExecutionContext(false, "", "");
        }

        public static ToolExecutionContext approved(String approvalId) {
            return new ToolExecutionContext(true, approvalId, "");
        }

        public static ToolExecutionContext protocol(String idempotencyKey, String approvalId) {
            return new ToolExecutionContext(
                    approvalId != null && !approvalId.isBlank(), approvalId, idempotencyKey);
        }

        public static ToolExecutionContext current() {
            return CURRENT.get();
        }

        public boolean approved() {
            return approved;
        }

        public String approvalId() {
            return approvalId;
        }

        public String idempotencyKey() {
            return idempotencyKey;
        }

        public static Scope activate(ToolExecutionContext context) {
            ToolExecutionContext previous = CURRENT.get();
            CURRENT.set(context != null ? context : normal());
            return () -> CURRENT.set(previous);
        }

        @FunctionalInterface
        public interface Scope extends AutoCloseable {
            @Override
            void close();
        }
    }
}
