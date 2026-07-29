# Order Fulfillment Demo Seed

这是 Ricbot Runtime v4 的可丢弃演示工程种子，不是完成品。准备脚本会把它复制到
`target/ricbot-full-demo/`，生成足够大的审计规则并初始化独立 Git 仓库。随后让 Team Run
实现 CSV 批量导入、库存原子预留、幂等、dry-run、JSONL 审计和完整测试。

不要直接在本目录运行演示，以免把 Agent 修改写回 Ricbot 仓库。
