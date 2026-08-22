package ru.rooyzee.elytrixaddonsphere;

import blib.com.mojang.datafixers.util.Pair;
import blib.com.mojang.serialization.Codec;
import blib.com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.by1337.bc.CaseBlock;
import dev.by1337.bc.animation.AbstractAnimation;
import dev.by1337.bc.animation.AnimationContext;
import dev.by1337.bc.prize.Prize;
import dev.by1337.bc.prize.PrizeSelector;
import dev.by1337.bc.yaml.CashedYamlContext;
import dev.by1337.virtualentity.api.virtual.VirtualEntity;
import dev.by1337.virtualentity.api.virtual.item.VirtualItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.by1337.blib.geom.Vec3d;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SphereAnimator extends AbstractAnimation {

    private final Prize winner;
    private final Config config;
    private final Vec3d center;
    private final Vec3d ringCenter;
    private final Particle trailParticle;
    private final Particle itemParticle;
    private final Particle.DustOptions dustOptions;
    private final Sound spawnSound;
    private final Sound winSound;

    private final List<VirtualItem> activeItems = new ArrayList<>();
    private VirtualItem winnerItem;

    private double orbitAngle = 0.0D;
    private double gamma = 0.0D;
    private double ringRadius;
    private double tilt = 0.0D;
    private double wavePhase = 0.0D;

    private int trailTick;
    private int swirlTick;
    private double swirlAngle = 0.0D;

    public SphereAnimator(
            CaseBlock caseBlock,
            AnimationContext context,
            Runnable onEndCallback,
            PrizeSelector prizeSelector,
            CashedYamlContext config,
            Player player
    ) {
        super(caseBlock, context, onEndCallback, prizeSelector, config, player);

        Prize selected = prizeSelector.getRandomPrize();
        if (selected == null) {
            throw new IllegalStateException("No prize available");
        }
        this.winner = selected;
        this.center = new Vec3d(this.blockPos).add(0.5D, 0.5D, 0.5D);

        this.config = config.get("settings", v -> {
            try {
                return ((Pair<Config, ?>) v.decode(Config.CODEC).getOrThrow()).getFirst();
            } catch (Throwable t) {
                return new Config();
            }
        }, new Config());

        this.ringRadius = this.config.radius;
        this.ringCenter = this.center.add(0.0D, this.config.height, 0.0D);

        this.dustOptions = new Particle.DustOptions(
                Color.fromRGB(
                        clamp(this.config.red),
                        clamp(this.config.green),
                        clamp(this.config.blue)
                ),
                0.5F
        );

        this.trailParticle = parseParticle(this.config.particleVector, Particle.FLAME);
        this.itemParticle = parseParticle(this.config.particleItems, Particle.FLAME);
        this.spawnSound = parseSound(this.config.timings.spawnSound, Sound.BLOCK_NOTE_BLOCK_PLING);
        this.winSound = parseSound(this.config.timings.winSound, Sound.ENTITY_PLAYER_LEVELUP);
    }

    @Override
    protected void onStart() {
        caseBlock.hideHologram();
        caseBlock.hideBlock();
    }

    @Override
    protected void animate() throws InterruptedException {
        int count = Math.max(4, Math.min(24, config.itemCount));
        int winnerIdx = RANDOM.nextInt(count);

        double[] slot = new double[count];
        for (int i = 0; i < count; i++) {
            slot[i] = (2.0D * Math.PI * i) / count;
        }

        List<VirtualItem> items = new ArrayList<>(count);

        try {
            ascent(count, winnerIdx, slot, items);
            orbit(count, items, slot);
            converge(count, items, slot);
            showcase(count, winnerIdx, items, slot);
        } finally {
            cleanup();
        }
    }

    @Override
    protected void onEnd() {
        cleanup();
        caseBlock.showHologram();
        caseBlock.showBlock();
        caseBlock.givePrize(winner, player);
    }

    @Override
    protected void onClick(VirtualEntity entity, Player player) {
    }

    @Override
    public void onInteract(PlayerInteractEvent event) {
    }

    private void ascent(int count, int winnerIdx, double[] slot, List<VirtualItem> items) throws InterruptedException {
        int steps = config.timings.ascentSteps;
        int gap = config.timings.ascentGap;
        int total = (count - 1) * gap + steps;

        int[] progress = new int[count];
        VirtualItem[] spawned = new VirtualItem[count];

        for (int t = 0; t < total; t++) {
            for (int i = 0; i < count; i++) {
                if (t < i * gap) continue;

                if (spawned[i] == null) {
                    Prize prize = (i == winnerIdx) ? winner : safePrize();
                    VirtualItem item = VirtualItem.create();
                    item.setItem(prize.itemStack());
                    item.setPos(center);
                    item.setNoGravity(true);
                    item.setNoMotion();

                    trackEntity(item);
                    activeItems.add(item);
                    items.add(item);
                    spawned[i] = item;

                    boolean named = applyName(item);
                    item.setCustomNameVisible(named && config.showItemNames != 0);

                    if (i == winnerIdx) {
                        winnerItem = item;
                    }

                    float pitch = 0.55F + 0.6F * ((float) i / count);
                    playSound(center, spawnSound, 0.3F, pitch);
                }

                progress[i]++;
                if (progress[i] > steps) continue;

                double k = smoothStep((double) progress[i] / steps);
                double ang = slot[i] - 0.9D * (1.0D - k);
                double r = config.radius * k;
                double y = config.height * k + 0.1D * Math.sin(Math.PI * k);

                spawned[i].setPos(new Vec3d(
                        center.x + Math.cos(ang) * r,
                        center.y + y,
                        center.z + Math.sin(ang) * r
                ));
            }
            sleepTicks(1L);
        }
    }

    private void orbit(int count, List<VirtualItem> items, double[] slot) throws InterruptedException {
        int ticks = config.timings.orbitTicks;
        double baseRadius = config.radius;
        double tiltMax = Math.toRadians(config.precession);

        for (int t = 0; t < ticks; t++) {
            double p = (double) t / ticks;
            double sm = smoothStep(p);

            ringRadius = baseRadius * (1.0D - 0.12D * sm);
            double cycle = (2.0D * Math.PI * t) / config.timings.tiltCycleTicks;
            tilt = tiltMax * (0.5D - 0.5D * Math.cos(cycle));
            double waveAmp = 0.15D * smoothStep(Math.min(1.0D, t / 15.0D));

            for (int i = 0; i < count; i++) {
                items.get(i).setPos(ringPos(i, count, slot, waveAmp));
            }

            drawTrail(items);

            orbitAngle += Math.toRadians(config.rotationSpeed * (0.75D + 0.5D * sm));
            gamma += Math.toRadians(0.8D);
            wavePhase += 0.1D;

            sleepTicks(1L);
        }
    }

    private void converge(int count, List<VirtualItem> items, double[] slot) throws InterruptedException {
        int ticks = config.timings.convergenceTicks;
        double startRadius = ringRadius;
        double startTilt = tilt;

        for (int t = 0; t < ticks; t++) {
            double p = (double) t / ticks;
            double sm = smoothStep(p);

            ringRadius = startRadius * (1.0D - 0.86D * sm);
            tilt = startTilt * (1.0D - sm);
            double waveAmp = 0.15D * (ringRadius / startRadius);

            for (int i = 0; i < count; i++) {
                items.get(i).setPos(ringPos(i, count, slot, waveAmp));
            }

            drawTrail(items);

            orbitAngle += Math.toRadians(config.rotationSpeed * (1.0D + 2.2D * sm));
            gamma += Math.toRadians(1.2D);
            wavePhase += 0.15D * (1.0D + sm);

            sleepTicks(1L);
        }

        playSound(ringCenter, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5F, 0.7F);
    }

    private void showcase(int count, int winnerIdx, List<VirtualItem> items, double[] slot) throws InterruptedException {
        int ticks = config.timings.winnerTicks;

        Vec3d[] start = new Vec3d[count];
        for (int i = 0; i < count; i++) {
            start[i] = ringPos(i, count, slot, 0.0D);
        }

        Vec3d top = ringCenter.add(0.0D, 0.45D, 0.0D);
        List<VirtualItem> losers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (i != winnerIdx) losers.add(items.get(i));
        }

        int gatherTicks = 4;
        int removalStart = gatherTicks + 1;
        int removalsPerTick = 2;

        for (int t = 0; t < ticks; t++) {
            if (t <= gatherTicks) {
                double k = smoothStep((double) t / gatherTicks);
                for (int i = 0; i < count; i++) {
                    if (i == winnerIdx) continue;
                    items.get(i).setPos(lerpVec(start[i], ringCenter, k));
                }
                winnerItem.setPos(lerpVec(start[winnerIdx], top, smoothStep((double) t / gatherTicks)));
            }

            if (t >= removalStart) {
                for (int r = 0; r < removalsPerTick && !losers.isEmpty(); r++) {
                    VirtualItem loser = losers.remove(0);
                    poof(loser.getPos());
                    removeAndForget(loser);
                }
            }

            if (t == removalStart + 2) {
                playSound(top, winSound, 0.8F, 1.0F);
                burst(top);
            }

            if (t > gatherTicks) {
                int bt = t - gatherTicks;
                double ramp = Math.min(1.0D, bt / 6.0D);
                double bob = 0.1D * Math.sin(0.13D * bt) * ramp;
                winnerItem.setPos(top.add(0.0D, bob, 0.0D));
                drawSwirl(top);
            }

            sleepTicks(1L);
        }

        removeAndForget(winnerItem);
        winnerItem = null;
    }

    private Vec3d ringPos(int i, int count, double[] slot, double waveAmp) {
        double ang = slot[i] + orbitAngle;
        double r = ringRadius;
        double y = waveAmp * Math.sin(wavePhase + (2.0D * Math.PI * i) / count);

        double x = Math.cos(ang) * r;
        double z = Math.sin(ang) * r;

        double cosT = Math.cos(tilt);
        double sinT = Math.sin(tilt);
        double y1 = y * cosT - z * sinT;
        double z1 = y * sinT + z * cosT;

        double cosG = Math.cos(gamma);
        double sinG = Math.sin(gamma);
        double x2 = x * cosG + z1 * sinG;
        double z2 = -x * sinG + z1 * cosG;

        return new Vec3d(ringCenter.x + x2, ringCenter.y + y1, ringCenter.z + z2);
    }

    private void drawTrail(List<VirtualItem> items) {
        if (config.timings.trailPeriod <= 0) return;
        if (trailTick++ % config.timings.trailPeriod != 0) return;

        for (VirtualItem item : items) {
            Vec3d pos = item.getPos();
            if (pos == null) continue;
            if (isRedstone(trailParticle)) {
                spawnParticle(Particle.REDSTONE, pos, 0, 0.0D, 0.0D, 0.0D, 0.0D, dustOptions);
            } else {
                spawnParticle(trailParticle, pos, 0, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    private void drawSwirl(Vec3d top) {
        if (swirlTick++ % 3 != 0) return;

        for (int k = 0; k < 2; k++) {
            swirlAngle += 0.55D;
            double y = 0.45D * Math.sin(swirlAngle * 0.5D);
            Vec3d pos = top.add(
                    Math.cos(swirlAngle) * 0.65D,
                    y,
                    Math.sin(swirlAngle) * 0.65D
            );
            if (isRedstone(itemParticle)) {
                spawnParticle(Particle.REDSTONE, pos, 0, 0.0D, 0.0D, 0.0D, 0.0D, dustOptions);
            } else {
                spawnParticle(itemParticle, pos, 0, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    private void poof(Vec3d pos) {
        if (pos == null) return;
        if (isRedstone(trailParticle)) {
            spawnParticle(Particle.REDSTONE, pos, 6, 0.22D, 0.22D, 0.22D, 0.0D, dustOptions);
        } else {
            spawnParticle(trailParticle, pos, 6, 0.22D, 0.22D, 0.22D, 0.0D);
        }
    }

    private void burst(Vec3d pos) {
        if (isRedstone(itemParticle)) {
            spawnParticle(Particle.REDSTONE, pos, 40, 0.5D, 0.5D, 0.5D, 0.0D, dustOptions);
        } else {
            spawnParticle(itemParticle, pos, 40, 0.5D, 0.5D, 0.5D, 0.0D);
        }
    }

    private static Method paperDisplayName;
    private static boolean paperDisplayNameResolved;

    private boolean applyName(VirtualItem item) {
        try {
            ItemStack stack = item.getItem();
            if (stack == null) return false;
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return false;

            Component name = paperName(meta);
            if (name == null && meta.hasDisplayName()) {
                name = legacyToComponent(meta.getDisplayName());
            }
            if (name == null) return false;

            item.setCustomName(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Component paperName(ItemMeta meta) {
        try {
            if (!paperDisplayNameResolved) {
                paperDisplayNameResolved = true;
                try {
                    paperDisplayName = meta.getClass().getMethod("displayName");
                } catch (Throwable ignored) {
                    paperDisplayName = null;
                }
            }
            if (paperDisplayName == null) return null;
            Object name = paperDisplayName.invoke(meta);
            return name instanceof Component ? (Component) name : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final Map<Character, NamedTextColor> LEGACY_COLORS = buildLegacyColors();

    private static Map<Character, NamedTextColor> buildLegacyColors() {
        Map<Character, NamedTextColor> m = new HashMap<>();
        m.put('0', NamedTextColor.BLACK);
        m.put('1', NamedTextColor.DARK_BLUE);
        m.put('2', NamedTextColor.DARK_GREEN);
        m.put('3', NamedTextColor.DARK_AQUA);
        m.put('4', NamedTextColor.DARK_RED);
        m.put('5', NamedTextColor.DARK_PURPLE);
        m.put('6', NamedTextColor.GOLD);
        m.put('7', NamedTextColor.GRAY);
        m.put('8', NamedTextColor.DARK_GRAY);
        m.put('9', NamedTextColor.BLUE);
        m.put('a', NamedTextColor.GREEN);
        m.put('b', NamedTextColor.AQUA);
        m.put('c', NamedTextColor.RED);
        m.put('d', NamedTextColor.LIGHT_PURPLE);
        m.put('e', NamedTextColor.YELLOW);
        m.put('f', NamedTextColor.WHITE);
        return m;
    }

    private static Component legacyToComponent(String legacy) {
        if (legacy == null || legacy.isEmpty()) return null;

        Component result = Component.empty();
        StringBuilder buf = new StringBuilder();
        NamedTextColor color = null;
        boolean bold = false, italic = false, underlined = false, strikethrough = false, obfuscated = false;

        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            if (c == '§' && i + 1 < legacy.length()) {
                char code = Character.toLowerCase(legacy.charAt(++i));
                NamedTextColor newColor = LEGACY_COLORS.get(code);
                if (newColor != null || code == 'r' || "klmno".indexOf(code) >= 0) {
                    if (buf.length() > 0) {
                        result = appendStyled(result, buf.toString(), color, bold, italic, underlined, strikethrough, obfuscated);
                        buf.setLength(0);
                    }
                    if (newColor != null) {
                        color = newColor;
                    } else if (code == 'r') {
                        color = null;
                        bold = italic = underlined = strikethrough = obfuscated = false;
                    } else if (code == 'k') obfuscated = true;
                    else if (code == 'l') bold = true;
                    else if (code == 'm') strikethrough = true;
                    else if (code == 'n') underlined = true;
                    else if (code == 'o') italic = true;
                    continue;
                }
            }
            buf.append(c);
        }

        if (buf.length() > 0) {
            result = appendStyled(result, buf.toString(), color, bold, italic, underlined, strikethrough, obfuscated);
        }

        return result;
    }

    private static Component appendStyled(Component parent, String text, NamedTextColor color,
                                           boolean bold, boolean italic, boolean underlined,
                                           boolean strikethrough, boolean obfuscated) {
        Component c = Component.text(text);
        if (color != null) c = c.color(color);
        if (bold) c = c.decoration(TextDecoration.BOLD, true);
        if (italic) c = c.decoration(TextDecoration.ITALIC, true);
        if (underlined) c = c.decoration(TextDecoration.UNDERLINED, true);
        if (strikethrough) c = c.decoration(TextDecoration.STRIKETHROUGH, true);
        if (obfuscated) c = c.decoration(TextDecoration.OBFUSCATED, true);
        return parent.append(c);
    }

    private Vec3d lerpVec(Vec3d a, Vec3d b, double t) {
        return new Vec3d(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t
        );
    }

    private double smoothStep(double t) {
        return t * t * (3.0D - 2.0D * t);
    }

    private Prize safePrize() {
        Prize p = prizeSelector.getRandomPrize();
        return p != null ? p : winner;
    }

    private void removeAndForget(VirtualItem item) {
        if (item == null) return;
        activeItems.remove(item);
        try {
            removeEntity(item);
        } catch (Throwable ignored) {
        }
    }

    private void cleanup() {
        for (VirtualItem item : new ArrayList<>(activeItems)) {
            try {
                removeEntity(item);
            } catch (Throwable ignored) {
            }
        }
        activeItems.clear();
    }

    private Particle parseParticle(String name, Particle fallback) {
        if (name == null || name.trim().isEmpty()) return fallback;
        try {
            return Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private Sound parseSound(String name, Sound fallback) {
        if (name == null || name.trim().isEmpty()) return fallback;
        try {
            return Sound.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private boolean isRedstone(Particle p) {
        return p == Particle.REDSTONE;
    }

    private int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static class Config {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("radius", 2.5D).forGetter(v -> v.radius),
                Codec.INT.optionalFieldOf("itemCount", 10).forGetter(v -> v.itemCount),
                Codec.DOUBLE.optionalFieldOf("rotationSpeed", 7.0D).forGetter(v -> v.rotationSpeed),
                Codec.DOUBLE.optionalFieldOf("precession", 30.0D).forGetter(v -> v.precession),
                Codec.DOUBLE.optionalFieldOf("height", 1.2D).forGetter(v -> v.height),
                Codec.STRING.optionalFieldOf("particleVector", "REDSTONE").forGetter(v -> v.particleVector),
                Codec.STRING.optionalFieldOf("particleItems", "REDSTONE").forGetter(v -> v.particleItems),
                Codec.INT.optionalFieldOf("red", 255).forGetter(v -> v.red),
                Codec.INT.optionalFieldOf("green", 105).forGetter(v -> v.green),
                Codec.INT.optionalFieldOf("blue", 180).forGetter(v -> v.blue),
                Codec.INT.optionalFieldOf("showItemNames", 1).forGetter(v -> v.showItemNames),
                Timings.CODEC.optionalFieldOf("timings", new Timings()).forGetter(v -> v.timings)
        ).apply(i, Config::new));

        final double radius;
        final int itemCount;
        final double rotationSpeed;
        final double precession;
        final double height;
        final String particleVector;
        final String particleItems;
        final int red;
        final int green;
        final int blue;
        final int showItemNames;
        final Timings timings;

        Config(double radius, int itemCount, double rotationSpeed, double precession, double height,
               String particleVector, String particleItems, int red, int green, int blue,
               int showItemNames, Timings timings) {
            this.radius = clamp(radius, 0.8D, 6.0D);
            this.itemCount = (int) clamp(itemCount, 4, 24);
            this.rotationSpeed = clamp(rotationSpeed, 1.0D, 20.0D);
            this.precession = clamp(precession, 0.0D, 70.0D);
            this.height = clamp(height, 0.0D, 4.0D);
            this.particleVector = particleVector == null ? "REDSTONE" : particleVector;
            this.particleItems = particleItems == null ? "REDSTONE" : particleItems;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.showItemNames = showItemNames != 0 ? 1 : 0;
            this.timings = timings == null ? new Timings() : timings;
        }

        Config() {
            this(2.5D, 10, 7.0D, 30.0D, 1.2D, "REDSTONE", "REDSTONE", 255, 105, 180, 1, new Timings());
        }

        private static double clamp(double v, double min, double max) {
            return Math.max(min, Math.min(max, v));
        }
    }

    private static class Timings {
        public static final Codec<Timings> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("ascentSteps", 5).forGetter(v -> v.ascentSteps),
                Codec.INT.optionalFieldOf("ascentGap", 2).forGetter(v -> v.ascentGap),
                Codec.INT.optionalFieldOf("orbitTicks", 150).forGetter(v -> v.orbitTicks),
                Codec.INT.optionalFieldOf("tiltCycleTicks", 80).forGetter(v -> v.tiltCycleTicks),
                Codec.INT.optionalFieldOf("convergenceTicks", 40).forGetter(v -> v.convergenceTicks),
                Codec.INT.optionalFieldOf("winnerTicks", 45).forGetter(v -> v.winnerTicks),
                Codec.INT.optionalFieldOf("trailPeriod", 2).forGetter(v -> v.trailPeriod),
                Codec.STRING.optionalFieldOf("spawnSound", "BLOCK_NOTE_BLOCK_PLING").forGetter(v -> v.spawnSound),
                Codec.STRING.optionalFieldOf("winSound", "ENTITY_PLAYER_LEVELUP").forGetter(v -> v.winSound)
        ).apply(i, Timings::new));

        final int ascentSteps;
        final int ascentGap;
        final int orbitTicks;
        final int tiltCycleTicks;
        final int convergenceTicks;
        final int winnerTicks;
        final int trailPeriod;
        final String spawnSound;
        final String winSound;

        Timings(int ascentSteps, int ascentGap, int orbitTicks, int tiltCycleTicks, int convergenceTicks,
                int winnerTicks, int trailPeriod, String spawnSound, String winSound) {
            this.ascentSteps = (int) clamp(ascentSteps, 2, 15);
            this.ascentGap = (int) clamp(ascentGap, 1, 5);
            this.orbitTicks = (int) clamp(orbitTicks, 20, 1200);
            this.tiltCycleTicks = (int) clamp(tiltCycleTicks, 30, 400);
            this.convergenceTicks = (int) clamp(convergenceTicks, 10, 200);
            this.winnerTicks = (int) clamp(winnerTicks, 10, 200);
            this.trailPeriod = (int) clamp(trailPeriod, 0, 10);
            this.spawnSound = spawnSound == null ? "BLOCK_NOTE_BLOCK_PLING" : spawnSound;
            this.winSound = winSound == null ? "ENTITY_PLAYER_LEVELUP" : winSound;
        }

        Timings() {
            this(5, 2, 150, 80, 40, 45, 2, "BLOCK_NOTE_BLOCK_PLING", "ENTITY_PLAYER_LEVELUP");
        }

        private static double clamp(double v, double min, double max) {
            return Math.max(min, Math.min(max, v));
        }
    }
}
