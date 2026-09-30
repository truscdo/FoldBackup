package io.github.truscdo.foldbackup.core.format;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Binary NBT tree codec shared by entity-region operations. */
final class NbtCodec {
    private NbtCodec() {}

    record Tag(int type, Object value) {}
    record TagList(int type, List<Tag> tags) {}

    static Tag parse(byte[] bytes) throws IOException {
        return parse(bytes, "Entity NBT must be a compound");
    }

    static Tag parse(byte[] bytes, String invalidRootMessage) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readUnsignedByte() != 10) throw new IOException(invalidRootMessage);
        in.readUTF();
        Tag root = readTag(in, 10);
        if (in.available() != 0) throw new IOException("Trailing entity NBT bytes");
        return root;
    }

    static byte[] write(Tag tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeByte(10);
        out.writeUTF("");
        writeTag(out, tag);
        return bytes.toByteArray();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Tag> compound(Tag tag) throws IOException {
        if (tag.type != 10) throw new IOException("Expected NBT compound");
        return (Map<String, Tag>) tag.value;
    }

    static List<Tag> compounds(Tag tag, String name) throws IOException {
        if (tag == null) return List.of();
        if (tag.type != 9) throw new IOException(name + " must be a list");
        TagList list = (TagList) tag.value;
        if (list.type != 10 && !(list.type == 0 && list.tags.isEmpty()))
            throw new IOException(name + " must contain compounds");
        return list.tags;
    }

    private static int length(DataInputStream in, int width) throws IOException {
        int n = in.readInt();
        if (n < 0 || (long) n * width > in.available()) throw new IOException("Invalid NBT length " + n);
        return n;
    }

    private static Tag readTag(DataInputStream in, int type) throws IOException {
        Object value = switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                byte[] array = new byte[length(in, 1)];
                in.readFully(array);
                yield array;
            }
            case 8 -> in.readUTF();
            case 9 -> {
                int elementType = in.readUnsignedByte();
                int count = length(in, 1);
                if (elementType > 12 || (elementType == 0 && count != 0))
                    throw new IOException("Invalid NBT list type");
                List<Tag> tags = new ArrayList<>();
                for (int i = 0; i < count; i++) tags.add(readTag(in, elementType));
                yield new TagList(elementType, tags);
            }
            case 10 -> {
                Map<String, Tag> tags = new LinkedHashMap<>();
                int childType;
                while ((childType = in.readUnsignedByte()) != 0) {
                    String name = in.readUTF();
                    if (tags.put(name, readTag(in, childType)) != null)
                        throw new IOException("Duplicate NBT key " + name);
                }
                yield tags;
            }
            case 11 -> {
                int[] array = new int[length(in, 4)];
                for (int i = 0; i < array.length; i++) array[i] = in.readInt();
                yield array;
            }
            case 12 -> {
                long[] array = new long[length(in, 8)];
                for (int i = 0; i < array.length; i++) array[i] = in.readLong();
                yield array;
            }
            default -> throw new IOException("Unknown NBT type " + type);
        };
        return new Tag(type, value);
    }

    private static void writeTag(DataOutputStream out, Tag tag) throws IOException {
        switch (tag.type) {
            case 1 -> out.writeByte((byte) tag.value);
            case 2 -> out.writeShort((short) tag.value);
            case 3 -> out.writeInt((int) tag.value);
            case 4 -> out.writeLong((long) tag.value);
            case 5 -> out.writeFloat((float) tag.value);
            case 6 -> out.writeDouble((double) tag.value);
            case 7 -> {
                byte[] array = (byte[]) tag.value;
                out.writeInt(array.length);
                out.write(array);
            }
            case 8 -> out.writeUTF((String) tag.value);
            case 9 -> {
                TagList list = (TagList) tag.value;
                out.writeByte(list.type);
                out.writeInt(list.tags.size());
                for (Tag child : list.tags) writeTag(out, child);
            }
            case 10 -> {
                for (var entry : compound(tag).entrySet()) {
                    out.writeByte(entry.getValue().type);
                    out.writeUTF(entry.getKey());
                    writeTag(out, entry.getValue());
                }
                out.writeByte(0);
            }
            case 11 -> {
                int[] array = (int[]) tag.value;
                out.writeInt(array.length);
                for (int value : array) out.writeInt(value);
            }
            case 12 -> {
                long[] array = (long[]) tag.value;
                out.writeInt(array.length);
                for (long value : array) out.writeLong(value);
            }
            default -> throw new IOException("Unknown NBT type " + tag.type);
        }
    }
}
