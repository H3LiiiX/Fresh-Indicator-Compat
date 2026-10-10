package com.fresharmorbar.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws Fresh Armor Bar's armor icons above other players' heads (Health Indicator Next).
 *
 * <p>Why this does not just stack textured quads: the icon is made of several layers (frame, material,
 * trim, trim glow, enchant glint). In the world those layers can only be separated by a tiny depth
 * offset, and the depth buffer cannot resolve it, so the material often covered the trim and the glint.
 * Instead each icon is composited on the CPU exactly the way Fresh Armor Bar blends it on the HUD and is
 * then drawn as ONE layer of coloured pixel quads (text render type + a white texture), so there is nothing left to z-fight. The enchant glint
 * is Fresh Armor Bar's own glint (same texture, UV scale, rotation, scroll speed, two passes, additive
 * blend, masked by the armor silhouette) evaluated per pixel corner.
 */
public class CompatArmorRenderer {
    

    // ---- layout of the Fresh Armor Bar strips (3 x 9px: left half, right half, full) ----------------
    private static final int SEG_LEFT = 0;
    private static final int SEG_RIGHT = 9;
    private static final int SEG_FULL = 18;
    private static final int ICON = 9;
    private static final int MAX_HALVES = 60; // same size as Fresh Armor Bar's cache

    // ---- values copied from Fresh Armor Bar -------------------------------------------------------
    private static final int DEFAULT_LEATHER_COLOR = 0xA06540;
    private static final float DYE_DARKEN = 0.8f;
    private static final float TRIM_GLOW_ALPHA = 0.875f;
    private static final float GLINT_UV_SCALE = 0.025f;
    private static final float GLINT_TEXTURE_SCALE = 8.0f;
    private static final float GLINT_ROTATION = 0.17453292f;
    private static final float GLINT_COS = (float) Math.cos(GLINT_ROTATION) * GLINT_TEXTURE_SCALE;
    private static final float GLINT_SIN = (float) Math.sin(GLINT_ROTATION) * GLINT_TEXTURE_SCALE;
    private static final float GLINT_COLOR_MULTIPLIER = 0.85f;
    private static final int MASK_ALPHA_THRESHOLD = 16; // Fresh Armor Bar discards mask alpha <= 0.0625

    /**
     * Multiplier on top of Fresh Armor Bar's glint strength. 1.0f is the exact HUD look (it also follows the
     * vanilla Glint Strength / Glint Speed video settings). The icons are small and seen from a distance, so
     * if you want the glint to pop more, raise this a little (1.2f - 1.5f).
     */
    private static final float GLINT_BOOST = 1.0f;

    private static final Identifier GLINT_TEX =
            Identifier.fromNamespaceAndPath("minecraft", "textures/misc/enchanted_glint_item.png");

    private static final int LIGHT = 15728880;

    /**
     * 2x2 opaque white texture shipped with this mod. The pixel quads are drawn with the "text" render type
     * (which writes depth, so clouds, water and other translucent things can no longer paint over the icons)
     * and this texture simply lets the per-vertex colours through.
     */
    private static final Identifier WHITE_TEX =
            Identifier.fromNamespaceAndPath("fresh_indicator_compat", "textures/misc/white.png");

    // ---- caches ---------------------------------------------------------------------------------
    private static final Img MISSING = new Img(0, 0, new int[0]);
    private static final Map<Identifier, Img> IMAGES = new ConcurrentHashMap<>();
    private static final Map<Key, Composite> COMPOSITES = new ConcurrentHashMap<>();
    private static Object lastResourceManager = null;

    private static float[] glintField = null;
    private static long glintFieldMillis = Long.MIN_VALUE;

    /** What one half (one armor point) of the bar looks like. Equal looks get merged into a full icon. */
    private record Look(Identifier tex, int tint, int trimRgb, boolean trimGlow, boolean enchanted) {}

    /** Everything that decides how a single slot looks. */
    private record Key(Look left, Look right, boolean same, boolean bg) {}

    /** A finished 9x9 icon: final ARGB pixels plus which pixels get the enchant glint. */
    private static final class Composite {
        final int[] px = new int[ICON * ICON];
        final boolean[] glint = new boolean[ICON * ICON];
        boolean anyGlint = false;
    }

    private static final class Img {
        final int w, h;
        final int[] argb;

        Img(int w, int h, int[] argb) {
            this.w = w;
            this.h = h;
            this.argb = argb;
        }

        int get(int x, int y) {
            return (x < 0 || y < 0 || x >= w || y >= h) ? 0 : argb[y * w + x];
        }
    }

    // =============================================================================================
    //  Reading the wearer's armor
    // =============================================================================================

    private static Look[] getEntityArmorData(LivingEntity entity) {
        Look[] halves = new Look[MAX_HALVES];
        EquipmentSlot[] order = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        int half = 0;

        for (EquipmentSlot slot : order) {
            ItemStack stack = ModCompat.getArmorStack(entity.getItemBySlot(slot));
            if (stack.isEmpty()) continue;

            var modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
            int protection = 0;
            if (modifiers != null) {
                protection = (int) Math.round(modifiers.compute(Attributes.ARMOR, 0.0, slot));
            }
            if (protection <= 0) continue;

            Identifier tex = ArmorBarTextures.getMaterialTex(stack);
            if (tex == null || tex.equals(ArmorBarTextures.EMPTY_TEX)) {
                tex = ArmorBarTextures.id("textures/gui/armorbar/strips/iron.png");
            }

            // Dyed armor, and undyed leather which Fresh Armor Bar tints with the default leather brown.
            int tint = -1;
            var dyed = stack.get(DataComponents.DYED_COLOR);
            if (dyed != null || ArmorBarTextures.LEATHER_STRIP.equals(tex)) {
                tint = darken(dyed != null ? dyed.rgb() : DEFAULT_LEATHER_COLOR);
            }

            int trimRgb = -1;
            boolean trimGlow = false;
            var trim = stack.get(DataComponents.TRIM);
            if (trim != null) {
                // Same material name Fresh Armor Bar uses ("emerald", "gold", ...): last part of the palette id.
                String asset;
                try {
                    String palettePath = trim.material().value().paletteId().getPath();
                    asset = palettePath.substring(palettePath.lastIndexOf('/') + 1);
                } catch (Throwable t) {
                    var key = trim.material().unwrapKey();
                    asset = key.isPresent() ? key.get().identifier().getPath() : "iron";
                }
                trimRgb = ArmorBarTextures.trimRgb(asset);
                trimGlow = ArmorBarTextures.isGlowTrim(asset);
            }

            Look look = new Look(tex, tint, trimRgb, trimGlow, stack.hasFoil());
            for (int i = 0; i < protection && half < MAX_HALVES; i++) {
                halves[half++] = look;
            }
        }
        return halves;
    }

    private static int darken(int rgb) {
        int r = (int) (((rgb >> 16 & 255) / 255.0F * DYE_DARKEN) * 255.0F);
        int g = (int) (((rgb >> 8 & 255) / 255.0F * DYE_DARKEN) * 255.0F);
        int b = (int) (((rgb & 255) / 255.0F * DYE_DARKEN) * 255.0F);
        return r << 16 | g << 8 | b;
    }

    // =============================================================================================
    //  Entry point (called from ArmorIndicatorRendererMixin)
    // =============================================================================================

    public static void renderCompatIcons(LivingEntity entity, PoseStack poseStack, OrderedSubmitNodeCollector queue,
                                         double squaredDistance, float top, int displaySlots, int slotOffset,
                                         float spacing, float layerStep) {
        checkResourceReload();
        Look[] halves = getEntityArmorData(entity);

        for (int heartIndex = 0; heartIndex < displaySlots; ++heartIndex) {
            int absoluteIndex = slotOffset + heartIndex;
            int row = heartIndex / 10;
            int slotInRow = heartIndex % 10;
            int rowWidth = Math.min(10, displaySlots - row * 10);
            float maxX = ((float) rowWidth * spacing + 1.0F) / 2.0F;
            float x = maxX - (float) slotInRow * spacing;
            float rowTop = top + (float) row * 10.0F;

            int leftHalf = absoluteIndex * 2;
            int rightHalf = absoluteIndex * 2 + 1;
            Look left = leftHalf >= 0 && leftHalf < MAX_HALVES ? halves[leftHalf] : null;
            Look right = rightHalf >= 0 && rightHalf < MAX_HALVES ? halves[rightHalf] : null;

            boolean hasPart = left != null || right != null;
            // Fresh Armor Bar: the empty frame is always there on the first row, and on later rows only under armor.
            boolean bg = row == 0 || hasPart;
            if (!hasPart && !bg) continue;

            boolean same = left != null && right != null && left.equals(right);
            drawSlot(poseStack, queue, x, rowTop, layerStep, new Key(left, same ? left : right, same, bg));
        }
    }

    // =============================================================================================
    //  Compositing (mirrors ArmorBarRenderer.drawPart / drawSide in Fresh Armor Bar)
    // =============================================================================================

    private static void checkResourceReload() {
        Object manager = Minecraft.getInstance().getResourceManager();
        if (manager != lastResourceManager) {
            IMAGES.clear();
            COMPOSITES.clear();
            glintField = null;
            glintFieldMillis = Long.MIN_VALUE;
            lastResourceManager = manager;
        }
    }

    private static Img image(Identifier id) {
        Img cached = IMAGES.get(id);
        if (cached != null) return cached == MISSING ? null : cached;

        Img loaded = null;
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(id);
            if (resource.isPresent()) {
                try (InputStream in = resource.get().open()) {
                    BufferedImage bi = ImageIO.read(in);
                    if (bi != null) {
                        int w = bi.getWidth(), h = bi.getHeight();
                        int[] argb = new int[w * h];
                        for (int y = 0; y < h; y++) {
                            for (int x = 0; x < w; x++) argb[y * w + x] = bi.getRGB(x, y);
                        }
                        loaded = new Img(w, h, argb);
                    }
                }
            }
        } catch (Throwable t) {
            System.out.printf("Could not read %s: %s\n", id, t.toString());
        }
        if (loaded == null) System.out.printf("Texture %s is not available; falling back to a plain icon.\n", id);
        IMAGES.put(id, loaded == null ? MISSING : loaded);
        return loaded;
    }

    private static Composite composite(Key key) {
        Composite c = COMPOSITES.get(key);
        if (c != null) return c;
        c = build(key);
        if (c == null) return null; // texture unavailable -> caller draws the fallback
        if (COMPOSITES.size() > 512) COMPOSITES.clear();
        COMPOSITES.put(key, c);
        return c;
    }

    private static Composite build(Key key) {
        Composite c = new Composite();

        if (key.bg) {
            Img empty = image(ArmorBarTextures.EMPTY_TEX);
            if (empty != null) blit(c.px, empty, 0, -1, 1.0f);
        }

        if (key.same) {
            if (!drawSide(c.px, key.left, SEG_FULL)) return null;
        } else {
            if (key.left != null && !drawSide(c.px, key.left, SEG_LEFT)) return null;
            if (key.right != null && !drawSide(c.px, key.right, SEG_RIGHT)) return null;
        }

        // Glint mask: like the shader, pixels left of x=4.5 use the left half's silhouette and the rest the
        // right half's. Only pixels that are really part of an enchanted piece get the glint.
        for (int r = 0; r < ICON; r++) {
            for (int col = 0; col < ICON; col++) {
                Look owner;
                int seg;
                if (key.same) {
                    owner = key.left;
                    seg = SEG_FULL;
                } else if (col < 4) {
                    owner = key.left;
                    seg = SEG_LEFT;
                } else if (col > 4) {
                    owner = key.right;
                    seg = SEG_RIGHT;
                } else if (key.right != null && coverage(key.right, SEG_RIGHT, col, r)) {
                    owner = key.right;
                    seg = SEG_RIGHT;
                } else {
                    owner = key.left;
                    seg = SEG_LEFT;
                }
                if (owner != null && owner.enchanted && coverage(owner, seg, col, r)) {
                    c.glint[r * ICON + col] = true;
                    c.anyGlint = true;
                }
            }
        }
        return c;
    }

    private static boolean coverage(Look look, int seg, int col, int row) {
        Img strip = image(look.tex);
        return strip != null && (strip.get(seg + col, row) >>> 24) > MASK_ALPHA_THRESHOLD;
    }

    /** material -> trim -> trim glow, like ArmorBarRenderer.drawSide. */
    private static boolean drawSide(int[] px, Look look, int seg) {
        Img strip = image(look.tex);
        if (strip == null) return false;
        blit(px, strip, seg, look.tint, 1.0f);

        if (look.trimRgb != -1) {
            Img trim = image(ArmorBarTextures.TRIM_BASE);
            if (trim != null) blit(px, trim, seg, look.trimRgb, 1.0f);
            if (look.trimGlow) {
                Img glow = image(ArmorBarTextures.TRIM_GLOW_TEX);
                if (glow != null) blit(px, glow, seg, -1, TRIM_GLOW_ALPHA);
            }
        }
        return true;
    }

    /** Alpha-blends one 9x9 slice of {@code src} (starting at column {@code seg}) onto {@code dst}. */
    private static void blit(int[] dst, Img src, int seg, int tint, float alphaMul) {
        float tr = tint == -1 ? 1f : (tint >> 16 & 255) / 255f;
        float tg = tint == -1 ? 1f : (tint >> 8 & 255) / 255f;
        float tb = tint == -1 ? 1f : (tint & 255) / 255f;
        for (int r = 0; r < ICON; r++) {
            for (int col = 0; col < ICON; col++) {
                int s = src.get(seg + col, r);
                int sa = Math.round((s >>> 24) * alphaMul);
                if (sa <= 0) continue;
                int sr = Math.round((s >> 16 & 255) * tr);
                int sg = Math.round((s >> 8 & 255) * tg);
                int sb = Math.round((s & 255) * tb);
                dst[r * ICON + col] = over(dst[r * ICON + col], sa << 24 | sr << 16 | sg << 8 | sb);
            }
        }
    }

    /** Standard (non-premultiplied) "source over destination". */
    private static int over(int dst, int src) {
        int sa = src >>> 24;
        if (sa == 255) return src;
        if (sa == 0) return dst;
        int da = dst >>> 24;
        if (da == 0) return src;
        float s = sa / 255f, d = da / 255f * (1f - s);
        float oa = s + d;
        int r = Math.round(((src >> 16 & 255) * s + (dst >> 16 & 255) * d) / oa);
        int g = Math.round(((src >> 8 & 255) * s + (dst >> 8 & 255) * d) / oa);
        int b = Math.round(((src & 255) * s + (dst & 255) * d) / oa);
        return Math.round(oa * 255f) << 24 | r << 16 | g << 8 | b;
    }

    // =============================================================================================
    //  Enchant glint (port of ArmorBarGlintRenderer's maths)
    // =============================================================================================

    private static Img glintImage() {
        return image(GLINT_TEX);
    }

    /**
     * Light that the glint adds on top of the icon, for each of the 10x10 pixel corners of an icon.
     * Every icon shares it, exactly like the HUD where each icon uses its own local coordinates.
     * Layout: (y * 10 + x) * 3 + {r,g,b}. A new array is made per update because queued draw calls keep
     * a reference to the one they were submitted with.
     */
    private static float[] currentGlint() {
        long millis = Util.getMillis();
        if (glintField != null && millis == glintFieldMillis) return glintField;

        var options = Minecraft.getInstance().options;
        double speed = options.glintSpeed().get();
        double strength = options.glintStrength().get();

        long time = (long) (millis * speed * 8.0D);
        float translateU = -((time % 110000L) / 110000.0f);
        float translateV = (time % 30000L) / 30000.0f;
        float colorScale = (float) (GLINT_COLOR_MULTIPLIER * strength) * GLINT_BOOST;

        Img glint = glintImage();
        float[] field = new float[(ICON + 1) * (ICON + 1) * 3];
        float[] sample = new float[4];

        for (int y = 0; y <= ICON; y++) {
            for (int x = 0; x <= ICON; x++) {
                float r = 0, g = 0, b = 0;
                for (int pass = 0; pass < 2; pass++) {
                    float offset = pass * 0.5f;
                    float u = (x / (float) ICON) * GLINT_UV_SCALE + offset;
                    float v = offset + (y / (float) ICON) * GLINT_UV_SCALE;
                    float gu = u * GLINT_COS - v * GLINT_SIN + translateU;
                    float gv = u * GLINT_SIN + v * GLINT_COS + translateV;
                    sampleGlint(glint, gu, gv, sample);
                    if (sample[3] < 0.1f) continue; // the shader discards these
                    float sr = sample[0] * colorScale, sg = sample[1] * colorScale, sb = sample[2] * colorScale;
                    // GLINT blend function: result = dst + src * src
                    r += sr * sr;
                    g += sg * sg;
                    b += sb * sb;
                }
                int i = (y * (ICON + 1) + x) * 3;
                field[i] = r;
                field[i + 1] = g;
                field[i + 2] = b;
            }
        }
        glintField = field;
        glintFieldMillis = millis;
        return field;
    }

    /** Bilinear, wrapping sample of the vanilla glint texture (procedural stripes if it cannot be read). */
    private static void sampleGlint(Img img, float u, float v, float[] out) {
        if (img == null) {
            float t = (u + v) * 6.2831855f * 2.0f;
            float s = 0.5f + 0.5f * (float) Math.sin(t);
            s *= s;
            out[0] = 0.62f * s;
            out[1] = 0.35f * s;
            out[2] = 1.0f * s;
            out[3] = 1.0f;
            return;
        }
        float fu = u - (float) Math.floor(u);
        float fv = v - (float) Math.floor(v);
        float px = fu * img.w - 0.5f, py = fv * img.h - 0.5f;
        int x0 = (int) Math.floor(px), y0 = (int) Math.floor(py);
        float tx = px - x0, ty = py - y0;
        int x1 = Math.floorMod(x0 + 1, img.w), y1 = Math.floorMod(y0 + 1, img.h);
        x0 = Math.floorMod(x0, img.w);
        y0 = Math.floorMod(y0, img.h);
        int c00 = img.argb[y0 * img.w + x0], c10 = img.argb[y0 * img.w + x1];
        int c01 = img.argb[y1 * img.w + x0], c11 = img.argb[y1 * img.w + x1];
        for (int ch = 0; ch < 4; ch++) {
            int shift = ch == 3 ? 24 : 16 - ch * 8;
            float top = (c00 >>> shift & 255) * (1 - tx) + (c10 >>> shift & 255) * tx;
            float bottom = (c01 >>> shift & 255) * (1 - tx) + (c11 >>> shift & 255) * tx;
            out[ch] = (top * (1 - ty) + bottom * ty) / 255f;
        }
    }

    // =============================================================================================
    //  Drawing
    // =============================================================================================

    private static void drawSlot(PoseStack poseStack, OrderedSubmitNodeCollector queue, float x, float top,
                                 float z, Key key) {
        Composite c = composite(key);
        if (c == null) {
            drawFallback(poseStack, queue, x, top, z, key);
            return;
        }
        final float[] glint = c.anyGlint ? currentGlint() : null;
        queue.submitCustomGeometry(poseStack, RenderTypes.text(WHITE_TEX),
                (pose, vertexConsumer) -> emit(pose.pose(), vertexConsumer, x, top, z, c, glint));
    }

    /**
     * Texture column {@code col} sits at local x in [x - col - 1, x - col] (the heart stack is mirrored in X),
     * texture row {@code row} at local y in [top - row - 1, top - row]. Vertex order and winding match
     * Health Indicator Next's own quads.
     */
    private static void emit(Matrix4f m, VertexConsumer vc, float x, float top, float z, Composite c, float[] glint) {
        for (int row = 0; row < ICON; row++) {
            int col = 0;
            while (col < ICON) {
                int argb = c.px[row * ICON + col];
                if ((argb >>> 24) == 0) {
                    col++;
                    continue;
                }
                float yT = top - row, yB = yT - 1.0F;
                float a = (argb >>> 24) / 255f;
                float r = (argb >> 16 & 255) / 255f, g = (argb >> 8 & 255) / 255f, b = (argb & 255) / 255f;

                if (glint != null && c.glint[row * ICON + col]) {
                    // one quad per pixel, colours taken at its four corners so the glint stays smooth
                    float xr = x - col, xl = xr - 1.0F;
                    vertex(vc, m, xl, yT, z, r, g, b, a, glint, col + 1, row);
                    vertex(vc, m, xr, yT, z, r, g, b, a, glint, col, row);
                    vertex(vc, m, xr, yB, z, r, g, b, a, glint, col, row + 1);
                    vertex(vc, m, xl, yB, z, r, g, b, a, glint, col + 1, row + 1);
                    col++;
                    continue;
                }

                int end = col + 1; // merge a run of identical, glint-free pixels into one quad
                while (end < ICON && c.px[row * ICON + end] == argb && !(glint != null && c.glint[row * ICON + end])) {
                    end++;
                }
                float xr = x - col, xl = x - end;
                vc.addVertex(m, xl, yT, z).setColor(r, g, b, a).setUv(0.5F, 0.5F).setLight(LIGHT);
                vc.addVertex(m, xr, yT, z).setColor(r, g, b, a).setUv(0.5F, 0.5F).setLight(LIGHT);
                vc.addVertex(m, xr, yB, z).setColor(r, g, b, a).setUv(0.5F, 0.5F).setLight(LIGHT);
                vc.addVertex(m, xl, yB, z).setColor(r, g, b, a).setUv(0.5F, 0.5F).setLight(LIGHT);
                col = end;
            }
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, float px, float py, float z,
                               float r, float g, float b, float a, float[] glint, int cornerX, int cornerY) {
        int i = (cornerY * (ICON + 1) + cornerX) * 3;
        vc.addVertex(m, px, py, z)
                .setColor(Math.min(1f, r + glint[i]), Math.min(1f, g + glint[i + 1]), Math.min(1f, b + glint[i + 2]), a)
                .setUv(0.5F, 0.5F)
                .setLight(LIGHT);
    }

    // =============================================================================================
    //  Fallback: only used when a texture cannot be read. Plain textured icon, no glint.
    // =============================================================================================

    private static void drawFallback(PoseStack poseStack, OrderedSubmitNodeCollector queue, float x, float top,
                                     float z, Key key) {
        if (key.bg) quad(poseStack, queue, x, top, z, 0, 9, -1, ArmorBarTextures.EMPTY_TEX);
        if (key.same) {
            fallbackSide(poseStack, queue, x, top, z, key.left, SEG_FULL);
        } else {
            if (key.left != null) fallbackSide(poseStack, queue, x, top, z, key.left, SEG_LEFT);
            if (key.right != null) fallbackSide(poseStack, queue, x, top, z, key.right, SEG_RIGHT);
        }
    }

    private static void fallbackSide(PoseStack poseStack, OrderedSubmitNodeCollector queue, float x, float top,
                                     float z, Look look, int seg) {
        float step = 0.4f; // large on purpose: far bigger than the depth buffer resolution
        quad(poseStack, queue, x, top, z + step, seg, 27, look.tint, look.tex);
        if (look.trimRgb != -1) {
            quad(poseStack, queue, x, top, z + step * 2, seg, 27, look.trimRgb, ArmorBarTextures.TRIM_BASE);
            if (look.trimGlow) {
                quad(poseStack, queue, x, top, z + step * 3, seg, 27, -1, ArmorBarTextures.TRIM_GLOW_TEX);
            }
        }
    }

    private static void quad(PoseStack poseStack, OrderedSubmitNodeCollector queue, float x, float top, float z,
                             int uOffset, int texWidth, int tint, Identifier texture) {
        float r = tint == -1 ? 1f : (tint >> 16 & 255) / 255f;
        float g = tint == -1 ? 1f : (tint >> 8 & 255) / 255f;
        float b = tint == -1 ? 1f : (tint & 255) / 255f;
        queue.submitCustomGeometry(poseStack, RenderTypes.text(texture), (pose, vc) -> {
            Matrix4f m = pose.pose();
            float bottom = top - 9.0F;
            float minU = (float) uOffset / (float) texWidth;
            float maxU = (float) (uOffset + 9) / (float) texWidth;
            vc.addVertex(m, x - 9.0F, top, z).setColor(r, g, b, 1f).setUv(maxU, 0.0F).setLight(LIGHT);
            vc.addVertex(m, x, top, z).setColor(r, g, b, 1f).setUv(minU, 0.0F).setLight(LIGHT);
            vc.addVertex(m, x, bottom, z).setColor(r, g, b, 1f).setUv(minU, 1.0F).setLight(LIGHT);
            vc.addVertex(m, x - 9.0F, bottom, z).setColor(r, g, b, 1f).setUv(maxU, 1.0F).setLight(LIGHT);
        });
    }
}