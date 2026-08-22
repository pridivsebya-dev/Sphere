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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SphereAnimator extends AbstractAnimation {

    private static final int SPAWN_STEPS = 6;
    private static final int ROTATION_TICKS = 160;
    private static final int WIREFRAME_DELAY_TICKS = 3;
    private static final double SHRINK_FINAL = 0.78D;
    private static final double LINE_STEP = 0.5D;
    private static final double MAX_ANGULAR_STEP_DEG = 3.0D;
    private static final int FINAL_COLLAPSE_TICKS = 20;
    private static final int FINAL_HOLD_TICKS = 15;
    private static final double LINE_OFFSET_FROM_END = 0.35D;

    private final Prize winner;
    private final Config config;
    private final Vec3d center;
    private final Particle itemParticle;
    private final Particle vectorParticle;
    private final Particle.DustOptions dustOptions;

    private final List<VirtualItem> activeItems = new ArrayList<>();
    private VirtualItem winnerItem;

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

        this.config = config.get("settings", v ->
                        ((Pair<Config, ?>) v.decode(Config.CODEC).getOrThrow()).getFirst(),
                new Config()
        );

        this.dustOptions = new Particle.DustOptions(
                Color.fromRGB(
                        clamp(this.config.red),
                        clamp(this.config.green),
                        clamp(this.config.blue)
                ),
                0.4F
        );

        this.itemParticle = parseParticle(this.config.particleItems, Particle.FLAME);
        this.vectorParticle = parseParticle(this.config.particleVector, Particle.FLAME);
    }

    @Override
    protected void onStart() {
        caseBlock.hideHologram();
        caseBlock.hideBlock();
    }

    @Override
    protected void animate() throws InterruptedException {
        int count = Math.max(4, config.itemCount);
        int winnerIdx = RANDOM.nextInt(count);

        double[] bx = new double[count];
        double[] by = new double[count];
        double[] bz = new double[count];
        buildSpherePoints(count, bx, by, bz);

        int[][] edges = buildEdges(bx, by, bz, count);

        List<VirtualItem> items = new ArrayList<>(count);

        try {
            for (int i = 0; i < count; i++) {
                Prize prize = (i == winnerIdx) ? winner : safePrize();
                Vec3d target = center.add(new Vec3d(
                        bx[i] * config.radius,
                        by[i] * config.radius,
                        bz[i] * config.radius
                ));

                VirtualItem item = spawnItem(prize, target);
                items.add(item);

                if (i == winnerIdx) {
                    winnerItem = item;
                }

                sleepTicks(1L);
            }

            rotate(items, bx, by, bz, edges);
            finish(items, bx, by, bz);
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

    // ── SPHERE POINTS: UV-структура для симметрии ──────────

    private void buildSpherePoints(int count, double[] outX, double[] outY, double[] outZ) {
        if (count == 16) {
            buildUVSphere16(outX, outY, outZ);
            return;
        }
        buildFibonacciSphere(count, outX, outY, outZ);
    }

    /**
     * Строит 16 точек как 4 горизонтальных кольца по 4 точки.
     * Кольца расположены на широтах, кратных 30° от экватора.
     * Каждое кольцо повёрнуто на 45° относительно соседнего для минимизации "полос".
     */
    private void buildUVSphere16(double[] outX, double[] outY, double[] outZ) {
        double[] latitudes = {60.0D, 20.0D, -20.0D, -60.0D};
        int pointsPerRing = 4;

        int idx = 0;
        for (int ring = 0; ring < latitudes.length; ring++) {
            double latRad = Math.toRadians(latitudes[ring]);
            double y = Math.sin(latRad);
            double ringRadius = Math.cos(latRad);

            double phaseOffset = (ring % 2 == 0) ? 0.0D : Math.PI / pointsPerRing;

            for (int p = 0; p < pointsPerRing; p++) {
                double phi = (2.0D * Math.PI * p) / pointsPerRing + phaseOffset;
                outX[idx] = ringRadius * Math.cos(phi);
                outY[idx] = y;
                outZ[idx] = ringRadius * Math.sin(phi);
                idx++;
            }
        }
    }

    private void buildFibonacciSphere(int count, double[] outX, double[] outY, double[] outZ) {
        if (count == 1) {
            outX[0] = 0.0D;
            outY[0] = 1.0D;
            outZ[0] = 0.0D;
            return;
        }

        double golden = Math.PI * (3.0D - Math.sqrt(5.0D));
        for (int i = 0; i < count; i++) {
            double y = 1.0D - (2.0D * i) / (count - 1.0D);
            double r = Math.sqrt(1.0D - y * y);
            double theta = golden * i;

            outX[i] = Math.cos(theta) * r;
            outY[i] = y;
            outZ[i] = Math.sin(theta) * r;
        }
    }

    // ── spawn ──────────────────────────────────────────────

    private VirtualItem spawnItem(Prize prize, Vec3d target) throws InterruptedException {
        VirtualItem item = VirtualItem.create();
        item.setItem(prize.itemStack());
        item.setPos(center);
        item.setNoGravity(true);
        freezeMotion(item);

        trackEntity(item);
        activeItems.add(item);

        applyName(item);
        item.setCustomNameVisible(true);

        for (int s = 1; s <= SPAWN_STEPS; s++) {
            double t = smoothStep((double) s / SPAWN_STEPS);
            Vec3d pos = lerpVec(center, target, t);
            item.setPos(pos);
            item.lookAt(center);
            freezeMotion(item);
            sleepTicks(1L);
        }

        item.setPos(target);
        item.lookAt(center);
        freezeMotion(item);

        playSound(target, Sound.BLOCK_FIRE_EXTINGUISH, 0.2F, 1.2F);
        return item;
    }

    // ── rotation ───────────────────────────────────────────

    private void rotate(List<VirtualItem> items, double[] bx, double[] by, double[] bz, int[][] edges) throws InterruptedException {
        int count = items.size();

        double maxAngularSpeed = Math.min(MAX_ANGULAR_STEP_DEG,
                Math.toDegrees(0.3D / Math.max(0.5D, config.radius)));

        double angleX = 0.0D;
        double angleY = 0.0D;

        double[][] historyX = new double[WIREFRAME_DELAY_TICKS + 1][count];
        double[][] historyY = new double[WIREFRAME_DELAY_TICKS + 1][count];
        double[][] historyZ = new double[WIREFRAME_DELAY_TICKS + 1][count];
        int historyIndex = 0;
        boolean historyFilled = false;

        for (int tick = 0; tick < ROTATION_TICKS; tick++) {
            double progress = (double) tick / ROTATION_TICKS;
            double smooth = smoothStep(progress);

            double speedX = maxAngularSpeed * (1.0D - 0.55D * smooth);
            double speedY = maxAngularSpeed * 0.75D * (1.0D - 0.55D * smooth);
            angleX += speedX;
            angleY += speedY;

            double radX = Math.toRadians(angleX);
            double radY = Math.toRadians(angleY);
            double cosX = Math.cos(radX);
            double sinX = Math.sin(radX);
            double cosY = Math.cos(radY);
            double sinY = Math.sin(radY);

            double currentRadius = config.radius * (1.0D - (1.0D - SHRINK_FINAL) * smooth);

            for (int i = 0; i < count; i++) {
                double px = bx[i] * currentRadius;
                double py = by[i] * currentRadius;
                double pz = bz[i] * currentRadius;

                double y1 = py * cosX - pz * sinX;
                double z1 = py * sinX + pz * cosX;

                double x2 = px * cosY + z1 * sinY;
                double z2 = -px * sinY + z1 * cosY;

                double finalX = center.x + x2;
                double finalY = center.y + y1;
                double finalZ = center.z + z2;

                historyX[historyIndex][i] = finalX;
                historyY[historyIndex][i] = finalY;
                historyZ[historyIndex][i] = finalZ;

                VirtualItem item = items.get(i);
                item.setPos(new Vec3d(finalX, finalY, finalZ));
                item.lookAt(center);
                freezeMotion(item);
            }

            int drawIndex;
            if (!historyFilled) {
                drawIndex = 0;
                if (tick >= WIREFRAME_DELAY_TICKS) {
                    historyFilled = true;
                }
            } else {
                drawIndex = (historyIndex + 1) % (WIREFRAME_DELAY_TICKS + 1);
            }

            drawWireframe(historyX[drawIndex], historyY[drawIndex], historyZ[drawIndex], edges);

            historyIndex = (historyIndex + 1) % (WIREFRAME_DELAY_TICKS + 1);

            sleepTicks(1L);
        }
    }

    // ── finish (симметричное сжатие) ───────────────────────

    private void finish(List<VirtualItem> items, double[] bx, double[] by, double[] bz) throws InterruptedException {
        int count = items.size();

        double lastAngleX = 0.0D;
        double lastAngleY = 0.0D;

        double maxAngularSpeed = Math.min(MAX_ANGULAR_STEP_DEG,
                Math.toDegrees(0.3D / Math.max(0.5D, config.radius)));

        for (int tick = 0; tick < ROTATION_TICKS; tick++) {
            double progress = (double) tick / ROTATION_TICKS;
            double smooth = smoothStep(progress);
            double speedX = maxAngularSpeed * (1.0D - 0.55D * smooth);
            double speedY = maxAngularSpeed * 0.75D * (1.0D - 0.55D * smooth);
            lastAngleX += speedX;
            lastAngleY += speedY;
        }

        double startRadius = config.radius * SHRINK_FINAL;

        double radX = Math.toRadians(lastAngleX);
        double radY = Math.toRadians(lastAngleY);
        double cosX = Math.cos(radX);
        double sinX = Math.sin(radX);
        double cosY = Math.cos(radY);
        double sinY = Math.sin(radY);

        for (int step = 1; step <= FINAL_COLLAPSE_TICKS; step++) {
            double t = smoothStep((double) step / FINAL_COLLAPSE_TICKS);
            double currentRadius = startRadius * (1.0D - t);

            for (int i = 0; i < count; i++) {
                VirtualItem item = items.get(i);
                if (item == null) continue;

                Vec3d pos;
                if (item == winnerItem) {
                    double px = bx[i] * startRadius;
                    double py = by[i] * startRadius;
                    double pz = bz[i] * startRadius;

                    double y1 = py * cosX - pz * sinX;
                    double z1 = py * sinX + pz * cosX;
                    double x2 = px * cosY + z1 * sinY;
                    double z2 = -px * sinY + z1 * cosY;

                    Vec3d start = new Vec3d(center.x + x2, center.y + y1, center.z + z2);
                    pos = lerpVec(start, center, t);
                } else {
                    double px = bx[i] * currentRadius;
                    double py = by[i] * currentRadius;
                    double pz = bz[i] * currentRadius;

                    double y1 = py * cosX - pz * sinX;
                    double z1 = py * sinX + pz * cosX;
                    double x2 = px * cosY + z1 * sinY;
                    double z2 = -px * sinY + z1 * cosY;

                    pos = new Vec3d(center.x + x2, center.y + y1, center.z + z2);
                }

                item.setPos(pos);
                item.lookAt(center);
                freezeMotion(item);
            }

            sleepTicks(1L);
        }

        for (VirtualItem item : new ArrayList<>(items)) {
            if (item == winnerItem) continue;
            removeAndForget(item);
        }

        if (winnerItem != null) {
            winnerItem.setPos(center);
            winnerItem.lookAt(center);
            freezeMotion(winnerItem);
        }

        playSound(center, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9F, 1.0F);

        for (int i = 0; i < FINAL_HOLD_TICKS; i++) {
            if (winnerItem != null) {
                winnerItem.setPos(center);
                freezeMotion(winnerItem);
            }
            sleepTicks(1L);
        }

        removeAndForget(winnerItem);
        winnerItem = null;
    }

    // ── wireframe ──────────────────────────────────────────

    private void drawWireframe(double[] posX, double[] posY, double[] posZ, int[][] edges) {
        for (int[] edge : edges) {
            int a = edge[0];
            int b = edge[1];

            double ax = posX[a], ay = posY[a], az = posZ[a];
            double bxp = posX[b], byp = posY[b], bzp = posZ[b];

            double dx = bxp - ax;
            double dy = byp - ay;
            double dz = bzp - az;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (dist <= 0.001D) continue;

            double nx = dx / dist;
            double ny = dy / dist;
            double nz = dz / dist;

            double start = LINE_OFFSET_FROM_END;
            double end = dist - LINE_OFFSET_FROM_END;

            if (end <= start) continue;

            for (double d = start; d <= end; d += LINE_STEP) {
                Vec3d point = new Vec3d(ax + nx * d, ay + ny * d, az + nz * d);
                if (isRedstone(vectorParticle)) {
                    spawnParticle(Particle.REDSTONE, point, 0, 0.0D, 0.0D, 0.0D, 0.0D, dustOptions);
                } else {
                    spawnParticle(vectorParticle, point, 0, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
        }
    }

    // ── edges ──────────────────────────────────────────────

    private int[][] buildEdges(double[] bx, double[] by, double[] bz, int count) {
        int neighborsPerPoint = Math.min(4, count - 1);

        Set<Long> edgeSet = new HashSet<>();
        List<int[]> edgeList = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            double[][] dists = new double[count][2];
            for (int j = 0; j < count; j++) {
                double dx = bx[i] - bx[j];
                double dy = by[i] - by[j];
                double dz = bz[i] - bz[j];
                dists[j][0] = j;
                dists[j][1] = dx * dx + dy * dy + dz * dz;
            }

            Arrays.sort(dists, (a, b) -> Double.compare(a[1], b[1]));

            for (int k = 1; k <= neighborsPerPoint && k < count; k++) {
                int j = (int) dists[k][0];
                int a = Math.min(i, j);
                int b = Math.max(i, j);
                long key = ((long) a << 32) | (b & 0xFFFFFFFFL);

                if (edgeSet.add(key)) {
                    edgeList.add(new int[]{a, b});
                }
            }
        }

        return edgeList.toArray(new int[0][]);
    }

    // ── freeze motion ──────────────────────────────────────

    private void freezeMotion(VirtualItem item) {
        if (item == null) return;
        try {
            for (Method m : item.getClass().getMethods()) {
                String name = m.getName();
                if ((name.equals("setDeltaMovement") || name.equals("setMotion") || name.equals("setVelocity"))
                        && m.getParameterCount() == 1) {
                    Class<?> p = m.getParameterTypes()[0];
                    if (p == Vec3d.class) {
                        m.invoke(item, new Vec3d(0.0D, 0.0D, 0.0D));
                        return;
                    }
                } else if ((name.equals("setDeltaMovement") || name.equals("setMotion") || name.equals("setVelocity"))
                        && m.getParameterCount() == 3) {
                    m.invoke(item, 0.0D, 0.0D, 0.0D);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ── utils ──────────────────────────────────────────────

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

    private void applyName(VirtualItem item) {
        try {
            ItemStack stack = item.getItem();
            if (stack == null) return;
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return;

            Object name = null;
            try {
                Method m = meta.getClass().getMethod("displayName");
                name = m.invoke(meta);
            } catch (Throwable ignored) {
            }

            if (name == null && meta.hasDisplayName()) {
                name = meta.getDisplayName();
            }
            if (name == null) return;

            for (Method m : item.getClass().getMethods()) {
                if (m.getName().equals("setCustomName") && m.getParameterCount() == 1) {
                    if (m.getParameterTypes()[0].isAssignableFrom(name.getClass())) {
                        m.invoke(item, name);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private Particle parseParticle(String name, Particle fallback) {
        if (name == null || name.trim().isEmpty()) return fallback;
        try {
            return Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
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

    // ── config ─────────────────────────────────────────────

    private static class Config {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.fieldOf("radius").forGetter(v -> v.radius),
                Codec.INT.fieldOf("itemCount").forGetter(v -> v.itemCount),
                Codec.STRING.fieldOf("particleVector").forGetter(v -> v.particleVector),
                Codec.STRING.fieldOf("particleItems").forGetter(v -> v.particleItems),
                Codec.INT.fieldOf("red").forGetter(v -> v.red),
                Codec.INT.fieldOf("green").forGetter(v -> v.green),
                Codec.INT.fieldOf("blue").forGetter(v -> v.blue)
        ).apply(i, Config::new));

        final double radius;
        final int itemCount;
        final String particleVector;
        final String particleItems;
        final int red;
        final int green;
        final int blue;

        Config(double radius, int itemCount, String particleVector, String particleItems, int red, int green, int blue) {
            this.radius = radius;
            this.itemCount = itemCount;
            this.particleVector = particleVector;
            this.particleItems = particleItems;
            this.red = red;
            this.green = green;
            this.blue = blue;
        }

        Config() {
            this(2.0D, 16, "REDSTONE", "REDSTONE", 255, 105, 180);
        }
    }
}