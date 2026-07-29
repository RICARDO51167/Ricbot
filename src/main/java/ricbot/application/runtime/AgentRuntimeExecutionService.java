package ricbot.application.runtime;

import ricbot.domain.agent.interfacep.AgentInvocationRuntime;
import ricbot.domain.agent.AgentRunResult;
import ricbot.domain.agent.AgentRunSpec;
import ricbot.domain.agent.AgentGraphFactory;
import ricbot.domain.runtime.AgentRuntime;
import ricbot.domain.runtime.dto.RunRequest;
import ricbot.domain.runtime.dto.RunView;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 将交互式调用适配到单一的生产环境 AgentRuntime API。
 */
public final class AgentRuntimeExecutionService implements AgentInvocationRuntime {
    private final AgentRuntime runtime;
    private final AgentGraphFactory graphs;

    public AgentRuntimeExecutionService(AgentRuntime runtime, AgentGraphFactory graphs) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        this.graphs = java.util.Objects.requireNonNull(graphs, "graphs");
    }

    @Override
    public AgentRunResult run(AgentRunSpec spec) throws Exception {
        // 生成或验证运行ID
        String runId = spec.getRunId() != null && !spec.getRunId().isBlank()
                ? spec.getRunId().trim() : UUID.randomUUID().toString();
        
        Instant started = Instant.now();
        
        try {
            // 准备运行时图结构
            graphs.prepare(runId, spec);
            
            // 构建并启动运行请求
            RunRequest request = new RunRequest(
                    runId, 
                    clean(spec.getSessionKey()), 
                    RunRequest.Mode.AGENT,
                    goal(spec), 
                    runtimeWorkspace(spec), 
                    Math.max(12, spec.getMaxIterations() * 3), 
                    metadata(spec)
            );
            
            RunView view = runtime.start(request);
            
            // 返回成功的执行结果
            return graphs.result(runId, started, view.state());
            
        } catch (Exception failure) {
            // 捕获异常并返回失败结果
            return graphs.failedResult(runId, started, failure);
        } finally {
            // 确保释放资源
            graphs.release(runId);
        }
    }

    /**
     * 从规范中提取元数据信息。
     */
    private static Map<String, Object> metadata(AgentRunSpec spec) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("model", clean(spec.getModel()));
        metadata.put("maxIterations", spec.getMaxIterations());
        metadata.put("maxToolResultChars", spec.getMaxToolResultChars());
        metadata.put("contextWindowTokens", spec.getContextWindowTokens() != null
                ? spec.getContextWindowTokens() : 128_000);
        metadata.put("providerRetryMode", clean(spec.getProviderRetryMode()));
        metadata.put("allowedTools", spec.getAllowedTools() != null ? spec.getAllowedTools() : java.util.List.of());
        metadata.put("toolWorkspace", spec.getWorkspace() != null ? spec.getWorkspace().toString() : "");
        
        // 合并自定义元数据
        if (spec.getMetadata() != null) {
            metadata.putAll(spec.getMetadata());
        }
        
        return Map.copyOf(metadata);
    }

    /**
     * 从初始消息中提取用户目标，优先使用最近的用户消息内容。
     */
    private static String goal(AgentRunSpec spec) {
        if (spec.getInitialMessages() != null) {
            for (int index = spec.getInitialMessages().size() - 1; index >= 0; index--) {
                Map<String, Object> message = spec.getInitialMessages().get(index);
                if ("user".equals(String.valueOf(message.get("role")))) {
                    String content = clean(String.valueOf(message.getOrDefault("content", "")));
                    if (!content.isBlank()) {
                        return content;
                    }
                }
            }
        }
        return "agent invocation";
    }

    /**
     * 清理字符串值：去除首尾空白，空值转为空字符串。
     */
    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    /**
     * 确定运行时工作空间路径，优先使用显式指定的运行时工作空间。
     */
    private static java.nio.file.Path runtimeWorkspace(AgentRunSpec spec) {
        return spec.getRuntimeWorkspace() != null 
                ? spec.getRuntimeWorkspace() 
                : spec.getWorkspace();
    }
}
