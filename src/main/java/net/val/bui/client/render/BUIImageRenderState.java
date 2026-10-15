package net.val.bui.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.val.bui.model.BUIGeometry.Corners;
import net.val.bui.model.BUIGeometry.Transform;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

import static net.val.bui.model.BUIValues.clamp;
import static net.val.bui.model.BUIValues.number;

/**
 * Internal GUI draw state for embedded images clipped to a supported rounded rectangle.
 */
public final class BUIImageRenderState implements GuiElementRenderState {

    private static final float EDGE = 0.5f;
    private static final float CURVE_STEP = 0.5f;
    private static final float FILL_STEP = 8f;

    private final Matrix3x2f pose;
    private final @Nullable ScreenRectangle scissor;
    private final @Nullable ScreenRectangle bounds;

    private final Identifier texture;
    private final Transform transform;

    private final float width;
    private final float height;

    private final float tl;
    private final float tr;
    private final float br;
    private final float bl;
    private final Corners corners;

    private final int alpha;

    /**
     * Captures an image-filled layer with its GUI pose, opacity and rounded clipping geometry.
     *
     * @param graphics current frame GUI graphics extractor
     * @param node decoded FIG node properties
     * @param texture uploaded texture or its logical surface metadata
     * @param transform node-to-GUI affine transform
     * @param opacity opacity multiplier, normally between zero and one
     */
    private BUIImageRenderState(GuiGraphicsExtractor graphics, Map<?, ?> node, Identifier texture, Transform transform, float opacity) {
        this.transform = transform;
        this.pose = new Matrix3x2f(graphics.pose());
        this.scissor = graphics.peekScissorStack();
        this.texture = texture;
        Map<?, ?> size = (Map<?, ?>) node.get("size");
        this.width = number(size.get("x"), 0f);
        this.height = number(size.get("y"), 0f);
        this.alpha = Math.round(clamp(opacity, 0f, 1f) * 255f);
        this.corners = Corners.from(node, width, height);
        this.tl = corners.tl();
        this.tr = corners.tr();
        this.br = corners.br();
        this.bl = corners.bl();
        var box = transform.bounds(width, height);
        int left = (int) Math.floor(box.left());
        int top = (int) Math.floor(box.top());
        int right = (int) Math.ceil(box.right());
        int bottom = (int) Math.ceil(box.bottom());
        ScreenRectangle transformed = new ScreenRectangle(left, top, right - left, bottom - top).transformMaxBounds(pose);
        this.bounds = scissor == null ? transformed : scissor.intersection(transformed);
    }

    /**
     * Submits image fill paints resolved from the component embedded images.
     *
     * @param graphics current frame GUI graphics extractor
     * @param rootDesign component design containing embedded image bytes
     * @param node decoded FIG node properties
     * @param transform node-to-GUI affine transform
     */
    public static void submit(GuiGraphicsExtractor graphics, Map<?, ?> rootDesign, Map<?, ?> node, Transform transform) {
        if (Boolean.FALSE.equals(node.get("visible"))) {
            return;
        }

        if (!(node.get("size") instanceof Map<?, ?> size)) {
            return;
        }

        if (number(size.get("x"), 0f) <= 0f || number(size.get("y"), 0f) <= 0f) {
            return;
        }

        if (!(node.get("fillPaints") instanceof List<?> paints)) {
            return;
        }

        float nodeOpacity = clamp(number(node.get("opacity"), 1f), 0f, 1f);

        for (Object rawPaint : paints) {
            if (!(rawPaint instanceof Map<?, ?> paint)) {
                continue;
            }

            if (Boolean.FALSE.equals(paint.get("visible"))) {
                continue;
            }

            Object type = paint.get("type");
            // Accept both the Kiwi IMAGE name and its numeric value.
            boolean imagePaint = "IMAGE".equals(type) || (type instanceof Number n && n.intValue() == 5);

            if (!imagePaint) {
                continue;
            }

            Identifier texture = BUITextures.resolve(rootDesign, paint);

            if (texture == null) {
                continue;
            }

            float opacity = nodeOpacity * clamp(number(paint.get("opacity"), 1f), 0f, 1f);
            graphics.submitGuiElementRenderState(new BUIImageRenderState(graphics, node, texture, transform, opacity));
        }
    }

    /**
     * Returns conservative screen-space bounds used by Minecraft GUI overlap ordering and culling.
     *
     */
    @Override
    public @Nullable ScreenRectangle bounds() {
        return bounds;
    }

    /**
     * Returns the active screen-space clipping rectangle, or null when no scissor was captured.
     *
     */
    @Override
    public @Nullable ScreenRectangle scissorArea() {
        return scissor;
    }

    /**
     * Returns the Minecraft GUI pipeline used to draw this state.
     *
     */
    @Override
    public RenderPipeline pipeline() {
        return RenderPipelines.GUI_TEXTURED;
    }

    /**
     * Resolves the texture bindings and sampler required by this GUI draw state.
     *
     */
    @Override
    public TextureSetup textureSetup() {
        AbstractTexture registeredTexture = Minecraft.getInstance().getTextureManager().getTexture(texture);

        return TextureSetup.singleTexture(registeredTexture.getTextureView(), com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.LINEAR));
    }

    /**
     * Emits this state vertex data into Minecraft GUI batching; the engine invokes this after frame extraction.
     *
     * @param consumer Minecraft GUI vertex consumer
     */
    @Override
    public void buildVertices(VertexConsumer consumer) {
        if (bounds == null) {
            return;
        }

        float topCurve = Math.max(tl, tr);
        float bottomCurve = height - Math.max(bl, br);
        float y = 0f;

        while (y < height) {
            boolean nearCurve = y < topCurve || y >= bottomCurve;
            float step = nearCurve ? CURVE_STEP : FILL_STEP;
            float nextY = Math.min(height, y + step);

            if (y < topCurve && nextY > topCurve) {
                nextY = topCurve;
            }

            if (y < bottomCurve && nextY > bottomCurve) {
                nextY = bottomCurve;
            }

            if (y < EDGE) {
                nextY = Math.min(nextY, EDGE);
            }

            if (y < height - EDGE && nextY > height - EDGE) {
                nextY = height - EDGE;
            }

            if (nextY <= y) {
                nextY = Math.min(height, y + 0.25f);
            }

            emitStrip(consumer, y, nextY);
            y = nextY;
        }
    }

    /**
     * Emits one textured strip with rounded edge coverage.
     *
     * @param consumer Minecraft GUI vertex consumer
     * @param y0 local Y coordinate of vertex 0
     * @param y1 local Y coordinate of vertex 1
     */
    private void emitStrip(VertexConsumer consumer, float y0, float y1) {
        float l0 = left(y0);
        float l1 = left(y1);
        float r0 = right(y0);
        float r1 = right(y1);
        float band0 = Math.min(EDGE, Math.max(0f, (r0 - l0) / 2f));
        float band1 = Math.min(EDGE, Math.max(0f, (r1 - l1) / 2f));
        float il0 = l0 + band0;
        float il1 = l1 + band1;
        float ir0 = r0 - band0;
        float ir1 = r1 - band1;
        quad(consumer, il0, y0, il1, y1, ir1, y1, ir0, y0, 1f, 1f, 1f, 1f);
        quad(consumer, Math.max(0f, l0 - EDGE), y0, Math.max(0f, l1 - EDGE), y1, il1, y1, il0, y0, 0f, 0f, 1f, 1f);
        quad(consumer, ir0, y0, ir1, y1, Math.min(width, r1 + EDGE), y1, Math.min(width, r0 + EDGE), y0, 1f, 1f, 0f, 0f);
    }

    /**
     * Samples the local rounded image left edge.
     *
     * @param y vertical coordinate in the method coordinate space
     */
    private float left(float y) {
        return corners.left(y, height);
    }

    /**
     * Samples the local rounded image right edge.
     *
     * @param y vertical coordinate in the method coordinate space
     */
    private float right(float y) {
        return corners.right(y, width, height);
    }

    /**
     * Emits a textured quad, correcting mirrored node geometry.
     *
     * @param consumer Minecraft GUI vertex consumer
     * @param x0 local X coordinate of vertex 0
     * @param y0 local Y coordinate of vertex 0
     * @param x1 local X coordinate of vertex 1
     * @param y1 local Y coordinate of vertex 1
     * @param x2 local X coordinate of vertex 2
     * @param y2 local Y coordinate of vertex 2
     * @param x3 local X coordinate of vertex 3
     * @param y3 local Y coordinate of vertex 3
     * @param c0 color or coverage at vertex 0
     * @param c1 color or coverage at vertex 1
     * @param c2 color or coverage at vertex 2
     * @param c3 color or coverage at vertex 3
     */
    private void quad(VertexConsumer consumer, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, float c0, float c1, float c2, float c3) {
        float determinant = transform.determinant();

        if (determinant < 0f) {
            vertex(consumer, x0, y0, c0);
            vertex(consumer, x3, y3, c3);
            vertex(consumer, x2, y2, c2);
            vertex(consumer, x1, y1, c1);
        } else {
            vertex(consumer, x0, y0, c0);
            vertex(consumer, x1, y1, c1);
            vertex(consumer, x2, y2, c2);
            vertex(consumer, x3, y3, c3);
        }
    }

    /**
     * Transforms an image vertex and assigns normalized UV coordinates and edge opacity.
     *
     * @param consumer Minecraft GUI vertex consumer
     * @param px local horizontal sample coordinate
     * @param py local vertical sample coordinate
     * @param coverage antialias coverage multiplier
     */
    private void vertex(VertexConsumer consumer, float px, float py, float coverage) {
        float top = clamp(py / EDGE, 0f, 1f);
        float bottom = clamp((height - py) / EDGE, 0f, 1f);
        int a8 = Math.round(alpha * coverage * Math.min(top, bottom));
        int color = (a8 << 24) | 0xFFFFFF;
        consumer.addVertexWith2DPose(pose, transform.x(px, py), transform.y(px, py)).setColor(color).setUv(px / width, py / height);
    }
}
