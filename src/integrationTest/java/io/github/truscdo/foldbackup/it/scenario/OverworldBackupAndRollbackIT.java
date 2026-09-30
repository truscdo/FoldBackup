package io.github.truscdo.foldbackup.it.scenario;

import io.github.truscdo.foldbackup.it.BlockPos;
import io.github.truscdo.foldbackup.it.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：主世界备份与内存回档。
 *
 * <p>
 * 场景（对应测试方案 §6.2）：
 * <ol>
 * <li>服务器运行中（由 {@link IntegrationTestBase} 管理）</li>
 * <li>在指定区块放置金块 + 信标</li>
 * <li>执行备份</li>
 * <li>修改方块（钻石块、书架、新增箱子）</li>
 * <li>回档 chunk [0, 0] 到快照</li>
 * <li>验证 chunk [0, 0] 已恢复；chunk [1, 0] 未被影响</li>
 * </ol>
 */
@DisplayName("Overworld backup & in-memory chunk rollback (Scenario B)")
public class OverworldBackupAndRollbackIT extends IntegrationTestBase {

        /** Chunk [0, 0]，偏移 (8, 8) → 世界坐标 (8, 64, 8)。 */
        private static final BlockPos GOLD_POS = BlockPos.fromChunk(0, 0, 8, 64, 8);

        /** Chunk [1, 0]，偏移 (8, 8) → 世界坐标 (24, 64, 8)。 */
        private static final BlockPos BEACON_POS = BlockPos.fromChunk(1, 0, 8, 64, 8);

        /** Chunk [0, 0]，偏移 (5, 5) → 世界坐标 (5, 64, 5) — 备份前为空气。 */
        private static final BlockPos NEW_CHEST_POS = BlockPos.fromChunk(0, 0, 5, 64, 5);

        @Test
        @DisplayName("Backup → modify blocks → rollback chunk → verify state")
        void testBackupAndRollback() throws Exception {
                // ── 步骤 0：确保测试区块已加载 ──
                // forceload 参数是方块坐标（column_pos），不是区块坐标：
                //   add 0 0  → block (0,0)  → chunk [0,0]
                //   add 16 0 → block (16,0) → chunk [1,0]
                System.out.println("--- Step 0: Force-load target chunks ---");
                rcon.sendCommand("/forceload add 0 0");
                rcon.sendCommand("/forceload add 16 0");
                System.out.println("  Chunks [0,0] and [1,0] force-loaded");

                // ── 步骤 1：初始化测试方块 ──
                // 放置可识别的方块 + 将 NEW_CHEST_POS 显式设为空气
                System.out.println("--- Step 1: Initialize test blocks ---");
                world.setBlock(GOLD_POS, "minecraft:gold_block");
                world.setBlock(BEACON_POS, "minecraft:beacon");
                world.setBlock(NEW_CHEST_POS, "minecraft:air");
                System.out.println("  Placed gold_block at " + GOLD_POS);
                System.out.println("  Placed beacon at " + BEACON_POS);
                System.out.println("  Placed air at " + NEW_CHEST_POS);

                // 验证测试环境
                String goldCheck = world.checkBlock(GOLD_POS, "minecraft:gold_block");
                assertTrue(goldCheck.isEmpty() || goldCheck.toLowerCase().contains("pass"),
                                "gold_block should be placed before backup. Got: " + goldCheck);
                String beaconCheck = world.checkBlock(BEACON_POS, "minecraft:beacon");
                assertTrue(beaconCheck.isEmpty() || beaconCheck.toLowerCase().contains("pass"),
                                "beacon should be placed before backup. Got: " + beaconCheck);
                String airCheck = world.checkBlock(NEW_CHEST_POS, "minecraft:air");
                assertTrue(airCheck.isEmpty() || airCheck.toLowerCase().contains("pass"),
                                "NEW_CHEST_POS should be air before backup. Got: " + airCheck);

                // ── 步骤 2：执行备份 ──
                System.out.println("--- Step 2: Take backup ---");
                // 确保世界已保存到磁盘
                rcon.sendCommand("/save-all");
                Thread.sleep(1000);
                String snapshotId = doBackup();
                System.out.println("  Backup completed, snapshot ID: " + snapshotId);

                // ── 步骤 3：修改方块 ──
                System.out.println("--- Step 3: Modify blocks post-backup ---");
                world.setBlock(GOLD_POS, "minecraft:diamond_block");
                world.setBlock(BEACON_POS, "minecraft:bookshelf");
                world.setBlock(NEW_CHEST_POS, "minecraft:chest");
                System.out.println("  Changed gold_block → diamond_block at " + GOLD_POS);
                System.out.println("  Changed beacon → bookshelf at " + BEACON_POS);
                System.out.println("  Added chest at " + NEW_CHEST_POS);

                // 验证修改已生效
                assertTrue(world.checkBlock(GOLD_POS, "minecraft:diamond_block").isEmpty()
                                || world.checkBlock(GOLD_POS, "minecraft:diamond_block").toLowerCase().contains("pass"),
                                "diamond_block should be present after modification");
                assertTrue(world.checkBlock(BEACON_POS, "minecraft:bookshelf").isEmpty()
                                || world.checkBlock(BEACON_POS, "minecraft:bookshelf").toLowerCase().contains("pass"),
                                "bookshelf should be present after modification");
                assertTrue(world.checkBlock(NEW_CHEST_POS, "minecraft:chest").isEmpty()
                                || world.checkBlock(NEW_CHEST_POS, "minecraft:chest").toLowerCase().contains("pass"),
                                "chest should be present after modification");

                // ── 步骤 4：执行 chunk [0, 0] 内存回档 ──
                System.out.println("--- Step 4: Rollback chunk [0, 0] ---");
                String rollbackResponse = rcon.sendCommand(
                                "/foldbackup rollback chunk 0 0 " + snapshotId);
                System.out.println("  Rollback response: " + rollbackResponse);

                assertTrue(rollbackResponse.contains("restored") || rollbackResponse.contains("✓"),
                                "Rollback should succeed, got: " + rollbackResponse);

                // ── 步骤 5：验证 chunk [0, 0] 已恢复 ──
                System.out.println("--- Step 5: Verify chunk [0, 0] rollback ---");
                assertTrue(world.checkBlock(GOLD_POS, "minecraft:gold_block").isEmpty()
                                || world.checkBlock(GOLD_POS, "minecraft:gold_block").toLowerCase().contains("pass"),
                                "gold_block should be restored at " + GOLD_POS);
                String chestCheck = world.checkBlock(NEW_CHEST_POS, "minecraft:air");
                assertTrue(chestCheck.isEmpty() || chestCheck.toLowerCase().contains("pass"),
                                "Position " + NEW_CHEST_POS + " was air at backup, should be air after rollback, got: "
                                                + chestCheck);
                System.out.println("  ✓ Chunk [0,0] correctly rolled back");

                // ── 步骤 6：验证 chunk [1, 0] 未被影响 ──
                System.out.println("--- Step 6: Verify chunk [1, 0] unchanged ---");
                assertTrue(world.checkBlock(BEACON_POS, "minecraft:bookshelf").isEmpty()
                                || world.checkBlock(BEACON_POS, "minecraft:bookshelf").toLowerCase().contains("pass"),
                                "bookshelf in chunk [1,0] should NOT be rolled back, got: "
                                                + world.checkBlock(BEACON_POS, "minecraft:bookshelf"));
                System.out.println("  ✓ Chunk [1,0] correctly left unchanged");

                System.out.println("=== TEST PASSED ===");
        }

        @Test
        @DisplayName("Backup -> modify chest NBT -> rollback chunk -> verify NBT restored")
        void testChestItemRollback() throws Exception {
                // Chunk [2, 0]，偏移 (8, 8) → 世界坐标 (40, 64, 8) — 与 B-1 测试隔离
                final BlockPos CHEST_POS = BlockPos.fromChunk(2, 0, 8, 64, 8);

                // ── 步骤 1：强制加载 chunk [2, 0] ──
                System.out.println("--- Step 1: Force-load chunk [2, 0] ---");
                rcon.sendCommand("/forceload add 32 0"); // block x=32 → chunk [2,0]
                System.out.println("  Chunk [2,0] force-loaded");

                // ── 步骤 2：放置箱子 ──
                System.out.println("--- Step 2: Place chest at " + CHEST_POS + " ---");
                world.setBlock(CHEST_POS, "minecraft:chest");
                assertTrue(world.checkBlock(CHEST_POS, "minecraft:chest").isEmpty()
                                || world.checkBlock(CHEST_POS, "minecraft:chest").toLowerCase().contains("pass"),
                                "chest should be placed");

                // ── 步骤 3：写入初始 NBT（钻石 × 1 + 自定义名称） ──
                System.out.println("--- Step 3: Write initial chest NBT ---");
                String initialNbt = "{Items:[{Slot:0,id:\"minecraft:diamond\",Count:1b}],"
                                + "CustomName:'{\"text\":\"备份前箱子\"}'}";
                world.mergeBlockData(CHEST_POS, initialNbt);

                // ── 步骤 4：验证初始 NBT ──
                System.out.println("--- Step 4: Verify initial NBT ---");
                String initialData = world.getBlockData(CHEST_POS);
                System.out.println("  Initial data: " + initialData);
                assertTrue(initialData.contains("diamond"),
                                "Initial NBT should contain diamond. Got: " + initialData);
                assertTrue(initialData.contains("备份前箱子"),
                                "Initial NBT should contain custom name. Got: " + initialData);

                // ── 步骤 5：执行备份 ──
                System.out.println("--- Step 5: Take backup ---");
                rcon.sendCommand("/save-all");
                Thread.sleep(1000);
                String snapshotId = doBackup();
                System.out.println("  Backup completed, snapshot ID: " + snapshotId);

                // ── 步骤 6：修改 NBT（绿宝石 × 5 + 不同名称） ──
                System.out.println("--- Step 6: Modify chest NBT ---");
                String modifiedNbt = "{Items:[{Slot:0,id:\"minecraft:emerald\",Count:5b}],"
                                + "CustomName:'{\"text\":\"修改后箱子\"}'}";
                world.mergeBlockData(CHEST_POS, modifiedNbt);

                // ── 步骤 7：验证修改已生效 ──
                System.out.println("--- Step 7: Verify modified NBT ---");
                String modifiedData = world.getBlockData(CHEST_POS);
                System.out.println("  Modified data: " + modifiedData);
                assertTrue(modifiedData.contains("emerald"),
                                "Modified NBT should contain emerald. Got: " + modifiedData);
                assertFalse(modifiedData.contains("diamond"),
                                "Modified NBT should NOT contain diamond. Got: " + modifiedData);

                // ── 步骤 8：回档 chunk [2, 0] ──
                System.out.println("--- Step 8: Rollback chunk [2, 0] ---");
                String rollbackResponse = rcon.sendCommand(
                                "/foldbackup rollback chunk 2 0 " + snapshotId);
                System.out.println("  Rollback response: " + rollbackResponse);
                assertTrue(rollbackResponse.contains("restored") || rollbackResponse.contains("✓"),
                                "Rollback should succeed, got: " + rollbackResponse);

                // ── 步骤 9：验证箱子方块类型保留 ──
                System.out.println("--- Step 9: Verify chest block type ---");
                assertTrue(world.checkBlock(CHEST_POS, "minecraft:chest").isEmpty()
                                || world.checkBlock(CHEST_POS, "minecraft:chest").toLowerCase().contains("pass"),
                                "Chest should remain as chest after rollback");
                System.out.println("  ✓ Chest block type preserved");

                // ── 步骤 10：验证箱子物品已还原（钻石恢复，绿宝石清除） ──
                System.out.println("--- Step 10: Verify chest items ---");
                String restoredData = world.getBlockData(CHEST_POS);
                System.out.println("  Restored data: " + restoredData);
                assertTrue(restoredData.contains("diamond"),
                                "Restored NBT should contain diamond. Got: " + restoredData);
                assertFalse(restoredData.contains("emerald"),
                                "Restored NBT should NOT contain emerald. Got: " + restoredData);
                System.out.println("  ✓ Chest items correctly restored");

                // ── 步骤 11：验证自定义名称已还原 ──
                System.out.println("--- Step 11: Verify custom name ---");
                assertTrue(restoredData.contains("备份前箱子"),
                                "Restored NBT should contain original custom name. Got: " + restoredData);
                System.out.println("  ✓ Custom name correctly restored");

                System.out.println("=== TEST PASSED ===");
        }

        @Test
        @DisplayName("Backup -> place blocks in all-air section -> rollback -> clear and place again")
        void testAirSectionRollback() throws Exception {
                // 使用 chunk [1, 0] 的高 Y 位置（y=200），该高度无自然地形。
                // 备份时该 section 为全空气 → NBT 省略 → sourceSections[i] == null。
                // 修复前：continue 跳过 → 回档后钻石块残留。
                // 修复后：创建全空气 section → 钻石块被清除。
                //
                // 注意：/forceload 的参数是方块坐标（column_pos），不是区块坐标。
                //   forceload add 1 0 只会加载 block (1,0) 所在的 chunk [0,0]。
                // 要加载 chunk [1,0] 需使用 block x=16（chunk 索引 = block >> 4）。
                // 主世界出生点附近的区块会被 spawn chunks 自动加载，但显式
                // forceload 能让本测试不依赖出生点，行为更确定。
                final BlockPos HIGH_POS = BlockPos.fromChunk(1, 0, 8, 200, 8);

                // ── 步骤 1：强制加载目标区块（与场景 B 相同的模式） ──
                System.out.println("--- Step 1: Force-load chunk [1, 0] ---");
                rcon.sendCommand("/forceload add 0 0");
                rcon.sendCommand("/forceload add 16 0");
                Thread.sleep(500);
                System.out.println("  Chunk [1,0] force-loaded");

                // ── 步骤 2：执行备份 ──
                // 此时高 Y section 未分配 → 全空气 → NBT 省略
                System.out.println("--- Step 2: Take backup (high-Y sections are all-air) ---");
                rcon.sendCommand("/save-all");
                Thread.sleep(1000);
                String snapshotId = doBackup();
                System.out.println("  Backup completed, snapshot ID: " + snapshotId);

                // ── 步骤 3：在高 Y 位置放置方块，创建新 section ──
                System.out.println("--- Step 3: Place block at high Y " + HIGH_POS + " ---");
                world.setBlock(HIGH_POS, "minecraft:diamond_block");
                assertTrue(world.checkBlock(HIGH_POS, "minecraft:diamond_block").isEmpty()
                                || world.checkBlock(HIGH_POS, "minecraft:diamond_block").toLowerCase().contains("pass"),
                                "diamond_block should be placed at high Y");

                // ── 步骤 4：回档 chunk [1, 0] ──
                System.out.println("--- Step 4: Rollback chunk [1, 0] ---");
                String rollbackResponse = rcon.sendCommand(
                                "/foldbackup rollback chunk 1 0 " + snapshotId);
                System.out.println("  Rollback response: " + rollbackResponse);
                assertTrue(rollbackResponse.contains("restored") || rollbackResponse.contains("✓"),
                                "Rollback should succeed, got: " + rollbackResponse);

                // ── 步骤 5：验证高 Y 方块已被清除（恢复为空气） ──
                System.out.println("--- Step 5: Verify high-Y block cleared ---");
                String airCheck = world.checkBlock(HIGH_POS, "minecraft:air");
                assertTrue(airCheck.isEmpty() || airCheck.toLowerCase().contains("pass"),
                                "High-Y position should be air after rollback (section was all-air at backup). Got: "
                                                + airCheck);
                System.out.println("  ✓ Block at high Y correctly cleared by rollback");

                // 回档后继续编辑：覆盖 Y=64 边界两侧及高空的空 section。
                // RCON 验证服务端写入，客户端玩家交互仍需在实际整合包中复测。
                for (int y : new int[] {63, 64, 65, 80, 200}) {
                        BlockPos pos = BlockPos.fromChunk(1, 0, 8, y, 8);
                        world.setBlock(pos, "minecraft:air");
                        world.setBlock(pos, "minecraft:emerald_block");
                        String placed = world.checkBlock(pos, "minecraft:emerald_block");
                        assertTrue(placed.toLowerCase(java.util.Locale.ROOT).contains("passed"),
                                        "Block must be writable after rollback at Y=" + y + ". Got: " + placed);
                }

                System.out.println("=== TEST PASSED ===");
        }
}
