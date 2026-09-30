# LRH 与 LRHD 格式设计

本文记录 LRH 与 LRHD 已实现格式。所有多字节整数使用大端序；偏移以文件或归档条目起点为零。

| 格式 | 状态 | 用途 |
| --- | --- | --- |
| LRH v3 | 位于 `LrhFormat` | 保存一个 Region 的完整区块内容 |
| LRHD v1 | 位于 `LrhdFormat` 与 `LrhdStreamWriter` | 保存一个目录下的所有 Region 区块变化 |

## 1. LRH

### 1.1 文件布局

| 顺序 | 部分 | 长度 |
| --- | --- | --- |
| 1 | 文件头 | 51 B |
| 2 | 未压缩区块表 | 8192 B |
| 3 | NBT Zstd 帧 | 可变，写入时启用 Frame Checksum |
| 4 | 文件尾签名 | 8 B |

### 1.2 文件头

| 偏移 | 字段 | 类型 / 长度 | 含义 |
| --- | --- | --- | --- |
| 0 | signature | 8 B | `0xc3ff13183cca9d9a` |
| 8 | version | u8 | 3 |
| 9 | newestTimestamp | i64 | 写入器汇总的非空区块最新时间戳，Unix epoch 秒 |
| 17 | compressionLevel | i16 | Zstd 压缩级别 |
| 19 | chunkCount | i32 | 非空区块数量 |
| 23 | compressedDataLength | u32 | NBT 压缩帧长度 |
| 27 | reserved | 8 B | 写入为零 |
| 35 | contentHash | 16 B | 下述 XXH3-128 内容哈希 |

### 1.3 区块表与数据区

区块表包含固定 1024 项，对应 Region 的 32 × 32 个区块。局部坐标对应索引 `localZ × 32 + localX`。

| 项内偏移 | 字段 | 类型 / 长度 | 含义 |
| --- | --- | --- | --- |
| 0 | length | i32 / 4 B | 未压缩 NBT 字节数；0 表示空区块；负数非法 |
| 4 | timestamp | i32 / 4 B | Unix epoch 秒，沿用 MCA 时间戳语义 |

NBT 数据帧从偏移 8243 开始，只包含按网格顺序拼接的非空区块 NBT，不包含区块表或逐记录长度前缀。解压后的区块偏移由前面各项长度累加得到。文件尾保存与文件头相同的 8 B 签名。

### 1.4 内容哈希与校验

```text
contentHash = XXH3-128(
    按网格顺序拼接的全部区块 NBT
    || 1024 项大端 i32 纯长度表（4096 B）
)
```

时间戳不参与内容哈希。Region blob 直接以这里计算的 `contentHash` 寻址；普通文件独立按原始字节哈希寻址。

| 部分 | 当前 `LrhFormat.read()` 的检查 |
| --- | --- |
| 签名、版本、文件尾 | 与预期值一致 |
| 压缩长度 | 与给定文件总长度一致 |
| 区块长度 | 非负，解压数据无不足或多余，并参与 contentHash |
| NBT | 解压校验及重新计算 contentHash |
| 时间戳 | 不参与内容哈希，无独立完整性校验 |
| 最新时间戳、区块数、压缩级别、保留字段 | 当前读取器未进行对应值的一致性检查 |

### 1.5 流式处理

- 写入：预留文件头和区块表，流式压缩 NBT、累计长度和哈希，结束后写文件尾并回填头表。
- 读取：读取头表，按长度顺序解压 NBT，消费完整帧后验证文件尾和内容哈希。
- 重压缩：将读取流直接连接到写入流，不需要中间解压文件。
- 校验完成前产生的输出不能视为成功结果；目标文件按现有临时文件和提交机制处理。

## 2. LRHD

### 2.1 目标与布局

采用未压缩的动态区块索引，放在数据区前。Region 只作为坐标和顺序分组。快照身份及基线关系由外层 `snapshot.bin` 管理，LRHD 文件头只保存压缩和索引元数据。通过元数据哈希保护文件头和完整索引，通过逐区块哈希与 Zstd checksum 保护 NBT。

```text
52 B 固定文件头 → 动态 Region 分组 → NBT Zstd 帧 → 8 B 文件尾
```

索引只保存发生替换或删除的区块。Region 和区块记录均按稳定顺序排列，索引长度可在写入 NBT 前根据差分规划计算。

### 2.2 文件头（52 B）

| 偏移 | 字段 | 类型 / 长度 | 含义 |
| --- | --- | --- | --- |
| 0 | signature | 8 B | `0x4c5248444c524844`（ASCII `LRHDLRHD`），文件尾使用相同值 |
| 8 | version | u8 / 1 B | 1 |
| 9 | reserved | u8 / 1 B | 必须为零 |
| 10 | compressionLevel | i16 / 2 B | Zstd 压缩级别 |
| 12 | regionCount | i32 / 4 B | 索引包含的 Region 数 |
| 16 | changedChunkCount | i32 / 4 B | 替换及删除记录总数 |
| 20 | dataLength | u64 / 8 B | 解压后的 NBT 总字节数 |
| 28 | compressedDataLength | u64 / 8 B | NBT Zstd 帧字节数 |
| 36 | metadataHash | 16 B | `XXH3-128(文件头（此字段置零）\|\|完整索引)` |

快照时间、基线时间及快照标识均由外层 `snapshot.bin` 提供。

`compressionLevel` 记录写入时使用的 Zstd 压缩级别；窗口参数由 Zstd 帧自身描述，不写入 LRHD。索引本身不压缩。

### 2.3 Region 分组

| 字段 | 类型 / 长度 | 含义 |
| --- | --- | --- |
| rx | i32 / 4 B | Region X 坐标 |
| rz | i32 / 4 B | Region Z 坐标 |
| chunkCount | u16 / 2 B | 本 Region 的变化区块记录数，范围 1~1024 |
| reserved | u16 / 2 B | 必须为零 |

每个 Region 分组固定 12 B，后接 `chunkCount` 个区块记录。Region 按有符号坐标 `(rx, rz)` 字典序严格递增，不允许重复。只收录至少含一个替换或删除记录的 Region。

### 2.4 每个区块记录（28 B）

| 项内偏移 | 字段 | 类型 / 长度 | 含义 |
| --- | --- | --- | --- |
| 0 | slotIndex | u16 / 2 B | `localZ × 32 + localX`，范围 0~1023 |
| 2 | state | u8 / 1 B | 1 替换；2 删除 |
| 3 | reserved | u8 / 1 B | 必须为零 |
| 4 | length | i32 / 4 B | 未压缩 NBT 长度；负数非法 |
| 8 | timestamp | i32 / 4 B | Unix epoch 秒，沿用 LRH/MCA 时间戳语义 |
| 12 | chunkHash | 16 B | `XXH3-128(该区块完整未压缩 NBT)` |

| 状态 | length | timestamp | chunkHash | 恢复行为 |
| --- | --- | --- | --- | --- |
| 替换 | 大于 0 | 目标区块时间戳 | NBT 哈希 | 写入新增或修改后的区块 |
| 删除 | 0 | 目标空槽时间戳 | 全零 | 移除基础区块 |

同一 Region 内的记录按 `slotIndex` 严格递增，不允许重复。索引不保存未变化记录。

未知状态必须拒绝。长度和时间戳按 LRH 使用有符号 i32；读取及写入时检查，不静默截断。
