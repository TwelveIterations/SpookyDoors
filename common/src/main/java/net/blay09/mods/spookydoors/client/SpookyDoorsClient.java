package net.blay09.mods.spookydoors.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import net.blay09.mods.balm.client.BalmClientRegistrars;
import net.blay09.mods.balm.client.platform.event.callback.ClientTickCallback;
import net.blay09.mods.balm.client.platform.event.callback.RenderCallback;
import net.blay09.mods.balm.platform.event.callback.LevelCallback;
import net.blay09.mods.spookydoors.SpookyDoors;
import net.blay09.mods.spookydoors.SpookyDoorsConfig;
import net.blay09.mods.spookydoors.client.render.SpookyDoorRenderer;
import net.blay09.mods.spookydoors.core.ClientSpookyDoor;
import net.blay09.mods.spookydoors.core.SpookyDoor;
import net.blay09.mods.spookydoors.core.SpookyDoorProvider;
import net.blay09.mods.spookydoors.item.ModItemTags;
import net.blay09.mods.spookydoors.util.SpookyDoorUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class SpookyDoorsClient {

    private static final Identifier UI_HINT_TEXTURE = SpookyDoors.id("textures/gui/door_ui_hint.png");
    private static final int UI_HINT_TICKS = 20;
    private static final float CAMERA_DRAG_FACTOR = 0.2f;

    private static final int SYNC_INTERVAL = 1;

    private static double lastMouseX;
    private static boolean isDragging;
    private static int rightClickDelayAfterDragging;
    private static float accumulatedOpennessChange;
    private static SpookyDoor activeDoor;
    private static Player draggingPlayer;
    private static int ticksSinceLastSync;
    private static boolean isDirty;

    private static int uiHintTicksLeft = 0;

    public static void initialize(BalmClientRegistrars registrars) {
        RenderCallback.Gui.AFTER.register(SpookyDoorsClient::onDrawGui);
        RenderCallback.BlockHighlight.EVENT.register(SpookyDoorsClient::onDrawHighlight);
        LevelCallback.Chunk.UNLOAD.register(SpookyDoorsClient::onChunkUnload);
        ClientTickCallback.AFTER.register(SpookyDoorsClient::onClientTick);
    }

    private static void onChunkUnload(LevelAccessor levelAccessor, ChunkAccess chunk, ChunkPos chunkPos) {
        if (levelAccessor instanceof Level level && level.isClientSide()) {
            SpookyDoorClientTracking.get(level).untrackDoorsInChunk(chunkPos.x(), chunkPos.z());
        }
    }

    public static void setActiveDoorAndDirty(Level level, BlockPos pos) {
        SpookyDoorsClient.activeDoor = SpookyDoorProvider.get(level).at(pos);
        SpookyDoorsClient.isDirty = true;
    }

    public static boolean onMoveMouse(long windowPointer, double x, double y) {
        if (activeDoor != null && isDragging) {
            final var state = activeDoor.state();
            if (!state.hasProperty(DoorBlock.FACING) || !state.hasProperty(DoorBlock.HINGE)) {
                return false;
            }

            final var facing = state.getValue(DoorBlock.FACING);
            final var hinge = state.getValue(DoorBlock.HINGE);
            final var rawDeltaX = x - lastMouseX;
            double deltaX = rawDeltaX;
            final var player = Minecraft.getInstance().player;
            if (player == null) {
                return false;
            }

            final var doorPos = activeDoor.pos();

            final double relativeX = player.getX() - doorPos.getX();
            final double relativeZ = player.getZ() - doorPos.getZ();

            boolean isPlayerBehind = switch (facing) {
                case NORTH -> relativeZ < 0;
                case SOUTH -> relativeZ > 0;
                case WEST -> relativeX < 0;
                case EAST -> relativeX > 0;
                default -> false;
            };

            if (isPlayerBehind) {
                deltaX = -deltaX;
            }

            deltaX = hinge == DoorHingeSide.LEFT ? -deltaX : deltaX;

            final double sensitivity = 0.005;
            final var currentOpenness = activeDoor.percentOpen();
            final var openness = Mth.clamp(currentOpenness + (float) (deltaX * sensitivity), 0f, 1f);
            final var opennessDelta = openness - currentOpenness;
            accumulatedOpennessChange += Math.abs(opennessDelta);
            activeDoor.operate(player, openness);
            if (opennessDelta != 0f) {
                if (SpookyDoorsConfig.getActive().moveCameraWithDoor) {
                    player.turn(rawDeltaX * CAMERA_DRAG_FACTOR, 0);
                }
                isDirty = true;
            }
            lastMouseX = x;

            return true;
        }
        return false;
    }

    private static void onDrawGui(GuiGraphicsExtractor guiGraphics, Window window) {
        if (uiHintTicksLeft > 0) {
            final var poseStack = guiGraphics.pose();
            poseStack.pushMatrix();
            final var screenCenterX = window.getGuiScaledWidth() / 2;
            final var screenCenterY = window.getGuiScaledHeight() / 2;
            poseStack.translate(screenCenterX, screenCenterY);
            poseStack.scale(0.4f, 0.4f);
            final var alpha = Math.round(uiHintTicksLeft / (float) UI_HINT_TICKS * 255f) << 24;
            guiGraphics.blit(RenderPipelines.GUI_TEXTURED, UI_HINT_TEXTURE, -23, -16 - 38, 0, 0, 46, 32, 46, 32, alpha | 0xFFFFFF);
            poseStack.popMatrix();
        }
    }

    private static boolean onDrawHighlight(BlockHitResult hitResult, PoseStack poseStack, MultiBufferSource multiBufferSource, Camera camera, int color, float lineWidth) {
        final var level = Minecraft.getInstance().level;
        if (level == null) {
            return true;
        }

        final var pos = hitResult.getBlockPos();
        final var state = level.getBlockState(pos);
        if (!state.hasProperty(DoorBlock.FACING) || !state.hasProperty(DoorBlock.HINGE)) {
            return true;
        }

        if (SpookyDoorUtils.isSupportedDoor(state)) {
            if (level.getWorldBorder().isWithinBounds(pos)) {
                final var cameraVec = camera.position();
                final var cameraX = cameraVec.x();
                final var cameraY = cameraVec.y();
                final var cameraZ = cameraVec.z();
                final var vertexConsumer = multiBufferSource.getBuffer(RenderTypes.lines());
                poseStack.pushPose();
                poseStack.translate(pos.getX() - cameraX,
                        pos.getY() - cameraY,
                        pos.getZ() - cameraZ);
                SpookyDoorRenderer.applyDoorPose(
                        poseStack,
                        SpookyDoorProvider.get(level).at(pos).percentOpen(),
                        state.getValue(DoorBlock.FACING),
                        state.getValue(DoorBlock.HINGE));
                final var shape = SpookyDoorRenderer.getOutlineShape(state);
                ShapeRenderer.renderShape(poseStack, vertexConsumer, shape, 0, 0, 0, 0x66000000, lineWidth);
                poseStack.popPose();
            }
            return false;
        }

        return true;
    }

    private static void onClientTick(Minecraft client) {
        if (isDragging && (client.player == null || client.player != draggingPlayer || !client.player.isAlive())) {
            cancelDragging();
        }

        ticksSinceLastSync++;
        if (ticksSinceLastSync >= SYNC_INTERVAL) {
            syncActiveDoorIfDirty();
            ticksSinceLastSync = 0;
        }

        if (!isDragging && activeDoor != null) {
            final var player = client.player;
            // entityInside tracks the door as active so we send sync updates
            // so we only reset it if we're not dragging AND not inside the door's position
            if (player == null
                    || activeDoor.pos().getX() != player.getBlockX()
                    || activeDoor.pos().getZ() != player.getBlockZ()) {
                syncActiveDoorIfDirty();
                activeDoor = null;
            }
        }
        if (uiHintTicksLeft > 0) {
            uiHintTicksLeft--;
        }
        if (rightClickDelayAfterDragging > 0) {
            rightClickDelayAfterDragging--;
        }
    }

    private static void syncActiveDoorIfDirty() {
        if (activeDoor instanceof ClientSpookyDoor clientSpookyDoor && isDirty) {
            clientSpookyDoor.syncToServer();
            isDirty = false;
        }
    }

    public static boolean captureUseKey() {
        if (isDragging) {
            rightClickDelayAfterDragging = 4;
        }
        // Prevent use key from interacting with the door while dragging
        //noinspection StatementWithEmptyBody
        while ((isDragging || rightClickDelayAfterDragging > 0) && Minecraft.getInstance().options.keyUse.consumeClick()) {
        }
        // We also set a rightClickDelay as part of the Mixin, so return true if dragging
        return isDragging;
    }

    public static boolean onKeyPress(int action, KeyEvent event) {
        return Minecraft.getInstance().options.keyUse.matches(event) && handleUseInput(action);
    }

    public static boolean onMouseInput(int button, int action) {
        final Minecraft minecraft = Minecraft.getInstance();
        return minecraft.options.keyUse.matchesMouse(new MouseButtonEvent(minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos(), new MouseButtonInfo(button, 0))) && handleUseInput(action);
    }

    public static boolean handleUseInput(int action) {
        final var minecraft = Minecraft.getInstance();
        if (action == InputConstants.PRESS) {
            final var hitResult = minecraft.hitResult;
            if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
                final var blockHitResult = ((BlockHitResult) hitResult);
                final var pos = blockHitResult.getBlockPos();
                final var level = minecraft.level;
                if (level != null) {
                    final var state = level.getBlockState(pos);
                    if (SpookyDoorUtils.canOperate(state)) {
                        final var entity = minecraft.getCameraEntity();
                        if (entity != null) {
                            final var door = SpookyDoorProvider.get(level).of(pos, state);
                            if (door.spooky()) {
                                if (shouldBypassDragging(minecraft, door)) {
                                    return false;
                                }

                                lastMouseX = minecraft.mouseHandler.xpos();
                                activeDoor = door;
                                if (activeDoor instanceof ClientSpookyDoor clientSpookyDoor) {
                                    clientSpookyDoor.locallyControlled(true);
                                }
                                draggingPlayer = minecraft.player;
                                isDragging = true;
                                return true;
                            }
                            return false;
                        }
                    }
                }
            }
        } else if (action == InputConstants.RELEASE) {
            if (activeDoor != null) {
                syncActiveDoorIfDirty();
                if (accumulatedOpennessChange < 0.1) {
                    uiHintTicksLeft = UI_HINT_TICKS;
                }
                accumulatedOpennessChange = 0f;
                if (activeDoor instanceof ClientSpookyDoor clientSpookyDoor) {
                    clientSpookyDoor.locallyControlled(false);
                }
            }
            isDragging = false;
            activeDoor = null;
            draggingPlayer = null;
        }
        return false;
    }

    private static void cancelDragging() {
        if (activeDoor instanceof ClientSpookyDoor clientSpookyDoor) {
            clientSpookyDoor.locallyControlled(false);
        }
        accumulatedOpennessChange = 0f;
        isDirty = false;
        isDragging = false;
        activeDoor = null;
        draggingPlayer = null;
    }

    private static boolean shouldBypassDragging(Minecraft client, SpookyDoor door) {
        final var player = client.player;
        if (player == null) {
            return false;
        }

        final var config = SpookyDoorsConfig.getActive();
        if (config.spookyDoorActivation == SpookyDoorsConfig.SpookyDoorActivation.FORCED) {
            return false;
        }

        for (final var hand : InteractionHand.values()) {
            final var itemStack = player.getItemInHand(hand);
            if (config.allowItemToHauntDoors && !door.spooky() && itemStack.is(ModItemTags.HAUNTS_DOORS)) {
                return true;
            }

            if (config.allowItemToExorciseDoors && door.spooky() && itemStack.is(ModItemTags.EXORCISES_DOORS)) {
                return true;
            }
        }

        return false;
    }
}
