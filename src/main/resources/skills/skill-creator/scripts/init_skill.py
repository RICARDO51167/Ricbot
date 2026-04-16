#!/usr/bin/env python3
"""
技能初始化器：基于模板创建一个新的技能目录

用法：
    init_skill.py <skill-name> --path <path> [--resources scripts,references,assets] [--examples]

示例：
    init_skill.py my-new-skill --path skills/public
    init_skill.py my-new-skill --path skills/public --resources scripts,references
    init_skill.py my-api-helper --path skills/private --resources scripts --examples
    init_skill.py custom-skill --path /custom/location
"""

import argparse
import re
import sys
from pathlib import Path

MAX_SKILL_NAME_LENGTH = 64
ALLOWED_RESOURCES = {"scripts", "references", "assets"}

SKILL_TEMPLATE = """---
name: {skill_name}
description: [TODO：用完整且信息充分的方式说明本技能做什么、以及何时使用。description 必须包含“何时使用”的触发场景（具体场景/文件类型/任务等）。]
---

# {skill_title}

## 概览

[TODO：用 1-2 句说明本技能能带来什么能力/效果]

## 如何组织本技能

[TODO：选择最适合本技能目的的结构。常见模式：

**1. 工作流驱动（Workflow-Based）**（适用于顺序流程）
- 适合有清晰分步流程的场景
- 示例：DOCX 技能 “工作流决策树” -> “读取” -> “创建” -> “编辑”
- 结构：## 概览 -> ## 工作流决策树 -> ## 第 1 步 -> ## 第 2 步…

**2. 任务驱动（Task-Based）**（适用于工具集合）
- 适合技能提供多种不同操作/能力的场景
- 示例：PDF 技能 “快速开始” -> “合并 PDF” -> “拆分 PDF” -> “提取文本”
- 结构：## 概览 -> ## 快速开始 -> ## 任务类别 1 -> ## 任务类别 2…

**3. 参考/规范（Reference/Guidelines）**（适用于标准或规范）
- 适合品牌规范、编码规范、需求说明等
- 示例：品牌样式 “品牌规范” -> “颜色” -> “字体” -> “特性”
- 结构：## 概览 -> ## 规范 -> ## 规格 -> ## 用法…

**4. 能力驱动（Capabilities-Based）**（适用于集成系统）
- 适合技能提供多个相互关联能力的场景
- 示例：产品管理 “核心能力” -> 编号能力列表
- 结构：## 概览 -> ## 核心能力 -> ### 1. 能力 -> ### 2. 能力…

模式可以按需混合。多数技能会组合多种模式（例如以任务驱动开头，再为复杂操作补充工作流）。

完成后请删除整个“如何组织本技能”章节——它只是指引占位。]

## [TODO：根据选择的结构，把这里替换成第一个主章节]

[TODO：在此添加内容。可参考已有技能中的示例：
- 技术类技能的代码示例
- 复杂工作流的决策树
- 贴近真实用户请求的具体示例
- 按需引用 scripts/templates/references 等资源]

## 资源（可选）

只创建本技能真正需要的资源目录。如果不需要资源，请删除本节。

### scripts/
可直接运行以执行特定操作的可执行代码（Python/Bash 等）。

**其它技能示例：**
- PDF 技能：`fill_fillable_fields.py`、`extract_form_field_info.py`——用于 PDF 操作的工具
- DOCX 技能：`document.py`、`utilities.py`——用于文档处理的 Python 模块

**适用：**Python 脚本、shell 脚本，或任何用于自动化、数据处理、特定操作的可执行代码。

**注意：**脚本可以在不读入上下文的情况下执行，但仍可能需要被 Codex 读取以便打补丁或做环境适配。

### references/
用于按需加载进上下文、为 Codex 的工作过程提供信息支撑的文档与参考材料。

**其它技能示例：**
- 产品管理：`communication.md`、`context_building.md`——详细工作流指南
- BigQuery：API 参考文档与查询示例
- Finance：schema 文档、公司政策

**适用：**深入文档、API 参考、数据库 schema、综合指南，或任何 Codex 在工作时需要查阅的细节信息。

### assets/
不打算加载进上下文，而是用于 Codex 最终输出产物的文件。

**其它技能示例：**
- 品牌样式：PowerPoint 模板（.pptx）、logo 文件
- 前端构建：HTML/React 样板工程目录
- 字体：字体文件（.ttf、.woff2）

**适用：**模板、样板代码、文档模板、图片、图标、字体，或任何需要复制/用于最终输出的文件。

---

**并不是每个技能都需要三类资源。**
"""

EXAMPLE_SCRIPT = '''#!/usr/bin/env python3
"""
{skill_name} 的示例辅助脚本

这是一个可直接执行的占位脚本。
如无需要可删除；需要时请替换为真实实现。

其它技能的真实脚本示例：
- pdf/scripts/fill_fillable_fields.py - 填充 PDF 表单字段
- pdf/scripts/convert_pdf_to_images.py - 将 PDF 页面转换为图片
"""

def main():
    print("这是 {skill_name} 的示例脚本")
    # TODO: Add actual script logic here
    # This could be data processing, file conversion, API calls, etc.

if __name__ == "__main__":
    main()
'''

EXAMPLE_REFERENCE = """# {skill_title} 的参考文档

这是一个用于“详细参考资料”的占位文档。
如无需要可删除；需要时请替换为真实内容。

其它技能的真实参考文档示例：
- product-management/references/communication.md - 状态更新的综合指南
- product-management/references/context_building.md - 收集上下文的深度指南
- bigquery/references/ - API 参考与查询示例

## 参考文档适用场景

参考文档适合用于：
- 完整的 API 文档
- 详细的工作流指南
- 复杂的多步骤流程
- 主 SKILL.md 放不下的长内容
- 仅在特定场景才需要加载的内容

## 结构建议

### API 参考示例
- 概览
- 认证
- 带示例的接口说明
- 错误码
- 速率限制

### 工作流指南示例
- 前置条件
- 分步指令
- 常见模式
- 排错
- 最佳实践
"""

EXAMPLE_ASSET = """# 示例资源文件

本占位文件用于说明资源文件应该存放的位置。
如无需要可删除；需要时请替换为真实资源文件（模板、图片、字体等）。

资源文件不用于加载进上下文，而是用于 Codex 生成的最终输出产物中。

其它技能的资源文件示例：
- 品牌规范：logo.png、slides_template.pptx
- 前端构建：包含 HTML/React 样板的 hello-world/ 目录
- 字体：custom-font.ttf、font-family.woff2
- 数据：sample_data.csv、test_dataset.json

## 常见资源类型

- 模板：.pptx、.docx、样板目录
- 图片：.png、.jpg、.svg、.gif
- 字体：.ttf、.otf、.woff、.woff2
- 样板代码：工程目录、starter 文件
- 图标：.ico、.svg
- 数据文件：.csv、.json、.xml、.yaml

注意：这是一个文本占位文件。真实资源可以是任意文件类型。
"""


def normalize_skill_name(skill_name):
    """Normalize a skill name to lowercase hyphen-case."""
    normalized = skill_name.strip().lower()
    normalized = re.sub(r"[^a-z0-9]+", "-", normalized)
    normalized = normalized.strip("-")
    normalized = re.sub(r"-{2,}", "-", normalized)
    return normalized


def title_case_skill_name(skill_name):
    """Convert hyphenated skill name to Title Case for display."""
    return " ".join(word.capitalize() for word in skill_name.split("-"))


def parse_resources(raw_resources):
    if not raw_resources:
        return []
    resources = [item.strip() for item in raw_resources.split(",") if item.strip()]
    invalid = sorted({item for item in resources if item not in ALLOWED_RESOURCES})
    if invalid:
        allowed = ", ".join(sorted(ALLOWED_RESOURCES))
        print(f"[ERROR] 未知资源类型：{', '.join(invalid)}")
        print(f"   允许的类型：{allowed}")
        sys.exit(1)
    deduped = []
    seen = set()
    for resource in resources:
        if resource not in seen:
            deduped.append(resource)
            seen.add(resource)
    return deduped


def create_resource_dirs(skill_dir, skill_name, skill_title, resources, include_examples):
    for resource in resources:
        resource_dir = skill_dir / resource
        resource_dir.mkdir(exist_ok=True)
        if resource == "scripts":
            if include_examples:
                example_script = resource_dir / "example.py"
                example_script.write_text(EXAMPLE_SCRIPT.format(skill_name=skill_name))
                example_script.chmod(0o755)
                print("[OK] 已创建 scripts/example.py")
            else:
                print("[OK] 已创建 scripts/")
        elif resource == "references":
            if include_examples:
                example_reference = resource_dir / "api_reference.md"
                example_reference.write_text(EXAMPLE_REFERENCE.format(skill_title=skill_title))
                print("[OK] 已创建 references/api_reference.md")
            else:
                print("[OK] 已创建 references/")
        elif resource == "assets":
            if include_examples:
                example_asset = resource_dir / "example_asset.txt"
                example_asset.write_text(EXAMPLE_ASSET)
                print("[OK] 已创建 assets/example_asset.txt")
            else:
                print("[OK] 已创建 assets/")


def init_skill(skill_name, path, resources, include_examples):
    """
    Initialize a new skill directory with template SKILL.md.

    Args:
        skill_name: Name of the skill
        path: Path where the skill directory should be created
        resources: Resource directories to create
        include_examples: Whether to create example files in resource directories

    Returns:
        Path to created skill directory, or None if error
    """
    # Determine skill directory path
    skill_dir = Path(path).resolve() / skill_name

    # Check if directory already exists
    if skill_dir.exists():
        print(f"[ERROR] 技能目录已存在：{skill_dir}")
        return None

    # Create skill directory
    try:
        skill_dir.mkdir(parents=True, exist_ok=False)
        print(f"[OK] 已创建技能目录：{skill_dir}")
    except Exception as e:
        print(f"[ERROR] 创建目录时出错：{e}")
        return None

    # Create SKILL.md from template
    skill_title = title_case_skill_name(skill_name)
    skill_content = SKILL_TEMPLATE.format(skill_name=skill_name, skill_title=skill_title)

    skill_md_path = skill_dir / "SKILL.md"
    try:
        skill_md_path.write_text(skill_content)
        print("[OK] 已创建 SKILL.md")
    except Exception as e:
        print(f"[ERROR] 创建 SKILL.md 时出错：{e}")
        return None

    # Create resource directories if requested
    if resources:
        try:
            create_resource_dirs(skill_dir, skill_name, skill_title, resources, include_examples)
        except Exception as e:
            print(f"[ERROR] 创建资源目录时出错：{e}")
            return None

    # Print next steps
    print(f"\n[OK] 技能 '{skill_name}' 已成功初始化：{skill_dir}")
    print("\n下一步：")
    print("1. 编辑 SKILL.md：完成 TODO 项并更新 description")
    if resources:
        if include_examples:
            print("2. 定制或删除 scripts/、references/、assets/ 下的示例文件")
        else:
            print("2. 按需向 scripts/、references/、assets/ 添加资源文件")
    else:
        print("2. 只有在需要时才创建资源目录（scripts/、references/、assets/）")
    print("3. 准备好后运行校验器检查技能结构")

    return skill_dir


def main():
    parser = argparse.ArgumentParser(
        description="创建一个包含 SKILL.md 模板的新技能目录。",
    )
    parser.add_argument("skill_name", help="技能名称（会规范化为 hyphen-case）")
    parser.add_argument("--path", required=True, help="技能输出目录")
    parser.add_argument(
        "--resources",
        default="",
        help="逗号分隔列表：scripts,references,assets",
    )
    parser.add_argument(
        "--examples",
        action="store_true",
        help="在所选资源目录内创建示例文件",
    )
    args = parser.parse_args()

    raw_skill_name = args.skill_name
    skill_name = normalize_skill_name(raw_skill_name)
    if not skill_name:
        print("[ERROR] 技能名必须至少包含一个字母或数字。")
        sys.exit(1)
    if len(skill_name) > MAX_SKILL_NAME_LENGTH:
        print(
            f"[ERROR] 技能名 '{skill_name}' 过长（{len(skill_name)} 字符）。"
            f"最大长度为 {MAX_SKILL_NAME_LENGTH} 字符。"
        )
        sys.exit(1)
    if skill_name != raw_skill_name:
        print(f"提示：技能名已从 '{raw_skill_name}' 规范化为 '{skill_name}'。")

    resources = parse_resources(args.resources)
    if args.examples and not resources:
        print("[ERROR] --examples 需要同时设置 --resources。")
        sys.exit(1)

    path = args.path

    print(f"正在初始化技能：{skill_name}")
    print(f"   位置：{path}")
    if resources:
        print(f"   资源：{', '.join(resources)}")
        if args.examples:
            print("   示例：已启用")
    else:
        print("   资源：无（按需创建）")
    print()

    result = init_skill(skill_name, path, resources, args.examples)

    if result:
        sys.exit(0)
    else:
        sys.exit(1)


if __name__ == "__main__":
    main()
