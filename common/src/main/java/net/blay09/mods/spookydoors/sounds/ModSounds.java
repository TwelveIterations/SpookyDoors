package net.blay09.mods.spookydoors.sounds;

import net.blay09.mods.balm.core.BalmRegistrar;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;

public class ModSounds {
    public static Holder<SoundEvent> doorCreak;

    public static void initialize(BalmRegistrar.Scoped<SoundEvent> soundEvents) {
        doorCreak = soundEvents.register("door_creak", SoundEvent::createVariableRangeEvent).asHolder();
    }
}
