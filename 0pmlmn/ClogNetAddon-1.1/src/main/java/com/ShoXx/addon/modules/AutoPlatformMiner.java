package com.ShoXx.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import com.ShoXx.addon.AddonTemplate;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class AutoPlatformMiner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> mineDelay = sgGeneral.add(new IntSetting.Builder()
        .name("mine-delay")
        .description("Delay before starting to mine the next block (in ticks).")
        .defaultValue(0)
        .min(0)
        .max(64)
        .sliderMax(64)
        .build()
    );

    private final Setting<Integer> moveDelay = sgGeneral.add(new IntSetting.Builder()
        .name("move-delay")
        .description("Delay after mining before moving backwards (in ticks).")
        .defaultValue(20)
        .min(1)
        .sliderMax(100)
        .build()
    );

    private final Setting<Boolean> autoWalk = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-walk")
        .description("Automatically walk backwards after mining using Baritone.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> requirePickaxe = sgGeneral.add(new BoolSetting.Builder()
        .name("require-pickaxe")
        .description("When ON: Hold down to break blocks (survival mode). When OFF: Fast tap blocks (creative mode).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> legitRotation = sgGeneral.add(new BoolSetting.Builder()
        .name("legit-rotation")
        .description("Rotate to look at blocks server-side while allowing free camera movement.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> diagonalMining = sgGeneral.add(new BoolSetting.Builder()
        .name("diagonal-mining")
        .description("Mine blocks in a diagonal pattern instead of straight line.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> miningTime = sgGeneral.add(new DoubleSetting.Builder()
        .name("mining-time")
        .description("How long to hold mining action before breaking the block (in seconds).")
        .defaultValue(2.55)
        .min(0.01)
        .max(5.00)
        .sliderMin(0.01)
        .sliderMax(5.00)
        .build()
    );

    // Render settings
    private final Setting<Boolean> renderOverlay = sgRender.add(new BoolSetting.Builder()
        .name("render-overlay")
        .description("Render block overlay for blocks to be mined.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the rendered blocks.")
        .defaultValue(new SettingColor(255, 0, 0, 50))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the rendered blocks.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .build()
    );

    private int currentBlock = 0;
    private int mineDelayTicks = 0;
    private int moveTicks = 0;
    private State state = State.MINING;
    private List<BlockPos> targetBlocks = new ArrayList<>();
    private boolean baritoneAvailable = false;
    private BlockPos currentMiningBlock = null;
    private boolean isMining = false;
    private Direction currentMiningDirection = Direction.UP;
    private long miningStartTime = 0;


    private enum State {
        MINING,
        WAITING_TO_MOVE,
        MOVING
    }

    public AutoPlatformMiner() {
        super(AddonTemplate.CATEGORY, "auto-platform-mine", "Automatically mines obsidian blocks in a 5-block wide platform 1 level below player, then moves backwards.");

        // Check if Baritone is available
        try {
            Class.forName("baritone.api.BaritoneAPI");
            baritoneAvailable = true;
            info("Baritone detected - auto-walk feature available");
        } catch (ClassNotFoundException e) {
            baritoneAvailable = false;
            info("Baritone not detected - auto-walk feature disabled");
        }
    }

    @Override
    public void onActivate() {
        currentBlock = 0;
        mineDelayTicks = 0;
        moveTicks = 0;
        state = State.MINING;
        currentMiningBlock = null;
        isMining = false;
        miningStartTime = 0;
        updateTargetBlocks();
    }

    @Override
    public void onDeactivate() {
        targetBlocks.clear();
        stopMining();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        switch (state) {
            case MINING:
                mine();
                break;
            case WAITING_TO_MOVE:
                waitToMove();
                break;
            case MOVING:
                handleMoving();
                break;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderOverlay.get() || mc.player == null) return;

        // Update target blocks for rendering
        updateTargetBlocks();

        // Render each target block
        for (BlockPos pos : targetBlocks) {
            Block block = mc.world.getBlockState(pos).getBlock();

            // Only render obsidian blocks
            if (block == Blocks.OBSIDIAN) {
                event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        }
    }

    private void updateTargetBlocks() {
        targetBlocks.clear();

        if (mc.player == null) return;

        if (diagonalMining.get()) {
            updateDiagonalTargetBlocks();
        } else {
            updateRegularTargetBlocks();
        }
    }

    private void updateRegularTargetBlocks() {
        Direction facing = mc.player.getHorizontalFacing();
        BlockPos playerPos = mc.player.getBlockPos();

        // Calculate the 5 block positions to mine
        // 1 level below player, 2 blocks in front, spanning 5 blocks wide
        BlockPos centerPos = playerPos.offset(facing, 2).down();

        // Add center block
        targetBlocks.add(centerPos);

        // Add blocks to the left and right (2 on each side)
        Direction rightDir = facing.rotateYClockwise();
        Direction leftDir = facing.rotateYCounterclockwise();

        targetBlocks.add(centerPos.offset(rightDir, 1));
        targetBlocks.add(centerPos.offset(rightDir, 2));
        targetBlocks.add(centerPos.offset(leftDir, 1));
        targetBlocks.add(centerPos.offset(leftDir, 2));
    }

    private void updateDiagonalTargetBlocks() {
        BlockPos playerPos = mc.player.getBlockPos();

        // Get the actual yaw angle to determine diagonal direction
        float yaw = mc.player.getYaw();

        // Normalize yaw to 0-360 range
        while (yaw < 0) yaw += 360;
        while (yaw >= 360) yaw -= 360;

        // Determine diagonal direction based on yaw
        // North = 0/360, East = 90, South = 180, West = 270
        // Northeast = 45, Southeast = 135, Southwest = 225, Northwest = 315
        String diagonalDirection = getDiagonalDirection(yaw);

        addDiagonalBlocks(playerPos, diagonalDirection);
    }

    private String getDiagonalDirection(float yaw) {
        // Determine which diagonal direction the player is facing
        // Minecraft yaw: North = 180, East = 270, South = 0, West = 90
        // But we need to convert this to a more intuitive system

        // Normalize yaw to 0-360 and convert to standard compass directions
        // where North = 0, East = 90, South = 180, West = 270
        float normalizedYaw = (yaw + 180) % 360;
        if (normalizedYaw < 0) normalizedYaw += 360;

        // Determine diagonal direction with 45-degree sectors
        if (normalizedYaw >= 337.5 || normalizedYaw < 22.5) {
            return "north";
        } else if (normalizedYaw >= 22.5 && normalizedYaw < 67.5) {
            return "northeast";
        } else if (normalizedYaw >= 67.5 && normalizedYaw < 112.5) {
            return "east";
        } else if (normalizedYaw >= 112.5 && normalizedYaw < 157.5) {
            return "southeast";
        } else if (normalizedYaw >= 157.5 && normalizedYaw < 202.5) {
            return "south";
        } else if (normalizedYaw >= 202.5 && normalizedYaw < 247.5) {
            return "southwest";
        } else if (normalizedYaw >= 247.5 && normalizedYaw < 292.5) {
            return "west";
        } else if (normalizedYaw >= 292.5 && normalizedYaw < 337.5) {
            return "northwest";
        }

        return "northeast"; // default
    }

    private void addDiagonalBlocks(BlockPos playerPos, String direction) {
        int y = playerPos.getY() - 1; // 1 level below player

        // Original pattern from your example (northeast direction):
        // (0,-4), (0,3), (1,-3), (1,-2), (2,-2), (2,-1), (3,-1), (3,0), (4,0)
        int[][] basePattern = {
            {0, -4}, {0, -3}, {1, -3}, {1, -2}, {2, -2}, {2, -1}, {3, -1}, {3, 0}, {4, 0}
        };

        for (int[] offset : basePattern) {
            int x = offset[0];
            int z = offset[1];
            int worldX, worldZ;

            // Transform coordinates based on direction
            switch (direction) {
                case "northeast":
                    worldX = playerPos.getX() + x;
                    worldZ = playerPos.getZ() + z;
                    break;
                case "southeast":
                    worldX = playerPos.getX() + z;
                    worldZ = playerPos.getZ() - x;
                    break;
                case "southwest":
                    worldX = playerPos.getX() - x;
                    worldZ = playerPos.getZ() - z;
                    break;
                case "northwest":
                    worldX = playerPos.getX() - z;
                    worldZ = playerPos.getZ() + x;
                    break;
                default: // fallback to northeast
                    worldX = playerPos.getX() + x;
                    worldZ = playerPos.getZ() + z;
                    break;
            }

            targetBlocks.add(new BlockPos(worldX, y, worldZ));
        }
    }

    private void mine() {
        // Update target blocks in case player moved
        updateTargetBlocks();

        if (requirePickaxe.get()) {
            // Survival mode: Hold down to break blocks
            mineWithPickaxe();
        } else {
            // Creative mode: Fast tap blocks
            mineCreative();
        }
    }

    private void mineCreative() {
        // Check if we need to wait for delay
        if (mineDelayTicks > 0) {
            mineDelayTicks--;
            return;
        }

        // Mine blocks one by one
        for (BlockPos pos : targetBlocks) {
            Block block = mc.world.getBlockState(pos).getBlock();

            // Only mine obsidian blocks
            if (block != Blocks.OBSIDIAN) continue;

            // Try to mine the block
            if (BlockUtils.canBreak(pos)) {
                if (legitRotation.get()) {
                    // Rotate to look at the block and break it
                    Vec3d hitVec = Vec3d.ofCenter(pos);
                    Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec));
                }

                BlockUtils.breakBlock(pos, true);
                mineDelayTicks = mineDelay.get(); // Set delay for next block
                return; // Mine one block per tick
            }
        }

        // All blocks mined, transition to waiting state
        state = State.WAITING_TO_MOVE;
        moveTicks = moveDelay.get();
    }

    private void mineWithPickaxe() {
        // Check if we need a pickaxe
        FindItemResult pickaxe = InvUtils.findInHotbar(itemStack ->
            itemStack.getItem() == Items.WOODEN_PICKAXE ||
                itemStack.getItem() == Items.STONE_PICKAXE ||
                itemStack.getItem() == Items.IRON_PICKAXE ||
                itemStack.getItem() == Items.GOLDEN_PICKAXE ||
                itemStack.getItem() == Items.DIAMOND_PICKAXE ||
                itemStack.getItem() == Items.NETHERITE_PICKAXE
        );

        if (!pickaxe.found()) {
            error("No pickaxe found in hotbar!");
            toggle();
            return;
        }

        InvUtils.swap(pickaxe.slot(), false);

        // If we're not currently mining a block, find the next one
        if (currentMiningBlock == null || mc.world.getBlockState(currentMiningBlock).getBlock() == Blocks.AIR) {
            // If the current block was broken and we haven't set a delay yet, set it now
            if (currentMiningBlock != null && mc.world.getBlockState(currentMiningBlock).getBlock() == Blocks.AIR && mineDelayTicks == 0) {
                mineDelayTicks = mineDelay.get();
                currentMiningBlock = null;
                isMining = false;
            }

            // Check if we need to wait for delay before starting next block
            if (mineDelayTicks > 0) {
                mineDelayTicks--;
                return;
            }

            // Stop any previous mining
            if (isMining) {
                stopMining();
            }

            // Find next block to mine
            currentMiningBlock = getNextBlockToMine();

            if (currentMiningBlock == null) {
                // All blocks mined, transition to waiting state
                state = State.WAITING_TO_MOVE;
                moveTicks = moveDelay.get();
                return;
            }

            // Start mining the new block
            startMining(currentMiningBlock);
        }

        // Continue mining the current block
        if (isMining && currentMiningBlock != null) {
            // Keep rotating to the block if legit rotation is enabled
            if (legitRotation.get()) {
                Vec3d hitVec = Vec3d.ofCenter(currentMiningBlock);
                Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec));
            }

            // Check if mining time has elapsed
            long miningTimeMs = (long) (miningTime.get() * 1000); // Convert seconds to milliseconds
            if (System.currentTimeMillis() - miningStartTime >= miningTimeMs) {
                info("Mining time elapsed for block at " + currentMiningBlock.toString());
                // Send stop packet to complete the mining
                sendStopMiningPacket(currentMiningBlock, currentMiningDirection);
                isMining = false;
                currentMiningBlock = null;
                miningStartTime = 0;
                mineDelayTicks = mineDelay.get(); // Set delay before next block
                return;
            }

            // Check if block is already broken (in case it breaks faster than expected)
            if (mc.world.getBlockState(currentMiningBlock).getBlock() == Blocks.AIR) {
                info("Block already broken at " + currentMiningBlock.toString());
                isMining = false;
                currentMiningBlock = null;
                miningStartTime = 0;
                mineDelayTicks = mineDelay.get(); // Set delay before next block
                return;
            }
        }
    }

    private BlockPos getNextBlockToMine() {
        for (BlockPos pos : targetBlocks) {
            Block block = mc.world.getBlockState(pos).getBlock();

            // Only mine obsidian blocks
            if (block != Blocks.OBSIDIAN) continue;

            if (BlockUtils.canBreak(pos)) {
                return pos;
            }
        }
        return null;
    }

    private void startMining(BlockPos pos) {
        if (pos == null) return;

        currentMiningBlock = pos;
        isMining = true;
        miningStartTime = System.currentTimeMillis();

        // Find the best direction to mine from
        currentMiningDirection = getBestMiningDirection(pos);

        if (legitRotation.get()) {
            // Rotate to look at the block
            Vec3d hitVec = Vec3d.ofCenter(pos);
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec));
        }

        // Send start mining packet
        sendStartMiningPacket(pos, currentMiningDirection);
    }

    private void stopMining() {
        if (isMining && currentMiningBlock != null) {
            sendStopMiningPacket(currentMiningBlock, currentMiningDirection);
        }
        isMining = false;
        currentMiningBlock = null;
        miningStartTime = 0;
    }

    private Direction getBestMiningDirection(BlockPos pos) {
        Vec3d playerPos = mc.player.getPos();
        Vec3d blockCenter = Vec3d.ofCenter(pos);
        Vec3d diff = blockCenter.subtract(playerPos);

        // Find the direction with the largest component
        double absX = Math.abs(diff.x);
        double absY = Math.abs(diff.y);
        double absZ = Math.abs(diff.z);

        if (absY > absX && absY > absZ) {
            return diff.y > 0 ? Direction.UP : Direction.DOWN;
        } else if (absX > absZ) {
            return diff.x > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return diff.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    private void sendStartMiningPacket(BlockPos pos, Direction direction) {
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
            pos,
            direction
        ));
        mc.player.swingHand(Hand.MAIN_HAND);
        info("Started mining block at " + pos.toString());
    }

    private void sendStopMiningPacket(BlockPos pos, Direction direction) {
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
            pos,
            direction
        ));
        info("Stopped mining block at " + pos.toString());
    }

    private void waitToMove() {
        if (moveTicks > 0) {
            moveTicks--;
            return;
        }

        // Start moving backwards
        if (autoWalk.get() && baritoneAvailable) {
            startBaritoneWalk();
            state = State.MOVING;
            moveTicks = 60; // Wait up to 3 seconds for movement
        } else {
            // Reset for next mining cycle
            currentBlock = 0;
            state = State.MINING;
        }
    }

    private void startBaritoneWalk() {
        if (!baritoneAvailable) return;

        try {
            String command;

            if (diagonalMining.get()) {
                // For diagonal mining, move diagonally backwards
                float yaw = mc.player.getYaw();
                while (yaw < 0) yaw += 360;
                while (yaw >= 360) yaw -= 360;

                String direction = getDiagonalDirection(yaw);

                switch (direction) {
                    case "northeast": // Facing northeast -> move southwest
                        command = "#goto ~-1 ~ ~1";
                        break;
                    case "southeast": // Facing southeast -> move northwest
                        command = "#goto ~-1 ~ ~-1";
                        break;
                    case "southwest": // Facing southwest -> move northeast
                        command = "#goto ~1 ~ ~-1";
                        break;
                    case "northwest": // Facing northwest -> move southeast
                        command = "#goto ~1 ~ ~1";
                        break;
                    default:
                        command = "#goto ~-1 ~ ~1"; // fallback to southwest
                        break;
                }
                info("Started Baritone diagonal auto-walk: moving diagonally backwards from " + direction);
            } else {
                // Regular straight backwards movement
                Direction facing = mc.player.getHorizontalFacing();
                switch (facing) {
                    case NORTH: // Facing North, move South
                        command = "#goto ~ ~ ~+1";
                        break;
                    case EAST: // Facing East, move West
                        command = "#goto ~-1 ~ ~";
                        break;
                    case SOUTH: // Facing South, move North
                        command = "#goto ~ ~ ~-1";
                        break;
                    case WEST: // Facing West, move East
                        command = "#goto ~+1 ~ ~";
                        break;
                    default:
                        command = "#goto ~ ~ ~+1"; // fallback
                        break;
                }
                info("Started Baritone auto-walk: moving 1 block backwards from " + facing.toString().toLowerCase());
            }

            mc.getNetworkHandler().sendChatMessage(command);

        } catch (Exception e) {
            error("Failed to start Baritone walk: " + e.getMessage());
            // Fallback to mining state
            currentBlock = 0;
            state = State.MINING;
        }
    }

    private void handleMoving() {
        if (moveTicks > 0) {
            moveTicks--;

            // Check if player is still moving
            if (baritoneAvailable) {
                try {
                    Vec3d velocity = mc.player.getVelocity();
                    double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);

                    if (speed > 0.1) {
                        // Still moving, reset timer
                        moveTicks = 20;
                        return;
                    }
                } catch (Exception e) {
                    // If there's an error checking movement, just continue
                }
            }

            return;
        }

        // Movement completed, reset for next mining cycle
        currentBlock = 0;
        state = State.MINING;
    }
}
