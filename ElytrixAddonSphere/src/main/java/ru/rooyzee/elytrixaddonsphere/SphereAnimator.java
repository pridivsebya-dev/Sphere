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
import dev.by1337.virtualentity.api.entity.EquipmentSlot;
import dev.by1337.virtualentity.api.virtual.VirtualEntity;
import dev.by1337.virtualentity.api.virtual.decoration.VirtualArmorStand;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.by1337.blib.geom.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SphereAnimator extends AbstractAnimation {

    private final Prize winner;
    private final Config config;
    private final Vec3d center;
    private final Vec3d ringCenter;
    private final Sound spawnSound;
    private final Sound winSound;

    private final List<VirtualArmorStand> activeItems = new ArrayList<>();
    private VirtualArmorStand winnerItem;

    private double orbitAngle = 0.0D;
    private double ringRadius;
    private double wavePhase = 0.0D;

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

        List<VirtualArmorStand> items = new ArrayList<>(count);

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

    private VirtualArmorStand spawnStand(Prize prize, Vec3d pos, float yaw) {
        VirtualArmorStand stand = VirtualArmorStand.create();
        stand.setSmall(true);
        stand.setNoBasePlate(true);
        stand.setNoGravity(true);
        stand.setInvisible(true);
        stand.setNoMotion();
        stand.setEquipment(EquipmentSlot.HEAD, prize.itemStack());
        stand.setCustomName(prize.displayNameComponent());
        stand.setCustomNameVisible(true);
        stand.setPos(pos);
        stand.setYaw(yaw);

        trackEntity(stand);
        activeItems.add(stand);
        return stand;
    }

    private void ascent(int count, int winnerIdx, double[] slot, List<VirtualArmorStand> items) throws InterruptedException {
        int steps = config.timings.ascentSteps;
        int gap = config.timings.ascentGap;
        int total = (count - 1) * gap + steps + 1;

        int[] progress = new int[count];
        VirtualArmorStand[] spawned = new VirtualArmorStand[count];

        for (int t = 0; t < total; t++) {
            for (int i = 0; i < count; i++) {
                if (t < i * gap) continue;

                if (spawned[i] == null) {
                    Prize prize = (i == winnerIdx) ? winner : safePrize();
                    float yaw = (float) Math.toDegrees(slot[i]) - 90.0F;
                    spawned[i] = spawnStand(prize, center, yaw);
                    items.add(spawned[i]);

                    if (i == winnerIdx) {
                        winnerItem = spawned[i];
                    }

                    float pitch = 0.55F + 0.6F * ((float) i / count);
                    playSound(center, spawnSound, 0.3F, pitch);
                }

                progress[i]++;
                int s = progress[i] - 1;
                if (s < 1 || s > steps) continue;

                double k = smoothStep((double) s / steps);
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

    private void orbit(int count, List<VirtualArmorStand> items, double[] slot) throws InterruptedException {
        int ticks = config.timings.orbitTicks;
        double baseRadius = config.radius;
        double speed = Math.toRadians(config.rotationSpeed);

        for (int t = 0; t < ticks; t++) {
            ringRadius = baseRadius * (1.0D - 0.10D * smoothStep((double) t / ticks));
            double waveAmp = 0.12D * smoothStep(Math.min(1.0D, t / 10.0D));

            for (int i = 0; i < count; i++) {
                items.get(i).setPos(ringPos(i, count, slot, waveAmp));
            }

            orbitAngle += speed;
            wavePhase += 0.1D;

            sleepTicks(1L);
        }
    }

    private void converge(int count, List<VirtualArmorStand> items, double[] slot) throws InterruptedException {
        int ticks = config.timings.convergenceTicks;
        double startRadius = ringRadius;

        for (int t = 0; t < ticks; t++) {
            double sm = smoothStep((double) t / ticks);

            ringRadius = startRadius * (1.0D - 0.86D * sm);
            double waveAmp = 0.12D * (ringRadius / startRadius);

            for (int i = 0; i < count; i++) {
                items.get(i).setPos(ringPos(i, count, slot, waveAmp));
            }

            orbitAngle += Math.toRadians(config.rotationSpeed * (1.0D + 2.2D * sm));
            wavePhase += 0.15D * (1.0D + sm);

            sleepTicks(1L);
        }

        playSound(ringCenter, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5F, 0.7F);
    }

    private void showcase(int count, int winnerIdx, List<VirtualArmorStand> items, double[] slot) throws InterruptedException {
        int ticks = config.timings.winnerTicks;

        Vec3d[] start = new Vec3d[count];
        for (int i = 0; i < count; i++) {
            start[i] = ringPos(i, count, slot, 0.0D);
        }

        Vec3d top = ringCenter.add(0.0D, -0.3D, 0.0D);
        List<VirtualArmorStand> losers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (i != winnerIdx) losers.add(items.get(i));
        }

        int gatherTicks = 3;
        int removalStart = gatherTicks + 1;
        int removalsPerTick = 4;

        for (int t = 0; t < ticks; t++) {
            if (t <= gatherTicks) {
                double k = smoothStep((double) t / gatherTicks);
                for (int i = 0; i < count; i++) {
                    if (i == winnerIdx) continue;
                    items.get(i).setPos(lerpVec(start[i], ringCenter, k));
                }
                winnerItem.setPos(lerpVec(start[winnerIdx], top, k));
            }

            if (t >= removalStart) {
                for (int r = 0; r < removalsPerTick && !losers.isEmpty(); r++) {
                    removeAndForget(losers.remove(0));
                }
            }

            if (t == removalStart + 2) {
                playSound(top, winSound, 0.8F, 1.0F);
            }

            if (t > gatherTicks) {
                int bt = t - gatherTicks;
                double ramp = Math.min(1.0D, bt / 6.0D);
                double bob = 0.1D * Math.sin(0.13D * bt) * ramp;
                winnerItem.setPos(top.add(0.0D, bob, 0.0D));
            }

            sleepTicks(1L);
        }

        removeAndForget(winnerItem);
        winnerItem = null;
    }

    private Vec3d ringPos(int i, int count, double[] slot, double waveAmp) {
        double ang = slot[i] + orbitAngle;
        double y = waveAmp * Math.sin(wavePhase + (2.0D * Math.PI * i) / count);
        return new Vec3d(
                ringCenter.x + Math.cos(ang) * ringRadius,
                ringCenter.y + y,
                ringCenter.z + Math.sin(ang) * ringRadius
        );
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

    private void removeAndForget(VirtualArmorStand item) {
        if (item == null) return;
        activeItems.remove(item);
        try {
            removeEntity(item);
        } catch (Throwable ignored) {
        }
    }

    private void cleanup() {
        for (VirtualArmorStand item : new ArrayList<>(activeItems)) {
            try {
                removeEntity(item);
            } catch (Throwable ignored) {
            }
        }
        activeItems.clear();
    }

    private Sound parseSound(String name, Sound fallback) {
        if (name == null || name.trim().isEmpty()) return fallback;
        try {
            return Sound.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static class Config {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("radius", 2.5D).forGetter(v -> v.radius),
                Codec.INT.optionalFieldOf("itemCount", 10).forGetter(v -> v.itemCount),
                Codec.DOUBLE.optionalFieldOf("rotationSpeed", 7.0D).forGetter(v -> v.rotationSpeed),
                Codec.DOUBLE.optionalFieldOf("height", 0.35D).forGetter(v -> v.height),
                Timings.CODEC.optionalFieldOf("timings", new Timings()).forGetter(v -> v.timings)
        ).apply(i, Config::new));

        final double radius;
        final int itemCount;
        final double rotationSpeed;
        final double height;
        final Timings timings;

        Config(double radius, int itemCount, double rotationSpeed, double height, Timings timings) {
            this.radius = clamp(radius, 0.8D, 6.0D);
            this.itemCount = (int) clamp(itemCount, 4, 24);
            this.rotationSpeed = clamp(rotationSpeed, 1.0D, 20.0D);
            this.height = clamp(height, 0.0D, 4.0D);
            this.timings = timings == null ? new Timings() : timings;
        }

        Config() {
            this(2.5D, 10, 7.0D, 0.35D, new Timings());
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
                Codec.INT.optionalFieldOf("convergenceTicks", 40).forGetter(v -> v.convergenceTicks),
                Codec.INT.optionalFieldOf("winnerTicks", 45).forGetter(v -> v.winnerTicks),
                Codec.STRING.optionalFieldOf("spawnSound", "BLOCK_NOTE_BLOCK_PLING").forGetter(v -> v.spawnSound),
                Codec.STRING.optionalFieldOf("winSound", "ENTITY_PLAYER_LEVELUP").forGetter(v -> v.winSound)
        ).apply(i, Timings::new));

        final int ascentSteps;
        final int ascentGap;
        final int orbitTicks;
        final int convergenceTicks;
        final int winnerTicks;
        final String spawnSound;
        final String winSound;

        Timings(int ascentSteps, int ascentGap, int orbitTicks, int convergenceTicks, int winnerTicks,
                String spawnSound, String winSound) {
            this.ascentSteps = (int) clamp(ascentSteps, 2, 15);
            this.ascentGap = (int) clamp(ascentGap, 1, 5);
            this.orbitTicks = (int) clamp(orbitTicks, 20, 1200);
            this.convergenceTicks = (int) clamp(convergenceTicks, 10, 200);
            this.winnerTicks = (int) clamp(winnerTicks, 10, 200);
            this.spawnSound = spawnSound == null ? "BLOCK_NOTE_BLOCK_PLING" : spawnSound;
            this.winSound = winSound == null ? "ENTITY_PLAYER_LEVELUP" : winSound;
        }

        Timings() {
            this(5, 2, 150, 40, 45, "BLOCK_NOTE_BLOCK_PLING", "ENTITY_PLAYER_LEVELUP");
        }

        private static double clamp(double v, double min, double max) {
            return Math.max(min, Math.min(max, v));
        }
    }
}
