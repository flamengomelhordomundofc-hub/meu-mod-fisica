package com.fisicamod;

import com.mojang.brigadier.arguments.FloatArgumentType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;

@Mod(FisicaMod.MODID)
public class FisicaMod {
    public static final String MODID = "fisicamod";
    private static final int MAX_DEBRIS = 90;
    private static final int MAX_COLLAPSE_PER_TICK = 10;
    private static final int MAX_TREE_LOGS = 48;

    private boolean enableDebris = true;
    private boolean enableExplosions = true;
    private boolean enableBlockGravity = true;
    private boolean enableTrees = true;
    private boolean enableItems = true;
    private boolean enableImpacts = true;
    private float gravityScale = 1.0f;

    private final List<DebrisPiece> debrisList = new ArrayList<>();
    private final Queue<CollapseTask> collapseQueue = new ArrayDeque<>();
    private final Set<BlockPos> queuedCollapseSet = new HashSet<>();

    public FisicaMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    private static final class CollapseTask {
        final ServerLevel level;
        final BlockPos pos;

        CollapseTask(ServerLevel level, BlockPos pos) {
            this.level = level;
            this.pos = pos;
        }
    }

    private static final class DebrisPiece {
        final Display.BlockDisplay entity;
        final ServerLevel level;
        final double ox, oy, oz;
        double x, y, z, vx, vy, vz;
        float rx, ry, rz, avx, avy, avz;
        final float baseScale;
        final boolean buoyant;
        int age;
        final int maxAge;

        DebrisPiece(Display.BlockDisplay entity, ServerLevel level, double ox, double oy, double oz,
                    double x, double y, double z, double vx, double vy, double vz,
                    float avx, float avy, float avz, float baseScale, boolean buoyant, int maxAge) {
            this.entity = entity;
            this.level = level;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.avx = avx;
            this.avy = avy;
            this.avz = avz;
            this.baseScale = baseScale;
            this.buoyant = buoyant;
            this.maxAge = maxAge;
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = event.getPos().immutable();
        BlockState state = event.getState();
        Player player = event.getPlayer();
        if (state.isAir() || !state.getFluidState().isEmpty()) return;

        if (enableDebris) {
            spawnBlockDebris(level, pos, state, 5, 0.22);
        }
        if (enableTrees && state.is(BlockTags.LOGS) && player != null && !player.isShiftKeyDown()) {
            tryFellTree(level, pos, player);
        }
        if (enableBlockGravity) {
            enqueueNeighborsForCollapse(level, pos);
        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!enableBlockGravity) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = event.getPos().immutable();
        BlockState state = event.getState();
        if (getSupportReach(state) >= 0) {
            enqueueCollapse(level, pos);
        }
    }

    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate event) {
        if (!enableExplosions) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Vec3 center = event.getExplosion().getPosition();
        List<BlockPos> blocks = event.getAffectedBlocks();
        RandomSource rand = level.random;
        int launched = 0;
        int debrisCount = 0;
        Iterator<BlockPos> it = blocks.iterator();

        while (it.hasNext()) {
            BlockPos bp = it.next().immutable();
            BlockState bs = level.getBlockState(bp);
            if (bs.isAir() || !bs.getFluidState().isEmpty() || bs.hasBlockEntity() || bs.is(Blocks.TNT) || bs.getDestroySpeed(level, bp) < 0.0f) {
                continue;
            }
            if (launched < 22 && rand.nextFloat() < 0.35f) {
                it.remove();
                Vec3 dir = Vec3.atCenterOf(bp).subtract(center);
                double dist = Math.max(0.5, dir.length());
                Vec3 norm = dir.scale(1.0 / dist);
                double power = 0.55 + rand.nextDouble() * 0.65;
                FallingBlockEntity fb = FallingBlockEntity.fall(level, bp, bs);
                fb.setHurtsEntities(2.5f, 30);
                fb.setDeltaMovement(norm.x * power, Math.max(0.35, norm.y * power + 0.45), norm.z * power);
                fb.hurtMarked = true;
                launched++;
            } else if (enableDebris && debrisCount < 16 && rand.nextFloat() < 0.25f) {
                spawnBlockDebris(level, bp, bs, 2, 0.55);
                debrisCount += 2;
            }
            if (enableBlockGravity && rand.nextFloat() < 0.25f) {
                enqueueCollapse(level, bp.above());
            }
        }

        for (Entity e : event.getAffectedEntities()) {
            if (e instanceof Display) continue;
            Vec3 push = e.position().subtract(center);
            double len = Math.max(0.6, push.length());
            double boost = Math.min(1.4, 2.2 / len);
            e.setDeltaMovement(e.getDeltaMovement().add((push.x / len) * boost, 0.35 + boost * 0.25, (push.z / len) * boost));
            e.hurtMarked = true;
        }
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, center.x, center.y + 0.5, center.z, 18, 1.5, 1.0, 1.5, 0.08);
    }

    @SubscribeEvent
    public void onLivingFall(LivingFallEvent event) {
        if (!enableImpacts) return;
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) return;
        float dist = event.getDistance();
        if (dist < 5.0f) return;

        BlockPos below = entity.blockPosition().below();
        BlockState belowState = level.getBlockState(below);
        if (!belowState.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, belowState), entity.getX(), entity.getY() + 0.1, entity.getZ(), Math.min(40, (int) (dist * 4)), 0.7, 0.15, 0.7, 0.18);
        }

        double radius = Math.min(5.0, dist * 0.45);
        for (Entity near : level.getEntitiesOfClass(Entity.class, entity.getBoundingBox().inflate(radius))) {
            if (near == entity || near instanceof Display) continue;
            Vec3 away = near.position().subtract(entity.position());
            double d = Math.max(0.5, away.length());
            double f = Math.min(0.9, (dist * 0.06) / d);
            near.setDeltaMovement(near.getDeltaMovement().add((away.x / d) * f, 0.25 + f * 0.3, (away.z / d) * f));
            near.hurtMarked = true;
        }

        if (dist >= 6.0f && (belowState.is(Blocks.GLASS) || belowState.is(Blocks.GLASS_PANE) || belowState.is(Blocks.ICE) || belowState.is(Blocks.FROSTED_ICE))) {
            level.destroyBlock(below, false);
            level.playSound(null, below, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 1.0f, 0.9f);
            if (enableDebris) {
                spawnBlockDebris(level, below, belowState, 6, 0.32);
            }
        }
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!enableImpacts) return;
        LivingEntity target = event.getEntity();
        if (!(target.level() instanceof ServerLevel level)) return;
        Entity attacker = event.getSource().getEntity();
        if (attacker != null && event.getAmount() >= 5.0f) {
            Vec3 dir = target.position().subtract(attacker.position());
            double len = Math.max(0.4, Math.hypot(dir.x, dir.z));
            double mult = Math.min(0.65, event.getAmount() * 0.045);
            target.setDeltaMovement(target.getDeltaMovement().add((dir.x / len) * mult, 0.14, (dir.z / len) * mult));
            target.hurtMarked = true;
            level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(), 6, 0.3, 0.3, 0.3, 0.15);
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (!enableImpacts) return;
        LivingEntity dead = event.getEntity();
        if (!(dead.level() instanceof ServerLevel level)) return;
        level.sendParticles(ParticleTypes.POOF, dead.getX(), dead.getY() + dead.getBbHeight() * 0.5, dead.getZ(), 12, 0.4, 0.4, 0.4, 0.08);
        if (enableDebris) {
            BlockState burstState = dead.isOnFire() ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.RED_TERRACOTTA.defaultBlockState();
            spawnBlockDebris(level, dead.blockPosition(), burstState, 4, 0.30);
        }
    }

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;
        tickDebris(level);
        if (enableBlockGravity) {
            tickCollapseQueue(level);
        }
        if (enableItems && level.getGameTime() % 2L == 0L) {
            tickItems(level);
        }
        if (Math.abs(gravityScale - 1.0f) > 0.01f) {
            tickCustomGravity(level);
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Iterator<DebrisPiece> it = debrisList.iterator();
        while (it.hasNext()) {
            DebrisPiece d = it.next();
            if (d.level == level) {
                if (!d.entity.isRemoved()) d.entity.discard();
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        clearAllDebris();
        collapseQueue.clear();
        queuedCollapseSet.clear();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fisica")
            .then(Commands.literal("status").executes(ctx -> {
                String msg = "FisicaMod: fragmentos=" + enableDebris + ", explosoes=" + enableExplosions
                    + ", gravidade_blocos=" + enableBlockGravity + ", arvores=" + enableTrees
                    + ", itens=" + enableItems + ", impactos=" + enableImpacts
                    + ", forca=" + gravityScale + "x, ativos=" + debrisList.size();
                ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                return 1;
            }))
            .then(Commands.literal("fragmentos").executes(ctx -> {
                enableDebris = !enableDebris;
                ctx.getSource().sendSuccess(() -> Component.literal("Fragmentos 3D: " + (enableDebris ? "ATIVADO" : "DESATIVADO")), false);
                return 1;
            }))
            .then(Commands.literal("explosoes").executes(ctx -> {
                enableExplosions = !enableExplosions;
                ctx.getSource().sendSuccess(() -> Component.literal("Explosoes fisicas: " + (enableExplosions ? "ATIVADO" : "DESATIVADO")), false);
                return 1;
            }))
            .then(Commands.literal("gravidade_blocos").executes(ctx -> {
                enableBlockGravity = !enableBlockGravity;
                ctx.getSource().sendSuccess(() -> Component.literal("Desmoronamento de blocos: " + (enableBlockGravity ? "ATIVADO" : "DESATIVADO")), false);
                return 1;
            }))
            .then(Commands.literal("arvores").executes(ctx -> {
                enableTrees = !enableTrees;
                ctx.getSource().sendSuccess(() -> Component.literal("Queda de arvores: " + (enableTrees ? "ATIVADO" : "DESATIVADO")), false);
                return 1;
            }))
            .then(Commands.literal("itens").executes(ctx -> {
                enableItems = !enableItems;
                ctx.getSource().sendSuccess(() -> Component.literal("Fisica de itens: " + (enableItems ? "ATIVADO" : "DESATIVADO")), false);
                return 1;
            }))
            .then(Commands.literal("impactos").executes(ctx -> {
                enableImpacts = !enableImpacts;
                ctx.getSource().sendSuccess(() -> Component.literal("Impactos e combate: " + (enableImpacts ? "ATIVADO" : "DESATIVADO")), false);
                return 1;
            }))
            .then(Commands.literal("forca").then(Commands.argument("valor", FloatArgumentType.floatArg(0.1f, 3.0f)).executes(ctx -> {
                gravityScale = FloatArgumentType.getFloat(ctx, "valor");
                ctx.getSource().sendSuccess(() -> Component.literal("Forca da gravidade: " + gravityScale + "x"), false);
                return 1;
            })))
            .then(Commands.literal("limpar").executes(ctx -> {
                int n = clearAllDebris();
                ctx.getSource().sendSuccess(() -> Component.literal("Fragmentos removidos: " + n), false);
                return 1;
            }))
        );
    }

    private void spawnBlockDebris(ServerLevel level, BlockPos pos, BlockState state, int count, double speed) {
        RandomSource rand = level.random;
        boolean buoyant = state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.WOOL);
        double ox = pos.getX() + 0.5;
        double oy = pos.getY() + 0.5;
        double oz = pos.getZ() + 0.5;

        for (int i = 0; i < count; i++) {
            while (debrisList.size() >= MAX_DEBRIS) {
                DebrisPiece old = debrisList.remove(0);
                if (!old.entity.isRemoved()) old.entity.discard();
            }
            Display.BlockDisplay display = EntityType.BLOCK_DISPLAY.create(level);
            if (display == null) return;
            display.setPos(ox, oy, oz);
            display.addTag("fisicamod_debris");
            display.setNoGravity(true);
            display.setInvulnerable(true);

            double sx = ox + (rand.nextDouble() - 0.5) * 0.55;
            double sy = oy + (rand.nextDouble() - 0.5) * 0.55;
            double sz = oz + (rand.nextDouble() - 0.5) * 0.55;
            double vx = (rand.nextDouble() - 0.5) * 2.0 * speed;
            double vy = 0.10 + rand.nextDouble() * speed * 1.1;
            double vz = (rand.nextDouble() - 0.5) * 2.0 * speed;
            float avx = (rand.nextFloat() - 0.5f) * 0.45f;
            float avy = (rand.nextFloat() - 0.5f) * 0.45f;
            float avz = (rand.nextFloat() - 0.5f) * 0.45f;
            float scale = 0.22f + rand.nextFloat() * 0.14f;
            int maxAge = 45 + rand.nextInt(35);

            CompoundTag tag = new CompoundTag();
            display.saveWithoutId(tag);
            tag.put("block_state", NbtUtils.writeBlockState(state));
            tag.putInt("interpolation_duration", 2);
            tag.putInt("start_interpolation", 0);
            tag.putFloat("shadow_radius", 0.1f);
            tag.putFloat("view_range", 0.75f);
            tag.put("transformation", buildTransformTag((float) (sx - ox - scale * 0.5f), (float) (sy - oy), (float) (sz - oz - scale * 0.5f), 0f, 0f, 0f, scale));
            display.load(tag);
            level.addFreshEntity(display);
            debrisList.add(new DebrisPiece(display, level, ox, oy, oz, sx, sy, sz, vx, vy, vz, avx, avy, avz, scale, buoyant, maxAge));
        }
    }

    private CompoundTag buildTransformTag(float tx, float ty, float tz, float rx, float ry, float rz, float scale) {
        CompoundTag t = new CompoundTag();
        ListTag tr = new ListTag();
        tr.add(FloatTag.valueOf(tx));
        tr.add(FloatTag.valueOf(ty));
        tr.add(FloatTag.valueOf(tz));
        t.put("translation", tr);

        ListTag sc = new ListTag();
        sc.add(FloatTag.valueOf(scale));
        sc.add(FloatTag.valueOf(scale));
        sc.add(FloatTag.valueOf(scale));
        t.put("scale", sc);

        Quaternionf q = new Quaternionf().rotationXYZ(rx, ry, rz);
        ListTag lr = new ListTag();
        lr.add(FloatTag.valueOf(q.x));
        lr.add(FloatTag.valueOf(q.y));
        lr.add(FloatTag.valueOf(q.z));
        lr.add(FloatTag.valueOf(q.w));
        t.put("left_rotation", lr);

        ListTag rr = new ListTag();
        rr.add(FloatTag.valueOf(0f));
        rr.add(FloatTag.valueOf(0f));
        rr.add(FloatTag.valueOf(0f));
        rr.add(FloatTag.valueOf(1f));
        t.put("right_rotation", rr);
        return t;
    }

    private void tickDebris(ServerLevel level) {
        Iterator<DebrisPiece> it = debrisList.iterator();
        while (it.hasNext()) {
            DebrisPiece d = it.next();
            if (d.level != level) continue;
            d.age++;
            if (d.age >= d.maxAge || d.entity.isRemoved() || !d.entity.isAlive()) {
                if (!d.entity.isRemoved()) d.entity.discard();
                it.remove();
                continue;
            }
            BlockPos cur = BlockPos.containing(d.x, d.y, d.z);
            boolean inWater = level.isLoaded(cur) && level.getFluidState(cur).is(FluidTags.WATER);
            if (inWater) {
                d.vy += d.buoyant ? 0.032 : -0.015 * gravityScale;
                d.vx *= 0.85;
                d.vy *= 0.82;
                d.vz *= 0.85;
            } else {
                d.vy -= 0.055 * gravityScale;
                d.vx *= 0.985;
                d.vz *= 0.985;
            }

            double nx = d.x + d.vx;
            double ny = d.y + d.vy;
            double nz = d.z + d.vz;

            BlockPos yPos = BlockPos.containing(d.x, ny, d.z);
            if (level.isLoaded(yPos) && level.getBlockState(yPos).blocksMotion()) {
                if (d.vy < 0.0) {
                    ny = Math.floor(d.y);
                    d.vy = -d.vy * 0.42;
                    if (Math.abs(d.vy) < 0.04) d.vy = 0.0;
                    d.vx *= 0.72;
                    d.vz *= 0.72;
                    d.avx *= 0.65;
                    d.avy *= 0.65;
                    d.avz *= 0.65;
                } else {
                    ny = d.y;
                    d.vy = -d.vy * 0.3;
                }
            }

            BlockPos xPos = BlockPos.containing(nx, ny, d.z);
            if (level.isLoaded(xPos) && level.getBlockState(xPos).blocksMotion()) {
                nx = d.x;
                d.vx = -d.vx * 0.45;
            }

            BlockPos zPos = BlockPos.containing(nx, ny, nz);
            if (level.isLoaded(zPos) && level.getBlockState(zPos).blocksMotion()) {
                nz = d.z;
                d.vz = -d.vz * 0.45;
            }

            d.x = nx;
            d.y = ny;
            d.z = nz;
            d.rx += d.avx;
            d.ry += d.avy;
            d.rz += d.avz;

            if (d.age % 2 == 0 || d.age >= d.maxAge - 2) {
                float s = d.baseScale;
                if (d.age > d.maxAge - 12) {
                    s = Math.max(0.01f, d.baseScale * ((d.maxAge - d.age) / 12.0f));
                }
                CompoundTag tag = new CompoundTag();
                d.entity.saveWithoutId(tag);
                tag.putInt("interpolation_duration", 2);
                tag.putInt("start_interpolation", 0);
                tag.put("transformation", buildTransformTag((float) (d.x - d.ox - s * 0.5f), (float) (d.y - d.oy), (float) (d.z - d.oz - s * 0.5f), d.rx, d.ry, d.rz, s));
                d.entity.load(tag);
            }
        }
    }

    private void tryFellTree(ServerLevel level, BlockPos origin, Player player) {
        Queue<BlockPos> q = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> logs = new ArrayList<>();
        q.add(origin);
        visited.add(origin);

        while (!q.isEmpty() && logs.size() < MAX_TREE_LOGS) {
            BlockPos cur = q.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos next = cur.offset(dx, dy, dz);
                        if (visited.add(next) && level.isLoaded(next) && level.getBlockState(next).is(BlockTags.LOGS)) {
                            logs.add(next);
                            q.add(next);
                            if (logs.size() >= MAX_TREE_LOGS) break;
                        }
                    }
                    if (logs.size() >= MAX_TREE_LOGS) break;
                }
                if (logs.size() >= MAX_TREE_LOGS) break;
            }
        }
        if (logs.size() < 2) return;

        int leaves = 0;
        BlockPos topLog = logs.get(logs.size() - 1);
        for (int dx = -2; dx <= 2 && leaves < 3; dx++) {
            for (int dy = -1; dy <= 2 && leaves < 3; dy++) {
                for (int dz = -2; dz <= 2 && leaves < 3; dz++) {
                    BlockPos lp = topLog.offset(dx, dy, dz);
                    if (level.isLoaded(lp) && level.getBlockState(lp).is(BlockTags.LEAVES)) leaves++;
                }
            }
        }
        if (leaves < 3) return;

        logs.sort(Comparator.comparingInt(BlockPos::getY));
        Vec3 look = player.getLookAngle();
        double hl = Math.hypot(look.x, look.z);
        double fx = hl > 0.01 ? look.x / hl : 1.0;
        double fz = hl > 0.01 ? look.z / hl : 0.0;
        int leafDebris = 0;

        for (BlockPos lp : logs) {
            BlockState ls = level.getBlockState(lp);
            if (!ls.is(BlockTags.LOGS)) continue;
            double tilt = Math.min(0.65, (lp.getY() - origin.getY()) * 0.05 + 0.08);
            FallingBlockEntity fb = FallingBlockEntity.fall(level, lp, ls);
            fb.setHurtsEntities(1.5f, 20);
            fb.setDeltaMovement(fx * tilt, 0.05, fz * tilt);
            fb.hurtMarked = true;

            if (enableDebris && leafDebris < 8) {
                for (Direction dir : Direction.values()) {
                    BlockPos np = lp.relative(dir);
                    BlockState ns = level.getBlockState(np);
                    if (ns.is(BlockTags.LEAVES)) {
                        spawnBlockDebris(level, np, ns, 1, 0.28);
                        leafDebris++;
                        break;
                    }
                }
            }
        }
        level.playSound(null, origin, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.2f, 0.7f);
    }

    private int getSupportReach(BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity() || state.is(BlockTags.LOGS)) return -1;
        if (state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.PODZOL)
            || state.is(Blocks.MYCELIUM) || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.MUD) || state.is(Blocks.CLAY)
            || state.is(Blocks.SOUL_SOIL) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.COBBLESTONE)
            || state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.NETHERRACK) || state.is(Blocks.GRAVEL)
            || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)) return 1;
        if (state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.ANDESITE) || state.is(Blocks.DIORITE)
            || state.is(Blocks.GRANITE) || state.is(Blocks.TUFF) || state.is(Blocks.SANDSTONE)
            || state.is(Blocks.RED_SANDSTONE) || state.is(Blocks.BLACKSTONE)) return 2;
        return -1;
    }

    private boolean hasStructuralSupport(ServerLevel level, BlockPos start, int maxReach) {
        if (level.getBlockState(start.below()).blocksMotion()) return true;
        if (maxReach <= 0) return false;
        Queue<BlockPos> q = new ArrayDeque<>();
        Set<BlockPos> vis = new HashSet<>();
        q.add(start);
        vis.add(start);

        while (!q.isEmpty()) {
            BlockPos cur = q.poll();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos next = cur.relative(d);
                int dist = Math.abs(next.getX() - start.getX()) + Math.abs(next.getZ() - start.getZ());
                if (dist > maxReach || !vis.add(next) || !level.isLoaded(next)) continue;
                BlockState ns = level.getBlockState(next);
                if (!ns.blocksMotion()) continue;
                if (level.getBlockState(next.below()).blocksMotion()) return true;
                q.add(next);
            }
        }
        return false;
    }

    private void enqueueCollapse(ServerLevel level, BlockPos pos) {
        BlockPos imm = pos.immutable();
        if (queuedCollapseSet.add(imm)) {
            collapseQueue.add(new CollapseTask(level, imm));
        }
    }

    private void enqueueNeighborsForCollapse(ServerLevel level, BlockPos center) {
        enqueueCollapse(level, center.above());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos h = center.relative(d);
            enqueueCollapse(level, h);
            enqueueCollapse(level, h.above());
        }
    }

    private void tickCollapseQueue(ServerLevel level) {
        int steps = Math.min(MAX_COLLAPSE_PER_TICK, collapseQueue.size());
        for (int i = 0; i < steps; i++) {
            CollapseTask task = collapseQueue.poll();
            if (task == null) break;
            if (task.level != level) {
                collapseQueue.add(task);
                continue;
            }
            queuedCollapseSet.remove(task.pos);
            if (!level.isLoaded(task.pos) || task.pos.getY() <= level.getMinBuildHeight() + 1) continue;
            BlockState st = level.getBlockState(task.pos);
            int reach = getSupportReach(st);
            if (reach < 0 || st.hasBlockEntity() || st.getDestroySpeed(level, task.pos) < 0.0f) continue;
            if (!hasStructuralSupport(level, task.pos, reach)) {
                FallingBlockEntity fb = FallingBlockEntity.fall(level, task.pos, st);
                fb.setHurtsEntities(2.0f, 30);
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, st), task.pos.getX() + 0.5, task.pos.getY() + 0.2, task.pos.getZ() + 0.5, 6, 0.3, 0.1, 0.3, 0.05);
                enqueueNeighborsForCollapse(level, task.pos);
            }
        }
    }

    private void tickItems(ServerLevel level) {
        Set<Integer> done = new HashSet<>();
        for (Player p : level.players()) {
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(24.0))) {
                if (!done.add(item.getId())) continue;
                ItemStack stack = item.getItem();
                Vec3 vel = item.getDeltaMovement();
                if (item.isInWater()) {
                    if (isBuoyantItem(stack)) {
                        boolean waterAbove = level.getFluidState(item.blockPosition().above()).is(FluidTags.WATER);
                        double vy = waterAbove ? Math.min(vel.y + 0.06, 0.14) : (Math.sin(level.getGameTime() * 0.25 + item.getId()) * 0.015 + 0.012);
                        item.setDeltaMovement(vel.x * 0.92, vy, vel.z * 0.92);
                        item.hurtMarked = true;
                    } else if (isHeavyItem(stack)) {
                        item.setDeltaMovement(vel.x * 0.86, Math.max(vel.y - 0.045, -0.35), vel.z * 0.86);
                        item.hurtMarked = true;
                    }
                } else if (item.onGround() && vel.y < -0.25) {
                    item.setDeltaMovement(vel.x * 0.85, -vel.y * 0.45, vel.z * 0.85);
                    item.hurtMarked = true;
                }
            }
        }
    }

    private void tickCustomGravity(ServerLevel level) {
        double deltaY = 0.08 * (1.0 - gravityScale);
        Set<Integer> done = new HashSet<>();
        for (Player p : level.players()) {
            if (done.add(p.getId()) && !p.onGround() && !p.isInWater() && !p.getAbilities().flying && !p.isFallFlying()) {
                Vec3 v = p.getDeltaMovement();
                p.setDeltaMovement(v.x, v.y + deltaY, v.z);
                p.hurtMarked = true;
                if (gravityScale < 1.0f) p.fallDistance *= gravityScale;
            }
            for (Entity e : level.getEntitiesOfClass(Entity.class, p.getBoundingBox().inflate(24.0))) {
                if (!done.add(e.getId()) || e instanceof Player || e instanceof Display || e.onGround() || e.isInWater() || e.isNoGravity()) continue;
                if (e instanceof LivingEntity || e instanceof FallingBlockEntity || e instanceof ItemEntity) {
                    Vec3 v = e.getDeltaMovement();
                    e.setDeltaMovement(v.x, v.y + deltaY * 0.65, v.z);
                    e.hurtMarked = true;
                }
            }
        }
    }

    private boolean isBuoyantItem(ItemStack stack) {
        return stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS) || stack.is(ItemTags.LEAVES) || stack.is(ItemTags.WOOL)
            || stack.is(ItemTags.BOATS) || stack.is(ItemTags.SIGNS) || stack.isEdible() || stack.is(Items.STICK)
            || stack.is(Items.PAPER) || stack.is(Items.FEATHER) || stack.is(Items.STRING);
    }

    private boolean isHeavyItem(ItemStack stack) {
        if (stack.is(Items.IRON_INGOT) || stack.is(Items.GOLD_INGOT) || stack.is(Items.DIAMOND) || stack.is(Items.NETHERITE_INGOT)
            || stack.is(Items.ANVIL) || stack.is(Items.RAW_IRON) || stack.is(Items.RAW_GOLD)) return true;
        if (stack.getItem() instanceof BlockItem bi) return bi.getBlock().defaultBlockState().is(BlockTags.MINEABLE_WITH_PICKAXE);
        return false;
    }

    private int clearAllDebris() {
        int count = debrisList.size();
        for (DebrisPiece d : debrisList) {
            if (!d.entity.isRemoved()) d.entity.discard();
        }
        debrisList.clear();
        return count;
    }
}
