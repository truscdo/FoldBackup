package com.foldbackup.mod.config;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * 处理 NeoForge 配置生命周期事件。
 * <p>
 * 注册在<b>模组事件总线</b>上（非 Forge 游戏总线）。
 * 在配置加载/重载时重新加载备份根路径，以便 bootstrapStorage() 获取任何更改。
 */
public class ConfigEventHandler {

    private final Runnable bootstrapStorage;

    public ConfigEventHandler(Runnable bootstrapStorage) {
        this.bootstrapStorage = bootstrapStorage;
    }

    @SubscribeEvent
    public void onConfigLoaded(ModConfigEvent.Loading event) {
        bootstrapStorage.run();
    }

    @SubscribeEvent
    public void onConfigReload(ModConfigEvent.Reloading event) {
        bootstrapStorage.run();
    }
}
