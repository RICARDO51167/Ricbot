# eval

对应生产包 `eval` 的自动化测试，验证正常路径、失败边界和持久化不变量。

## 边界

本说明只描述当前保留的能力。生成产物、运行数据和已删除模块不属于本目录契约。

## 子目录

无直接子目录。

## 直接文件

- `EvalGoldenScenariosTest.java`：验证 Eval Golden Scenarios Test 的行为与边界。
- `EvalHarnessTest.java`：验证 Eval Harness Test 的行为与边界。
- `EvalMatrixRunnerTest.java`：验证 Eval Matrix Runner Test 的行为与边界。

