---
name: skill-creator
description: 创建或更新 AgentSkills。用于设计、组织、打包包含脚本、参考资料与资源文件的技能。
---

# 技能创建器

本技能提供创建高质量技能的指导。

## 关于技能

技能是模块化、可自包含的能力包，通过提供专门知识、工作流与工具用法来扩展代理的能力。
可以把它理解为面向特定领域或任务的“上手指南/作业手册”——它把通用代理变成具备特定流程知识的专用代理，而这些流程知识并不是任何模型都能完整内建的。

### 技能能提供什么

1. 专门工作流：针对特定领域的多步骤流程
2. 工具集成：面向特定文件格式或 API 的操作指引
3. 领域知识：公司内部知识、数据结构、业务逻辑
4. 资源捆绑：用于复杂/重复任务的脚本、参考资料与资源文件

## 核心原则

### 简洁最关键

上下文窗口是一种“公共资源”。技能需要和系统提示词、对话历史、其它技能的元数据以及用户当前请求共同占用上下文窗口。

**默认假设：代理已经很聪明。**只添加代理原本不具备的上下文。对每一段信息都进行质疑：“代理真的需要这段解释吗？”“这段文字的 token 成本值得吗？”

宁可用简洁示例，也不要冗长解释。

### 设置合适的自由度

根据任务的脆弱性与可变性来匹配指令的具体程度：

**高自由度（文字型指引）**：适用于多种方法都可行、决策依赖上下文、主要靠启发式判断的场景。

**中自由度（伪代码或带参数脚本）**：适用于存在推荐范式、允许一定变体、或行为受配置影响的场景。

**低自由度（固定脚本、少量参数）**：适用于操作脆弱易错、需要高度一致性、或必须遵循特定步骤序列的场景。

可以把代理看作在探索一条路径：悬崖边的窄桥需要明确护栏（低自由度），开阔平原则允许多条路线（高自由度）。

### 技能的组成

每个技能都由一个必需的 SKILL.md 以及可选的捆绑资源组成：

```
skill-name/
├── SKILL.md (required)
│   ├── YAML frontmatter metadata (required)
│   │   ├── name: (required)
│   │   └── description: (required)
│   └── Markdown instructions (required)
└── Bundled Resources (optional)
    ├── scripts/          - Executable code (Python/Bash/etc.)
    ├── references/       - Documentation intended to be loaded into context as needed
    └── assets/           - Files used in output (templates, icons, fonts, etc.)
```

#### SKILL.md（必需）

每个 SKILL.md 包含：

- **Frontmatter**（YAML）：包含 `name` 与 `description` 字段。代理用来判断是否触发技能时只会读取这些字段，因此必须清晰、完整地说明技能做什么、何时该用。
- **正文**（Markdown）：使用技能的操作说明与指导。只有在技能被触发之后才会加载（如果需要的话）。

#### 捆绑资源（可选）

##### 脚本（`scripts/`）

用于需要确定性可靠、或经常重复编写的任务的可执行代码（Python/Bash 等）。

- **何时添加**：同一段代码被反复重写，或需要确定性可靠时
- **示例**：用于 PDF 旋转的 `scripts/rotate_pdf.py`
- **优势**：省 token、结果确定；可在不加载进上下文的情况下直接执行
- **注意**：脚本仍可能需要被代理读取以便打补丁或做环境适配

##### 参考资料（`references/`）

用于按需加载进上下文、为代理的工作过程提供信息支撑的文档与参考材料。

- **何时添加**：代理在执行过程中需要查阅的文档
- **示例**：财务数据结构 `references/finance.md`、公司 NDA 模板 `references/mnda.md`、公司政策 `references/policies.md`、API 规范 `references/api_docs.md`
- **适用场景**：数据库 schema、API 文档、领域知识、公司政策、详细工作流指南
- **优势**：让 SKILL.md 保持精简；只有当代理判断需要时才加载
- **最佳实践**：若文件很大（>10k 词），在 SKILL.md 中给出 grep 或 glob 的模式，便于代理高效使用内置搜索工具；说明何时应优先使用默认的 `grep(output_mode="files_with_matches")`、`grep(output_mode="count")`、`grep(fixed_strings=true)`、`glob(entry_type="dirs")`，或用 `head_limit` / `offset` 进行分页
- **避免重复**：信息应存在于 SKILL.md 或 references 其一，不要两边都写。除非内容是技能的核心，否则详细信息优先放 references，以保持 SKILL.md 精简且可发现而不挤占上下文窗口。SKILL.md 只保留关键流程指令与工作流指导；把详细参考资料、schema 与示例移到 references。

##### 资源文件（`assets/`）

不打算加载进上下文，而是用于代理最终输出产物的文件。

- **何时添加**：技能需要一些会用于最终输出的文件时
- **示例**：品牌素材 `assets/logo.png`、PPT 模板 `assets/slides.pptx`、HTML/React 脚手架 `assets/frontend-template/`、字体 `assets/font.ttf`
- **适用场景**：模板、图片、图标、样板代码、字体、会被复制/修改的示例文档
- **优势**：将输出资源与文档分离，让代理能直接使用文件而无需加载进上下文

#### 技能里不该包含什么

技能应只包含直接支撑其功能的必要文件。不要创建多余文档或辅助文件，包括但不限于：

- README.md
- INSTALLATION_GUIDE.md
- QUICK_REFERENCE.md
- CHANGELOG.md
- etc.

技能只应包含 AI 代理完成任务所需的信息。不应包含技能如何被创建的过程性说明、安装/测试步骤、面向用户的使用文档等。额外文档只会带来杂乱与困惑。

### 渐进式披露原则

技能使用三级加载机制来高效管理上下文：

1. **元数据（name + description）**：始终在上下文中（约 100 词）
2. **SKILL.md 正文**：技能触发时加载（<5k 词）
3. **捆绑资源**：按需加载（理论上无限，因为脚本可在不读入上下文的情况下执行）

#### 渐进式披露的常见模式

将 SKILL.md 正文控制在必要内容且不超过 500 行，以减少上下文膨胀。接近该上限时就把内容拆分到单独文件。拆分后必须在 SKILL.md 中引用这些文件，并清楚说明何时需要读取，确保使用者知道它们存在并知道何时该用。

**关键原则：**当技能支持多个变体/框架/选项时，SKILL.md 只保留核心流程与选择指引。把变体细节（模式、示例、配置）放到单独的参考文件里。

**模式 1：高层指南 + 引用**

```markdown
# PDF 处理

## 快速开始

用 pdfplumber 提取文本：
[code example]

## 高级功能

- **表单填写**：完整指南见 [FORMS.md](FORMS.md)
- **API 参考**：全部方法见 [REFERENCE.md](REFERENCE.md)
- **示例**：常见模式见 [EXAMPLES.md](EXAMPLES.md)
```

代理只在需要时才读取 FORMS.md、REFERENCE.md 或 EXAMPLES.md。

**模式 2：按领域组织**

对于覆盖多个领域的技能，按领域组织内容以避免加载无关上下文：

```
bigquery-skill/
├── SKILL.md（概览与导航）
└── reference/
    ├── finance.md（收入、计费指标）
    ├── sales.md（商机、漏斗）
    ├── product.md（API 用法、功能）
    └── marketing.md（活动、归因）
```

当用户询问销售指标时，代理只读取 sales.md。

类似地，对于支持多个框架或变体的技能，可以按变体组织：

```
cloud-deploy/
├── SKILL.md（工作流 + 云厂商选择）
└── references/
    ├── aws.md（AWS 部署模式）
    ├── gcp.md（GCP 部署模式）
    └── azure.md（Azure 部署模式）
```

当用户选择 AWS 时，代理只读取 aws.md。

**模式 3：条件式细节**

先展示基础内容，再链接到进阶内容：

```markdown
# DOCX 处理

## 创建文档

创建新文档使用 docx-js。详见 [DOCX-JS.md](DOCX-JS.md)。

## 编辑文档

简单编辑可直接修改 XML。

**需要修订模式（tracked changes）**：见 [REDLINING.md](REDLINING.md)
**需要 OOXML 细节**：见 [OOXML.md](OOXML.md)
```

代理只在用户需要这些能力时才读取 REDLINING.md 或 OOXML.md。

**重要规范：**

- **避免过深的引用层级**：references 最好保持从 SKILL.md 一跳可达；所有参考文件都应在 SKILL.md 中直接链接。
- **组织较长的参考文件**：超过 100 行的文件，建议在开头提供目录，便于代理预览时把握全貌。

## 技能创建流程

技能创建通常包含以下步骤：

1. 用具体示例理解技能
2. 规划可复用的技能内容（scripts、references、assets）
3. 初始化技能（运行 init_skill.py）
4. 编辑技能（实现资源并编写 SKILL.md）
5. 打包技能（运行 package_skill.py）
6. 根据真实使用反馈迭代

按顺序执行这些步骤；只有在明确不适用时才跳过。

### 技能命名

- 仅使用小写字母、数字与连字符；将用户提供的标题规范化为 hyphen-case（例如 "Plan Mode" -> `plan-mode`）。
- 生成的名称不超过 64 个字符（字母/数字/连字符）。
- 优先使用简短、以动词开头且能描述动作的短语。
- 当能提升清晰度或触发效果时，可按工具做命名空间（例如 `gh-address-comments`、`linear-address-issue`）。
- 技能目录名必须与技能名完全一致。

### 第 1 步：用具体示例理解技能

只有当技能的使用模式已经非常明确时才可跳过此步；即便是在改进已有技能时，这一步仍然很有价值。

要创建有效的技能，必须先明确技能将如何被使用的具体示例。这些示例可以来自用户的真实例子，也可以是你生成并经用户反馈验证的例子。

例如，构建 image-editor 技能时，可以问：

- “image-editor 技能需要支持哪些功能？编辑、旋转，还有别的吗？”
- “你能给一些会怎么用这个技能的例子吗？”
- “我能想到用户可能会说‘去掉红眼’或‘旋转图片’。你还能想到其它用法吗？”
- “用户会说什么话来触发这个技能？”

为避免让用户应接不暇，不要在一条消息里问太多问题。先问最关键的问题，再按需追问以提高效果。

当你对技能应支持的功能边界有清晰共识时，结束此步。

### 第 2 步：规划可复用的技能内容

将具体示例转化为有效技能时，对每个示例做分析：

1. 思考从零开始如何完成该示例
2. 识别在反复执行这些工作流时，哪些 scripts/references/assets 会有帮助

示例：构建 `pdf-editor` 技能以处理“帮我旋转这个 PDF”之类的问题，分析会发现：

1. Rotating a PDF requires re-writing the same code each time
2. A `scripts/rotate_pdf.py` script would be helpful to store in the skill

示例：设计 `frontend-webapp-builder` 技能以处理“帮我做个 todo 应用”或“做个仪表盘追踪步数”之类的问题，分析会发现：

1. Writing a frontend webapp requires the same boilerplate HTML/React each time
2. An `assets/hello-world/` template containing the boilerplate HTML/React project files would be helpful to store in the skill

示例：构建 `big-query` 技能以处理“今天有多少用户登录？”之类的问题，分析会发现：

1. Querying BigQuery requires re-discovering the table schemas and relationships each time
2. A `references/schema.md` file documenting the table schemas would be helpful to store in the skill

为确定技能内容，需要对每个具体示例进行分析，产出要包含的可复用资源清单：scripts、references、assets。

### 第 3 步：初始化技能

此时就应该真正创建技能了。

Skip this step only if the skill being developed already exists, and iteration or packaging is needed. In this case, continue to the next step.

从零创建新技能时，总是先运行 `init_skill.py`。该脚本会生成一个包含技能所需结构的模板目录，让创建过程更高效、更可靠。

对于 `ricbot`，自定义技能应放在当前工作区的 `skills/` 目录下，运行时才能被自动发现（例如 `<workspace>/skills/my-skill/SKILL.md`）。

用法：

```bash
scripts/init_skill.py <skill-name> --path <output-directory> [--resources scripts,references,assets] [--examples]
```

示例：

```bash
scripts/init_skill.py my-skill --path ./workspace/skills
scripts/init_skill.py my-skill --path ./workspace/skills --resources scripts,references
scripts/init_skill.py my-skill --path ./workspace/skills --resources scripts --examples
```

脚本会：

- 在指定路径创建技能目录
- 生成带正确 frontmatter 与 TODO 占位符的 SKILL.md 模板
- 根据 `--resources` 可选创建资源目录
- 设置 `--examples` 时可选生成示例文件

初始化后，按需定制 SKILL.md 并添加资源。若使用了 `--examples`，请替换或删除占位文件。

### 第 4 步：编辑技能

编辑（新生成或已有的）技能时，要记住：技能是给“另一个代理实例”使用的。只写对代理有帮助且不显而易见的信息。思考哪些流程知识、领域细节、可复用资源能帮助另一个代理实例更高效地完成任务。

#### 学习成熟的设计模式

根据技能需求参考这些指南：

- **多步骤流程**：查看 references/workflows.md，了解顺序流程与条件逻辑
- **特定输出格式/质量标准**：查看 references/output-patterns.md，了解模板与示例模式

这些文件包含经过验证的技能设计最佳实践。

#### 从可复用资源开始实现

开始实现时，先从上一步识别出的可复用资源着手：`scripts/`、`references/`、`assets/`。注意此步可能需要用户提供材料。例如实现 `brand-guidelines` 技能时，用户可能需要提供品牌素材/模板放进 `assets/`，或把文档放进 `references/`。

新增脚本必须通过实际运行来测试，确保无 bug 且输出符合预期。若脚本数量很多且相似，只需测试有代表性的样本，以在完成效率与可靠性之间做平衡。

如果使用了 `--examples`，删除不需要的占位文件。只创建实际需要的资源目录。

#### 更新 SKILL.md

**写作要求：**始终使用祈使句/动词原形的指令风格。

##### Frontmatter

编写包含 `name` 与 `description` 的 YAML frontmatter：

- `name`：技能名称
- `description`：这是技能最主要的触发机制，帮助代理理解何时该使用该技能。
  - 同时包含“技能做什么”与“何时使用”的具体触发语句/上下文。
  - 所有“何时使用”的信息都应写在这里——不要写在正文里。正文只有触发后才会加载，因此正文里的“何时使用本技能”对触发没有帮助。
  - `docx` 技能的示例 description："全面的文档创建、编辑与分析，支持修订模式、批注、格式保留与文本提取。当代理需要处理专业文档（.docx）时使用，包括：(1) 创建新文档，(2) 修改/编辑内容，(3) 处理修订模式，(4) 添加批注，或其它文档任务。"

保持 frontmatter 精简。在 `ricbot` 中，如有必要也支持 `metadata` 与 `always`，但除非确有需要，否则不要添加额外字段。

##### Body

编写如何使用该技能及其捆绑资源的指令。

### 第 5 步：打包技能

技能开发完成后，需要打包成可分发的 .skill 文件交付给用户。打包流程会先自动校验技能，以确保满足所有要求：

```bash
scripts/package_skill.py <path/to/skill-folder>
```

可选：指定输出目录：

```bash
scripts/package_skill.py <path/to/skill-folder> ./dist
```

打包脚本会：

1. **自动校验**技能，检查：
   - YAML frontmatter 格式与必需字段
   - 技能命名规范与目录结构
   - description 的完整性与质量
   - 文件组织与资源引用

2. **打包**：校验通过后，创建以技能名命名的 .skill 文件（例如 `my-skill.skill`），包含全部文件并保持用于分发的目录结构。.skill 文件本质上是带 .skill 扩展名的 zip 文件。

   安全限制：不允许符号链接；只要存在任意 symlink，打包就会失败。

如果校验失败，脚本会报告错误并退出，不会生成包。修复校验错误后重新执行打包命令。

### 第 6 步：迭代

测试技能后，用户可能会提出改进需求。通常这会发生在刚用完技能时，因为那时对技能表现的上下文最清晰。

**迭代工作流：**

1. 在真实任务中使用技能
2. 观察卡点或低效之处
3. 确定应如何更新 SKILL.md 或捆绑资源
4. 实施修改并再次测试
