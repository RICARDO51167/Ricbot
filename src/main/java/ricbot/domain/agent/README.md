# agent

Agent Graph、Run Journal、Checkpoint、恢复、上下文和单次执行核心。

## 边界

本说明只描述当前保留的能力。生成产物、运行数据和已删除模块不属于本目录契约。

## 子目录

- `graph/`：通用 Agent Graph 的节点、边、条件路由、游标和执行状态。

## 直接文件

- `AgentCommands.java`：Agent Commands：本包内的领域类型或协作组件。
- `AgentContextService.java`：Agent Context Service：封装该领域用例或业务规则。
- `AgentExecutionService.java`：Agent Execution Service：封装该领域用例或业务规则。
- `AgentHookFactory.java`：Agent Hook Factory：本包内的领域类型或协作组件。
- `AgentLoop.java`：Agent Loop：驱动对应执行生命周期。
- `AgentNodeScheduler.java`：Agent Node Scheduler：本包内的领域类型或协作组件。
- `AgentNodeState.java`：Agent Node State：执行结果、报告或状态值对象。
- `AgentNodeType.java`：Agent Node Type：本包内的领域类型或协作组件。
- `AgentPersistenceComponents.java`：Agent Persistence Components：本包内的领域类型或协作组件。
- `AgentPersistenceFactory.java`：Agent Persistence Factory：本包内的领域类型或协作组件。
- `AgentRequestContext.java`：Agent Request Context：本包内的领域类型或协作组件。
- `AgentRetryPolicy.java`：Agent Retry Policy：本包内的领域类型或协作组件。
- `AgentRunController.java`：Agent Run Controller：本包内的领域类型或协作组件。
- `AgentRunResult.java`：Agent Run Result：执行结果、报告或状态值对象。
- `AgentRunSpec.java`：Agent Run Spec：声明配置、选项或不可变执行规格。
- `AgentRunner.java`：Agent Runner：驱动对应执行生命周期。
- `AgentRuntimeCore.java`：Agent Runtime Core：驱动对应执行生命周期。
- `AgentRuntimeCoreFactory.java`：Agent Runtime Core Factory：驱动对应执行生命周期。
- `AgentTeamWorkerRunner.java`：Agent Team Worker Runner：驱动对应执行生命周期。
- `AuditedSideEffectStore.java`：Audited Side Effect Store：负责状态持久化、读取或查询。
- `AutoCompact.java`：Auto Compact：本包内的领域类型或协作组件。
- `CheckpointService.java`：Checkpoint Service：封装该领域用例或业务规则。
- `ContextAssembler.java`：Context Assembler：本包内的领域类型或协作组件。
- `ContextBuilder.java`：Context Builder：本包内的领域类型或协作组件。
- `ContextCommandRenderer.java`：Context Command Renderer：本包内的领域类型或协作组件。
- `ContextQualityReport.java`：Context Quality Report：执行结果、报告或状态值对象。
- `ContextSelectionService.java`：Context Selection Service：封装该领域用例或业务规则。
- `ContextSource.java`：Context Source：本包内的领域类型或协作组件。
- `ExecutableRunFork.java`：Executable Run Fork：本包内的领域类型或协作组件。
- `ExecutionOutcome.java`：Execution Outcome：本包内的领域类型或协作组件。
- `FileRunCheckpointStore.java`：File Run Checkpoint Store：负责状态持久化、读取或查询。
- `FileRunJournalStore.java`：File Run Journal Store：负责状态持久化、读取或查询。
- `FileSideEffectStore.java`：File Side Effect Store：负责状态持久化、读取或查询。
- `GraphRunService.java`：Graph Run Service：封装该领域用例或业务规则。
- `ModelNodeExecutor.java`：Model Node Executor：本包内的领域类型或协作组件。
- `OpenTelemetryRunEventSink.java`：Open Telemetry Run Event Sink：事件、消息或审计事实模型。
- `PersistenceResult.java`：Persistence Result：执行结果、报告或状态值对象。
- `PreparedSessionContext.java`：Prepared Session Context：本包内的领域类型或协作组件。
- `PromptContextBundle.java`：Prompt Context Bundle：本包内的领域类型或协作组件。
- `ResumePoint.java`：Resume Point：本包内的领域类型或协作组件。
- `RunCheckpoint.java`：Run Checkpoint：本包内的领域类型或协作组件。
- `RunCheckpointPhase.java`：Run Checkpoint Phase：本包内的领域类型或协作组件。
- `RunCheckpointStore.java`：Run Checkpoint Store：负责状态持久化、读取或查询。
- `RunEvent.java`：Run Event：事件、消息或审计事实模型。
- `RunEventEmitter.java`：Run Event Emitter：事件、消息或审计事实模型。
- `RunEventReplayService.java`：Run Event Replay Service：封装该领域用例或业务规则。
- `RunEventSink.java`：Run Event Sink：事件、消息或审计事实模型。
- `RunEventType.java`：Run Event Type：事件、消息或审计事实模型。
- `RunFork.java`：Run Fork：本包内的领域类型或协作组件。
- `RunJournalException.java`：Run Journal Exception：事件、消息或审计事实模型。
- `RunJournalStore.java`：Run Journal Store：负责状态持久化、读取或查询。
- `RunRecoveryCoordinator.java`：Run Recovery Coordinator：本包内的领域类型或协作组件。
- `RunResumeService.java`：Run Resume Service：封装该领域用例或业务规则。
- `RunState.java`：Run State：执行结果、报告或状态值对象。
- `RunStatus.java`：Run Status：执行结果、报告或状态值对象。
- `RuntimeCapabilityWarning.java`：Runtime Capability Warning：驱动对应执行生命周期。
- `SessionPersistenceService.java`：Session Persistence Service：封装该领域用例或业务规则。
- `SessionPreparationService.java`：Session Preparation Service：封装该领域用例或业务规则。
- `SessionRuntimeKeys.java`：Session Runtime Keys：驱动对应执行生命周期。
- `SharedRunCheckpointStore.java`：Shared Run Checkpoint Store：负责状态持久化、读取或查询。
- `SharedRunJournalStore.java`：Shared Run Journal Store：负责状态持久化、读取或查询。
- `SharedSideEffectStore.java`：Shared Side Effect Store：负责状态持久化、读取或查询。
- `SideEffectApplicationService.java`：Side Effect Application Service：封装该领域用例或业务规则。
- `SideEffectClaim.java`：Side Effect Claim：本包内的领域类型或协作组件。
- `SideEffectConfirmationRequiredException.java`：Side Effect Confirmation Required Exception：本包内的领域类型或协作组件。
- `SideEffectCoordinator.java`：Side Effect Coordinator：本包内的领域类型或协作组件。
- `SideEffectOutcome.java`：Side Effect Outcome：本包内的领域类型或协作组件。
- `SideEffectRecord.java`：Side Effect Record：本包内的领域类型或协作组件。
- `SideEffectStatus.java`：Side Effect Status：执行结果、报告或状态值对象。
- `SideEffectStore.java`：Side Effect Store：负责状态持久化、读取或查询。
- `SpawnWorkerService.java`：Spawn Worker Service：封装该领域用例或业务规则。
- `TaskState.java`：Task State：执行结果、报告或状态值对象。
- `TaskSummaryRenderer.java`：Task Summary Renderer：执行结果、报告或状态值对象。
- `TaskSummaryService.java`：Task Summary Service：封装该领域用例或业务规则。
- `ToolContextApplier.java`：Tool Context Applier：工具协议、实现或注册逻辑。
- `ToolContextInjector.java`：Tool Context Injector：工具协议、实现或注册逻辑。
- `ToolInvocationRecord.java`：Tool Invocation Record：工具协议、实现或注册逻辑。
- `ToolInvocationStatus.java`：Tool Invocation Status：工具协议、实现或注册逻辑。
- `ToolNodeExecutor.java`：Tool Node Executor：工具协议、实现或注册逻辑。
- `ToolRecoveryAction.java`：Tool Recovery Action：工具协议、实现或注册逻辑。
- `ToolRecoveryResolution.java`：Tool Recovery Resolution：工具协议、实现或注册逻辑。
- `ToolTraceSummarizer.java`：Tool Trace Summarizer：工具协议、实现或注册逻辑。

