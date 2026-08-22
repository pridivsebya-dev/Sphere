package ru.rooyzee.elytrixaddonsphere;

import dev.by1337.bc.addon.AbstractAddon;
import dev.by1337.bc.animation.AnimationRegistry;
import org.bukkit.plugin.Plugin;
import org.by1337.blib.util.SpacedNameKey;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

public final class Main extends AbstractAddon {

    public static final String ANIMATION_KEY = "elytrix:sphere";
    private static final String RESOURCE_NAME = "sphereAnimation.yml";
    private static final String TARGET_DIR = "animations/sphere";

    @Override
    public void onEnable() {
        AnimationRegistry.INSTANCE.register(ANIMATION_KEY, SphereAnimator::new);
        saveDefaultAnimationFile();
    }

    @Override
    public void onDisable() {
        AnimationRegistry.INSTANCE.unregister(new SpacedNameKey(ANIMATION_KEY));
    }

    private void saveDefaultAnimationFile() {
        Plugin plugin = getPlugin();
        File dir = new File(plugin.getDataFolder(), TARGET_DIR);

        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Не удалось создать папку: " + dir.getAbsolutePath());
        }

        File targetFile = new File(dir, RESOURCE_NAME);
        if (targetFile.exists()) {
            return;
        }

        try (InputStream in = getResource(RESOURCE_NAME)) {
            if (in == null) {
                throw new IllegalStateException("Ресурс " + RESOURCE_NAME + " не найден внутри jar!");
            }
            Files.copy(in, targetFile.toPath());
        } catch (IOException e) {
            throw new RuntimeException("Не удалось сохранить " + RESOURCE_NAME, e);
        }
    }
}