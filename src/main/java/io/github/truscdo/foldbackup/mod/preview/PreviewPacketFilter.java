package io.github.truscdo.foldbackup.mod.preview;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;

/** 单个玩家的区块视图过滤器。包处理及安装、移除均在 channel event loop 上顺序执行。 */
final class PreviewPacketFilter extends ChannelOutboundHandlerAdapter {
    private final ChunkPos target;
    private final Channel channel;
    // 网络线程写，服务器线程读取；客户端卸载/重建世界后不再恢复旧视图。
    private volatile boolean invalidated;

    PreviewPacketFilter(Channel channel, ChunkPos target) {
        this.channel = channel;
        this.target = target;
        channel.eventLoop().execute(() -> channel.pipeline().addLast(this));
    }

    boolean isInvalidated() { return invalidated; }

    /** 从当前 handler 的上下文发出，绕过自身过滤，无需记录每个预览包。 */
    void send(Packet<?> packet) {
        channel.eventLoop().execute(() -> {
            var context = channel.pipeline().context(this);
            if (!invalidated && context != null) context.writeAndFlush(packet);
        });
    }

    /** 移除和恢复在同一队列任务中执行；已卸载的客户端视图不再恢复。 */
    void close(Packet<?> restoration) {
        channel.eventLoop().execute(() -> {
            // 断开连接会由 Netty 自动拆除 pipeline。
            if (channel.pipeline().context(this) == null) return;
            channel.pipeline().remove(this);
            if (!invalidated && restoration != null) channel.writeAndFlush(restoration);
        });
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
        if (message instanceof Packet<?> packet) {
            Packet<?> filtered = filter(packet);
            if (filtered == null) {
                promise.setSuccess();
                return;
            }
            message = filtered;
        }
        super.write(ctx, message, promise);
    }

    private Packet<?> filter(Packet<?> packet) {
        if (invalidated) return packet;
        // 放在 pipeline 尾部，也兼容本地连接；bundle 显式过滤，保持其余包的顺序。
        if (packet instanceof ClientboundBundlePacket bundle) {
            var remaining = new ArrayList<Packet<? super ClientGamePacketListener>>();
            for (var child : bundle.subPackets()) {
                @SuppressWarnings("unchecked")
                var filtered = (Packet<? super ClientGamePacketListener>) filter(child);
                if (filtered != null) remaining.add(filtered);
            }
            return remaining.isEmpty() ? null : new ClientboundBundlePacket(remaining);
        }
        if (packet instanceof ClientboundRespawnPacket
                || packet instanceof ClientboundForgetLevelChunkPacket forget && forget.pos().equals(target)) {
            invalidated = true;
            return packet;
        }
        if (packet instanceof ClientboundBlockUpdatePacket update && contains(update.getPos())) return null;
        if (packet instanceof ClientboundSectionBlocksUpdatePacket update
                && update.sectionPos.x() == target.x && update.sectionPos.z() == target.z) return null;
        if (packet instanceof ClientboundBlockEntityDataPacket update && contains(update.getPos())) return null;
        if (packet instanceof ClientboundBlockEventPacket event && contains(event.getPos())) return null;
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk
                && chunk.getX() == target.x && chunk.getZ() == target.z) return null;
        // 爆炸包同时携带方块破坏和玩家击退，不能整包丢弃或修改共享包。
        if (packet instanceof ClientboundExplodePacket explosion) {
            var outside = explosion.getToBlow().stream().filter(pos -> !contains(pos)).toList();
            if (outside.size() != explosion.getToBlow().size())
                return new ClientboundExplodePacket(explosion.getX(), explosion.getY(), explosion.getZ(),
                        explosion.getPower(), outside,
                        new Vec3(explosion.getKnockbackX(), explosion.getKnockbackY(), explosion.getKnockbackZ()),
                        explosion.getBlockInteraction(), explosion.getSmallExplosionParticles(),
                        explosion.getLargeExplosionParticles(), explosion.getExplosionSound());
        }
        return packet;
    }

    private boolean contains(BlockPos pos) {
        return (pos.getX() >> 4) == target.x && (pos.getZ() >> 4) == target.z;
    }
}
