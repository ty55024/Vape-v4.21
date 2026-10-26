package gg.vape.utils.render;

import gg.vape.Vape;
import gg.vape.utils.render.GuiRenderPrimitives;
import gg.vape.utils.render.PotionEffectIcon;
import gg.vape.utils.render.PotionEffectIconKey;
import gg.vape.utils.render.PotionEffectIconRenderBackend;
import gg.vape.utils.render.PotionEffectIconTexture;
import gg.vape.wrapper.impl.PotionEffect;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class PotionEffectIconRenderer {
    private static String legacyMarker;
    static HashMap<PotionEffectIconKey, PotionEffectIconRenderBackend> cache;
    /**
     * 待截图队列。HUD 阶段（EventRender2D）里做离屏截图在 1.21.11 / 26.1.2 上不生效：
     * 帧缓冲目标正确、精灵与 UV 也正确，但整张截图是全透明（批次绘制报 GL_INVALID_ENUM），
     * 而同样的绘制在开 GUI 后或世界渲染阶段就正常。所以 HUD 里只登记请求，
     * 真正的 capture 统一放到世界渲染阶段（EventRender3DCallback）执行。
     */
    private static final LinkedHashMap<Integer, PotionEffect> pendingCaptures = new LinkedHashMap<Integer, PotionEffect>();
    private static final HashMap<Integer, Integer> captureFailures = new HashMap<Integer, Integer>();
    private static final int MAX_WORLD_CAPTURE_ATTEMPTS = 8;
    private static final int MAX_CAPTURES_PER_FRAME = 4;

    public static void render(PotionEffect effect, float x, float y, int width, int height, float opacity, boolean worldSpace) {
        try {
            if (effect == null) {
                return;
            }
            int effectId = effect.C();
            PotionEffectIconKey cacheKey = new PotionEffectIconKey(effectId);
            if (!GuiRenderPrimitives.d()) {
                // 老版本（1.8.9/1.12.2）：立即模式截图本身可用，保持原逻辑。
                PotionEffectIconRenderer.ensureCached(effectId);
                PotionEffectIconRenderBackend legacyRenderer = cache.get(cacheKey);
                if (legacyRenderer != null) {
                    legacyRenderer.renderQueued(x, y, width, height, opacity, worldSpace);
                }
                return;
            }
            PotionEffectIconRenderBackend cachedRenderer = cache.get(cacheKey);
            if (cachedRenderer == null) {
                // 只登记请求；截图在世界阶段完成，下一帧起就能正常画出图标。
                if (!pendingCaptures.containsKey(effectId)) {
                    pendingCaptures.put(effectId, effect);
                }
                return;
            }
            cachedRenderer.renderQueued(x, y, width, height, opacity, worldSpace);
        }
        catch (Exception exception) {
            Vape.logThrowable(exception);
        }
    }

    /**
     * 在世界渲染阶段调用：执行 HUD 阶段登记的图标截图请求。
     * 截图失败（帧缓冲整张全透明）会留在队列里重试，超过上限后放弃，避免每帧重截的卡顿。
     */
    public static void flushPendingCaptures() {
        if (pendingCaptures.isEmpty()) {
            return;
        }
        ArrayList<Map.Entry<Integer, PotionEffect>> batch = new ArrayList<Map.Entry<Integer, PotionEffect>>(pendingCaptures.entrySet());
        int processed = 0;
        for (Map.Entry<Integer, PotionEffect> entry : batch) {
            if (processed >= MAX_CAPTURES_PER_FRAME) {
                break;
            }
            ++processed;
            int effectId = entry.getKey();
            PotionEffect effect = entry.getValue();
            try {
                PotionEffectIconKey cacheKey = new PotionEffectIconKey(effectId);
                PotionEffectIconRenderBackend renderer = cache.get(cacheKey);
                if (renderer == null) {
                    renderer = new PotionEffectIconTexture();
                    cache.put(cacheKey, renderer);
                }
                renderer.capture(effect);
                boolean captured = !(renderer instanceof PotionEffectIconTexture)
                        || ((PotionEffectIconTexture)renderer).hasContent();
                if (captured) {
                    pendingCaptures.remove(effectId);
                    captureFailures.remove(effectId);
                    continue;
                }
                int failures = captureFailures.containsKey(effectId) ? captureFailures.get(effectId) : 0;
                if (++failures >= MAX_WORLD_CAPTURE_ATTEMPTS) {
                    pendingCaptures.remove(effectId);
                    captureFailures.remove(effectId);
                    Vape.debugLog("[PotionIcon] capture gave up id=" + effectId);
                } else {
                    captureFailures.put(effectId, failures);
                }
            }
            catch (Throwable throwable) {
                pendingCaptures.remove(effectId);
                captureFailures.remove(effectId);
                Vape.logThrowable(throwable);
            }
        }
    }

    public static void ensureCached(int effectId) {
        PotionEffectIconKey cacheKey = new PotionEffectIconKey(effectId);
        if (!cache.containsKey(cacheKey)) {
            PotionEffectIconRenderer.createRenderer(PotionEffect.o(effectId, 100, 0), cacheKey);
        }
    }

    public static void setLegacyMarker(String legacyMarker) {
        PotionEffectIconRenderer.legacyMarker = legacyMarker;
    }

    public static String getLegacyMarker() {
        return legacyMarker;
    }

    public static void clear() {
        for (PotionEffectIconRenderBackend renderer : cache.values()) {
            renderer.dispose();
        }
        cache.clear();
        pendingCaptures.clear();
        captureFailures.clear();
    }

    public static void render(PotionEffect effect, float x, float y, int width, int height, float opacity) {
        PotionEffectIconRenderer.render(effect, x, y, width, height, opacity, false);
    }

    private static void createRenderer(PotionEffect effect, PotionEffectIconKey cacheKey) {
        PotionEffectIconRenderBackend renderer = GuiRenderPrimitives.d() ? new PotionEffectIconTexture() : new PotionEffectIcon();
        // 现代版本的离屏截图只在世界阶段生效（见 flushPendingCaptures），这里不截图；
        // 老版本走立即模式截图，在这里完成，失败也缓存，避免每帧重试造成卡顿。
        if (!GuiRenderPrimitives.d()) {
            try {
                renderer.capture(effect);
            }
            catch (Exception exception) {
                Vape.logThrowable(exception);
            }
        }
        cache.put(cacheKey, renderer);
    }

    static {
        cache = new HashMap();
        PotionEffectIconRenderer.setLegacyMarker(null);
    }
}
