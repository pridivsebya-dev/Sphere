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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Анимация открытия кейса «сфера» (elytrix:sphere).
 *
 * Фазы:
 *  1. SPAWN    — призы каскадом вылетают из центра блока на точки сферы;
 *  2. ROTATE   — сфера вращается по двум осям с замедлением и сжатием,
 *                каркас из частиц рисуется с задержкой (эффект шлейфа);
 *  3. COLLAPSE — сфера схлопывается в центр, остаётся только победитель;
 *  4. HOLD     — победитель держится в центре, затем выдаётся приз.
 *
 * Все тайминги и визуальные параметры настраиваются в sphereAnimation.yml.
 * Любое отсутствующее поле конфига заменяется значением по умолчанию,
 * полностью битый конфиг — не ломает анимацию.
 */
public class SphereAnimator extends AbstractAnimation {

    private final Prize winner;
    private final Config config;
    private final Vec3d center;
    private final Particle itemParticle;
    private final Particle vectorParticle;
    private final Particle.DustOptions dustOptions;
    private final Sound spawnSound;
    private final Sound winSound;

    private final List<VirtualItem> activeItems = new ArrayList<>();
    private VirtualItem winnerItem;

    /** Счётчик тиков для периодических эффектов (аура предметов). */
    private int tickCounter;

    // Состояние конца фазы вращения — используется фазой сжатия,
    // чтобы переход между фазами был без малейшего скачка.
    private double endAngleX = 0.0D;
    private double endAngleY = 0.0D;
    private double endRadius;

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
                // Битый или неполный конфиг не должен ломать анимацию —
                // откатываемся на значения по умолчанию.
                return new Config();
            }
        }, new Config());

        this.endRadius = this.config.radius;

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
        this.spawnSound = parseSound(this.config.timings.spawnSound, Sound.BLOCK_FIRE_EXTINGUISH);
        this.winSound = parseSound(this.config.timings.winSound, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
    }

    @Override
    protected void onStart() {
        caseBlock.hideHologram();
        caseBlock.hideBlock();
    }

    @Override
    protected void animate() throws InterruptedException {
        int count = Math.max(4, Math.min(64, config.itemCount));
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
            finish(items, bx, by, bz, edges);
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
        // Достаточно одного пакета обнуления скорости: сущность виртуальная,
        // гравитация выключена, позицию полностью ведёт сервер.
        item.setNoMotion();

        trackEntity(item);
        activeItems.add(item);

        boolean named = applyName(item);
        item.setCustomNameVisible(named);

        int steps = config.timings.spawnSteps;
        for (int s = 1; s <= steps; s++) {
            double t = smoothStep((double) s / steps);
            Vec3d pos = lerpVec(center, target, t);
            item.setPos(pos);
            item.lookAt(center);
            sleepTicks(1L);
        }

        item.setPos(target);
        item.lookAt(center);

        float pitch = 0.9F + RANDOM.nextFloat() * 0.5F;
        playSound(target, spawnSound, 0.2F, pitch);
        return item;
    }

    // ── rotation ───────────────────────────────────────────

    private void rotate(List<VirtualItem> items, double[] bx, double[] by, double[] bz, int[][] edges) throws InterruptedException {
        int count = items.size();
        int ticks = config.timings.rotationTicks;

        double maxAngularSpeed = Math.min(config.rotationSpeed,
                Math.toDegrees(0.3D / Math.max(0.5D, config.radius)));

        double angleX = 0.0D;
        double angleY = 0.0D;

        int historySize = config.timings.wireframeDelayTicks + 1;
        double[][] historyX = new double[historySize][count];
        double[][] historyY = new double[historySize][count];
        double[][] historyZ = new double[historySize][count];
        int historyIndex = 0;
        boolean historyFilled = false;

        for (int tick = 0; tick < ticks; tick++) {
            double progress = (double) tick / ticks;
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

            double currentRadius = config.radius * (1.0D - (1.0D - config.shrink) * smooth);

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
            }

            drawItemAura(items);

            int drawIndex;
            if (!historyFilled) {
                drawIndex = 0;
                if (tick >= config.timings.wireframeDelayTicks) {
                    historyFilled = true;
                }
            } else {
                drawIndex = (historyIndex + 1) % historySize;
            }

            drawWireframe(historyX[drawIndex], historyY[drawIndex], historyZ[drawIndex], edges);

            historyIndex = (historyIndex + 1) % historySize;

            // Запоминаем фактическое состояние конца тика, чтобы
            // следующая фаза продолжила ровно с этого же места.
            endAngleX = angleX;
            endAngleY = angleY;
            endRadius = currentRadius;

            sleepTicks(1L);
        }
    }

    // ── finish (симметричное сжатие) ───────────────────────

    private void finish(List<VirtualItem> items, double[] bx, double[] by, double[] bz, int[][] edges) throws InterruptedException {
        int count = items.size();

        double radX = Math.toRadians(endAngleX);
        double radY = Math.toRadians(endAngleY);
        double cosX = Math.cos(radX);
        double sinX = Math.sin(radX);
        double cosY = Math.cos(radY);
        double sinY = Math.sin(radY);

        double[] px = new double[count];
        double[] py = new double[count];
        double[] pz = new double[count];
        double[] buf = new double[3];

        for (int step = 1; step <= config.timings.finalCollapseTicks; step++) {
            double t = smoothStep((double) step / config.timings.finalCollapseTicks);
            double currentRadius = endRadius * (1.0D - t);

            for (int i = 0; i < count; i++) {
                VirtualItem item = items.get(i);
                if (item == null) continue;

                Vec3d pos;
                if (item == winnerItem) {
                    // Победитель летит в центр из своей текущей позиции.
                    rotatePoint(bx[i], by[i], bz[i], endRadius, cosX, sinX, cosY, sinY, buf);
                    Vec3d start = new Vec3d(center.x + buf[0], center.y + buf[1], center.z + buf[2]);
                    pos = lerpVec(start, center, t);
                } else {
                    rotatePoint(bx[i], by[i], bz[i], currentRadius, cosX, sinX, cosY, sinY, buf);
                    pos = new Vec3d(center.x + buf[0], center.y + buf[1], center.z + buf[2]);
                }

                item.setPos(pos);
                item.lookAt(center);

                px[i] = pos.x;
                py[i] = pos.y;
                pz[i] = pos.z;
            }

            // Каркас сжимается вместе с предметами.
            drawWireframe(px, py, pz, edges);
            drawItemAura(items);

            sleepTicks(1L);
        }

        for (VirtualItem item : new ArrayList<>(items)) {
            if (item == winnerItem) continue;
            removeAndForget(item);
        }

        if (winnerItem != null) {
            winnerItem.setPos(center);
            winnerItem.lookAt(center);
        }

        playSound(center, winSound, 0.9F, 1.0F);

        for (int i = 0; i < config.timings.finalHoldTicks; i++) {
            if (winnerItem != null) {
                winnerItem.setPos(center);
            }
            sleepTicks(1L);
        }

        removeAndForget(winnerItem);
        winnerItem = null;
    }

    // ── wireframe & aura ───────────────────────────────────

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

            double start = config.timings.wireframeIndent;
            double end = dist - config.timings.wireframeIndent;

            if (end <= start) continue;

            for (double d = start; d <= end; d += config.timings.wireframeStep) {
                Vec3d point = new Vec3d(ax + nx * d, ay + ny * d, az + nz * d);
                if (isRedstone(vectorParticle)) {
                    spawnParticle(Particle.REDSTONE, point, 0, 0.0D, 0.0D, 0.0D, 0.0D, dustOptions);
                } else {
                    spawnParticle(vectorParticle, point, 0, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
        }
    }

    /**
     * Аура вокруг предметов (опция particleItems).
     * Раз в itemAuraPeriod тиков выпускает по одной частице у каждого предмета.
     * itemAuraPeriod = 0 отключает ауру.
     */
    private void drawItemAura(List<VirtualItem> items) {
        if (config.timings.itemAuraPeriod <= 0) return;
        if (tickCounter++ % config.timings.itemAuraPeriod != 0) return;

        for (VirtualItem item : items) {
            if (item == null) continue;
            Vec3d pos = item.getPos();
            if (pos == null) continue;
            if (isRedstone(itemParticle)) {
                spawnParticle(Particle.REDSTONE, pos, 0, 0.0D, 0.0D, 0.0D, 0.0D, dustOptions);
            } else {
                spawnParticle(itemParticle, pos, 0, 0.0D, 0.0D, 0.0D, 0.0D);
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

    // ── item names ─────────────────────────────────────────

    /** Метод Paper ItemMeta#displayName(), резолвится один раз. */
    private static Method paperDisplayName;
    private static boolean paperDisplayNameResolved;

    /**
     * Устанавливает имя приза над предметом.
     * Возвращает true, если имя удалось установить.
     */
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

    /** Конвертирует строку с legacy-кодами '§' в adventure Component. */
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

    // ── utils ──────────────────────────────────────────────

    /** Поворот базовой точки сферы на заданные углы (X, затем Y). */
    private static void rotatePoint(double bx, double by, double bz, double radius,
                                    double cosX, double sinX, double cosY, double sinY,
                                    double[] out) {
        double x = bx * radius;
        double y = by * radius;
        double z = bz * radius;

        double y1 = y * cosX - z * sinX;
        double z1 = y * sinX + z * cosX;

        out[0] = x * cosY + z1 * sinY;
        out[1] = y1;
        out[2] = -x * sinY + z1 * cosY;
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

    // ── config ─────────────────────────────────────────────

    private static class Config {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("radius", 2.5D).forGetter(v -> v.radius),
                Codec.INT.optionalFieldOf("itemCount", 16).forGetter(v -> v.itemCount),
                Codec.DOUBLE.optionalFieldOf("shrink", 0.78D).forGetter(v -> v.shrink),
                Codec.DOUBLE.optionalFieldOf("rotationSpeed", 3.0D).forGetter(v -> v.rotationSpeed),
                Codec.STRING.optionalFieldOf("particleVector", "REDSTONE").forGetter(v -> v.particleVector),
                Codec.STRING.optionalFieldOf("particleItems", "REDSTONE").forGetter(v -> v.particleItems),
                Codec.INT.optionalFieldOf("red", 255).forGetter(v -> v.red),
                Codec.INT.optionalFieldOf("green", 105).forGetter(v -> v.green),
                Codec.INT.optionalFieldOf("blue", 180).forGetter(v -> v.blue),
                Timings.CODEC.optionalFieldOf("timings", new Timings()).forGetter(v -> v.timings)
        ).apply(i, Config::new));

        final double radius;
        final int itemCount;
        final double shrink;
        final double rotationSpeed;
        final String particleVector;
        final String particleItems;
        final int red;
        final int green;
        final int blue;
        final Timings timings;

        Config(double radius, int itemCount, double shrink, double rotationSpeed,
               String particleVector, String particleItems, int red, int green, int blue,
               Timings timings) {
            this.radius = clamp(radius, 0.5D, 8.0D);
            this.itemCount = (int) clamp(itemCount, 4, 64);
            this.shrink = clamp(shrink, 0.3D, 0.95D);
            this.rotationSpeed = clamp(rotationSpeed, 0.5D, 8.0D);
            this.particleVector = particleVector == null ? "REDSTONE" : particleVector;
            this.particleItems = particleItems == null ? "REDSTONE" : particleItems;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.timings = timings == null ? new Timings() : timings;
        }

        Config() {
            this(2.5D, 16, 0.78D, 3.0D, "REDSTONE", "REDSTONE", 255, 105, 180, new Timings());
        }

        private static double clamp(double v, double min, double max) {
            return Math.max(min, Math.min(max, v));
        }
    }

    /** Тайминги и параметры эффектов — вложенная секция settings.timings. */
    private static class Timings {
        public static final Codec<Timings> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("rotationTicks", 160).forGetter(v -> v.rotationTicks),
                Codec.INT.optionalFieldOf("spawnSteps", 6).forGetter(v -> v.spawnSteps),
                Codec.INT.optionalFieldOf("finalCollapseTicks", 20).forGetter(v -> v.finalCollapseTicks),
                Codec.INT.optionalFieldOf("finalHoldTicks", 15).forGetter(v -> v.finalHoldTicks),
                Codec.INT.optionalFieldOf("wireframeDelayTicks", 3).forGetter(v -> v.wireframeDelayTicks),
                Codec.DOUBLE.optionalFieldOf("wireframeStep", 0.5D).forGetter(v -> v.wireframeStep),
                Codec.DOUBLE.optionalFieldOf("wireframeIndent", 0.35D).forGetter(v -> v.wireframeIndent),
                Codec.INT.optionalFieldOf("itemAuraPeriod", 0).forGetter(v -> v.itemAuraPeriod),
                Codec.STRING.optionalFieldOf("spawnSound", "BLOCK_FIRE_EXTINGUISH").forGetter(v -> v.spawnSound),
                Codec.STRING.optionalFieldOf("winSound", "ENTITY_EXPERIENCE_ORB_PICKUP").forGetter(v -> v.winSound)
        ).apply(i, Timings::new));

        final int rotationTicks;
        final int spawnSteps;
        final int finalCollapseTicks;
        final int finalHoldTicks;
        final int wireframeDelayTicks;
        final double wireframeStep;
        final double wireframeIndent;
        final int itemAuraPeriod;
        final String spawnSound;
        final String winSound;

        Timings(int rotationTicks, int spawnSteps, int finalCollapseTicks, int finalHoldTicks,
                int wireframeDelayTicks, double wireframeStep, double wireframeIndent,
                int itemAuraPeriod, String spawnSound, String winSound) {
            this.rotationTicks = (int) clamp(rotationTicks, 10, 1200);
            this.spawnSteps = (int) clamp(spawnSteps, 1, 20);
            this.finalCollapseTicks = (int) clamp(finalCollapseTicks, 1, 200);
            this.finalHoldTicks = (int) clamp(finalHoldTicks, 0, 200);
            this.wireframeDelayTicks = (int) clamp(wireframeDelayTicks, 1, 20);
            this.wireframeStep = clamp(wireframeStep, 0.15D, 2.0D);
            this.wireframeIndent = clamp(wireframeIndent, 0.0D, 2.0D);
            this.itemAuraPeriod = (int) clamp(itemAuraPeriod, 0, 40);
            this.spawnSound = spawnSound == null ? "BLOCK_FIRE_EXTINGUISH" : spawnSound;
            this.winSound = winSound == null ? "ENTITY_EXPERIENCE_ORB_PICKUP" : winSound;
        }

        Timings() {
            this(160, 6, 20, 15, 3, 0.5D, 0.35D, 0, "BLOCK_FIRE_EXTINGUISH", "ENTITY_EXPERIENCE_ORB_PICKUP");
        }

        private static double clamp(double v, double min, double max) {
            return Math.max(min, Math.min(max, v));
        }
    }
}
