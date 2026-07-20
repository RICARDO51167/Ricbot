#!/usr/bin/env python3
"""
用于 ricbot 技能目录的最小校验器。
"""

import re
import sys
from pathlib import Path
from typing import Optional

try:
    import yaml
except ModuleNotFoundError:
    yaml = None

MAX_SKILL_NAME_LENGTH = 64
ALLOWED_FRONTMATTER_KEYS = {
    "name",
    "description",
    "metadata",
    "always",
    "license",
    "allowed-tools",
}
ALLOWED_RESOURCE_DIRS = {"scripts", "references", "assets"}
PLACEHOLDER_MARKERS = ("[todo", "todo:")


def _extract_frontmatter(content: str) -> Optional[str]:
    lines = content.splitlines()
    if not lines or lines[0].strip() != "---":
        return None
    for i in range(1, len(lines)):
        if lines[i].strip() == "---":
            return "\n".join(lines[1:i])
    return None


def _parse_simple_frontmatter(frontmatter_text: str) -> Optional[dict[str, str]]:
    """Fallback parser for simple frontmatter when PyYAML is unavailable."""
    parsed: dict[str, str] = {}
    current_key: Optional[str] = None
    multiline_key: Optional[str] = None

    for raw_line in frontmatter_text.splitlines():
        stripped = raw_line.strip()
        if not stripped or stripped.startswith("#"):
            continue

        is_indented = raw_line[:1].isspace()
        if is_indented:
            if current_key is None:
                return None
            current_value = parsed[current_key]
            parsed[current_key] = f"{current_value}\n{stripped}" if current_value else stripped
            continue

        if ":" not in stripped:
            return None

        key, value = stripped.split(":", 1)
        key = key.strip()
        value = value.strip()
        if not key:
            return None

        if value in {"|", ">"}:
            parsed[key] = ""
            current_key = key
            multiline_key = key
            continue

        if (value.startswith('"') and value.endswith('"')) or (
            value.startswith("'") and value.endswith("'")
        ):
            value = value[1:-1]
        parsed[key] = value
        current_key = key
        multiline_key = None

    if multiline_key is not None and multiline_key not in parsed:
        return None
    return parsed


def _load_frontmatter(frontmatter_text: str) -> tuple[Optional[dict], Optional[str]]:
    if yaml is not None:
        try:
            frontmatter = yaml.safe_load(frontmatter_text)
        except yaml.YAMLError as exc:
            return None, f"frontmatter 中的 YAML 无效：{exc}"
        if not isinstance(frontmatter, dict):
            return None, "Frontmatter 必须是 YAML 字典"
        return frontmatter, None

    frontmatter = _parse_simple_frontmatter(frontmatter_text)
    if frontmatter is None:
        return None, "frontmatter 中的 YAML 无效：未安装 PyYAML 时不支持该语法"
    return frontmatter, None


def _validate_skill_name(name: str, folder_name: str) -> Optional[str]:
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name):
        return (
            f"名称 '{name}' 应为 hyphen-case "
            "（仅允许小写字母、数字与单个连字符）"
        )
    if len(name) > MAX_SKILL_NAME_LENGTH:
        return (
            f"名称过长（{len(name)} 字符）。"
            f"最大长度为 {MAX_SKILL_NAME_LENGTH} 字符。"
        )
    if name != folder_name:
        return f"技能名 '{name}' 必须与目录名 '{folder_name}' 一致"
    return None


def _validate_description(description: str) -> Optional[str]:
    trimmed = description.strip()
    if not trimmed:
        return "description 不能为空"
    lowered = trimmed.lower()
    if any(marker in lowered for marker in PLACEHOLDER_MARKERS):
        return "description 仍包含 TODO 占位文本"
    if "<" in trimmed or ">" in trimmed:
        return "description 不能包含尖括号（< 或 >）"
    if len(trimmed) > 1024:
        return f"description 过长（{len(trimmed)} 字符）。最大长度为 1024 字符。"
    return None


def validate_skill(skill_path):
    """校验技能目录结构与必需 frontmatter。"""
    skill_path = Path(skill_path).resolve()

    if not skill_path.exists():
        return False, f"未找到技能目录：{skill_path}"
    if not skill_path.is_dir():
        return False, f"路径不是目录：{skill_path}"

    skill_md = skill_path / "SKILL.md"
    if not skill_md.exists():
        return False, "未找到 SKILL.md"

    try:
        content = skill_md.read_text(encoding="utf-8")
    except OSError as exc:
        return False, f"无法读取 SKILL.md：{exc}"

    frontmatter_text = _extract_frontmatter(content)
    if frontmatter_text is None:
        return False, "frontmatter 格式无效"

    frontmatter, error = _load_frontmatter(frontmatter_text)
    if error:
        return False, error

    unexpected_keys = sorted(set(frontmatter.keys()) - ALLOWED_FRONTMATTER_KEYS)
    if unexpected_keys:
        allowed = ", ".join(sorted(ALLOWED_FRONTMATTER_KEYS))
        unexpected = ", ".join(unexpected_keys)
        return (
            False,
            f"SKILL.md frontmatter 中存在不允许的键：{unexpected}。允许的属性包括：{allowed}",
        )

    if "name" not in frontmatter:
        return False, "frontmatter 中缺少 'name'"
    if "description" not in frontmatter:
        return False, "frontmatter 中缺少 'description'"

    name = frontmatter["name"]
    if not isinstance(name, str):
        return False, f"name 必须是字符串，实际为 {type(name).__name__}"
    name_error = _validate_skill_name(name.strip(), skill_path.name)
    if name_error:
        return False, name_error

    description = frontmatter["description"]
    if not isinstance(description, str):
        return False, f"description 必须是字符串，实际为 {type(description).__name__}"
    description_error = _validate_description(description)
    if description_error:
        return False, description_error

    always = frontmatter.get("always")
    if always is not None and not isinstance(always, bool):
        return False, f"'always' 必须是布尔值，实际为 {type(always).__name__}"

    for child in skill_path.iterdir():
        if child.name == "SKILL.md":
            continue
        if child.is_dir() and child.name in ALLOWED_RESOURCE_DIRS:
            continue
        if child.is_symlink():
            continue
        return (
            False,
            f"技能根目录中存在不允许的文件或目录：{child.name}。"
            "只允许 SKILL.md、scripts/、references/、assets/。",
        )

    return True, "技能结构有效！"


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("用法：python quick_validate.py <skill_directory>")
        sys.exit(1)

    valid, message = validate_skill(sys.argv[1])
    print(message)
    sys.exit(0 if valid else 1)
