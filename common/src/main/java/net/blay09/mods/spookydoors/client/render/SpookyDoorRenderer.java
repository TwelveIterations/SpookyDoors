package net.blay09.mods.spookydoors.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.blay09.mods.spookydoors.block.SpookyDoorShapes;
import net.blay09.mods.spookydoors.client.SpookyDoorClientTracking;
import net.blay09.mods.spookydoors.core.SpookyDoorProvider;
import net.blay09.mods.spookydoors.util.SpookyDoorUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.AxisAngle4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

public class SpookyDoorRenderer {

    public static void submitDoors(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, Vec3 cameraPosition) {
        final var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        final var minecraft = Minecraft.getInstance();
        final var blockModelSet = minecraft.getModelManager().getBlockStateModelSet();
        for (final var basePos : SpookyDoorClientTracking.get(level).doorPositions()) {
            final var baseState = level.getBlockState(basePos);
            if (!SpookyDoorUtils.isSupportedDoor(baseState)) {
                continue;
            }

            submitDoorHalf(level, blockModelSet, poseStack, submitNodeCollector, cameraPosition, basePos, baseState, basePos);

            final var upperPos = basePos.above();
            final var upperState = level.getBlockState(upperPos);
            if (SpookyDoorUtils.isSupportedDoor(upperState)
                    && upperState.hasProperty(DoorBlock.HALF)
                    && upperState.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
                submitDoorHalf(level, blockModelSet, poseStack, submitNodeCollector, cameraPosition, upperPos, upperState, basePos);
            }
        }
    }

    private static void submitDoorHalf(
            Level level,
            BlockStateModelSet blockModelSet,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            Vec3 cameraPosition,
            BlockPos pos,
            BlockState state,
            BlockPos basePos) {
        if (!state.hasProperty(DoorBlock.OPEN) || !state.hasProperty(DoorBlock.FACING) || !state.hasProperty(DoorBlock.HINGE)) {
            return;
        }

        final var openness = SpookyDoorProvider.get(level).at(basePos).percentOpen();
        final var stateForRender = state.setValue(DoorBlock.OPEN, false);

        poseStack.pushPose();
        poseStack.translate(pos.getX() - cameraPosition.x(), pos.getY() - cameraPosition.y(), pos.getZ() - cameraPosition.z());
        applyDoorPose(poseStack, openness, state.getValue(DoorBlock.FACING), state.getValue(DoorBlock.HINGE));
        submitDoorModel(level, blockModelSet, poseStack, submitNodeCollector, pos, stateForRender, openness);
        poseStack.popPose();
    }

    private static void submitDoorModel(
            Level level,
            BlockStateModelSet blockModelSet,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            BlockPos pos,
            BlockState state,
            float openness) {
        final var model = blockModelSet.get(state);
        final var renderState = new BlockModelRenderState();
        model.collectParts(renderState.scratchRandomSource(state.getSeed(pos)), renderState.setupModel(new Matrix4f(), model.hasMaterialFlag(BakedQuad.FLAG_TRANSLUCENT)));
        collectTintLayers(level, pos, state, renderState);
        renderState.submit(poseStack, submitNodeCollector, getDoorLight(level, pos, state, openness), OverlayTexture.NO_OVERLAY, 0);
    }

    private static void collectTintLayers(Level level, BlockPos pos, BlockState state, BlockModelRenderState renderState) {
        final var tintSources = Minecraft.getInstance().getBlockColors().getTintSources(state);
        for (final BlockTintSource tintSource : tintSources) {
            renderState.tintLayers().add(tintSource.colorInWorld(state, (BlockAndTintGetter) level, pos));
        }
    }

    private static int getDoorLight(Level level, BlockPos pos, BlockState state, float openness) {
        final var shape = SpookyDoorShapes.getInteractionShape(state, openness);
        final var blockAndTintGetter = (BlockAndTintGetter) level;
        final var lighter = new BlockModelLighter();
        final var light = new int[]{lighter.getLightCoords(state, blockAndTintGetter, pos)};

        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            sampleLight(lighter, blockAndTintGetter, state, pos, minX, minY, minZ, light);
            sampleLight(lighter, blockAndTintGetter, state, pos, maxX, maxY, maxZ, light);
            sampleLight(lighter, blockAndTintGetter, state, pos, (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2, light);
        });

        return light[0];
    }

    private static void sampleLight(BlockModelLighter lighter, BlockAndTintGetter level, BlockState state, BlockPos pos, double x, double y, double z, int[] light) {
        final var samplePos = BlockPos.containing(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
        light[0] = LightCoordsUtil.max(light[0], lighter.getLightCoords(state, level, samplePos));
    }

    public static VoxelShape getOutlineShape(BlockState state) {
        return SpookyDoorShapes.getDoorGeometry(state, false).shape();
    }

    public static void applyDoorPose(PoseStack poseStack, float openness, Direction facing, DoorHingeSide hinge) {
        final var pivot = SpookyDoorShapes.getPivot(facing, hinge);
        final var offX = pivot.x() / 16.0D;
        final var offZ = pivot.z() / 16.0D;
        poseStack.translate(offX, 0, offZ);
        poseStack.rotate(new Quaternionf(new AxisAngle4d(openness * Math.PI / 2, 0, hinge == DoorHingeSide.LEFT ? 1 : -1, 0)));
        poseStack.translate(-offX, 0, -offZ);
    }
}
