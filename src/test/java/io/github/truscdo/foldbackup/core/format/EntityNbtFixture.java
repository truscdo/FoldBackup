package io.github.truscdo.foldbackup.core.format;

import java.io.*;
import java.util.UUID;

/** Writes actual binary NBT without putting Minecraft on the unit-test runtime classpath. */
public final class EntityNbtFixture {
    private EntityNbtFixture() {}
    public static byte[] entity(UUID id, double x, double z) throws IOException {
        return entity(id, x, z, "minecraft:armor_stand", 11, "", null);
    }
    public static byte[] entity(UUID id, double x, double z, String type, int uuidType, String omit, byte[] passenger) throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        if (!omit.equals("id")) { named(out, 8, "id"); out.writeUTF(type); }
        if (!omit.equals("UUID")) {
            if (uuidType == 4) {
                named(out, 4, "UUIDMost"); out.writeLong(id.getMostSignificantBits());
                named(out, 4, "UUIDLeast"); out.writeLong(id.getLeastSignificantBits());
            } else {
                named(out, uuidType, "UUID");
                if (uuidType == 8) out.writeUTF(id.toString());
                else { out.writeInt(4); out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
            }
        }
        if (!omit.equals("Pos")) { named(out, 9, "Pos"); out.writeByte(6); out.writeInt(3); out.writeDouble(x); out.writeDouble(64); out.writeDouble(z); }
        if (!omit.equals("Rotation")) { named(out, 9, "Rotation"); out.writeByte(5); out.writeInt(2); out.writeFloat(45); out.writeFloat(10); }
        named(out, 8, "CustomName"); out.writeUTF("{\"text\":\"fixture\"}");
        named(out, 12, "mod:opaque"); out.writeInt(2); out.writeLong(123); out.writeLong(-456);
        if (passenger != null) { named(out, 9, "Passengers"); out.writeByte(10); out.writeInt(1); out.write(passenger); }
        out.writeByte(0); return bytes.toByteArray();
    }
    public static byte[] chunk(int x, int z, byte[]... entities) throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        named(out, 10, ""); named(out, 11, "Position"); out.writeInt(2); out.writeInt(x); out.writeInt(z);
        named(out, 3, "DataVersion"); out.writeInt(3955);
        if (entities != null) {
            named(out, 9, "Entities"); out.writeByte(10); out.writeInt(entities.length);
            for (byte[] entity : entities) out.write(entity);
        }
        out.writeByte(0); return bytes.toByteArray();
    }
    private static void named(DataOutputStream out, int type, String name) throws IOException { out.writeByte(type); out.writeUTF(name); }
}
