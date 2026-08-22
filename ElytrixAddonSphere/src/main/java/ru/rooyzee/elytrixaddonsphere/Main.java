package ru.rooyzee.elytrixaddonsphere;

import dev.by1337.bc.addon.AbstractAddon;
import dev.by1337.bc.animation.AnimationRegistry;
import org.bukkit.plugin.Plugin;
import org.by1337.blib.util.SpacedNameKey;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.logging.Level;

public final class Main extends AbstractAddon {

    public static final String ANIMATION_KEY = "elytrix:sphere";
    private static final String RESOURCE_NAME = "sphereAnimation.yml";
    private static final String TARGET_DIR = "animations/sphere";

    @Override
    public void onEnable() {
        AnimationRegistry.INSTANCE.register(ANIMATION_KEY, SphereAnimator::new);
        saveDefaultAnimationFile();
        getPlugin().getLogger().info("Анимация " + ANIMATION_KEY + " зарегистрирована");
    }

    @Override
    public void onDisable() {
        AnimationRegistry.INSTANCE.unregister(new SpacedNameKey(ANIMATION_KEY));
    }

    /**
     * Копирует дефолтный sphereAnimation.yml в animations/sphere/,
     * если его там ещё нет. Ошибка копирования не отключает аддон —
     * анимация умеет работать на встроенных значениях по умолчанию.
     */
    private void saveDefaultAnimationFile() {
        Plugin plugin = getPlugin();
        try {
            File dir = new File(plugin.getDataFolder(), TARGET_DIR);
            Files.createDirectories(dir.toPath());

            File targetFile = new File(dir, RESOURCE_NAME);
            if (targetFile.exists()) {
                return;
            }

            try (InputStream in = getResource(RESOURCE_NAME)) {
                if (in == null) {
                    plugin.getLogger().warning("Ресурс " + RESOURCE_NAME + " не найден внутри jar!");
                    return;
                }
                Files.copy(in, targetFile.toPath());
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось сохранить " + RESOURCE_NAME, e);
        }
    }
}
