package io.github.truscdo.foldbackup.mod.preview;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.shorts.ShortSets;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

class PreviewPacketFilterTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ClientboundBlockUpdatePacket update(int x, int z) {
        return new ClientboundBlockUpdatePacket(new BlockPos(x, 64, z), Blocks.STONE.defaultBlockState());
    }

    // Empty chunk payload: exercise the real packet codec without constructing a server world.
    private static ClientboundLevelChunkWithLightPacket chunk(int x, int z) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            buffer.writeInt(x);
            buffer.writeInt(z);
            buffer.writeNbt(new CompoundTag());
            buffer.writeVarInt(0); // section bytes
            buffer.writeVarInt(0); // block entities
            for (int i = 0; i < 4; i++) buffer.writeVarInt(0); // light masks
            buffer.writeVarInt(0); // sky light arrays
            buffer.writeVarInt(0); // block light arrays
            return ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buffer);
        } finally { buffer.release(); }
    }

    @Test void filtersFullChunksSectionsAndBlockEvents() {
        var channel = new EmbeddedChannel();
        new PreviewPacketFilter(channel, new ChunkPos(0, 0));
        channel.runPendingTasks();
        try {
            assertFalse(channel.writeOutbound(chunk(0, 0)));
            var neighbor = chunk(1, 0);
            assertTrue(channel.writeOutbound(neighbor));
            assertSame(neighbor, channel.readOutbound());
            assertFalse(channel.writeOutbound(new ClientboundSectionBlocksUpdatePacket(
                    SectionPos.of(0, 4, 0), ShortSets.EMPTY_SET, null)));
            assertFalse(channel.writeOutbound(new ClientboundBlockEventPacket(BlockPos.ZERO, Blocks.CHEST, 1, 1)));
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void respawnImmediatelyAllowsSameCoordinatesInNewWorld() {
        var channel = new EmbeddedChannel();
        var filter = new PreviewPacketFilter(channel, new ChunkPos(0, 0));
        channel.runPendingTasks();
        try {
            // The filter examines packet type only; no spawn data is serialized by EmbeddedChannel.
            var respawn = new ClientboundRespawnPacket(null, (byte) 0);
            assertTrue(channel.writeOutbound(respawn));
            assertSame(respawn, channel.readOutbound());
            var newWorld = chunk(0, 0);
            assertTrue(channel.writeOutbound(newWorld));
            assertSame(newWorld, channel.readOutbound());
            assertTrue(filter.isInvalidated());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void explosionPreservesKnockbackAndOutsideBlocksWithoutMutatingSharedPacket() {
        var channel = new EmbeddedChannel();
        new PreviewPacketFilter(channel, new ChunkPos(0, 0));
        channel.runPendingTasks();
        try {
            var outside = new BlockPos(16, 64, 0);
            var affected = List.of(new BlockPos(15, 64, 0), outside);
            var packet = new ClientboundExplodePacket(15, 64, 0, 4, affected, new Vec3(1, 2, 3),
                    Explosion.BlockInteraction.DESTROY, ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER,
                    Holder.direct(SoundEvents.GENERIC_EXPLODE.value()));
            channel.writeOutbound(packet);
            ClientboundExplodePacket filtered = channel.readOutbound();
            assertEquals(List.of(outside), filtered.getToBlow());
            assertEquals(affected, packet.getToBlow());
            assertEquals(1, filtered.getKnockbackX());
            assertEquals(2, filtered.getKnockbackY());
            assertEquals(3, filtered.getKnockbackZ());
            assertSame(packet.getExplosionSound(), filtered.getExplosionSound());
            assertSame(packet.getSmallExplosionParticles(), filtered.getSmallExplosionParticles());
            assertSame(packet.getLargeExplosionParticles(), filtered.getLargeExplosionParticles());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void filtersOnlyTargetChunkAndAllowsHistoricalPackets() {
        var channel = new EmbeddedChannel();
        var filter = new PreviewPacketFilter(channel, new ChunkPos(-1, 0));
        channel.runPendingTasks();
        try {
            assertFalse(channel.writeOutbound(update(-1, 0)));
            var neighbor = update(0, 0);
            assertTrue(channel.writeOutbound(neighbor));
            assertSame(neighbor, channel.readOutbound());
            var historical = update(-1, 0);
            filter.send(historical);
            channel.runPendingTasks();
            assertSame(historical, channel.readOutbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void filtersBundleContentsWithoutDroppingNeighborUpdates() {
        var channel = new EmbeddedChannel();
        new PreviewPacketFilter(channel, new ChunkPos(0, 0));
        channel.runPendingTasks();
        try {
            var neighbor = update(16, 0);
            channel.writeOutbound(new ClientboundBundlePacket(List.of(update(0, 0), neighbor)));
            ClientboundBundlePacket bundle = channel.readOutbound();
            assertEquals(List.of(neighbor), StreamSupport.stream(bundle.subPackets().spliterator(), false).toList());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void forgetDisablesFilterBeforeLaterPacketsAndSuppressesRestoration() {
        var channel = new EmbeddedChannel();
        var filter = new PreviewPacketFilter(channel, new ChunkPos(0, 0));
        channel.runPendingTasks();
        try {
            var forget = new ClientboundForgetLevelChunkPacket(new ChunkPos(0, 0));
            var subsequent = update(0, 0);
            channel.writeOutbound(new ClientboundBundlePacket(List.of(forget, subsequent)));
            ClientboundBundlePacket bundle = channel.readOutbound();
            assertEquals(List.of(forget, subsequent), StreamSupport.stream(bundle.subPackets().spliterator(), false).toList());
            assertTrue(filter.isInvalidated());
            filter.send(update(0, 0));
            filter.close(update(0, 0));
            channel.runPendingTasks();
            assertNull(channel.readOutbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void exitRestoresAfterHistoricalPacketsAndReleasesLiveUpdates() {
        var channel = new EmbeddedChannel();
        var filter = new PreviewPacketFilter(channel, new ChunkPos(0, 0));
        channel.runPendingTasks();
        try {
            var historical = update(0, 0);
            var current = update(0, 0);
            filter.send(historical);
            filter.close(current);
            channel.runPendingTasks();
            assertSame(historical, channel.readOutbound());
            assertSame(current, channel.readOutbound());
            var later = update(0, 0);
            assertTrue(channel.writeOutbound(later));
            assertSame(later, channel.readOutbound());
        } finally { channel.finishAndReleaseAll(); }
    }
}
