package net.blay09.mods.spookydoors.level;

import net.blay09.mods.spookydoors.SpookyDoors;
import net.blay09.mods.spookydoors.SpookyDoorsConfig;
import net.blay09.mods.spookydoors.network.ClientboundDoorStatePacket;
import net.blay09.mods.spookydoors.core.ServerSpookyDoor;
import net.blay09.mods.spookydoors.core.SpookyDoor;
import net.blay09.mods.spookydoors.core.SpookyDoorProvider;
import net.blay09.mods.spookydoors.util.SpookyDoorUtils;
import net.blay09.mods.balm.api.Balm;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;

public class SpookyDoorSavedData extends SavedData implements SpookyDoorProvider {

    public static final String ID = SpookyDoors.MOD_ID;

    private final Map<Long, Float> percentOpenByPos = new HashMap<>();
    private final Map<Long, Boolean> spookyByPos = new HashMap<>();
    private final Map<Long, Long> nextCreakSoundGameTimeByPos = new HashMap<>();
    private ServerLevel level;

    public static SpookyDoorSavedData get(ServerLevel level) {
        final var factory = new SavedData.Factory<>(SpookyDoorSavedData::new, SpookyDoorSavedData::load, null);
        final var data = level.getDataStorage().computeIfAbsent(factory, ID);
        data.level = level;
        return data;
    }

    public static SpookyDoorSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        final var data = new SpookyDoorSavedData();
        final var doors = tag.getList("Doors", Tag.TAG_COMPOUND);
        for (int i = 0; i < doors.size(); i++) {
            final var doorTag = doors.getCompound(i);
            final var pos = doorTag.getLong("Pos");
            if (doorTag.contains("PercentOpen", Tag.TAG_FLOAT)) {
                data.percentOpenByPos.put(pos, doorTag.getFloat("PercentOpen"));
            }
            if (doorTag.contains("Spooky", Tag.TAG_BYTE)) {
                data.spookyByPos.put(pos, doorTag.getBoolean("Spooky"));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        final var doors = new ListTag();
        for (final var entry : percentOpenByPos.entrySet()) {
            final var doorTag = new CompoundTag();
            doorTag.putLong("Pos", entry.getKey());
            doorTag.putFloat("PercentOpen", entry.getValue());
            final var spooky = spookyByPos.get(entry.getKey());
            if (spooky != null) {
                doorTag.putBoolean("Spooky", spooky);
            }
            doors.add(doorTag);
        }
        for (final var entry : spookyByPos.entrySet()) {
            if (percentOpenByPos.containsKey(entry.getKey())) {
                continue;
            }

            final var doorTag = new CompoundTag();
            doorTag.putLong("Pos", entry.getKey());
            doorTag.putBoolean("Spooky", entry.getValue());
            doors.add(doorTag);
        }
        tag.put("Doors", doors);
        return tag;
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
        return percentOpenByPos.get(pos.asLong());
    }

    public void setPercentOpen(BlockPos pos, float percentOpen) {
        percentOpenByPos.put(pos.asLong(), Mth.clamp(percentOpen, 0f, 1f));
        setDirty();
    }

    public boolean isSpooky(BlockPos pos) {
        if (SpookyDoorsConfig.getActive().spookyDoorActivation == SpookyDoorsConfig.SpookyDoorActivation.FORCED) {
            return true;
        }

        final var spooky = spookyByPos.get(pos.asLong());
        if (spooky != null) {
            return spooky;
        }

        return SpookyDoorsConfig.getActive().spookyDoorActivation == SpookyDoorsConfig.SpookyDoorActivation.DEFAULT;
    }

    public void setSpooky(BlockPos pos, boolean spooky) {
        spookyByPos.put(pos.asLong(), spooky);
        setDirty();
    }

    public boolean isIndividuallySpooky(BlockPos pos) {
        return spookyByPos.getOrDefault(pos.asLong(), SpookyDoorsConfig.getActive().spookyDoorActivation == SpookyDoorsConfig.SpookyDoorActivation.DEFAULT);
    }

    public void syncDoorsInChunk(ServerPlayer player, ChunkPos chunkPos) {
        final var positions = new HashSet<Long>();
        positions.addAll(percentOpenByPos.keySet());
        positions.addAll(spookyByPos.keySet());

        final var chunk = level.getChunk(chunkPos.x, chunkPos.z);
        SpookyDoorUtils.forEachSupportedDoor(level, chunk, (pos, state) -> positions.add(pos.asLong()));

        for (final var key : positions) {
            final var pos = BlockPos.of(key);
            if (pos.getX() >> 4 != chunkPos.x || pos.getZ() >> 4 != chunkPos.z) {
                continue;
            }

            final var state = level.getBlockState(pos);
            if (SpookyDoorUtils.isSupportedDoor(state)) {
                final var door = new ServerSpookyDoor(level, this, pos);
                Balm.getNetworking().sendTo(player, new ClientboundDoorStatePacket(pos, door.percentOpen(), door.spooky()));
            }
        }
    }

    @Override
    public void remove(BlockPos pos, BlockState state) {
        remove(SpookyDoorUtils.getBasePos(pos, state));
    }

    public boolean remove(BlockPos pos) {
        final var key = pos.asLong();
        final var removedCreakTime = nextCreakSoundGameTimeByPos.remove(key) != null;
        final var removedPercentOpen = percentOpenByPos.remove(key) != null;
        final var removedSpooky = spookyByPos.remove(key) != null;
        if (removedPercentOpen || removedSpooky) {
            setDirty();
        }
        return removedCreakTime || removedPercentOpen || removedSpooky;
    }

    public long getNextCreakSoundGameTime(BlockPos pos) {
        return nextCreakSoundGameTimeByPos.getOrDefault(pos.asLong(), 0L);
    }

    public void setNextCreakSoundGameTime(BlockPos pos, long gameTime) {
        nextCreakSoundGameTimeByPos.put(pos.asLong(), gameTime);
    }
}
