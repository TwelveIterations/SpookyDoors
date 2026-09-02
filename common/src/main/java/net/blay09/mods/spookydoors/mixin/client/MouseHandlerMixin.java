package net.blay09.mods.spookydoors.mixin.client;

import net.blay09.mods.spookydoors.client.SpookyDoorsClient;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void onMove(long handle, double xpos, double ypos, double xrel, double yrel, CallbackInfo ci) {
        if (SpookyDoorsClient.onMoveMouse(handle, xpos, ypos)) {
            //noinspection DataFlowIssue
            ((MouseHandler) (Object) this).setIgnoreFirstMove();
            ci.cancel();
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void onButton(long handle, MouseButtonInfo rawButtonInfo, int action, CallbackInfo ci) {
        if (SpookyDoorsClient.onMouseInput(rawButtonInfo.button(), action)) {
            ci.cancel();
        }
    }
}
