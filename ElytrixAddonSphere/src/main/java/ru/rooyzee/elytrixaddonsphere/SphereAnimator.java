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
import dev.by1337.virtualentity.api.virtual.item.VirtualItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Particle;
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
    private final Particle trailParticle;
    private final Particle itemParticle;
    private final Particle.DustOptions dustOptions;

    private final List<VirtualEntity> activeItems = new ArrayList<>();
    private VirtualEntity winnerItem;
    private VirtualEntity[] nameStands;

    private double orbitAngle = 0.0D;
    private double ringRadius;
    private double swirlAngle = 0.0D;
    private int swirlTick;

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
        this.trailParticle = parseParticle(this.config.particleVector, Particle.REDSTONE);
        this.itemParticle = parseParticle(this.config.particleItems, Particle.REDSTONE);

        this.dustOptions = new Particle.DustOptions(
                Color.fromRGB(
                        Math.max(0, Math.min(255, this.config.red)),
                        Math.max(0, Math.min(255, this.config.green)),
                        Math.max(0, Math.min(255, this.config.blue))
                ),
                0.6F
        );
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

        List<VirtualEntity> items = new ArrayList<>(count);
        nameStands = new VirtualEntity[count];

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

    private VirtualEntity spawnDisplay(Prize prize, Vec3d pos, float yaw) {
        if (config.standMode) {
            VirtualArmorStand stand = VirtualArmorStand.create();
            stand.setSmall(true);
            stand.setNoBasePlate(true);
            stand.setNoGravity(true);
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setNoMotion();
            stand.setEquipment(EquipmentSlot.HEAD, prize.itemStack());
            stand.setPos(pos);
            stand.setYaw(yaw);
            trackEntity(stand);
            activeItems.add(stand);
            return stand;
        }

        VirtualItem item = VirtualItem.create();
        item.setItem(prize.itemStack());
        item.setPos(pos);
        item.setNoGravity(true);
        item.setNoMotion();
        item.setCustomName(prize.displayNameComponent());
        item.setCustomNameVisible(true);
        trackEntity(item);
        activeItems.add(item);
        return item;
    }

    private VirtualEntity spawnNameStand(Prize prize, Vec3d pos) {
        Component name = prize.displayNameComponent();
        if (name == null) return null;

        VirtualArmorStand stand = VirtualArmorStand.create();
        stand.setMarker(true);
        stand.setSmall(true);
        stand.setNoBasePlate(true);
        stand.setNoGravity(true);
        stand.setInvisible(true);
        stand.setNoMotion();
        stand.setCustomName(name);
        stand.setCustomNameVisible(true);
        stand.setPos(pos);
        trackEntity(stand);
        activeItems.add(stand);
        return stand;
    }

    private void ascent(int count, int winnerIdx, double[] slot, List<VirtualEntity> items) throws InterruptedException {
        int steps = config.timings.ascentSteps;
        int gap = config.timings.ascentGap;
        int total = (count - 1) * gap + steps + 1;

        Vec3d hidden = center.add(0.0D, 6.5D, 0.0D);

        int[] progress = new int[count];
        VirtualEntity[] spawned = new VirtualEntity[count];

        for (int t = 0; t < total; t++) {
            for (int i = 0; i < count; i++) {
                if (t < i * gap) continue;

                if (spawned[i] == null) {
                    Prize prize = (i == winnerIdx) ? winner : safePrize();
                    float yaw = (float) Math.toDegrees(slot[i]) - 90.0F;
                    spawned[i] = spawnDisplay(prize, hidden, yaw);
                    items.add(spawned[i]);

                    if (config.standMode) {
                        nameStands[i] = spawnNameStand(prize, hidden);
                    }

                    if (i == winnerIdx) {
                        winnerItem = spawned[i];
                    }

                    float pitch = 0.55F + 0.6F * ((float) i / count);
                    playSound(center, spawnSound, 0.3F, pitch);
                }

                progress[i]++;
                int s = progress[i] - 1;
                if (s < 1 || s > steps) continue;

                double f = 1.0D - smoothStep((double) s / steps);
                double ang = slot[i] + 0.8D * f;
                double r = config.radius * (1.0D - 0.35D * f);
                double y = config.height + 2.8D * f;

                Vec3d pos = new Vec3d(
                        center.x + Math.cos(ang) * r,
                        center.y + y,
                        center.z + Math.sin(ang) * r
                );
                spawned[i].setPos(pos);

                if (nameStands[i] != null) {
                    nameStands[i].setPos(pos.add(0.0D, config.nameOffset, 0.0D));
                }
            }
            sleepTicks(1L);
        }
    }

    private void orbit(int count, List<VirtualEntity> items, double[] slot) throws InterruptedException {
        int ticks = config.timings.orbitTicks;
        double baseRadius = config.radius;
        double speed = Math.toRadians(config.rotationSpeed);

        for (int t = 0; t < ticks; t++) {
            ringRadius = baseRadius * (1.0D - 0.10D * smoothStep((double) t / ticks));

            for (int i = 0; i < count; i++) {
                Vec3d pos = ringPos(i, slot);
                items.get(i).setPos(pos);

                if (nameStands[i] != null) {
                    nameStands[i].setPos(pos.add(0.0D, config.nameOffset, 0.0D));
                }
            }

            drawItemSwirl(items, slot);

            orbitAngle += speed;

            sleepTicks(1L);
        }
    }

    private void converge(int count, List<VirtualEntity> items, double[] slot) throws InterruptedException {
        int ticks = config.timings.convergenceTicks;
        double startRadius = ringRadius;

        for (int t = 0; t < ticks; t++) {
            double sm = smoothStep((double) t / ticks);

            ringRadius = startRadius * (1.0D - 0.5D * sm);

            for (int i = 0; i < count; i++) {
                Vec3d pos = ringPos(i, slot);
                items.get(i).setPos(pos);

                if (nameStands[i] != null) {
                    nameStands[i].setPos(pos.add(0.0D, config.nameOffset, 0.0D));
                }
            }

            drawItemSwirl(items, slot);

            orbitAngle += Math.toRadians(config.rotationSpeed * (1.0D + 2.2D * sm));

            sleepTicks(1L);
        }

        playSound(ringCenter, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5F, 0.7F);
    }

    private void showcase(int count, int winnerIdx, List<VirtualEntity> items, double[] slot) throws InterruptedException {
        int ticks = config.timings.winnerTicks;

        Vec3d top = ringCenter.add(0.0D, -0.3D, 0.0D);
        double displayY = config.standMode ? 1.0D : 0.0D;
        Vec3d showTop = top.add(0.0D, displayY, 0.0D);

        List<Integer> order = new ArrayList<>();
        for (int k = 1; k < count; k++) {
            order.add((winnerIdx + k) % count);
        }

        int flightTicks = 7;
        int lastDepart = order.size() - 1;
        int lastVanish = lastDepart + flightTicks;
        int winnerStart = lastVanish + 2;
        int winnerFly = 6;
        int revealTick = winnerStart + winnerFly;

        Vec3d[] startPos = new Vec3d[count];
        double[] dirX = new double[count];
        double[] dirZ = new double[count];
        int[] departedAt = new int[count];
        for (int i = 0; i < count; i++) {
            departedAt[i] = -1;
        }

        for (int t = 0; t < ticks; t++) {
            double spin = config.rotationSpeed * Math.max(0.15D,
                    3.2D - 3.05D * Math.min(1.0D, (double) t / Math.max(1, lastDepart)));
            orbitAngle += Math.toRadians(spin);

            for (int idx : order) {
                if (departedAt[idx] != -1) continue;
                Vec3d pos = ringPos(idx, slot);
                items.get(idx).setPos(pos);

                if (nameStands[idx] != null) {
                    nameStands[idx].setPos(pos.add(0.0D, config.nameOffset, 0.0D));
                }
            }

            if (t < winnerStart) {
                Vec3d wp = ringPos(winnerIdx, slot);
                winnerItem.setPos(wp);

                if (nameStands[winnerIdx] != null) {
                    nameStands[winnerIdx].setPos(wp.add(0.0D, config.nameOffset, 0.0D));
                }
            }

            for (int k = 0; k < order.size(); k++) {
                int idx = order.get(k);
                if (departedAt[idx] != -1 || t < k) continue;

                departedAt[idx] = t;
                Vec3d sp = items.get(idx).getPos();
                startPos[idx] = sp == null ? ringPos(idx, slot) : sp;

                double rx = startPos[idx].x - ringCenter.x;
                double rz = startPos[idx].z - ringCenter.z;
                double rl = Math.sqrt(rx * rx + rz * rz);
                if (rl < 0.001D) {
                    rx = Math.cos(slot[idx]);
                    rz = Math.sin(slot[idx]);
                    rl = 1.0D;
                }
                dirX[idx] = rx / rl;
                dirZ[idx] = rz / rl;
            }

            for (int idx : order) {
                int d0 = departedAt[idx];
                if (d0 == -1) continue;
                int dt = t - d0;
                if (dt > flightTicks) continue;

                double e = smoothStep((double) dt / flightTicks);
                Vec3d pos = startPos[idx].add(dirX[idx] * 1.7D * e, 2.6D * e, dirZ[idx] * 1.7D * e);
                items.get(idx).setPos(pos);

                if (nameStands[idx] != null) {
                    nameStands[idx].setPos(pos.add(0.0D, config.nameOffset, 0.0D));
                }

                if (dt == flightTicks) {
                    poof(pos.add(0.0D, displayY, 0.0D));
                    removeAndForget(items.get(idx));

                    if (nameStands[idx] != null) {
                        removeAndForget(nameStands[idx]);
                        nameStands[idx] = null;
                    }
                }
            }

            if (t >= winnerStart && t < revealTick) {
                double wp = smoothStep((double) (t - winnerStart) / winnerFly);
                Vec3d wpos = lerpVec(ringPos(winnerIdx, slot), top, wp);
                winnerItem.setPos(wpos);

                if (nameStands[winnerIdx] != null) {
                    nameStands[winnerIdx].setPos(wpos.add(0.0D, config.nameOffset, 0.0D));
                }
            }

            if (t == revealTick) {
                playSound(top, winSound, 0.8F, 1.0F);
                burst(showTop);
            }

            if (t > revealTick) {
                int bt = t - revealTick;
                double ramp = Math.min(1.0D, bt / 6.0D);
                double bob = 0.1D * Math.sin(0.13D * bt) * ramp;
                Vec3d wpos = top.add(0.0D, bob, 0.0D);
                winnerItem.setPos(wpos);

                if (nameStands[winnerIdx] != null) {
                    nameStands[winnerIdx].setPos(wpos.add(0.0D, config.nameOffset, 0.0D));
                }
                drawSwirl(showTop);
            }

            sleepTicks(1L);
        }

        removeAndForget(winnerItem);
        winnerItem = null;

        if (nameStands[winnerIdx] != null) {
            removeAndForget(nameStands[winnerIdx]);
            nameStands[winnerIdx] = null;
        }
    }

    private Vec3d ringPos(int i, double[] slot) {
        double ang = slot[i] + orbitAngle;
        return new Vec3d(
                ringCenter.x + Math.cos(ang) * ringRadius,
                ringCenter.y,
                ringCenter.z + Math.sin(ang) * ringRadius
        );
    }

    private void drawItemSwirl(List<VirtualEntity> items, double[] slot) {
        if (swirlTick++ % 2 != 0) return;
        swirlAngle += 0.6D;

        double baseY = config.standMode ? 1.0D : 0.15D;

        for (int i = 0; i < items.size(); i++) {
            Vec3d pos = items.get(i).getPos();
            if (pos == null) continue;

            for (int k = 0; k < 2; k++) {
                double a = swirlAngle + slot[i] + k * Math.PI;
                Vec3d point = pos.add(
                        Math.cos(a) * 0.4D,
                        baseY + 0.12D * Math.sin(2.0D * a),
                        Math.sin(a) * 0.4D
                );
                if (isRedstone(trailParticle)) {
                    spawnParticle(Particle.REDSTONE, point, 0, 0.0D, 0.0D, 0.0D, 0.0D, dustOptions);
                } else {
                    spawnParticle(trailParticle, point, 0, 0.0D, 0.0D, 0.0D, 0.0D);
                }
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

    private void removeAndForget(VirtualEntity item) {
        if (item == null) return;
        activeItems.remove(item);
        try {
            removeEntity(item);
        } catch (Throwable ignored) {
        }
    }

    private void cleanup() {
        for (VirtualEntity item : new ArrayList<>(activeItems)) {
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

    private static class Config {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("radius", 2.5D).forGetter(v -> v.radius),
                Codec.INT.optionalFieldOf("itemCount", 10).forGetter(v -> v.itemCount),
                Codec.DOUBLE.optionalFieldOf("rotationSpeed", 7.0D).forGetter(v -> v.rotationSpeed),
                Codec.DOUBLE.optionalFieldOf("height", 0.35D).forGetter(v -> v.height),
                Codec.STRING.optionalFieldOf("displayMode", "STAND").forGetter(v -> v.displayMode),
                Codec.DOUBLE.optionalFieldOf("nameOffset", 1.15D).forGetter(v -> v.nameOffset),
                Codec.STRING.optionalFieldOf("particleVector", "REDSTONE").forGetter(v -> v.particleVector),
                Codec.STRING.optionalFieldOf("particleItems", "REDSTONE").forGetter(v -> v.particleItems),
                Codec.INT.optionalFieldOf("red", 255).forGetter(v -> v.red),
                Codec.INT.optionalFieldOf("green", 105).forGetter(v -> v.green),
                Codec.INT.optionalFieldOf("blue", 180).forGetter(v -> v.blue),
                Timings.CODEC.optionalFieldOf("timings", new Timings()).forGetter(v -> v.timings)
        ).apply(i, Config::new));

        final double radius;
        final int itemCount;
        final double rotationSpeed;
        final double height;
        final String displayMode;
        final boolean standMode;
        final double nameOffset;
        final String particleVector;
        final String particleItems;
        final int red;
        final int green;
        final int blue;
        final Timings timings;

        Config(double radius, int itemCount, double rotationSpeed, double height,
               String displayMode, double nameOffset, String particleVector, String particleItems,
               int red, int green, int blue, Timings timings) {
            this.radius = clamp(radius, 0.8D, 6.0D);
            this.itemCount = (int) clamp(itemCount, 4, 24);
            this.rotationSpeed = clamp(rotationSpeed, 1.0D, 20.0D);
            this.height = clamp(height, 0.0D, 4.0D);
            this.displayMode = displayMode == null ? "STAND" : displayMode;
            this.standMode = !this.displayMode.trim().equalsIgnoreCase("ITEM");
            this.nameOffset = clamp(nameOffset, 0.0D, 3.0D);
            this.particleVector = particleVector == null ? "REDSTONE" : particleVector;
            this.particleItems = particleItems == null ? "REDSTONE" : particleItems;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.timings = timings == null ? new Timings() : timings;
        }

        Config() {
            this(2.5D, 10, 7.0D, 0.35D, "STAND", 1.15D, "REDSTONE", "REDSTONE", 255, 105, 180, new Timings());
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
