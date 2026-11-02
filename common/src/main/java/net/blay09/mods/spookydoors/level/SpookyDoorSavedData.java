package net.blay09.mods.spookydoors.level;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.blay09.mods.spookydoors.SpookyDoors;
import net.blay09.mods.spookydoors.SpookyDoorsConfig;
import net.blay09.mods.spookydoors.network.ClientboundDoorStatePacket;
import net.blay09.mods.spookydoors.core.ServerSpookyDoor;
import net.blay09.mods.spookydoors.core.SpookyDoor;
import net.blay09.mods.spookydoors.core.SpookyDoorProvider;
import net.blay09.mods.spookydoors.util.SpookyDoorUtils;
import net.blay09.mods.balm.Balm;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class SpookyDoorSavedData extends SavedData implements SpookyDoorProvider {

    public static final String ID = SpookyDoors.MOD_ID;
    private static final Codec<SpookyDoorSavedData> CODEC = PersistedSpookyDoor.CODEC.listOf()
            .fieldOf("doors")
            .xmap(SpookyDoorSavedData::fromDoors, SpookyDoorSavedData::toDoors)
            .codec();
    private static final SavedDataType<SpookyDoorSavedData> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(SpookyDoors.MOD_ID, ID), SpookyDoorSavedData::new, CODEC, DataFixTypes.LEVEL);

    private final Map<Long, PersistedSpookyDoor> doorsByPos = new HashMap<>();
    private ServerLevel level;

    public static SpookyDoorSavedData get(ServerLevel level) {
        final var data = level.getDataStorage().computeIfAbsent(TYPE);
        data.level = level;
        return data;
    }

    private static SpookyDoorSavedData fromDoors(List<PersistedSpookyDoor> doors) {
        final var data = new SpookyDoorSavedData();
        for (final var door : doors) {
            data.doorsByPos.put(door.pos.asLong(), door);
        }
        return data;
    }

    private static List<PersistedSpookyDoor> toDoors(SpookyDoorSavedData data) {
        final var doors = new ArrayList<PersistedSpookyDoor>();
        for (final var door : data.doorsByPos.values()) {
            if (door.hasPersistedData()) {
                doors.add(door);
            }
        }
        return doors;
    }

    private static class PersistedSpookyDoor {
        private static final Codec<PersistedSpookyDoor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(PersistedSpookyDoor::getPos),
                Codec.FLOAT.optionalFieldOf("percent_open").forGetter(PersistedSpookyDoor::getPercentOpen),
                Codec.BOOL.optionalFieldOf("spooky").forGetter(PersistedSpookyDoor::getSpooky)
        ).apply(instance, PersistedSpookyDoor::new));

        private final BlockPos pos;
        private @Nullable Float percentOpen;
        private @Nullable Boolean spooky;
        private long nextCreakSoundGameTime;

        private PersistedSpookyDoor(long pos) {
            this(BlockPos.of(pos));
        }

        private PersistedSpookyDoor(BlockPos pos) {
            this.pos = pos;
        }

        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        private PersistedSpookyDoor(BlockPos pos, Optional<Float> percentOpen, Optional<Boolean> spooky) {
            this.pos = pos;
            this.percentOpen = percentOpen.orElse(null);
            this.spooky = spooky.orElse(null);
        }

        private BlockPos getPos() {
            return pos;
        }

        private Optional<Float> getPercentOpen() {
            return Optional.ofNullable(percentOpen);
        }

        private Optional<Boolean> getSpooky() {
            return Optional.ofNullable(spooky);
        }

        private boolean hasPersistedData() {
            return percentOpen != null || spooky != null;
        }
    }

    @Override
    public SpookyDoor at(BlockPos pos) {
        final var basePos = SpookyDoorUtils.getBasePos(pos, level.getBlockState(pos));
        return new ServerSpookyDoor(level, this, basePos);
    }

    @Override
    public SpookyDoor of(BlockPos pos, BlockState state) {
        return new ServerSpookyDoor(level, this, SpookyDoorUtils.getBasePos(pos, state));
    }

    @Nullable
    public Float getPercentOpen(BlockPos pos) {
        final var door = doorsByPos.get(pos.asLong());
        return door != null ? door.percentOpen : null;
    }

    public void setPercentOpen(BlockPos pos, float percentOpen) {
        doorsByPos.computeIfAbsent(pos.asLong(), PersistedSpookyDoor::new).percentOpen = Mth.clamp(percentOpen, 0f, 1f);
        setDirty();
    }

    public boolean isSpooky(BlockPos pos) {
        if (SpookyDoorsConfig.getActive().spookyDoorActivation == SpookyDoorsConfig.SpookyDoorActivation.FORCED) {
            return true;
        }

        final var door = doorsByPos.get(pos.asLong());
        if (door != null && door.spooky != null) {
            return door.spooky;
        }

        return SpookyDoorsConfig.getActive().spookyDoorActivation == SpookyDoorsConfig.SpookyDoorActivation.DEFAULT;
    }

    public void setSpooky(BlockPos pos, boolean spooky) {
        doorsByPos.computeIfAbsent(pos.asLong(), PersistedSpookyDoor::new).spooky = spooky;
        setDirty();
    }

    public boolean isIndividuallySpooky(BlockPos pos) {
        final var door = doorsByPos.get(pos.asLong());
        if (door != null && door.spooky != null) {
            return door.spooky;
        }

        return SpookyDoorsConfig.getActive().spookyDoorActivation == SpookyDoorsConfig.SpookyDoorActivation.DEFAULT;
    }

    public void syncDoorsInChunk(ServerPlayer player, ChunkPos chunkPos) {
        final var positions = new HashSet<>(doorsByPos.keySet());

        final var chunk = level.getChunk(chunkPos.x(), chunkPos.z());
        SpookyDoorUtils.forEachSupportedDoor(level, chunk, (pos, _) -> positions.add(pos.asLong()));

        for (final var key : positions) {
            final var pos = BlockPos.of(key);
            if (pos.getX() >> 4 != chunkPos.x() || pos.getZ() >> 4 != chunkPos.z()) {
                continue;
            }

            final var state = level.getBlockState(pos);
            if (SpookyDoorUtils.isSupportedDoor(state)) {
                final var door = new ServerSpookyDoor(level, this, pos);
                Balm.networking().sendTo(player, new ClientboundDoorStatePacket(pos, door.percentOpen(), door.spooky()));
            }
        }
    }

    @Override
    public void remove(BlockPos pos, BlockState state) {
        remove(SpookyDoorUtils.getBasePos(pos, state));
    }

    public boolean remove(BlockPos pos) {
        final var key = pos.asLong();
        final var removedDoor = doorsByPos.remove(key);
        if (removedDoor == null) {
            return false;
        }
        if (removedDoor.hasPersistedData()) {
            setDirty();
        }
        return true;
    }

    public long getNextCreakSoundGameTime(BlockPos pos) {
        final var door = doorsByPos.get(pos.asLong());
        return door != null ? door.nextCreakSoundGameTime : 0L;
    }

    public void setNextCreakSoundGameTime(BlockPos pos, long gameTime) {
        doorsByPos.computeIfAbsent(pos.asLong(), PersistedSpookyDoor::new).nextCreakSoundGameTime = gameTime;
    }

}
