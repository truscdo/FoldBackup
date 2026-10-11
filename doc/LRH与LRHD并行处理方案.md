# LRH 与 LRHD 并行处理方案

本文说明在线备份与独立归档工具中 LRH/LRHD 的并行边界。LRH、LRHD 格式定义见[格式设计](LRH与LRHD数据结构.md)。

## 一、整体结构

```text
备份与归档
├─ Minecraft Mod：在线快照写入 LRH
│  └─ Region 与普通文件任务并行，快照提交由协调线程完成
└─ 独立工具：CLI / TUI 共用归档核心
   ├─ 全量 LRH 导出与重压缩
   ├─ LRH / LRHD 恢复与导入
   └─ LRHD 差异包生成
```

Mod 在线备份只写入 LRH blob，不生成 LRHD 差异包。LRHD 差异包导出属于独立归档流程。

## 二、Mod 在线备份：LRH

```text
备份任务
├─ Minecraft 主线程：强制保存所有维度并禁用自动保存
├─ foldbackup-worker：执行备份协调流程
│  ├─ RegionProcessor 遍历世界目录并提交有界线程池
│  │  ├─ Region：元数据校验 → 快照复用 / 内容去重 / 流式写入 LRH
│  │  └─ 普通文件：哈希比较 → 复用 / 写入 blob
│  └─ 汇总扫描结果并提交快照索引
└─ Minecraft 主线程：恢复各维度原有保存状态并完成任务
```

- Minecraft API 仅用于主线程上的世界保存与保存状态切换；工作线程通过文件流读取世界文件并处理 LRH。
- Mod 的单线程执行器串行运行备份协调及相关后台操作；并行发生在 `RegionProcessor` 的有界工作池中。

## 三、独立归档工具

CLI 与 TUI 调用同一组归档核心服务。

### 3.1 全量 LRH 导出

```text
导出调用线程（协调线程）
├─ 读取快照清单并准备 snapshot.bin 元数据
│  └─ 顺序读取每个 LRH 的 23 B 前缀，统计 chunk_count
├─ 创建临时 TAR 并首先写入 snapshot.bin
├─ 处理 Region
│  ├─ 不重压缩：在调用线程中逐个复制 LRH blob
│  ├─ 顺序重压缩 (nThreads == 1)：在调用线程中逐个解压、重压缩并写入 TAR
│  └─ 并行重压缩（nThreads > 1）
│     ├─ 有界 producer worker pool：每个任务处理一个 .lrh
│     │  └─ 读取 LRH → 解压 NBT → 以 level 19 重压缩 → 输出到共享有界块缓冲
│     └─ 调用线程兼任唯一 TAR writer
│        ├─ 优先选择已完成的 Region 结果，不要求按清单顺序写入
│        ├─ TAR 条目头及 LRH 头使用0占位，完成后回填
│        └─ 缓冲区背压时，选一个未完成的输出改为流式写入以释放缓冲区
├─ 顺序复制普通文件
└─ 完成临时 TAR 后原子提交；失败时删除临时文件
```

- **线程边界：**元数据准备在导出调用线程同步完成。并行分支创建重压缩 worker pool；执行 pipeline 的调用线程同时负责唯一的 TAR 写入。普通文件复制和不重压缩路径也由该调用线程顺序执行。
- **任务粒度：**一个 producer 任务对应一个 `.lrh` Region。worker 领取任务后，从 blob 流式读取并解压 LRH，再把 NBT 流交给 level 19 的压缩器。压缩数据按块送入共享缓冲，不缓存完整 Region 或整个 TAR。
- **writer 的背压协作：**writer 平时先选择已完成的结果写入 TAR。若没有已完成结果、producer 正在等待块缓冲且已用块达到预算边界，它会选择一个已有输出块的未完成 Region，持续将该 Region 当前产生的块写入 TAR 并释放块，使 producer 能继续压缩和产出数据。这是排空输出以解除缓冲区背压。
- **LRH / TAR 收尾：**每个 LRH TAR 条目先写入头部占位，再边消费压缩块边写入条目；对应 producer 完成后，根据摘要补齐 LRH 头部和尾部。所有 Region 与普通文件完成后关闭临时 TAR，再原子移动到目标路径；失败时删除临时 TAR。
- 不重压缩路径和普通文件复制为顺序操作。

### 3.2 LRH / LRHD 恢复与导入

```text
打开归档并建立恢复计划
├─ TarIndex 顺序扫描 TAR 头部，记录条目位置和长度
├─ 校验 snapshot.bin、归档路径及差异包基线
└─ 建立 Region 与普通文件清单

LRH
├─ 世界恢复
│  ├─ 一个 .lrh Region 对应一个恢复任务
│  ├─ 独立有界输入流 → 流式校验 / 解码 LRH
│  └─ 按区块写出暂存 MCA/MCC
└─ 在线快照导入
   ├─ 一个 .lrh Region 对应一个导入任务
   ├─ 流式校验 LRH 并计算 Region contentHash
   ├─ 按 contentHash 复用或提交 LRH blob
   └─ 保留清单中的源文件属性并生成 RegionEntry

LRHD
├─ LRHD worker pool：每个 LRHD 使用一个顺序 reader 消费单个连续 Zstd 帧
│  ├─ 按索引顺序把当前 Region 的区块变化写入暂存
│  └─ 每完成一个 Region 暂存，就将该 Region 交给 Region worker pool
├─ Region worker pool：并行合并基础 LRH 与对应 Region 的差异
│  └─ 校验替换区块哈希并合并 Region
│     ├─ 世界恢复：暂存 MCA/MCC
│     └─ 在线快照导入：暂存完整 LRH blob
└─ 所有 Region 任务及 LRHD 校验成功后，提交恢复文件或快照
```

- **LRH 解码与校验：**`LrhFormat.read` 按长度表顺序流式解压全部 1024 个槽位，并将每槽 NBT 字节交给下游 consumer，不缓存完整 Region。读取过程校验 LRH 签名 / 版本、压缩帧长度、槽位长度、帧是否恰好由长度表覆盖、尾部签名，以及基于 NBT 和长度表计算的 contentHash。
- **世界恢复：**全量 LRH 包中每个 Region 独立运行。`McaStreamWriter` 逐区块生成标准 MCA；超过 MCA 扇区限制的区块写为 MCC。MCA/MCC 先落入恢复暂存目录。所有 Region 任务以及存在时的 LRHD 校验成功后，恢复器再将暂存文件逐个移动到目标目录；目标目录发布是逐文件进行的。
- **在线导入：**全量包的 Region 按 `contentHash` 共享 LRH 对象。导入校验对象的内容身份后复用已有 blob，或将对象提交到对应内容地址；不生成中间 MCA/MCC。普通文件按原始字节哈希共享压缩对象，导入时校验解压后的长度和哈希。清单中的源文件属性随快照保存；所有对象完成后才提交快照清单。
- **带 LRHD 时：**每个 LRHD reader 按索引顺序消费单个连续 Zstd 帧，将当前 Region 的替换区块暂存到独立文件，再交给 Region worker 与基础 LRH 并行合并。reader 与 Region worker 共用有界在途窗口和磁盘 I/O 预算；暂存文件只保留待处理或正在处理的替换区块，不缓存完整 Region。LRHD 帧及区块哈希验证成功后才报告该组 Region 完成；恢复文件和导入快照仍在全部任务成功后发布。
- **差异在线导入：**差异清单描述目标快照的全部 Region 和普通文件路径。基线中已有的 `contentHash` 直接复用；缺失的目标 Region 才通过基础 LRH 与 LRHD 合并并编码为 LRH，结果哈希必须与目标清单一致。普通文件按路径覆盖或删除，缺失的内容对象从差异包导入。LRHD reader 仍完整消费并验证所有差异帧，之后才提交目标快照清单。

## 四、LRHD 差异包生成

```text
Region 比较与解码任务
├─ 一个目录对应一个比较与解码任务
├─ 解码基础 LRH，仅保留区块哈希
├─ 逐区块解码目标 LRH 并计算哈希
│  ├─ 相同: 释放
│  ├─ 变化: 交给 Zstd 编码器
│  └─ 删除: 仅记录索引
└─ 完成基准与目标 LRH 校验

Zstd 编码器
├─ 一个目录对应一个编码器实例
├─ 由 ZSTDMT 池线程并行压缩，worker 数在帧开始前一次确定
├─ 按 Region 与区块顺序消费变化 NBT
└─ 单帧输出一个连续 Zstd 帧，不另设应用层压缩线程

TAR 写入
├─ 按目录顺序逐个写入，一个目录一个条目
├─ TAR 条目头使用 0 占位，完成后回填
└─ 如果目录内无变化区块则回滚条目（截断占位头）
```

- 每个目标 LRH 只解码一次。
- 由于目录任务大小无法准确估算，如果并行进行，资源分配可能不合理，所以目录任务按顺序处理。
- 解码基础 LRH 在独立线程中运行，因此下一个 Region 的基础 LRH 由一个助手线程提前解码，使压缩任务不因等待而空转。
- 比较与解码任务同一时刻至多持有一个变化区块，写入编码器后所有权转移；反压由编码器内部队列提供
- 每个目录的编码器按当前压缩预算申请尽可能多的 worker，帧内并行度再由 job 数决定。
- lrhd 的写入按照编码布局进行。
