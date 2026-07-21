# agent

对应生产包 `agent` 的自动化测试，验证正常路径、失败边界和持久化不变量。

## 边界

本说明只描述当前保留的能力。生成产物、运行数据和已删除模块不属于本目录契约。

## 子目录

- `graph/`：对应生产包 `graph` 的自动化测试，验证正常路径、失败边界和持久化不变量。

## 直接文件

- `AgentCommandsTest.java`：验证 Agent Commands Test 的行为与边界。
- `AgentContextServiceTest.java`：验证 Agent Context Service Test 的行为与边界。
- `AgentExecutionServiceTest.java`：验证 Agent Execution Service Test 的行为与边界。
- `AgentHookFactoryTest.java`：验证 Agent Hook Factory Test 的行为与边界。
- `AgentLoopTest.java`：验证 Agent Loop Test 的行为与边界。
- `AgentLoopToolCallTest.java`：验证 Agent Loop Tool Call Test 的行为与边界。
- `AgentNodeSchedulerTest.java`：验证 Agent Node Scheduler Test 的行为与边界。
- `AgentRetryPolicyTest.java`：验证 Agent Retry Policy Test 的行为与边界。
- `AgentRunnerCheckpointResumeTest.java`：验证 Agent Runner Checkpoint Resume Test 的行为与边界。
- `AgentRunnerTest.java`：验证 Agent Runner Test 的行为与边界。
- `AgentTeamWorkerRunnerTest.java`：验证 Agent Team Worker Runner Test 的行为与边界。
- `ContextAssemblerTest.java`：验证 Context Assembler Test 的行为与边界。
- `ContextBuilderStructuredContextTest.java`：验证 Context Builder Structured Context Test 的行为与边界。
- `ContextBuilderTest.java`：验证 Context Builder Test 的行为与边界。
- `ContextSelectionServiceTest.java`：验证 Context Selection Service Test 的行为与边界。
- `FileRunCheckpointStoreTest.java`：验证 File Run Checkpoint Store Test 的行为与边界。
- `FileRunJournalStoreTest.java`：验证 File Run Journal Store Test 的行为与边界。
- `PromptContextBundleTest.java`：验证 Prompt Context Bundle Test 的行为与边界。
- `RunEventReplayServiceTest.java`：验证 Run Event Replay Service Test 的行为与边界。
- `RunEventSinkTest.java`：验证 Run Event Sink Test 的行为与边界。
- `RunRecoveryCoordinatorTest.java`：验证 Run Recovery Coordinator Test 的行为与边界。
- `RunResumeServiceTest.java`：验证 Run Resume Service Test 的行为与边界。
- `RunStateTest.java`：验证 Run State Test 的行为与边界。
- `SessionPersistenceServiceTest.java`：验证 Session Persistence Service Test 的行为与边界。
- `SessionPreparationServiceTest.java`：验证 Session Preparation Service Test 的行为与边界。
- `SharedPersistenceStoresTest.java`：验证 Shared Persistence Stores Test 的行为与边界。
- `SideEffectCoordinatorTest.java`：验证 Side Effect Coordinator Test 的行为与边界。
- `SpawnWorkerServiceTest.java`：验证 Spawn Worker Service Test 的行为与边界。
- `TaskStateTest.java`：验证 Task State Test 的行为与边界。
- `TaskSummaryServiceTest.java`：验证 Task Summary Service Test 的行为与边界。
- `ToolContextInjectorTest.java`：验证 Tool Context Injector Test 的行为与边界。

