# llm

大模型 Provider 集成，所有网络访问均为 CLI 发起的出站调用。

## 边界

本说明只描述当前保留的能力。生成产物、运行数据和已删除模块不属于本目录契约。

## 子目录

- `anthropic/`：Anthropic Messages API 的出站 Provider 实现。
- `api/`：Provider 公共 SPI、消息、工具调用和多部分请求模型。
- `openai/`：OpenAI-compatible 出站 Provider，可通过 Base URL 连接兼容服务。
- `provider/`：Provider 注册、规格和模型能力解析。

## 直接文件

除本说明外无直接文件；实现位于上列子目录。

