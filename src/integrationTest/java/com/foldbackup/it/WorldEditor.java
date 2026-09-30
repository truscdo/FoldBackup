package com.foldbackup.it;

import java.io.IOException;
import java.util.Objects;

/**
 * 通过 RCON 命令操作 Minecraft 世界中的方块。
 *
 * <p>
 * 所有坐标均为绝对世界坐标。
 */
public class WorldEditor {

    private final RconClient rcon;

    public WorldEditor(RconClient rcon) {
        this.rcon = Objects.requireNonNull(rcon, "rcon");
    }

    /**
     * 在指定位置放置方块。
     *
     * @param pos     世界坐标
     * @param blockId 例如 {@code minecraft:gold_block}
     */
    public void setBlock(BlockPos pos, String blockId) throws IOException {
        rcon.sendCommand(String.format("/setblock %d %d %d %s", pos.x(), pos.y(), pos.z(), blockId));
    }

    /**
     * 用方块填充长方体区域。
     */
    public void fill(BlockPos from, BlockPos to, String blockId) throws IOException {
        rcon.sendCommand(String.format("/fill %d %d %d %d %d %d %s",
                from.x(), from.y(), from.z(), to.x(), to.y(), to.z(), blockId));
    }

    /**
     * 读取指定位置的方块数据 NBT。
     *
     * @return {@code /data get block} 的原始响应文本
     */
    public String getBlockData(BlockPos pos) throws IOException {
        return rcon.sendCommand(String.format("/data get block %d %d %d", pos.x(), pos.y(), pos.z()));
    }

    /**
     * 检查指定位置的方块是否为期望的 ID，
     * 使用 {@code /execute if block}。
     *
     * @return 命令响应，方块匹配时返回空字符串
     */
    public String checkBlock(BlockPos pos, String blockId) throws IOException {
        return rcon.sendCommand(String.format("/execute if block %d %d %d %s", pos.x(), pos.y(), pos.z(), blockId));
    }

    /**
     * 在指定位置生成标记实体（盔甲架）。
     *
     * <p>实体回档场景见 {@code EntityRollbackIT}。
     */
    public void spawnMarkerEntity(BlockPos pos, String customName) throws IOException {
        rcon.sendCommand(String.format(
                "/summon minecraft:armor_stand %f %f %f {CustomName:'\"%s\"',NoGravity:1b,Invisible:1b}",
                pos.x() + 0.5, (double) pos.y(), pos.z() + 0.5, customName));
    }

    /**
     * 将 NBT 数据合并到指定位置的方块实体中。
     * <p>
     * 在 NBT 中的 JSON 字符串使用单引号（{@code '...'}）包裹，
     * 以避免与 RCON 协议的引号冲突。
     *
     * @param pos 世界坐标
     * @param nbt 要合并的 NBT 数据，例如
     *            {@code {Items:[{Slot:0,id:"minecraft:diamond",Count:1b}]}}
     * @return 命令响应
     */
    public String mergeBlockData(BlockPos pos, String nbt) throws IOException {
        return rcon.sendCommand(String.format(
                "/data merge block %d %d %d %s",
                pos.x(), pos.y(), pos.z(), nbt));
    }

    /**
     * 触发备份并从成功消息中解析快照 ID。
     *
     * @return 快照 ID，若解析失败则返回 {@code null}
     */
    public String triggerBackup(boolean force) throws IOException {
        String cmd = force ? "/foldbackup backup --force" : "/foldbackup backup";
        String response = rcon.sendCommand(cmd);
        // 命令是异步的；解析首次响应。
        // Example response: Backup completed! Snapshot: S-example
        if (response.contains("completed")) {
            // 成功消息中包含快照 ID
            int idx = response.indexOf("Snapshot:");
            if (idx >= 0) {
                String after = response.substring(idx + "Snapshot:".length()).trim();
                int space = after.indexOf(' ');
                return space > 0 ? after.substring(0, space) : after;
            }
        }
        return null;
    }

    /**
     * 轮询备份状态（通过 {@code /foldbackup status}）直至空闲。
     *
     * @return 最近的活动任务 ID，若已空闲则返回 {@code null}
     */
    public String waitForBackupComplete(int timeoutSeconds) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String status = rcon.sendCommand("/foldbackup status");
            // "Idle" 表示无活动备份
            if (status.contains("Idle")) {
                return null;
            }
            Thread.sleep(500);
        }
        throw new RuntimeException("Backup did not complete within " + timeoutSeconds + "s");
    }
}
