package net.val.bui.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.val.bui.BetterUI;
import net.val.bui.error.BUIErrors;

/**
 * Validated client-to-server request containing only a registered action ID; no client-supplied command or
 * callback code is executed.
 */
public record BUIPressPayload(String actionId) implements CustomPacketPayload {

    /**
     * Creates a server action request after validating its namespaced ID. Widgets normally create this payload
     * automatically.
     *
     * @param actionId namespaced action ID such as yourmod:open_panel
     */
    public BUIPressPayload {
        actionId = validateActionId(actionId);
    }

    /**
     * Validates a lowercase namespaced action ID with at most 128 ASCII characters and returns it unchanged. Use
     * your own mod ID, for example {@code yourmod:open_panel}.
     *
     * @param actionId namespaced action ID such as yourmod:open_panel
     * @return the validated action ID unchanged
     */
    public static String validateActionId(String actionId) {
        BUIErrors.requireNonNull(actionId, BUIPressPayload.class, "validateActionId", "actionId");
        if (actionId.length() > 128 || !actionId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw BUIErrors.argument(BUIPressPayload.class, "validateActionId", "Invalid server action ID: " + actionId,
                    "Use a namespaced ID such as yourmod:open_panel, with at most 128 lowercase ASCII characters.");
        }
        return actionId;
    }

    /** Shared NeoForge payload identifier; consuming mods do not register it again. */
    public static final Type<BUIPressPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BetterUI.MODID, "press"));

    /** Codec enforcing the 128-character wire limit in addition to ID validation. */
    public static final StreamCodec<ByteBuf, BUIPressPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(128), BUIPressPayload::actionId, BUIPressPayload::new);

    /**
     * Returns the bUI payload type used by NeoForge network registration.
     *
     * @return the registered bUI press-payload type
     */
    @Override
    public Type<BUIPressPayload> type() {
        return TYPE;
    }
}
