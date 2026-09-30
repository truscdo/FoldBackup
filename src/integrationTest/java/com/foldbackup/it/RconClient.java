package com.foldbackup.it;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 轻量级 Minecraft RCON 客户端。
 *
 * <p>
 * 协议已对照 Minecraft 服务器字节码验证：
 * 整数为小端序，通过
 * {@code DataOutputStream.writeInt(Integer.reverseBytes(v))} 写入。
 */
public class RconClient implements Closeable {

    private static final int TYPE_LOGIN = 3;
    private static final int TYPE_COMMAND = 2;

    private final String host;
    private final int port;
    private final String password;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private int requestId;

    public RconClient(String host, int port, String password) {
        this.host = host;
        this.port = port;
        this.password = password;
    }

    public void connect() throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10_000);
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());

        int loginId = ++requestId;
        writePacket(loginId, TYPE_LOGIN, password);
        RconPacket response = readPacket();
        if (response.id == -1 || response.id != loginId) {
            throw new IOException("RCON auth failed");
        }
    }

    public String sendCommand(String command) throws IOException {
        int cmdId = ++requestId;
        writePacket(cmdId, TYPE_COMMAND, command);
        RconPacket response = readPacket();
        if (response.id != cmdId) throw new IOException("Unexpected RCON response id " + response.id + ", expected " + cmdId);
        StringBuilder result = new StringBuilder(response.payload);
        if (command.equals("/stop") || command.equals("stop")) return result.toString().trim();

        // Large snapshot lists span multiple responses. After receiving the first response,
        // send an unknown packet type as a harmless delimiter. Sending both requests up front
        // is unsafe: Minecraft's RCON reader assumes a single request per socket read.
        int endId = ++requestId;
        writePacket(endId, 0, "");
        while (true) {
            RconPacket next = readPacket();
            if (next.id == endId) break;
            if (next.id != cmdId) throw new IOException("Unexpected RCON response id " + next.id);
            result.append(next.payload);
        }
        return result.toString().trim();
    }

    @Override
    public void close() throws IOException {
        if (socket != null)
            socket.close();
    }

    private void writePacket(int id, int type, String payload) throws IOException {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        // 长度包含长度字段之后的所有内容：id(4) + type(4) + payload + null(1) + padding(1)
        int dataLen = payloadBytes.length + 10;
        // 将整个数据包构建为单个字节数组，确保 TCP 发送的原子性
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(dataLen + 4);
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        dos.writeInt(Integer.reverseBytes(dataLen));
        dos.writeInt(Integer.reverseBytes(id));
        dos.writeInt(Integer.reverseBytes(type));
        dos.write(payloadBytes);
        dos.writeShort(0);
        dos.flush();
        // 一次性写入 socket 输出流
        out.write(baos.toByteArray());
        out.flush();
    }

    private RconPacket readPacket() throws IOException {
        int length = Integer.reverseBytes(in.readInt());
        if (length < 10 || length > 1024 * 1024)
            throw new IOException("Invalid RCON packet length: " + length);
        int id = Integer.reverseBytes(in.readInt());
        int type = Integer.reverseBytes(in.readInt());
        int payloadLen = length - 8;
        if (payloadLen > 0) {
            byte[] payloadBytes = new byte[payloadLen];
            in.readFully(payloadBytes);
            int realLen = payloadLen;
            while (realLen > 0 && payloadBytes[realLen - 1] == 0)
                realLen--;
            return new RconPacket(id, type, new String(payloadBytes, 0, realLen, StandardCharsets.UTF_8));
        }
        return new RconPacket(id, type, "");
    }

    private record RconPacket(int id, int type, String payload) {
    }
}
