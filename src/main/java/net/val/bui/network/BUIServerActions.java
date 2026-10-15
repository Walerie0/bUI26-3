package net.val.bui.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.val.bui.error.BUIErrors;

/**
 * Common-side registry of namespaced server callbacks. Registrations are safe across concurrent mod initialization; callback code must authorize the requesting player.
 */
public final class BUIServerActions {

    private static final Map<String, Consumer<ServerPlayer>> ACTIONS = new ConcurrentHashMap<>();

    /**
     * Prevents construction of the shared server action registry.
     */
    private BUIServerActions() {}

    /**
     * Registers a server callback under a unique namespaced ID such as {@code yourmod:open_panel}. Call this once during your mod initialization. A matching {@code onPressServer(id)} binding invokes it on mouse press. Validate the player permissions, distance and current game state inside the callback before applying changes; client requests are not authorization.
     *
     * @param id unique namespaced action ID, for example yourmod:open_panel
     * @param action server callback that validates the requesting player
     * @throws IllegalArgumentException if the ID or callback is invalid
     * @throws IllegalStateException if the ID has already been registered
     */
    public static void registerAction(String id, Consumer<ServerPlayer> action) {
        BUIPressPayload.validateActionId(id);
        BUIErrors.requireNonNull(action, BUIServerActions.class, "registerAction", "action");

        if (ACTIONS.putIfAbsent(id, action) != null) {
            throw new IllegalStateException(BUIErrors.message(BUIServerActions.class, "registerAction", "Duplicate bUI server action: " + id, "Register each namespaced server action once during your mod initialization, using your own mod ID."));
        }
    }

    /**
     * Registers the bUI client-to-server payload. The bUI mod entry point calls this automatically; consuming mods should register actions rather than duplicate payload registration.
     *
     * @param event Minecraft or NeoForge event being handled
     */
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(
                BUIPressPayload.TYPE,
                BUIPressPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (!(context.player() instanceof ServerPlayer player)) {
                        return;
                    }

                    Consumer<ServerPlayer> action = ACTIONS.get(payload.actionId());

                    if (action != null) {
                        action.accept(player);
                    }
                }
        );
    }
}
