# persistence

对应生产包 `persistence` 的自动化测试，验证正常路径、失败边界和持久化不变量。

## 边界

本说明只描述当前保留的能力。生成产物、运行数据和已删除模块不属于本目录契约。

## 子目录

无直接子目录。

## 直接文件

- `EventClassificationCatalogTest.java`：验证 Event Classification Catalog Test 的行为与边界。
- `FileRuntimeFactJournalTest.java`：验证 File Runtime Fact Journal Test 的行为与边界。
- `FileSharedStateStoreTest.java`：验证 File Shared State Store Test 的行为与边界。
- `ImmutableArtifactStoreTest.java`：验证 Immutable Artifact Store Test 的行为与边界。

