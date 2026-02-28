package com.odtheking.odinaddon.mixin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "moe.nea.firmament.features.misc.ModAnnouncer", remap = false)
public abstract class FirmamentModAnnouncerMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("OdinAddon/FirmamentFix");

    @Inject(
            method = "onServerJoin(Lmoe/nea/firmament/events/JoinServerEvent;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void odinaddon$disableFirmamentModAnnouncer(CallbackInfo ci) {
        LOGGER.info("Firmament detected, disabled ModAnnouncer packet.");
        ci.cancel();
    }
}
