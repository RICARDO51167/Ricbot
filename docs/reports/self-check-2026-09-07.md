# Ricbot 项目自检报告

检查日期：2026-09-07（Asia/Shanghai）  
检查对象：`main` 分支、提交 `2731e3f` 加当前工作区全部未提交变更。  
结论：**现有自动化验证通过，v6 核心已有较好的可靠性基础；但取消、文件写入、干净环境发布和旧数据升级仍有需要优先处理的问题。建议先完成一轮可靠性收尾，再扩展功能。**

## 1. 检查范围与项目现状

本次检查覆盖项目结构、构建与发布脚本、运行时调度与持久化、工具执行、文件访问、配置读取、依赖声明及测试。采用源码检查、完整发布检查和独立最小复现三种方式。未修改生产代码、现有测试和业务配置；新增此报告，验证产物保存在构建输出目录。

| 项目 | 本次观察 |
| --- | --- |
| 技术形态 | Java 17、Maven、CLI、SQLite、可替换的模型 Provider |
| 生产源码 | 322 个 Java 文件，40,688 行 |
| 测试源码 | 65 个 Java 文件，7,999 行，包含测试辅助文件 |
| 执行的自动化用例 | 273 个，0 失败、0 错误、0 跳过 |
| 重构状态 | v6 单一 Durable Runtime 替代旧 Agent/Team/Graph/Task 体系 |
| 检查开始时的 Git 状态 | 87 项修改、106 项删除、106 项未跟踪，共 299 个状态条目；未跟踪目录可能包含多个文件 |
| 默认本机 Java | JDK 25.0.1；最终发布验证使用 JDK 17.0.12，与项目目标和 CI 主版本一致 |

这些结论针对**当前工作区快照**，不能直接代表远端已提交版本。源码与测试行数用于理解规模，不代表覆盖率；项目目前没有生成可据以报告的覆盖率数据。

已有值得保留的设计：

- 固定运行阶段与纯 `RuntimeReducer`，有利于状态重放和故障定位。
- Activation 租约、原子提交、Model Invocation Ledger 与 Effect Ledger 已有实现和回归测试。
- 未知写入结果进入 `UNKNOWN`，具有对账和人工确认入口；历史重放与实际执行分离。
- Child Run 使用统一生命周期，已有依赖、Join、Fork 和预算并发测试。
- 已有完整发布脚本、固定评测、重放比较、配置诊断以及日志轮转。

## 2. 实际验证结果

最终执行：在具备本机测试端口和进程查询权限的环境中，以 JDK 17 运行完整 `scripts/release-check.sh`。

| 验证项 | 结果 | 说明 |
| --- | --- | --- |
| 干净构建及完整测试 | 通过 | 273 个测试全部通过 |
| SQLite schema v3 / 旧库归档测试 | 通过 | 项目现有回归通过 |
| Durable Runtime / 架构边界 | 通过 | 包含持久化、重放、租约、取消等现有测试 |
| JAR 打包 | 通过 | 生成可执行 JAR |
| Config Doctor | 缺少可选凭据 | 未设置 `DASHSCOPE_API_KEY`，门禁允许固定评测继续 |
| 固定评测 Smoke | 通过 | 共 12 项：11 项通过、1 项预期失败、0 项意外失败 |
| Eval Replay | 通过 | 同样为 11 项通过、1 项预期失败 |
| 与本机 Golden 基线比较 | 通过 | 12 项不变，0 回归、0 改善 |
| 变更格式检查 | 通过 | `git diff --check` 无错误 |
| 发布脚本最终结果 | PASS | 依赖本机已有的评测基线，见 F03 |

首次在受限环境、默认 JDK 25 下运行时，出现 2 个本地 HTTP 端口绑定错误和 1 个进程查询权限导致的失败。取得所需本机执行权限并改用 JDK 17 后，完整检查通过，因此不将首轮这 3 项计为项目缺陷。

本次还执行了两个独立探针，使用临时文件和临时数据库，确认现有测试以外的边界问题。关键输出已保留在本文附录。

验证边界：没有调用真实付费模型接口，没有开展 Docker 实机验收、长时间压力测试、真实跨进程强杀恢复测试，也没有完成全量依赖漏洞扫描。现有故障注入测试通过，不等同于这些场景均已验证。

原始门禁报告：[release-check-report.md](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/target/release-check-report.md)。

## 3. 问题与风险优先级

P1：建议在扩大使用范围或正式发布前完成。P2：建议进入下一轮迭代。优先级根据本项目的实际影响判断，不等同于漏洞评级。

| 编号 | 优先级 | 问题或风险 | 证据状态 |
| --- | --- | --- | --- |
| F01 | P1 | 中断执行线程后，本地命令仍可能继续写文件 | 已复现 |
| F02 | P1 | 文件编辑会丢失原有执行权限 | 已复现 |
| F03 | P1 | 发布门禁依赖未纳入版本管理的本地基线 | 已确认配置链路；未运行远端 CI |
| F04 | P2 | 文件被删除后，旧 SHA 写入仍会重新创建文件 | 已复现；另有校验与替换之间的竞争窗口 |
| F05 | P2 | 新写入路径绕过 2 MiB 大小限制 | 已复现 |
| F06 | P2 | Run 最大步数在恢复后重新计数 | 已复现 |
| F07 | P2 | 工具参数预校验没有验证参数类型 | 已复现 |
| F08 | P2 | 空闲运行时持续写事务表，并扫描所有历史 Run | 空闲写入已复现；扫描路径已确认 |
| F09 | P2 | 日志依赖落后于已发布的安全修复，缺少持续扫描 | 依赖声明和官方公告已核对 |
| F10 | P1 | 已知旧 v3 数据库升级采用直接删除重建 | 已确认设计行为；未操作用户数据库 |

### F01：取消调用后，外部命令仍可能继续执行

**现象与影响：**启动“短暂等待后写入标记文件”的命令，待子进程启动后中断 Java 执行线程。调用抛出 `InterruptedException`，但随后标记文件仍被写入。用户停止任务后，外部修改仍可能发生。

**原因：**`LocalExecutionBackend` 只在正常超时分支终止进程树；中断或异常进入 `finally` 时，只清理读取线程，没有终止进程。运行时取消工具采用中断执行线程的方式，正好经过这一边界。

证据：[LocalExecutionBackend.java:40](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/execution/LocalExecutionBackend.java:40)、[AgentPhaseServices.java:126](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/application/runtime/AgentPhaseServices.java:126)。

**建议：**统一处理成功退出、超时、中断和异常时的进程生命周期；在中断路径终止并等待进程树退出，保留中断状态，区分执行结果未知与已确认停止。Docker 后端还需单独验证容器清理语义。

**验收：**在命令执行前、执行中和输出读取中取消，均不能在确认停止后继续修改文件；测试覆盖孙进程，并确认没有残留进程。账本中的 `UNKNOWN` 处理不能替代外部进程的停止。

### F02：文件编辑会改变已有文件的权限

**现象与影响：**对权限为 `rwxr-xr-x`（0755）的脚本执行现有 `compareAndWrite` 后，权限变为 `rw-------`（0600）。因此模型正常修改脚本内容后，脚本可能无法直接执行，也会出现无关的 Git 文件模式变更。

**原因：**实现创建临时文件，再将临时文件替换到目标路径，但未保存和恢复原文件权限。`write_file` 与 `edit_file` 都使用此路径。

证据：[FileToolSupport.java:120](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/tool/filesystem/FileToolSupport.java:120)、[WriteFileTool.java:121](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/tool/filesystem/WriteFileTool.java:121)、[EditFileTool.java:113](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/tool/filesystem/EditFileTool.java:113)。

**建议与验收：**更新已有文件时保留受支持的权限属性；新文件采用明确的默认权限。覆盖 0644 普通文件、0755 脚本，并核对内容变更不产生意外模式变更。其他平台的属性行为需要单独验证。

### F03：本机发布成功不能在干净 CI 中复现

**已确认的配置链路：**

1. 发布脚本要求 `.ricbot/eval-baselines/golden/summary.json` 和 `cases.jsonl` 存在，缺失时最终失败。
2. `.gitignore` 忽略全部 `.ricbot` 目录；这些基线文件没有被 Git 跟踪。
3. CI 只有检出、安装 JDK/Maven 缓存、执行发布检查，没有获取评测基线的步骤。
4. 本机已有基线，因此本次比较通过。

由上述配置可推出：**干净 runner 执行到基线比较时会因为基线缺失而失败。**这不是一次远端流水线运行记录。

证据：[release-check.sh:14](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/scripts/release-check.sh:14)、[release-check.sh:414](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/scripts/release-check.sh:414)、[.gitignore:40](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/.gitignore:40)、[ci.yml:18](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/.github/workflows/ci.yml:18)。

**建议：**将脱敏、固定的 Golden 基线放入可跟踪目录，或由 CI 下载带版本和校验值的基线产物。基线更新应作为独立、可审查的变更。每次用当前候选代码重新生成基线会削弱回归比较，应避免将其作为缺失基线的默认补救。

**验收：**从完全没有 `.ricbot` 的干净检出执行发布检查，仍能与独立固定基线比较。顺带修正 CI 产物上传目录：当前没有包含脚本实际生成的 `release-check-eval-smoke`、`release-check-eval-replay` 和 `release-check-eval-compare` 目录。

### F04：文件覆盖的冲突检测不完整

**复现：**写入原文件、记录 SHA、删除原文件，再以旧 SHA 调用 `compareAndWrite`；写入成功并重新创建文件，没有返回冲突。

**原因：**只有 `Files.exists(path)` 为真时才检查 SHA，因此“原文件本应存在，但现在已删除”被放过。此外，SHA 校验与最终 `move` 是两个步骤，`ATOMIC_MOVE` 只保证替换动作的原子性，不能将前面的检查一并变成原子比较交换。

证据：[FileToolSupport.java:114](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/tool/filesystem/FileToolSupport.java:114)。

**影响：**外部编辑器或其他进程的删除、更新可能被后续写入覆盖。Runtime 的资源租约只能协调遵守同一租约协议的参与方。

**建议与验收：**显式区分“创建新文件”和“更新指定版本”；更新目标消失必须报冲突。进一步明确与外部写入者的协调策略，保留冲突版本，验证删除、并发修改、路径别名等情况；不能仅靠方法名或一次重复检查宣称完整 CAS。

### F05：写入大小限制被新实现绕过

**复现：**通过当前写入辅助方法成功写出 2,097,153 字节文件，而 `MAX_TEXT_FILE_BYTES` 为 2,097,152 字节。

**原因：**原 `writeText` 会检查 UTF-8 字节数，新 `compareAndWrite` 直接写入临时文件，没有相同限制，而两个文件修改工具已切换至新方法。

证据：[FileToolSupport.java:86](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/tool/filesystem/FileToolSupport.java:86)、[FileToolSupport.java:114](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/tool/filesystem/FileToolSupport.java:114)。

**建议与验收：**在共享写入入口恢复字节上限，并在实际落盘前拒绝超限；覆盖恰好等于上限、超过一字节和多字节中文内容，确认被拒绝时原文件不变。

### F06：最大运行步数没有覆盖整个 Run 生命周期

**复现：**设置 `maxSupersteps=1`，每次执行后等待用户输入，再恢复 5 次。最终持久状态 `superstep=6`，仍为 `WAITING`，未触发限制。

**原因：**`driveLocked` 使用局部变量 `steps=0`；每次恢复或重新驱动都会重置计数，没有根据持久化的 Run 进度限制总步数。

证据：[LocalDurableAgentRuntime.java:210](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/application/runtime/LocalDurableAgentRuntime.java:210)。

**影响：**包含等待、重试、审批或进程恢复的长任务，可以绕过 Run 级别的步数上限。项目的 token、费用等预算另有实现，本复现不表示这些独立预算也被绕过。

**建议与验收：**明确这是每次调度限额还是整个 Run 限额。若保留 `RunSpec.maxSupersteps` 的总量语义，应从持久进度计算剩余额度，并定义 Fork 的计数起点。覆盖等待恢复、进程重启和 Fork 后限额行为。

### F07：工具参数校验只检查键，没有检查值的类型

**复现：**向 `write_file` 提交 `{"path":123,"content":"test"}`，`ToolDispatcher.prepare` 通过，执行时才抛出 `ClassCastException`。

**原因：**`validate` 只检查 required 和 additionalProperties，没有验证 string、integer、boolean、嵌套结构、枚举或范围。

证据：[ToolDispatcher.java:106](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/tool/api/ToolDispatcher.java:106)。

**影响：**模型偶发参数错误无法在统一入口转为明确的 `INVALID_ARGUMENTS`。根据当前写 Effect 对异常的处理路径，这类执行期异常还可能进入 `UNKNOWN` 并要求人工确认；本次探针直接验证到预校验通过和类型转换失败。

**建议与验收：**在授权和外部执行前完成参数 schema 校验；至少覆盖所有内建工具实际使用的类型、范围和嵌套结构。错误参数不得写入外部资源，也不应创建已派发的 Effect。

### F08：空闲调度产生持续写入和历史全表扫描

**复现：**启动一个没有任何 Run 的新 Runtime，观察 1,204 毫秒，`runtime_transactions` 增加 10 行。

**原因：**后台每约 100 毫秒 tick；租约恢复无论是否存在过期项都会进入写事务。事务包装器每次都会插入一条全局事务记录。定时器查找和 READY Run 查找还会加载全部 Run，并在内存中筛选。

证据：[LocalDurableAgentRuntime.java:64](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/application/runtime/LocalDurableAgentRuntime.java:64)、[SqliteRuntimeTransactionFacade.java:28](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/runtime/SqliteRuntimeTransactionFacade.java:28)、[SqliteDurableRuntimeStore.java:321](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/runtime/SqliteDurableRuntimeStore.java:321)、[SqliteDurableRuntimeStore.java:430](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/runtime/SqliteDurableRuntimeStore.java:430)。

**影响：**长期驻留时，即使没有业务，也会增长事务表、占用 SQLite 写锁；历史 Run 越多，轮询反序列化成本越高。具体吞吐和磁盘增长量尚未压测。

**建议与验收：**为空闲状态增加退避或事件唤醒；仅对实际事实变更分配事务序号；为可执行 Run 和到期等待提供带索引、带批量上限的专用查询。空闲检查不得无限生成业务事实，历史终态 Run 增加时，调度单次读取量应保持有界。

### F09：依赖安全维护需要补齐

**已确认：**项目使用 Logback 1.5.6。官方后续版本修复了配置处理相关问题，包括 CVE-2024-12798 与 CVE-2024-12801。截至本次查询，官方列出的稳定系列为 1.6.x，已发布 1.6.3；1.5.x 被列为 legacy。[官方历史公告](https://logback.qos.ch/news-archive.html)、[官方当前公告](https://logback.qos.ch/news.html)。

当前默认日志配置是文件轮转，没有使用 Janino 条件表达式或相关危险配置。因此本报告确认的是**依赖版本落后于安全修复**，没有证明项目当前存在可从远端直接利用的漏洞。

证据：[pom.xml:43](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/pom.xml:43)、[logback.xml](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/resources/logback.xml)。当前 Maven 和 CI 没有依赖漏洞扫描、SBOM 或持续依赖更新配置。

**建议：**单独升级并验证 Logback 1.6.3 与兼容的 SLF4J 组合，检查日志初始化、轮转和异常记录。Jackson、OpenTelemetry 分别按同一系列的统一版本管理；先生成完整依赖清单和 SBOM，再依据官方兼容性与漏洞信息决定升级。本次未对其他依赖作“安全”或“存在漏洞”的结论。

### F10：已知旧 v3 布局升级会直接删除历史数据

**已确认行为：**当数据库属于受支持的旧 v3 布局且无活跃租约时，`rebuildKnownLegacyV3IfNeeded` 会删除数据库及 WAL/SHM 文件，然后初始化空库。此分支没有像 v2/v5 归档路径那样保存原库。

这是 README 已说明、测试允许的当前设计行为，**属于升级数据保留风险**。没有活跃租约，并不等于没有需要保留的历史 Run、审计记录或等待中的任务。

证据：[SqliteDurableRuntimeStore.java:997](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/runtime/SqliteDurableRuntimeStore.java:997)，实际删除位于 [第 1038 行](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/runtime/SqliteDurableRuntimeStore.java:1038)。

**建议与验收：**对非空库先备份归档并写入校验清单，再迁移或建立新库；提供升级预检、历史数据读取和恢复步骤。用包含终态历史、等待任务及未知 Effect 的旧库样本验证数据去向，避免仅用空旧库验证“可重建”。本次未打开或迁移用户实际运行数据库。

## 4. 架构与维护方面的改进空间

### 4.1 阶段拆分还没有形成独立职责

`AgentPhaseServices` 达 1,323 行，集中持有 Provider、工具、账本、上下文、审批、变更集及多张进程内状态表。部分 Handler 只是转发调用，例如 `ToolEffectPhaseHandler` 仅 10 行。阶段路由清楚，但行为仍集中在一个大类中。

其他较大的类包括 `CliCommands` 1,306 行、`EvalHarness` 1,271 行、`SqliteDurableRuntimeStore` 1,152 行。文件长度本身不是缺陷，但这些类承载多个变化原因，修改影响范围偏大。

建议让 Handler 真正拥有相应的阶段行为；共享层只保留窄接口服务。优先分离模型输入与调用、工具授权与结果处理、委派与变更操作，并保持当前纯 Reducer 的边界。

### 4.2 后台调度和可观察性适合做下一轮收敛

后台 `runScheduledWork` 按列表同步驱动 READY Run，长模型调用会拖延后续候选 Run 的处理。线程池有两个线程，并不意味着后台 Child Run 已获得受控的并行调度。这里是源码层面的能力限制，尚无负载测试结果。

建议分开计时/续租与执行队列，增加明确的并发度、队列上限和公平性策略。对后台 tick 异常、续租失败和订阅异常增加可查询的健康状态、结构化日志和指标；当前多处异常被吞掉，会提高定位成本。证据：[LocalDurableAgentRuntime.java:234](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/application/runtime/LocalDurableAgentRuntime.java:234)。

### 4.3 配置错误与默认配置的边界不够明确

显式配置文件解析失败时，`ConfigLoader.loadConfig` 记录警告后继续返回默认配置；文件不存在也会使用默认值。用户可能以为指定的模型、工作区和限制已经生效，实际运行的是另一套配置。此处未证明权限被放大。

建议显式指定的配置缺失或损坏时清楚报错；首次无配置启动则走独立的引导路径。在诊断输出中标明每项关键设置的来源。证据：[ConfigLoader.java:109](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/src/main/java/ricbot/infra/config/ConfigLoader.java:109)。

### 4.4 测试需要从结构契约扩展到用户可观察行为

273 个用例和故障注入已经提供了有效基础，但本次最小复现说明：实际进程退出、文件模式、参数类型、跨恢复配额和干净 CI 等仍未被现有测试拦截。

建议针对本报告问题补充少量、有外部结果断言的回归测试，再增加跨进程恢复、Docker 清理、长响应与流式中断测试。引入覆盖率报告来定位核心路径盲区，不宜用源码行数或单一百分比代替可靠性验收。

### 4.5 版本与发布资料尚未收尾

当前 Maven 坐标仍为 `org.example:Ricbot:1.0-SNAPSHOT`，仓库没有正式 License；README 已明确承认这一点。大规模未提交重构也使当前工作区与可发布版本之间存在差距。

建议在稳定后形成可定位的 v6 提交与版本说明，明确软件版本、数据库 schema、状态格式和工具协议版本的关系，补齐升级兼容表、安装说明与项目决定采用的 License。此项为发布准备建议，不是法律意见。

## 5. 后续升级与演进路线

以下按依赖关系安排，工作量为粗估，需结合负责人对代码的熟悉程度调整；不是交付承诺。

| 阶段 | 目标与工作 | 粗估 | 完成标准 |
| --- | --- | --- | --- |
| A：v6 可靠性收尾 | 先修 F01/F02/F03；确定 F10 的历史数据保留方案；随后修 F04—F07 | 约 1—2 周 | 本次探针对应的回归全部通过；干净检出发布成功；已有文件属性和旧数据可保留 |
| B：核心维护性与成本控制 | 修 F08/F09；拆分阶段行为；补参数契约、后台健康状态、配置错误提示 | 约 2—3 周 | 空闲开销有界；依赖扫描和核心指标可用；阶段可独立测试 |
| C：持续运行能力 | 真实进程崩溃恢复、受控并发、公平调度、备份恢复、历史清理、跨平台测试 | 约 2—4 周 | 有明确恢复时间、取消延迟和吞吐基线；长时间运行无持续异常增长 |
| D：按产品需求扩展 | 任务观察界面、远程控制接口、更多 Provider/工具适配、领域评测 | 按需求拆分 | 所有入口复用同一持久化内核，具有权限与取消的一致行为 |

### 建议优先演进的具体方向

**运行时可诊断性。**提供 Run 时间线、当前等待原因、预算消耗、租约状态与 UNKNOWN Effect 的集中视图。可以先从 CLI 查询完善，再增加轻量只读界面，直接使用现有查询接口。

**执行边界标准化。**完善 ToolDescriptor 和参数 schema、稳定的结果/错误分类、超时和取消契约、结果对账协议。新工具接入前执行统一契约测试，减少在 Provider 或具体工具中重复处理。

**可控的 Child Run 并行。**在修复取消、资源冲突和调度开销后，加入有界工作队列、根任务并发配额和公平调度；用真实任务评测衡量时延收益与 token 成本。

**持久数据生命周期。**为 runtime/application 两个数据库及 Artifact 建立协调备份、兼容迁移和保留策略。清理策略必须保留 Replay/Fork 需要的起点与引用，先定义哪些历史能力仍受支持。

**更有代表性的评测。**保留现有确定性 12 项作为快速回归层；补充长轨迹、多文件修改、取消、审批等待、上下文压缩、工具错误恢复与不同 Provider 的契约场景。真实模型评测单独设预算，记录成功率、费用、时延和人工接管次数。

**运行平台升级。**继续以 Java 17 作为当前兼容基线，可增加本机已具备的 Java 21/25 兼容验证；只有在测试和运行依赖通过后再调整最低版本。数据库继续保留 SQLite 本地方案，用实测的写锁等待、多进程争用和远程部署需求决定是否增加其他存储适配器。

## 6. 建议的下一轮验收清单

- 停止任务后，命令和后代进程确实退出，确认停止后没有继续写入。
- 编辑文件保留执行位；并发更新、删除与超限写入能返回明确冲突或拒绝。
- 错误参数在派发前被拒绝，不造成不必要的人工 Effect 确认。
- Run 步数在等待、重启和 Fork 场景中遵守已声明的限额语义。
- 空闲运行和历史 Run 增长都不会导致无限事务膨胀或无限查询批量。
- 非空旧数据库升级前有可校验备份，并能恢复或读取历史记录。
- 干净检出与本机使用同一固定基线，CI 能保存完整评测产物。
- 对真实 Provider、Docker 和跨进程故障场景建立独立、预算受控的验收记录。

## 附录：本次最小复现关键输出

文件与进程探针使用临时目录，调用当前编译产物中的真实实现：

```text
file_mode_before=rwxr-xr-x
file_mode_after=rw-------
deleted_target_recreated=true
write_bytes=2097153, documented_limit=2097152
cancel_worker_outcome=InterruptedException
external_write_after_cancel=true
```

运行时探针使用临时 SQLite 数据库及简单的固定阶段实现；参数探针调用真实工具分发器与 `write_file`：

```text
idle_elapsed_ms=1204
idle_transaction_rows_added=10
idle_run_count=0
configured_max_supersteps=1
actual_supersteps=6
after_resumes_status=WAITING
invalid_path_type_passed_prepare=true
invalid_type_execution_exception=ClassCastException
```

复现方式：文件探针依次修改 0755 文件、以已删除文件的旧 SHA 写入、写入超过限制一字节的内容，以及中断一个会延迟写入标记的命令。运行时探针启动空库观察事务数，然后运行最大一步、每次暂停等待输入的 Run 并恢复五次，最后提交错误类型的写文件参数。

构建输出中的验证资料位于 [self-check-2026-09-07](/Users/rcd/Develop/JavaAbout/IdeaProjects/Ricbot/target/self-check-2026-09-07)。该目录随构建清理可能被删除，关键结果已固化在本报告中。
