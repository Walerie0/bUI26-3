package net.val.bui.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.val.bui.BetterUI;
import net.val.bui.error.BUIErrors;
import net.val.bui.model.BUIEffectRasterizer;
import net.val.bui.model.BUIGeometry.Transform;
import net.val.bui.model.BUIGeometry;
import net.val.bui.model.BUIStyleStack;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

import static net.val.bui.model.BUIValues.clamp;
import static net.val.bui.model.BUIValues.number;

/**
 * Internal GUI state and bounded cache of locally rasterized effect textures. It does not implement Glass or sampling of the Minecraft backdrop.
 */
public final class BUIEffectRenderState implements GuiElementRenderState {

    /**
     * Internal uploaded effect surface location, logical bounds and pixel budget cost.
     */
    private record Texture(Identifier id, float left, float top, float width, float height, long pixels) {}

    private static final Map<Map<String, Object>, Texture> CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static final List<Identifier> RETIRED = new ArrayList<>();
    private static final List<String> APPEARANCE = List.of("size", "cornerRadius", "rectangleCornerRadiiIndependent",
            "rectangleTopLeftCornerRadius", "rectangleTopRightCornerRadius", "rectangleBottomRightCornerRadius",
            "rectangleBottomLeftCornerRadius", "fillPaints", "strokePaints", "strokeWeight", "strokeAlign", "effects");
    private static GuiGraphicsExtractor frame;
    private static long sequence, cachedPixels;

    private final Matrix3x2f pose;
    private final @Nullable ScreenRectangle scissor;
    private final @Nullable ScreenRectangle bounds;
    private final Transform transform;
    private final Texture texture;
    private final int color;

    /**
     * Captures a cached effect texture with its screen transform, opacity and clipping bounds.
     *
     * @param graphics current frame GUI graphics extractor
     * @param transform node-to-GUI affine transform
     * @param texture uploaded texture or its logical surface metadata
     * @param opacity opacity multiplier, normally between zero and one
     */
    private BUIEffectRenderState(GuiGraphicsExtractor graphics, Transform transform, Texture texture, float opacity) {
        this.pose = new Matrix3x2f(graphics.pose());
        this.scissor = graphics.peekScissorStack();
        this.transform = transform;
        this.texture = texture;
        this.color = Math.round(clamp(opacity, 0, 1) * 255) << 24 | 0xffffff;
        var box = transform.bounds(texture.left(), texture.top(), texture.left() + texture.width(), texture.top() + texture.height());
        int left = (int) Math.floor(box.left()), top = (int) Math.floor(box.top());
        int right = (int) Math.ceil(box.right()), bottom = (int) Math.ceil(box.bottom());
        ScreenRectangle rect = new ScreenRectangle(left, top, right - left, bottom - top).transformMaxBounds(pose);
        this.bounds = scissor == null ? rect : scissor.intersection(rect);
    }

    /**
     * Submits a cached surface when a layer has a supported local effect. Returns true when it handles or hides the node, and false when normal fill/stroke rendering should proceed.
     *
     * @param graphics current frame GUI graphics extractor
     * @param design decoded component design; treat shared nested values as read-only
     * @param node decoded FIG node properties
     * @param transform node-to-GUI affine transform
     */
    public static boolean submit(GuiGraphicsExtractor graphics, Map<?, ?> design, Map<?, ?> node, Transform transform) {
        if (Boolean.FALSE.equals(node.get("visible")) || number(node.get("opacity"), 1) <= 0) return true;
        if (BUIGeometry.width(node) <= 0 || BUIGeometry.height(node) <= 0) return true;
        if (BUIStyleStack.fromNode(node).effects().stream().noneMatch(BUIEffectRasterizer::supports)) return false;

        if (frame != graphics) {
            for (Identifier id : RETIRED) Minecraft.getInstance().getTextureManager().release(id);
            RETIRED.clear();
            frame = graphics;
        }
        Map<String, Object> key = new LinkedHashMap<>();
        for (String property : APPEARANCE) {
            if (node.containsKey(property)) key.put(property, node.get(property));
        }
        if (design.containsKey("buiEmbeddedImages")) key.put("images", design.get("buiEmbeddedImages"));
        Texture texture = CACHE.get(key);
        if (texture == null) {
            BUIEffectRasterizer.Surface surface = BUIEffectRasterizer.render(design, node);
            NativeImage image = new NativeImage(surface.width(), surface.height(), false);
            DynamicTexture dynamic = null;
            try {
                for (int y = 0; y < surface.height(); y++) {
                    for (int x = 0; x < surface.width(); x++) image.setPixel(x, y, surface.pixels()[y * surface.width() + x]);
                }
                Identifier id = Identifier.fromNamespaceAndPath(BetterUI.MODID, "dynamic/effect_" + sequence++);
                dynamic = new DynamicTexture(() -> "bUI layer effects", image);
                Minecraft.getInstance().getTextureManager().register(id, dynamic);
                texture = new Texture(id, surface.left(), surface.top(), surface.logicalWidth(), surface.logicalHeight(),
                        (long) surface.width() * surface.height());
                CACHE.put(key, texture);
                cachedPixels += texture.pixels();
            } catch (RuntimeException e) {
                if (dynamic != null) dynamic.close();
                else image.close();
                throw new IllegalStateException(BUIErrors.message(BUIEffectRenderState.class, "submit",
                        "Failed to upload the layer effect texture: " + e.getMessage(),
                        "Render on the client render thread and reduce oversized component dimensions or effects."), e);
            }
            while (CACHE.size() > 64 || cachedPixels > 16_777_216) {
                var oldest = CACHE.entrySet().iterator();
                Texture retired = oldest.next().getValue();
                oldest.remove();
                cachedPixels -= retired.pixels();
                // Extracted render states can still reference this texture until this frame is drawn.
                RETIRED.add(retired.id());
            }
        }
        graphics.submitGuiElementRenderState(new BUIEffectRenderState(graphics, transform, texture, number(node.get("opacity"), 1)));
        return true;
    }

    /**
     * Returns conservative screen-space bounds used by Minecraft GUI overlap ordering and culling.
     *
     */
    @Override public @Nullable ScreenRectangle bounds() { return bounds; }
    /**
     * Returns the active screen-space clipping rectangle, or null when no scissor was captured.
     *
     */
    @Override public @Nullable ScreenRectangle scissorArea() { return scissor; }
    /**
     * Returns the Minecraft GUI pipeline used to draw this state.
     *
     */
    @Override public RenderPipeline pipeline() { return RenderPipelines.GUI_TEXTURED; }

    /**
     * Resolves the texture bindings and sampler required by this GUI draw state.
     *
     */
    @Override
    public TextureSetup textureSetup() {
        var registered = Minecraft.getInstance().getTextureManager().getTexture(texture.id());
        return TextureSetup.singleTexture(registered.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
    }

    /**
     * Emits this state vertex data into Minecraft GUI batching; the engine invokes this after frame extraction.
     *
     * @param consumer Minecraft GUI vertex consumer
     */
    @Override
    public void buildVertices(VertexConsumer consumer) {
        if (bounds == null) return;
        float left = texture.left(), top = texture.top(), right = left + texture.width(), bottom = top + texture.height();
        vertex(consumer, left, top, 0, 0);
        if (transform.determinant() * pose.determinant() < 0) {
            vertex(consumer, right, top, 1, 0);
            vertex(consumer, right, bottom, 1, 1);
            vertex(consumer, left, bottom, 0, 1);
        } else {
            vertex(consumer, left, bottom, 0, 1);
            vertex(consumer, right, bottom, 1, 1);
            vertex(consumer, right, top, 1, 0);
        }
    }

    /**
     * Emits one transformed surface vertex with its color and texture coordinate.
     *
     * @param consumer Minecraft GUI vertex consumer
     * @param x horizontal coordinate in the method coordinate space
     * @param y vertical coordinate in the method coordinate space
     * @param u normalized horizontal texture coordinate
     * @param v normalized vertical texture coordinate
     */
    private void vertex(VertexConsumer consumer, float x, float y, float u, float v) {
        consumer.addVertexWith2DPose(pose, transform.x(x, y), transform.y(x, y)).setColor(color).setUv(u, v);
    }
}
