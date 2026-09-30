package io.github.truscdo.foldbackup.mod;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/** 读取从快照区域数据块中提取的未压缩 NBT。 */
public final class SnapshotNbt {
    private SnapshotNbt() {}

    public static CompoundTag read(byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return NbtIo.read(in);
        }
    }
}
