---
name: weather
description: 获取当前天气与预报（无需 API Key）。
homepage: https://wttr.in/:help
metadata: {"ricbot":{"emoji":"🌤️","requires":{"bins":["curl"]}}}
---

# Weather

两个免费的服务，无需 API Key。

## wttr.in (primary)

快速一行：
```bash
curl -s "wttr.in/London?format=3"
# Output: London: ⛅️ +8°C
```

紧凑格式：
```bash
curl -s "wttr.in/London?format=%l:+%c+%t+%h+%w"
# Output: London: ⛅️ +8°C 71% ↙5km/h
```

完整预报：
```bash
curl -s "wttr.in/London?T"
```

格式代码：`%c` 天气状况 · `%t` 温度 · `%h` 湿度 · `%w` 风 · `%l` 地点 · `%m` 月相

小贴士：
- 空格要做 URL 编码：`wttr.in/New+York`
- 支持机场代码：`wttr.in/JFK`
- 单位：`?m`（公制）`?u`（英制/美制）
- 只看今天：`?1` · 只看当前：`?0`
- PNG：`curl -s "wttr.in/Berlin.png" -o /tmp/weather.png`

## Open-Meteo (fallback, JSON)

免费、无需 key，适合程序化使用：
```bash
curl -s "https://api.open-meteo.com/v1/forecast?latitude=51.5&longitude=-0.12&current_weather=true"
```

先获取城市坐标再查询。会返回包含温度、风速、weathercode 等字段的 JSON。

Docs: https://open-meteo.com/en/docs
