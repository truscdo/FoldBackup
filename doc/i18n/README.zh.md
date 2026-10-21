# FoldBackup

FoldBackup 是面向 Minecraft NeoForge 服务端的世界备份工具，支持自动与手动`快照备份`、`归档压缩`、`历史区块预览`和`区块回档`。

快照的储存使用了与git类似的内容去重方案，每次备份仅记录更改和新增的文件，同时使用lrh格式储存Region文件，在1.21.1 neoforge 80+ mod 生存服中，压缩等级为19时的`归档`文件可以做到仅为原存档的三分之一大小。

同一个 JAR 既可以安装为 NeoForge 模组，也可以作为独立工具运行，提供独立命令行（CLI）和交互菜单（TUI），方便在服务器关闭时使用功能。

## 主要功能

- **在线备份**：按玩家在线时间定时备份，支持手动触发，以及可选的启动、关闭时备份
- **内容去重**：新建快照的未变化内容不占用空间
- **快照管理**：支持查看快照链中每个快照占用的空间，以及可配置的自动清理
- **区块预览与回档**：在游戏中预览区块历史景象，允许指定快照中的单个区块恢复到世界中

    同时支持游戏内区块回档与游戏外CLI直接对存档进行区块回档，可以用于处理异常区块或者实体过多导致游戏崩溃的情况
- **存档导出**：将快照导出为 Minecraft 游戏存档
- **归档压缩**：可以创建完整归档或差分归档，并使用归档文件恢复为游戏存档或导入到快照仓库

    归档用于储存和分发存档，同时支持指定一个旧的完整归档，在此基础上生成差分归档；对于服务器场景，这可以在一次完整归档下载后，通过差分归档文件将存档同步至本地，本地编辑完成后再通过差分归档同步至服务器，仅传输更改部分，节约带宽

### LRH 与 Linear

**LRH 是基于 Linear region 格式的流式实现版本**，用于 FoldBackup 的 Region 数据压缩与存储。原始项目为 [xymb-endcrystalme/LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools)，感谢其提供的格式设计与实现。

另外新增了 LRHD 格式用于记录归档中的区块差异，实现区块级差异备份与储存。

## 快速开始

### 创建备份

1. 将 FoldBackup JAR 放入 NeoForge 服务端的 `mods/` 目录。
2. 启动服务器，等待世界加载和 FoldBackup 初始化完成。
3. 在游戏内以权限等级 2 或更高的身份执行：

   ```text
   /foldbackup backup --force
   /foldbackup list
   ```

   单机存档即使未开启作弊，主机玩家也能使用这些命令；通过局域网加入的其它玩家不能使用。

默认备份目录是服务端运行目录下的 `foldbackups/`，可以通过 `general.backupPath` 修改。该配置是备份根：专用服务器直接以它为仓库，单机则使用 `foldbackups/<存档目录名>`，每个存档各自持有独立仓库。备份前会强制保存世界并暂时关闭自动保存，结束后恢复保存状态。

默认开启自动备份，间隔按有玩家在线时的累计时间计算。手动备份也受最小备份间隔限制，需要跳过该间隔时可以使用 `/foldbackup backup --force`。

### 整体回档

回档整个游戏存档可以使用TUI或者CLI。

TUI的使用较为简单，每次世界启动时都会在该世界的仓库目录生成启动脚本（专用服务器为 `foldbackups/`，单机为 `foldbackups/<存档目录名>`）：

- Windows：`foldbackup-tui.bat`
- Linux／支持 Bash 的环境：`foldbackup-tui.sh`

运行即可打开TUI，脚本会自动定位 JAR、准备运行库并启动菜单。在菜单中选择“导出为游戏存档”，选择快照、输出形式和输出路径，再确认执行即可获得对应快照时间的游戏存档，然后可以根据需要对游戏存档进行替换。

### 区块回档

支持游戏内使用命令预览与回档单个区块，同时也支持使用CLI直接操作游戏存档回档单个区块。

在游戏中可以使用`/foldbackup preview <x> <z> [快照ID]`对目标快照和目标区块进行预览，这条命令会改变目标区块在客户端显示的内容，替换为目标快照时间的区块景象，不会真正的修改区块；
`x`与`z`是区块坐标，可以通过F3信息显示，或者将方块坐标除以16并向下取整获得；
预览时目标区块会显示蓝色粒子效果进行区分，使用`/foldbackup preview exit`即可退出预览。

游戏内回档可使用`/foldbackup rollback chunk <x> <z> [快照ID]`，也可以用`/foldbackup rollback chunk in <维度> <x> <z> [快照ID]`指定维度。目标区块必须已经加载到内存中；未加载的区块请使用下面的CLI离线回档方式。省略快照ID时使用最新快照。

当需要使用CLI回档时，以下示例假设当前目录为 `foldbackups`，且服务端存档目录为同级的 `world`。如果在其它目录执行，需要相应调整JAR、存档和快照目录路径；也可以用 `--snapshot-root <目录>` 指定快照目录。单机存档的仓库位于 `foldbackups/<存档目录名>`，需要把 `--snapshot-root` 指向该目录。离线回档前必须先关闭服务端。

首次使用CLI时，需要先提取随 JAR 打包的运行库，这个设计是为了避免mod与CLI的依赖冲突：

```bash
java -jar ../mods/foldbackup-0.1.0.jar extract
```

运行完毕后即可使用其它命令。

列出快照：

```bash
java -jar ../mods/foldbackup-0.1.0.jar snapshot list
```

从列表中复制目标快照ID，然后执行区块回档。以下命令会将主世界 `world` 中区块 `[x, z]` 恢复到该快照的状态：

```bash
java -jar ../mods/foldbackup-0.1.0.jar snapshot rollback <快照ID> --world ../world --chunk <区块X> <区块Z>
```

`--world` 指向目标维度目录：主世界使用存档根目录，下界使用 `../world/DIM-1`，末地使用 `../world/DIM1`，自定义维度使用其 `dimensions/<命名空间>/<路径>` 目录。`--chunk` 接收有符号整数区块坐标，不是方块坐标；例如方块坐标 `-1` 对应区块坐标 `-1`。CLI 会在执行前要求确认；可追加 `--yes` 跳过确认。

CLI会恢复目标区块的地形、方块实体及可恢复的实体数据，不会恢复 POI、玩家数据或世界级配置。请检查目标快照、维度和区块坐标后再确认操作。

### 完整归档与差分归档

归档用于储存和分发存档，以高压缩率为目标，性能占用较大，因此仅提供CLI和TUI实现。

归档操作同样推荐使用TUI进行；为了方便管理，归档以快照为目标，所以创建归档文件前需要有对应的快照，这可以通过游戏内备份，或者TUI与CLI中的快照创建实现。

在TUI中选择创建归档选项，选择对应的快照和压缩级别等信息即可创建归档；如果希望创建差分归档，额外选择一个快照作为基础即可。

归档可以恢复为游戏存档或者快照仓库的快照，作为存档分发方案时，可以将mod与tui启动脚本一起打包。

归档格式与游戏版本无关，对于mod不支持的版本，也可以使用CLI或者TUI进行压缩。

## 常用游戏内命令

| 命令 | 用途 |
| --- | --- |
| `/foldbackup backup` | 手动创建快照 |
| `/foldbackup backup --force` | 跳过最小备份间隔触发备份 |
| `/foldbackup list` | 查看快照列表 |
| `/foldbackup status` | 查看任务状态 |
| `/foldbackup info <快照ID>` | 查看快照详情 |
| `/foldbackup delete <快照ID>` | 删除指定快照 |
| `/foldbackup preview <x> <z> [快照ID]` | 预览当前维度的历史区块 |
| `/foldbackup preview exit` | 结束预览 |
| `/foldbackup rollback chunk <x> <z> [快照ID]` | 回档当前维度的单个区块 |
| `/foldbackup rollback chunk in <维度> <x> <z> [快照ID]` | 回档指定维度的单个区块 |

## 关键配置

配置通过 NeoForge 的 SERVER 配置系统管理。下表中的名称使用“分组.键名”表示。

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `general.enabled` | `true` | 开启自动备份 |
| `general.backupPath` | `./foldbackups` | 相对于服务端运行目录的备份根，也支持绝对路径。专用服务器直接以它为仓库；单机在其下按存档目录名各建一个仓库 |
| `general.compressionLevel` | `6` | Zstd 压缩级别，范围 1–22 |
| `scheduling.intervalMinutes` | `120` | 自动备份的玩家在线时间累计间隔，单位分钟 |
| `scheduling.minIntervalMinutes` | `10` | 两次备份的最小墙钟间隔，单位分钟 |
| `scheduling.backupOnStart` | `false` | 启动时备份 |
| `scheduling.backupOnStop` | `false` | 关闭时备份 |
| `retention.enabled` | `true` | 开启自动清理 |
| `retention.maxSizeGB` | `50` | 清理后的仓库容量目标，单位 GiB；0 表示不限 |
| `retention.minFreeSpaceGB` | `10` | 文件系统剩余空间目标，单位 GiB；0 表示禁用 |
| `retention.maxAgeDays` | `30` | 快照最大保留天数；0 表示不限 |
| `performance.regionThreads` | `0` | 处理工作线程数，0 表示自动 |
| `performance.regionMemoryLimitMB` | `0` | 流式任务内存预算，0 表示自动 |

**自动清理可能删除包括手动备份在内的全部历史快照。** 容量或剩余空间限制也可能导致未满 30 天的快照被删除；剩余空间目标不代表已为下一次备份预留空间。三个清理限制均设为 0 时禁用自动删除，也可以通过 `retention.enabled` 关闭自动清理。

`general.excludePatterns` 按世界目录内的相对路径匹配，路径分隔符使用 `/`。默认排除 `**/session.lock`、`**/backups`、`**/.backups` 和 `**/*.wip`；匹配目录时会排除整个子目录树。

## 从源码构建

完整环境准备、测试命令及报告位置见 [构建与测试指南](../构建与测试指南.md)。

## 许可证与致谢

FoldBackup 的项目元数据声明采用 LGPL v3 许可证。

LRH 基于 Linear region 格式实现，原始项目 [LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools) 采用 BSD-2-Clause 许可证。感谢原作者及贡献者的工作。
