package net.blay09.mods.spookydoors.block;

import net.blay09.mods.spookydoors.SpookyDoorsConfig;
import net.blay09.mods.spookydoors.client.SpookyDoorsClient;
import net.blay09.mods.spookydoors.core.ServerSpookyDoor;
import net.blay09.mods.spookydoors.core.SpookyDoorProvider;
import net.blay09.mods.spookydoors.item.ModItemTags;
import net.blay09.mods.spookydoors.util.SpookyDoorUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class SpookyDoorBlockHooks {

    @Nullable
    public static RenderShape getRenderShape(BlockState state) {
        return SpookyDoorUtils.isSupportedDoor(state) ? RenderShape.INVISIBLE : null;
    }

    @Nullable
    public static VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos) {
        if (level instanceof Level worldLevel && isSpookyDoor(state, worldLevel, pos)) {
            final var openness = SpookyDoorProvider.get(worldLevel).of(pos, state).percentOpen();
            final var closedShape = SpookyDoorShapes.getDoorGeometry(state, false).shape();
            if (openness <= 0f) {
                return closedShape;
            } else if (openness >= 1f) {
                return SpookyDoorShapes.getDoorGeometry(state, true).shape();
            }

            // For partially opened doors, only the hinge should actually be collision. The player can run through the
            // partially opened door to open or close it with their body.
            return Shapes.join(closedShape, SpookyDoorShapes.getDoorGeometry(state, true).shape(), BooleanOp.AND);
        }

        return null;
    }

    @Nullable
    public static VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        if (level instanceof Level worldLevel && isSpookyDoor(state, worldLevel, pos)) {
            final var door = SpookyDoorProvider.get(worldLevel).of(pos, state);
            final var openness = door.percentOpen();
            return SpookyDoorShapes.getInteractionShape(state, openness);
        }

        return null;
    }

    public static void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (!isSpookyDoor(state, level, pos) || !SpookyDoorUtils.canOperate(state)) {
            return;
        }

        final var isLocalClientPlayer = level.isClientSide && entity instanceof Player player && player.isLocalPlayer();
        final var isRemoteMob = !level.isClientSide && !(entity instanceof Player);
        if (!isLocalClientPlayer && !isRemoteMob) {
            return;
        }

        if (!state.hasProperty(DoorBlock.FACING)) {
            return;
        }

        final var facing = state.getValue(DoorBlock.FACING);
        final var door = SpookyDoorProvider.get(level).of(pos, state);
        var percentOpen = door.percentOpen();
        if (percentOpen > 0 && percentOpen < 1) {
            final var doorFacingDirection = Vec3.atLowerCornerOf(facing.getNormal()).normalize();
            final var entityPosition = entity.position();
            final var doorPosition = Vec3.atCenterOf(pos);
            final var doorToEntity = entityPosition.subtract(doorPosition).normalize();
            final var entityVelocity = entity.getDeltaMovement();
            final var movementTowardsFacing = doorFacingDirection.dot(entityVelocity);
            final var positionAlignment = doorFacingDirection.dot(doorToEntity);
            final var adjustmentSpeed = (float) Math.abs(movementTowardsFacing) * 0.9f;

            if (positionAlignment < 0 && movementTowardsFacing > 0) {
                percentOpen = Math.min(percentOpen + adjustmentSpeed, 1f);
            } else if (positionAlignment > 0 && movementTowardsFacing < 0 && percentOpen < 0.8f) {
                percentOpen = Math.max(percentOpen - adjustmentSpeed, 0.0f);
            }

            door.operate(entity, percentOpen);
            if (isLocalClientPlayer) {
                SpookyDoorsClient.setActiveDoorAndDirty(level, door.pos());
            }
        }
    }

    public static void onPlace(BlockState state, Level level, BlockPos pos, BlockState previousState) {
        if (!SpookyDoorUtils.isSupportedDoor(state)) {
            return;
        }

        final var door = SpookyDoorProvider.get(level).of(pos, state);
        final boolean redstoneChanged = !state.hasProperty(DoorBlock.POWERED) || !previousState.hasProperty(DoorBlock.POWERED) || previousState.getValue(DoorBlock.POWERED) != state.getValue(DoorBlock.POWERED);
        if (door.spooky() && (redstoneChanged || !previousState.is(state.getBlock()))) {
            door.percentOpen(state.getOptionalValue(DoorBlock.OPEN).orElse(false) ? 1f : 0f);
            if (door instanceof ServerSpookyDoor serverSpookyDoor) {
                serverSpookyDoor.syncToClients();
            }
        }
    }

    public static void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState) {
        if (SpookyDoorUtils.isSupportedDoor(state) && !state.is(newState.getBlock())) {
            SpookyDoorProvider.get(level).remove(pos, state);
        }
    }

    @Nullable
    public static InteractionResult useItemOn(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand) {
        if (!SpookyDoorUtils.isSupportedDoor(state)) {
            return null;
        }

        final var ghastTearResult = tryHaunt(state, level, pos, player, hand);
        if (ghastTearResult != null) {
            return ghastTearResult;
        }

        if (!isSpookyDoor(state, level, pos)) {
            return null;
        }

        final var honeycombResult = tryExorcise(state, level, pos, player, hand);
        if (honeycombResult != null) {
            return honeycombResult;
        }

        if (!SpookyDoorUtils.canOperate(state)) {
            return null;
        }

        final var targetOpen = !state.getOptionalValue(DoorBlock.OPEN).orElse(false);
        final var door = SpookyDoorProvider.get(level).of(pos, state);
        door.operate(player, targetOpen ? 1f : 0f);
        level.gameEvent(player, targetOpen ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    public static InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult blockHitResult) {
        if (!SpookyDoorUtils.isSupportedDoor(state)) {
            return null;
        }

        if (!SpookyDoorUtils.canOperate(state)) {
            return null;
        }

        final var targetOpen = !state.getOptionalValue(DoorBlock.OPEN).orElse(false);
        final var door = SpookyDoorProvider.get(level).of(pos, state);
        door.operate(player, targetOpen ? 1f : 0f);
        level.gameEvent(player, targetOpen ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    private static InteractionResult tryHaunt(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand) {
        final var config = SpookyDoorsConfig.getActive();
        if (!config.allowItemToHauntDoors || config.spookyDoorActivation == SpookyDoorsConfig.SpookyDoorActivation.FORCED) {
            return null;
        }

        final var itemStack = player.getItemInHand(hand);
        if (!itemStack.is(ModItemTags.HAUNTS_DOORS)) {
            return null;
        }

        final var door = SpookyDoorProvider.get(level).of(pos, state);
        if (door.spooky()) {
            return null;
        }

        door.spooky(true);

        if (level instanceof ServerLevel serverLevel) {
            if (door instanceof ServerSpookyDoor serverSpookyDoor) {
                serverSpookyDoor.syncToClients();
            }

            level.playSound(null, pos, SoundEvents.GHAST_AMBIENT, SoundSource.BLOCKS, 1f, 1f);
            final var basePos = SpookyDoorUtils.getBasePos(pos, state);
            serverLevel.sendParticles(ParticleTypes.SOUL, basePos.getX() + 0.5, basePos.getY() + 1.0, basePos.getZ() + 0.5, 24, 0.45, 0.85, 0.45, 0.03);
            serverLevel.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, basePos.getX() + 0.5, basePos.getY() + 1.0, basePos.getZ() + 0.5, 24, 0.45, 0.85, 0.45, 0.03);
            level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
            player.awardStat(Stats.ITEM_USED.get(itemStack.getItem()));
            if (!player.getAbilities().instabuild) {
                itemStack.shrink(1);
            }
        }

        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    private static InteractionResult tryExorcise(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand) {
        final var config = SpookyDoorsConfig.getActive();
        if (!config.allowItemToExorciseDoors || config.spookyDoorActivation == SpookyDoorsConfig.SpookyDoorActivation.FORCED) {
            return null;
        }

        final var itemStack = player.getItemInHand(hand);
        if (!itemStack.is(ModItemTags.EXORCISES_DOORS)) {
            return null;
        }

        final var door = SpookyDoorProvider.get(level).of(pos, state);
        door.spooky(false);

        if (level instanceof ServerLevel serverLevel) {
            if (door instanceof ServerSpookyDoor serverSpookyDoor) {
                serverSpookyDoor.syncToClients();
            }

            level.playSound(null, pos, SoundEvents.HONEYCOMB_WAX_ON, SoundSource.BLOCKS, 1f, 1f);
            final var basePos = SpookyDoorUtils.getBasePos(pos, state);
            serverLevel.sendParticles(ParticleTypes.WAX_ON, basePos.getX() + 0.5, basePos.getY() + 1.0, basePos.getZ() + 0.5, 24, 0.45, 0.85, 0.45, 0.03);
            serverLevel.sendParticles(ParticleTypes.POOF, basePos.getX() + 0.5, basePos.getY() + 1.0, basePos.getZ() + 0.5, 24, 0.45, 0.85, 0.45, 0.03);
            level.levelEvent(player, 3003, pos, 0);
            player.awardStat(Stats.ITEM_USED.get(itemStack.getItem()));
            if (!player.getAbilities().instabuild) {
                itemStack.shrink(1);
            }
        }

        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    public static void setOpen(DoorBlock doorBlock, Level level, BlockState state, BlockPos pos, boolean open) {
        if (state.is(doorBlock) && isSpookyDoor(state, level, pos) && state.getOptionalValue(DoorBlock.OPEN).orElse(false) != open) {
            final var openness = open ? 1f : 0f;
            final var door = SpookyDoorProvider.get(level).of(pos, state);
            door.percentOpen(openness);
            if (door instanceof ServerSpookyDoor serverSpookyDoor) {
                serverSpookyDoor.syncToClients();
            }
        }
    }

    public static void neighborChanged(DoorBlock doorBlock, Level level, BlockState previousState, BlockPos pos) {
        final var state = level.getBlockState(pos);
        if (!previousState.is(doorBlock) || !state.is(doorBlock) || !isSpookyDoor(state, level, pos)) {
            return;
        }

        final var wasOpen = previousState.getOptionalValue(DoorBlock.OPEN).orElse(false);
        final var isOpen = state.getOptionalValue(DoorBlock.OPEN).orElse(false);
        if (wasOpen == isOpen) {
            return;
        }

        final var door = SpookyDoorProvider.get(level).of(pos, state);
        door.percentOpen(isOpen ? 1f : 0f);
        if (door instanceof ServerSpookyDoor serverSpookyDoor) {
            serverSpookyDoor.syncToClients();
        }
    }

    public static boolean isSpookyDoor(BlockState state, Level level, BlockPos pos) {
        return SpookyDoorUtils.isSupportedDoor(state) && SpookyDoorProvider.get(level).of(pos, state).spooky();
    }
}
