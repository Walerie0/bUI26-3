package net.val.bui.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.val.bui.BetterUI;
import net.val.bui.error.BUIErrors;

/**
 * Internal client texture resolver and upload cache for embedded FIG images.
 */
public final class BUITextures {

    // Archive aliases share the same byte array, so they also share a GPU texture.
    private static final Map<byte[], Identifier> CACHE = new IdentityHashMap<>();

    /**
     * Prevents construction; use the static methods of this utility class.
     */
    private BUITextures() {}

    /**
     * Resolves an embedded image reference and returns its uploaded texture ID, or null when no embedded image matches.
     *
     * @param design decoded component design; treat shared nested values as read-only
     * @param paint decoded fill or stroke paint
     */
    public static Identifier resolve(Map<?, ?> design, Map<?, ?> paint) {
        if (!(design.get("buiEmbeddedImages") instanceof Map<?, ?> images)) return null;

        Set<String> candidates = new LinkedHashSet<>();
        if (paint.get("image") instanceof Map<?, ?> image) {
            Object hash = image.get("hash");
            if (hash instanceof byte[] bytes) {
                String hex = HexFormat.of().formatHex(bytes);
                candidates.add("images/" + hex);
                candidates.add(hex);
            } else if (hash instanceof String value) {
                candidates.add("images/" + value);
                candidates.add(value);
            }
            addCandidate(candidates, image.get("imageRef"));
            addCandidate(candidates, image.get("name"));
        }
        addCandidate(candidates, paint.get("imageRef"));
        addCandidate(candidates, paint.get("imageHash"));
        addCandidate(candidates, paint.get("rasterContentHash"));

        for (String candidate : candidates) {
            if (images.get(candidate) instanceof byte[] bytes) return upload(candidate, bytes);
        }
        return null;
    }

    /**
     * Adds normalized image-path aliases for embedded texture lookup.
     *
     * @param candidates ordered resource aliases to search
     * @param raw decoded source value
     */
    private static void addCandidate(Set<String> candidates, Object raw) {
        if (raw == null) return;
        String value = raw.toString().trim();
        if (!value.isEmpty()) {
            candidates.add(value);
            candidates.add("images/" + value);
        }
    }

    /**
     * Uploads one image byte array once and reuses its texture ID. Decode or upload failures include a recovery hint and preserve their original cause.
     *
     * @param key property name or resource lookup alias
     * @param bytes complete encoded input bytes
     */
    private static Identifier upload(String key, byte[] bytes) {
        Identifier existing = CACHE.get(bytes);
        if (existing != null) return existing;

        NativeImage image = null;
        DynamicTexture texture = null;
        try {
            image = NativeImage.read(bytes);
            texture = new DynamicTexture(() -> "bUI: " + key, image);
            Identifier id = Identifier.fromNamespaceAndPath(BetterUI.MODID, "dynamic/image_" + CACHE.size());
            Minecraft.getInstance().getTextureManager().register(id, texture);
            CACHE.put(bytes, id);
            return id;
        } catch (IOException | RuntimeException e) {
            if (texture != null) texture.close();
            else if (image != null) image.close();
            throw new IllegalStateException(BUIErrors.message(BUITextures.class, "upload",
                    "Failed to decode or upload image " + key + ": " + e.getMessage(),
                    "Use a supported embedded PNG or JPEG image and render it on the client render thread."), e);
        }
    }
}
