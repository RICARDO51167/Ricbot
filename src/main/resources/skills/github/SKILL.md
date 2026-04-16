---
name: github
description: "使用 `gh` CLI 与 GitHub 交互。通过 `gh issue`、`gh pr`、`gh run`、`gh api` 处理 issue、PR、CI 运行记录与高级查询。"
metadata: {"ricbot":{"emoji":"🐙","requires":{"bins":["gh"]},"install":[{"id":"brew","kind":"brew","formula":"gh","bins":["gh"],"label":"安装 GitHub CLI（brew）"},{"id":"apt","kind":"apt","package":"gh","bins":["gh"],"label":"安装 GitHub CLI（apt）"}]}}
---

# GitHub 技能

使用 `gh` CLI 与 GitHub 交互。不在 git 目录中时，务必显式指定 `--repo owner/repo`，或直接使用 URL。

## Pull Request

查看某个 PR 的 CI 状态：
```bash
gh pr checks 55 --repo owner/repo
```

列出最近的 workflow 运行记录：
```bash
gh run list --repo owner/repo --limit 10
```

查看某次运行，并定位失败步骤：
```bash
gh run view <run-id> --repo owner/repo
```

只查看失败步骤的日志：
```bash
gh run view <run-id> --repo owner/repo --log-failed
```

## 高级查询（API）

`gh api` 适用于访问其它子命令无法直接获取的数据。

获取 PR 的指定字段：
```bash
gh api repos/owner/repo/pulls/55 --jq '.title, .state, .user.login'
```

## JSON 输出

多数命令支持 `--json` 输出结构化数据，可配合 `--jq` 做过滤：

```bash
gh issue list --repo owner/repo --json number,title --jq '.[] | "\(.number): \(.title)"'
```
