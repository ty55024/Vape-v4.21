package gg.vape.wrapper.impl;

import gg.vape.Vape;
import gg.vape.mapping.mappings.MRenderWorldLastEvent;
import gg.vape.wrapper.Wrapper;

public class RenderWorldLastEvent
extends Wrapper {

    private static boolean resolverFailureLogged;

    /**
     * 光影（OptiFine/shadersmod）的渲染分辨率倍率。1.8.9 + OptiFine 上该静态字段存在、
     * 但在没有加载光影包之前是 0.0；调用方（ESP2D 的 2D 框等）会用它做除数换算屏幕坐标，
     * 除以 0 得到 Infinity，框就被画到屏幕外（表现：ESP 2D 完全不显示，且没有任何报错，
     * 因为事件总线是空 catch）。这里把 0/非有限值统一当作 1.0（等价于未开光影分辨率缩放），
     * 读取失败（字段版本不同）也同样回退到 1.0。
     */
    public static float getRenderResolutionMultiplier() {
        if (!Vape.renderReady) {
            return 1.0f;
        }
        try {
            MRenderWorldLastEvent shadersConfig = RenderWorldLastEvent.vapeInstance.getMappingsMapperCompat().shadersConfig;
            if (shadersConfig == null) {
                return 1.0f;
            }
            float multiplier = MRenderWorldLastEvent.getRenderResolutionMultiplier(shadersConfig);
            if (!Float.isFinite(multiplier) || multiplier <= 0.0f) {
                return 1.0f;
            }
            return multiplier;
        }
        catch (Throwable throwable) {
            if (!resolverFailureLogged) {
                resolverFailureLogged = true;
                Vape.debugLog("[RenderWorldLastEvent] render resolution multiplier unavailable: " + throwable);
            }
            return 1.0f;
        }
    }

    public RenderWorldLastEvent(Object handle) {
        super(handle);
    }
}

