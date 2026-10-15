package net.val.bui;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.val.bui.network.BUIServerActions;

/**
 * NeoForge entry point for the bUI library. It registers networking without loading client demo screens or bundled components.
 */
@Mod(BetterUI.MODID)
public class BetterUI {

    /** Library mod namespace used for metadata and networking. */
    public static final String MODID = "bui";

    /**
     * Registers the bUI network payloads with the mod event bus. NeoForge constructs this entry point automatically; consuming mods do not need to instantiate it.
     *
     * @param modEventBus NeoForge mod event bus
     */
    public BetterUI(IEventBus modEventBus) {
        modEventBus.addListener(BUIServerActions::registerPayloads);
    }
}
