package net.blay09.mods.spookydoors.mixin.client;

import net.blay09.mods.spookydoors.client.SpookyDoorClientTracking;
import net.blay09.mods.spookydoors.core.SpookyDoorProvider;
import net.blay09.mods.spookydoors.util.SpookyDoorUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Shadow
    private ClientLevel level;

    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void handleLevelChunkWithLight(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        final var chunk = level.getChunk(packet.x(), packet.z());
        SpookyDoorClientTracking.get(level).trackDoorsInChunk(chunk);
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("TAIL"))
    private void handleForgetLevelChunk(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        SpookyDoorClientTracking.get(level).untrackDoorsInChunk(packet.pos().x(), packet.pos().z());
    }

    @Inject(method = "handleBlockUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
    private void beforeHandleBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if (!SpookyDoorUtils.isSupportedDoor(packet.getBlockState())) {
            final var previousState = level.getBlockState(packet.getPos());
            if (SpookyDoorUtils.isSupportedDoor(previousState)) {
                SpookyDoorProvider.get(level).remove(packet.getPos(), previousState);
            }
        }
    }

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void afterHandleBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        final var state = packet.getBlockState();
        if (SpookyDoorUtils.isSupportedDoor(state)) {
            SpookyDoorProvider.get(level).of(packet.getPos(), state);
        }
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
    private void beforeHandleChunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        packet.runUpdates((pos, state) -> {
            if (!SpookyDoorUtils.isSupportedDoor(state)) {
                final var previousState = level.getBlockState(pos);
                if (SpookyDoorUtils.isSupportedDoor(previousState)) {
                    SpookyDoorProvider.get(level).remove(pos, previousState);
                }
            }
        });
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void afterHandleChunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        packet.runUpdates((pos, state) -> {
            if (SpookyDoorUtils.isSupportedDoor(state)) {
                SpookyDoorProvider.get(level).of(pos, state);
            }
        });
    }
}
