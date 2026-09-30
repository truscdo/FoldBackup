# CLI 命令设计方案

## 常用命令

为方便阅读，以下命令均忽略了 `--snapshot-root <dir>`、`--archive-root <dir>`、`--lib <dir>`，实际使用时需要按需添加。

```text
foldbackup extract [--output <dir>]

foldbackup snapshot list
foldbackup snapshot create --world <dir> [--threads <N>]
foldbackup snapshot import --all-archive
foldbackup snapshot export <snapshot-id> --output <path> [--zip] [--threads <N>]
foldbackup snapshot delete <snapshot-id>
foldbackup snapshot rollback <snapshot-id> --world <dir> --chunk <x> <z>

foldbackup archive create --snapshot <snapshot-id> [--base <snapshot-id>]
foldbackup archive export <archive-file> --output <dir>
foldbackup archive verify [<archive-file>]

foldbackup tui
```

## 默认配置

| 参数 | 含义 | 适用命令 | 默认值 |
| --- | --- | --- | --- |
| `--snapshot-root <dir>` | 在线备份库目录 | `snapshot` 全部子命令、`archive create`、`tui` | 工作目录 |
| `--archive-root <dir>` | 归档库目录 | `archive` 全部子命令、`snapshot import --all-archive`、`tui` | 工作目录 |
| `--lib <dir>` | 运行库目录 | 除了 `extract` 外的全部命令 | 工作目录下的 `.foldbackup-cli` |

`extract` 用于提取运行库，省略 `--output` 时输出到工作目录下的 `.foldbackup-cli`，与 `--lib` 的默认位置一致。指定其他提取位置后，后续命令需通过 `--lib` 指向该位置。

## 命令语义

以下为目标 CLI 行为约定，不表示所有选项均已实现。

### 快照导入与归档创建

- `snapshot create --world <dir> [--threads <N>]` 直接从游戏存档创建新快照。
- `snapshot import --all-archive` 扫描归档库中的全部归档，依据归档依赖关系恢复其中的快照并导入在线库。缺失依赖或归档损坏时需报告具体文件和原因，不能将不完整的快照标记为导入成功。
- `archive create --snapshot <snapshot-id>` 为指定在线快照创建完整归档，输出到归档库。
- 指定 `--base <snapshot-id>` 时，以该在线快照为基准，与目标在线快照计算差异并创建差异归档。两个快照都必须存在于在线库，`--base` 不接受归档文件路径。
- 差异归档不能独立恢复。导入或导出时，需要归档库中存在对应基准快照的完整归档；缺失时明确报错，不使用当前在线库数据替代归档依赖。

### 导出产物与输出路径

| 命令 | 导出产物 | `--output` 含义 |
| --- | --- | --- |
| `snapshot export <snapshot-id>` | 指定快照对应的 Minecraft save 存档目录 | 最终 save 目录路径，不再追加快照名子目录 |
| `snapshot export <snapshot-id> --zip` | 上述 save 存档的 ZIP 包 | 最终 ZIP 文件路径，必须使用 `.zip` 扩展名 |
| `archive export <archive-file>` | 目标归档对应的 Minecraft save 存档目录 | 最终 save 目录路径，不再追加归档名子目录 |

### 区块回滚

`snapshot rollback` ，其作用范围仅为指定维度中的指定区块，不表示整个世界回滚。

- `--world <dir>` 指向目标维度目录：主世界使用 save 根目录，下界使用 `<save>/DIM-1`，末地使用 `<save>/DIM1`，自定义维度使用 `<save>/dimensions/<namespace>/<path>`。程序根据目录确定快照中的维度路径。
- `--chunk <x> <z>` 接收有符号整数区块坐标，不是方块坐标。方块坐标换算为区块坐标时除以 16 并向下取整。
- 恢复指定区块的地形、方块实体及可恢复的实体数据；实体冲突或跳过情况需在结果中报告。当前范围不包含 POI 数据、玩家数据及世界级配置，不宣称完整恢复该区块的全部关联状态。
- 目标世界必须停止运行，写入前检查世界锁；无法取得锁时终止回滚。

### 归档校验

`archive verify <archive-file>` 校验指定归档及其恢复所需的依赖；省略文件参数时，校验归档库中的全部归档。校验包括归档格式、数据完整性和基准依赖是否完整。发现损坏或缺失依赖时报告具体文件并以非零退出码结束；全库校验应继续检查其余归档，最后汇总结果。

## 已有目标与操作确认

- 两种 `export` 的最终输出路径已存在时默认拒绝执行，不合并目录或覆盖文件。首次实现不提供覆盖选项，用户需选择新的输出路径。
- `extract` 允许使用已存在的目录，但遇到同名运行库文件时拒绝覆盖；`archive create` 遇到同名归档也拒绝覆盖。
- 批量导入时，已有且内容一致的快照跳过并报告；相同 ID 对应不同内容时报告冲突，不覆盖已有快照。
- `snapshot delete` 和 `snapshot rollback` 在交互终端中先显示目标及影响范围，再要求确认。提供 `--yes` 跳过交互确认；非交互环境未指定 `--yes` 时直接报错，不等待输入。
- `--yes` 仅跳过确认，不跳过世界锁、依赖完整性及数据校验。

## 高级选项

高级选项保留在详细帮助中，不放入常用命令示例。省略时由程序提供合理默认值，具体默认值在实现时确定。

| 选项 | 适用命令 | 用途 |
| --- | --- | --- |
| `--level <N>` | `archive create` | 指定压缩级别 |
| `--no-recompress` | `archive create` | 禁用重新压缩 |
| `--threads <N>` | `archive create`、`archive export`、`snapshot create`、`snapshot export`、`snapshot import --all-archive` | 指定线程数 |
| `--yes` | `snapshot delete`、`snapshot rollback` | 跳过交互确认，供脚本或已确认操作使用；默认不启用 |
