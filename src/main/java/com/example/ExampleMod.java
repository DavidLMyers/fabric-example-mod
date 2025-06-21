package com.example; // Ensure this package matches your project setup

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.noise.PerlinNoiseSampler;
import net.minecraft.util.math.random.LocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Random;

// Static imports for cleaner command registration
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class ExampleMod implements ModInitializer {
    public static final String MOD_ID = "modid";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // --- Noise Samplers for Terrain Generation ---
    private static PerlinNoiseSampler heightNoise;
    private static PerlinNoiseSampler detailNoise;
    private static PerlinNoiseSampler materialNoise;
    private static PerlinNoiseSampler cragNoise;

    /**
     * A static inner class to hold all magic numbers and configurable parameters.
     * This centralizes configuration, making the code cleaner and easier to tweak.
     */
    private static final class Constants {
        // --- General ---
        public static final long NOISE_SEED = 1234567890L;
        public static final int COMMAND_PERMISSION_LEVEL = 2;

        // --- Mountain Generation (getMountainHeightAt) ---
        public static final double HEIGHT_NOISE_SCALE = 0.007;
        public static final double DETAIL_NOISE_SCALE = 0.04;
        public static final double CRAG_NOISE_SCALE = 0.12;
        public static final double SLOPE_STEEPNESS_EXPONENT = 3.5;
        public static final double SLOPE_FALLOFF_FACTOR = 0.95;
        public static final double HEIGHT_VARIATION_FACTOR = 0.35;
        public static final double DETAIL_VARIATION_FACTOR = 0.12;
        public static final double CRAG_VARIATION_FACTOR = 0.04;
        public static final double PLATEAU_RADIUS_FACTOR = 0.25; // Percentage of radius for the flat top
        public static final double PLATEAU_SMOOTHING_EXPONENT = 3.0;

        // --- Mountain Materials (generateMountainChunk) ---
        public static final double MATERIAL_NOISE_SCALE = 0.06;
        public static final int FLATNESS_THRESHOLD = 3; // Max height diff to neighbors to be "flat"
        public static final int SNOW_CAP_Y_LEVEL = 250;
        public static final int BLUE_ICE_Y_LEVEL = 280;
        public static final double MOSS_LAYER_DEPTH = 2.0;
        public static final double GRAVEL_HEIGHT_PERCENTAGE = 0.2; // Gravel appears in the bottom 20%
        public static final double ANDESITE_THRESHOLD = 0.7;
        public static final double DIORITE_THRESHOLD = 0.4;
        public static final double GRAVEL_THRESHOLD = 0.1;
        public static final double BLUE_ICE_MATERIAL_THRESHOLD = 0.7;
        public static final double SNOW_MATERIAL_THRESHOLD = 0.4;

        // --- Tiering / Carving (tierArea) ---
        public static final double CARVER_BASE_DISPLACEMENT_FACTOR = 2.0;
        public static final double SECONDARY_SHIFT_MIN_MAGNITUDE = 10.0;
        public static final double SECONDARY_SHIFT_MAX_MAGNITUDE = 30.0;
        
        // --- Smoothing (smoothArea) ---
        public static final int PILLAR_NEIGHBOR_COUNT_THRESHOLD = 7; // How many neighbors must be shorter to be a pillar
        public static final int PILLAR_HEIGHT_DIFFERENCE_THRESHOLD = 4; // How much taller a pillar must be than its neighbors
        public static final float PILLAR_SMOOTH_STRENGTH = 0.5f; // How aggressively to lower pillars (0-1)


        // --- Command Argument Constraints ---
        public static final int MIN_RADIUS = 20;
        public static final int MAX_RADIUS = 250;
        public static final int MIN_HEIGHT = 100;
        public static final int MAX_HEIGHT = 320;
        public static final int MIN_CLEAR_RADIUS = 1;
        public static final int MAX_CLEAR_RADIUS = 250;
        public static final int MIN_CLEAR_HEIGHT = 1;
        public static final int MAX_CLEAR_HEIGHT = 400;
    }


    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Example Mod: {}!", MOD_ID);

        // Initialize noise samplers with slightly different seeds for more organic, non-overlapping patterns.
        heightNoise = new PerlinNoiseSampler(new LocalRandom(Constants.NOISE_SEED));
        detailNoise = new PerlinNoiseSampler(new LocalRandom(Constants.NOISE_SEED + 1));
        materialNoise = new PerlinNoiseSampler(new LocalRandom(Constants.NOISE_SEED + 2));
        cragNoise = new PerlinNoiseSampler(new LocalRandom(Constants.NOISE_SEED + 3));

        registerCommands();
    }

    /**
     * Registers all the commands for this mod.
     */
    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            // --- /generatemountain command ---
            dispatcher.register(literal("generatemountain")
                .requires(source -> source.hasPermissionLevel(Constants.COMMAND_PERMISSION_LEVEL))
                .then(argument("x", IntegerArgumentType.integer())
                .then(argument("y", IntegerArgumentType.integer())
                .then(argument("z", IntegerArgumentType.integer())
                .then(argument("radius", IntegerArgumentType.integer(Constants.MIN_RADIUS, Constants.MAX_RADIUS))
                .then(argument("maxHeight", IntegerArgumentType.integer(Constants.MIN_HEIGHT, Constants.MAX_HEIGHT))
                    .executes(context -> {
                        final ServerCommandSource source = context.getSource();
                        final BlockPos origin = new BlockPos(IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"), IntegerArgumentType.getInteger(context, "z"));
                        final int radius = IntegerArgumentType.getInteger(context, "radius");
                        final int maxHeight = IntegerArgumentType.getInteger(context, "maxHeight");

                        generateMountainChunk(source.getWorld(), origin, radius, maxHeight);
                        source.sendFeedback(() -> Text.literal("Generated a mountain peak at " + origin.toShortString()), false);
                        return 1;
                    })
                ))))));

            // --- /tiering command ---
            dispatcher.register(literal("tiering")
                .requires(source -> source.hasPermissionLevel(Constants.COMMAND_PERMISSION_LEVEL))
                .then(argument("x", IntegerArgumentType.integer())
                .then(argument("y", IntegerArgumentType.integer())
                .then(argument("z", IntegerArgumentType.integer())
                .then(argument("radius", IntegerArgumentType.integer())
                .then(argument("maxHeight", IntegerArgumentType.integer())
                .then(argument("instances", IntegerArgumentType.integer())
                .then(argument("tierYMin", IntegerArgumentType.integer())
                .then(argument("shiftMin", IntegerArgumentType.integer())
                .then(argument("shiftMax", IntegerArgumentType.integer())
                    .executes(context -> {
                        final ServerCommandSource source = context.getSource();
                        final BlockPos origin = new BlockPos(IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"), IntegerArgumentType.getInteger(context, "z"));
                        final int radius = IntegerArgumentType.getInteger(context, "radius");
                        final int maxHeight = IntegerArgumentType.getInteger(context, "maxHeight");
                        final int instances = IntegerArgumentType.getInteger(context, "instances");
                        final int tierYMin = IntegerArgumentType.getInteger(context, "tierYMin");
                        final int shiftMin = IntegerArgumentType.getInteger(context, "shiftMin");
                        final int shiftMax = IntegerArgumentType.getInteger(context, "shiftMax");

                        tierArea(source.getWorld(), origin, radius, maxHeight, instances, tierYMin, shiftMin, shiftMax);
                        source.sendFeedback(() -> Text.literal("Applied tiering effect to mountain at " + origin.toShortString()), false);
                        return 1;
                    })
                ))))))))));
                
            // --- /smooth command ---
            dispatcher.register(literal("smooth")
                .requires(source -> source.hasPermissionLevel(Constants.COMMAND_PERMISSION_LEVEL))
                .then(argument("x", IntegerArgumentType.integer())
                .then(argument("y", IntegerArgumentType.integer())
                .then(argument("z", IntegerArgumentType.integer())
                .then(argument("radius", IntegerArgumentType.integer(1, Constants.MAX_RADIUS))
                .then(argument("height", IntegerArgumentType.integer(1, Constants.MAX_CLEAR_HEIGHT))
                .then(argument("iterations", IntegerArgumentType.integer(1, 50))
                .then(argument("strength", FloatArgumentType.floatArg(0.0f, 1.0f))
                    .executes(context -> {
                        final ServerCommandSource source = context.getSource();
                        final BlockPos origin = new BlockPos(IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"), IntegerArgumentType.getInteger(context, "z"));
                        final int radius = IntegerArgumentType.getInteger(context, "radius");
                        final int height = IntegerArgumentType.getInteger(context, "height");
                        final int iterations = IntegerArgumentType.getInteger(context, "iterations");
                        final float strength = FloatArgumentType.getFloat(context, "strength");

                        smoothArea(source.getWorld(), origin, radius, height, iterations, strength);
                        source.sendFeedback(() -> Text.literal("Applied smoothing to area."), false);
                        return 1;
                    })
                ))))))));

            // --- /cleararea command ---
            dispatcher.register(literal("cleararea")
                .requires(source -> source.hasPermissionLevel(Constants.COMMAND_PERMISSION_LEVEL))
                .then(argument("x", IntegerArgumentType.integer())
                .then(argument("y", IntegerArgumentType.integer())
                .then(argument("z", IntegerArgumentType.integer())
                .then(argument("radius", IntegerArgumentType.integer(Constants.MIN_CLEAR_RADIUS, Constants.MAX_CLEAR_RADIUS))
                .then(argument("height", IntegerArgumentType.integer(Constants.MIN_CLEAR_HEIGHT, Constants.MAX_CLEAR_HEIGHT))
                    .executes(context -> {
                        final ServerCommandSource source = context.getSource();
                        final BlockPos origin = new BlockPos(IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"), IntegerArgumentType.getInteger(context, "z"));
                        final int radius = IntegerArgumentType.getInteger(context, "radius");
                        final int height = IntegerArgumentType.getInteger(context, "height");

                        clearArea(source.getWorld(), origin, radius, height);
                        source.sendFeedback(() -> Text.literal("Cleared area."), false);
                        return 1;
                    })
                ))))));
        });
    }
    
    // --- Primary Functions: Generation, Tiering, Smoothing, Clearing ---
    
    /**
     * Generates a mountain by filling blocks from a base Y up to a calculated height map.
     */
    private static void generateMountainChunk(ServerWorld world, BlockPos centerOrigin, int radius, int maxHeight) {
        final int baseGroundY = centerOrigin.getY();
        final int size = 2 * radius + 1;
        final int[][] heightMap = new int[size][size];

        // First Pass: Pre-calculate all height values.
        for (int ix = -radius; ix <= radius; ix++) {
            for (int iz = -radius; iz <= radius; iz++) {
                heightMap[ix + radius][iz + radius] = getMountainHeightAt(centerOrigin.getX() + ix, centerOrigin.getZ() + iz, centerOrigin, radius, maxHeight);
            }
        }

        // Second Pass: Place blocks based on the height map.
        for (int ix = -radius; ix <= radius; ix++) {
            for (int iz = -radius; iz <= radius; iz++) {
                final int arrayX = ix + radius;
                final int arrayZ = iz + radius;
                final int currentTargetHeight = heightMap[arrayX][arrayZ];
                final boolean isFlatSurface = isSurfaceFlat(heightMap, arrayX, arrayZ, size, currentTargetHeight, baseGroundY);

                for (int y = baseGroundY; y <= currentTargetHeight; y++) {
                    final BlockPos currentBlockPos = new BlockPos(centerOrigin.getX() + ix, y, centerOrigin.getZ() + iz);
                    BlockState blockToPlace = getBlockForPlacement(y, currentTargetHeight, isFlatSurface, currentBlockPos);
                    world.setBlockState(currentBlockPos, blockToPlace, 3);
                }
            }
        }
    }

    /**
     * Carves ledges and tiers into an existing mountain area by applying warped, inverted mountain shapes.
     */
    private static void tierArea(ServerWorld world, BlockPos centerOrigin, int radius, int maxHeight, int instances, int tierYMin, int shiftMin, int shiftMax) {
        final Random random = new Random();
        final BlockState airState = Blocks.AIR.getDefaultState();
        final int centerX = centerOrigin.getX();
        final int centerZ = centerOrigin.getZ();

        for (int i = 0; i < instances; i++) {
            final boolean primaryAxisIsX = random.nextBoolean();
            final int primaryDirection = random.nextBoolean() ? 1 : -1;
            final int randYShiftBase = random.nextInt(maxHeight - tierYMin + 1) + tierYMin;

            for (int ix = -radius; ix <= radius; ix++) {
                for (int iz = -radius; iz <= radius; iz++) {
                    final int destX = centerX + ix;
                    final int destZ = centerZ + iz;

                    final int yOrigSurface = getMountainHeightAt(destX, destZ, centerOrigin, radius, maxHeight);
                    final double heightRatio = MathHelper.clamp((double) (yOrigSurface - tierYMin) / (maxHeight - tierYMin), 0.0, 1.0);
                    final double intrusionAmount = MathHelper.lerp(heightRatio, shiftMin, shiftMax);
                    final double totalDisplacement = (radius * Constants.CARVER_BASE_DISPLACEMENT_FACTOR) - intrusionAmount;
                    final double primaryShift = primaryDirection * totalDisplacement;
                    
                    final double secondaryShiftMagnitude = MathHelper.lerp(heightRatio, Constants.SECONDARY_SHIFT_MIN_MAGNITUDE, Constants.SECONDARY_SHIFT_MAX_MAGNITUDE);
                    final double secondaryShift = (random.nextDouble() * 2.0 - 1.0) * secondaryShiftMagnitude;
                    
                    final double sourceX = destX + (primaryAxisIsX ? primaryShift : secondaryShift);
                    final double sourceZ = destZ + (primaryAxisIsX ? secondaryShift : primaryShift);

                    final int yVirtualCarveSurface = (int)Math.round(getBilinearInterpolatedCarverHeight(sourceX, sourceZ, centerOrigin, radius, maxHeight));
                    final int yCarveBottom = maxHeight - yVirtualCarveSurface + randYShiftBase;

                    for (int y = yCarveBottom; y < world.getDimension().height(); y++) {
                        world.setBlockState(new BlockPos(destX, y, destZ), airState, 2);
                    }
                }
            }
        }
    }

    /**
     * NEW: A post-processing function that smooths terrain within a given area.
     * It identifies and removes pillars, and softens sharp edges to create more natural curves.
     */
    private static void smoothArea(ServerWorld world, BlockPos origin, int radius, int height, int iterations, float strength) {
        LOGGER.info("Starting smoothing process...");
        final int size = 2 * radius + 1;
        final int minX = origin.getX() - radius;
        final int minZ = origin.getZ() - radius;
        final int minY = origin.getY();
        final int maxY = minY + height;
        
        // 1. Build initial heightmap from the world
        int[][] originalHeightMap = new int[size][size];
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                originalHeightMap[x][z] = minY; // Default to base
                for (int y = maxY; y >= minY; y--) {
                    if (!world.getBlockState(new BlockPos(minX + x, y, minZ + z)).isAir()) {
                        originalHeightMap[x][z] = y;
                        break;
                    }
                }
            }
        }
        
        // 2. Create a copy of the heightmap to be modified in iterations
        int[][] smoothedHeightMap = new int[size][];
        for(int i = 0; i < size; i++) smoothedHeightMap[i] = Arrays.copyOf(originalHeightMap[i], size);

        // 3. Run smoothing iterations
        for (int i = 0; i < iterations; i++) {
            int[][] tempHeightMap = new int[size][];
            for(int j = 0; j < size; j++) tempHeightMap[j] = Arrays.copyOf(smoothedHeightMap[j], size);

            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    float neighborAvg = getAverageNeighborHeight(smoothedHeightMap, x, z, size);
                    int currentHeight = smoothedHeightMap[x][z];
                    
                    // Pillar detection logic
                    int shorterNeighbors = countShorterNeighbors(smoothedHeightMap, x, z, size, Constants.PILLAR_HEIGHT_DIFFERENCE_THRESHOLD);
                    
                    float smoothStrength;
                    if (shorterNeighbors >= Constants.PILLAR_NEIGHBOR_COUNT_THRESHOLD) {
                        // If it's a pillar, use a strong, fixed smoothing value to aggressively lower it.
                        smoothStrength = Constants.PILLAR_SMOOTH_STRENGTH;
                    } else {
                        // Otherwise, use the user-defined strength for general smoothing.
                        smoothStrength = strength;
                    }
                    
                    // Apply smoothing by interpolating towards the average height of neighbors
                    tempHeightMap[x][z] = (int)MathHelper.lerp(smoothStrength, currentHeight, neighborAvg);
                }
            }
            smoothedHeightMap = tempHeightMap; // The result of this iteration becomes the input for the next
        }
        
        // 4. Apply the calculated changes to the world
        LOGGER.info("Applying smoothed changes to the world...");
        BlockState fillState = Blocks.STONE.getDefaultState(); // Default fill block

        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                int originalHeight = originalHeightMap[x][z];
                int finalHeight = smoothedHeightMap[x][z];
                BlockPos.Mutable currentPos = new BlockPos.Mutable(minX + x, 0, minZ + z);

                if (finalHeight < originalHeight) { // Carve away blocks
                    for (int y = originalHeight; y > finalHeight; y--) {
                        world.setBlockState(currentPos.setY(y), Blocks.AIR.getDefaultState(), 2);
                    }
                } else if (finalHeight > originalHeight) { // Fill in blocks
                    // Try to get the block type from what was originally there
                    BlockState sampleState = world.getBlockState(currentPos.setY(originalHeight));
                    if (!sampleState.isAir()) {
                        fillState = sampleState;
                    }
                    for (int y = originalHeight + 1; y <= finalHeight; y++) {
                        world.setBlockState(currentPos.setY(y), fillState, 2);
                    }
                }
            }
        }
        LOGGER.info("Smoothing process complete.");
    }
    
    /**
     * Clears a cubic area and replaces it with air.
     */
    private static void clearArea(ServerWorld world, BlockPos centerOrigin, int radius, int height) {
        final BlockState airState = Blocks.AIR.getDefaultState();
        final int minX = centerOrigin.getX() - radius;
        final int maxX = centerOrigin.getX() + radius;
        final int minZ = centerOrigin.getZ() - radius;
        final int maxZ = centerOrigin.getZ() + radius;
        final int minY = Math.max(centerOrigin.getY(), world.getBottomY());
        final int maxY = Math.min(centerOrigin.getY() + height - 1, world.getDimension().height() - 1);

        LOGGER.info("Clearing area from X:{} to {} Y:{} to {} Z:{} to {}", minX, maxX, minY, maxY, minZ, maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    world.setBlockState(new BlockPos(x, y, z), airState, 2);
                }
            }
        }
    }
    
    // --- Helper and Calculation Functions ---

    /**
     * Determines the correct block to place based on height, flatness, and position.
     */
    private static BlockState getBlockForPlacement(int y, int targetHeight, boolean isFlat, BlockPos pos) {
        if (y == targetHeight) {
            return getSurfaceBlock(y, getMaterialNoise(pos), isFlat);
        } else if (y >= targetHeight - Constants.MOSS_LAYER_DEPTH && isFlat) {
            return Blocks.MOSS_BLOCK.getDefaultState();
        } else {
            double heightRatio = (targetHeight > 0) ? (double) y / targetHeight : 0;
            return getUndergroundBlock(getMaterialNoise(pos), heightRatio);
        }
    }

    /**
     * Calculates the target surface height of a mountain at a given world coordinate.
     */
    private static int getMountainHeightAt(int worldX, int worldZ, BlockPos centerOrigin, int radius, int maxHeight) {
        final int baseX = centerOrigin.getX();
        final int baseY = centerOrigin.getY();
        final int baseZ = centerOrigin.getZ();

        final double heightValue = heightNoise.sample(worldX * Constants.HEIGHT_NOISE_SCALE, 0.0, worldZ * Constants.HEIGHT_NOISE_SCALE);
        final double detailValue = detailNoise.sample(worldX * Constants.DETAIL_NOISE_SCALE, 0.0, worldZ * Constants.DETAIL_NOISE_SCALE);
        final double cragValue = cragNoise.sample(worldX * Constants.CRAG_NOISE_SCALE, 0.0, worldZ * Constants.CRAG_NOISE_SCALE);
        
        final double normalizedHeight = (heightValue + 1.0) / 2.0;
        final double normalizedDetail = (detailValue + 1.0) / 2.0;
        final double normalizedCrag = (cragValue + 1.0) / 2.0;

        final double distFromCenterX = worldX - baseX;
        final double distFromCenterZ = worldZ - baseZ;
        final double chebyshevDist = Math.max(Math.abs(distFromCenterX), Math.abs(distFromCenterZ));
        final double normalizedSlopeDist = (radius > 0) ? chebyshevDist / radius : 0;

        final double slopeFalloff = Math.pow(normalizedSlopeDist, Constants.SLOPE_STEEPNESS_EXPONENT) * (maxHeight - baseY) * Constants.SLOPE_FALLOFF_FACTOR;
        final double mainHeightVariation = (normalizedHeight - 0.5) * (maxHeight * Constants.HEIGHT_VARIATION_FACTOR);
        final double detailVariation = (normalizedDetail - 0.5) * (maxHeight * Constants.DETAIL_VARIATION_FACTOR);
        final double cragVariation = (normalizedCrag - 0.5) * (maxHeight * Constants.CRAG_VARIATION_FACTOR);

        int targetHeight = (int) (maxHeight - slopeFalloff + mainHeightVariation + detailVariation + cragVariation);

        final double euclideanDist = Math.sqrt(distFromCenterX * distFromCenterX + distFromCenterZ * distFromCenterZ);
        final double plateauRadius = radius * Constants.PLATEAU_RADIUS_FACTOR;
        if (euclideanDist < plateauRadius) {
            final double lerpFactor = Math.pow(euclideanDist / plateauRadius, Constants.PLATEAU_SMOOTHING_EXPONENT);
            targetHeight = (int) MathHelper.lerp(lerpFactor, maxHeight, targetHeight);
        }

        return Math.max(targetHeight, baseY);
    }

    /**
     * NEW: Calculates a simplified, smooth height value intended for the tiering/carving function.
     */
    private static int getCarverHeightAt(int worldX, int worldZ, BlockPos centerOrigin, int radius, int maxHeight) {
        final int baseX = centerOrigin.getX();
        final int baseY = centerOrigin.getY();
        final int baseZ = centerOrigin.getZ();

        final double heightValue = heightNoise.sample(worldX * Constants.HEIGHT_NOISE_SCALE, 0.0, worldZ * Constants.HEIGHT_NOISE_SCALE);
        final double normalizedHeight = (heightValue + 1.0) / 2.0;

        final double distFromCenterX = worldX - baseX;
        final double distFromCenterZ = worldZ - baseZ;
        final double chebyshevDist = Math.max(Math.abs(distFromCenterX), Math.abs(distFromCenterZ));
        final double normalizedSlopeDist = (radius > 0) ? chebyshevDist / radius : 0;
        
        final double slopeFalloff = Math.pow(normalizedSlopeDist, Constants.SLOPE_STEEPNESS_EXPONENT) * (maxHeight - baseY) * Constants.SLOPE_FALLOFF_FACTOR;
        final double mainHeightVariation = (normalizedHeight - 0.5) * (maxHeight * Constants.HEIGHT_VARIATION_FACTOR);

        final int targetHeight = (int) (maxHeight - slopeFalloff + mainHeightVariation);
        return Math.max(targetHeight, baseY);
    }
    
    private static double getMaterialNoise(BlockPos pos) {
        return materialNoise.sample(pos.getX() * Constants.MATERIAL_NOISE_SCALE, pos.getY() * Constants.MATERIAL_NOISE_SCALE, pos.getZ() * Constants.MATERIAL_NOISE_SCALE);
    }

    /**
     * Determines the appropriate block for the mountain's surface.
     */
    private static BlockState getSurfaceBlock(int y, double materialNoiseValue, boolean isFlat) {
        if (isFlat) {
            if (y > Constants.BLUE_ICE_Y_LEVEL && materialNoiseValue > Constants.BLUE_ICE_MATERIAL_THRESHOLD) return Blocks.BLUE_ICE.getDefaultState();
            if (y > Constants.SNOW_CAP_Y_LEVEL && materialNoiseValue > Constants.SNOW_MATERIAL_THRESHOLD) return Blocks.SNOW_BLOCK.getDefaultState();
            return Blocks.MOSS_BLOCK.getDefaultState();
        }
        return Blocks.STONE.getDefaultState();
    }

    /**
     * Determines the appropriate block for the mountain's interior.
     */
    private static BlockState getUndergroundBlock(double materialNoiseValue, double heightRatio) {
        if (materialNoiseValue > Constants.ANDESITE_THRESHOLD) return Blocks.ANDESITE.getDefaultState();
        if (materialNoiseValue > Constants.DIORITE_THRESHOLD) return Blocks.DIORITE.getDefaultState();
        if (materialNoiseValue > Constants.GRAVEL_THRESHOLD && heightRatio < Constants.GRAVEL_HEIGHT_PERCENTAGE) return Blocks.GRAVEL.getDefaultState();
        return Blocks.STONE.getDefaultState();
    }

    /**
     * Checks if a point on the heightmap is relatively flat by comparing it to its neighbors.
     */
    private static boolean isSurfaceFlat(final int[][] heightMap, int x, int z, int size, int currentHeight, int baseGroundY) {
        if (currentHeight <= baseGroundY) return false;
        for (int nx = -1; nx <= 1; nx++) {
            for (int nz = -1; nz <= 1; nz++) {
                if (nx == 0 && nz == 0) continue;
                if (Math.abs(currentHeight - heightMap[MathHelper.clamp(x + nx, 0, size-1)][MathHelper.clamp(z + nz, 0, size-1)]) > Constants.FLATNESS_THRESHOLD) return false;
            }
        }
        return true;
    }

    /**
     * Performs bilinear interpolation on the SMOOTH carver height function.
     */
    private static double getBilinearInterpolatedCarverHeight(double x, double z, BlockPos origin, int radius, int maxHeight) {
        final int x0 = (int) Math.floor(x);
        final int z0 = (int) Math.floor(z);
        final double fx = x - x0;
        final double fz = z - z0;
        
        final double h00 = getCarverHeightAt(x0, z0, origin, radius, maxHeight);
        final double h10 = getCarverHeightAt(x0 + 1, z0, origin, radius, maxHeight);
        final double h01 = getCarverHeightAt(x0, z0 + 1, origin, radius, maxHeight);
        final double h11 = getCarverHeightAt(x0 + 1, z0 + 1, origin, radius, maxHeight);

        final double topLerp = MathHelper.lerp(fx, h00, h10);
        final double bottomLerp = MathHelper.lerp(fx, h01, h11);
        return MathHelper.lerp(fz, topLerp, bottomLerp);
    }
    
    /**
     * NEW: Helper for the smoothing function. Calculates the average height of a column's 8 neighbors.
     */
    private static float getAverageNeighborHeight(int[][] heightMap, int x, int z, int size) {
        float total = 0;
        int count = 0;
        for (int nx = -1; nx <= 1; nx++) {
            for (int nz = -1; nz <= 1; nz++) {
                if (nx == 0 && nz == 0) continue;
                int neighborX = x + nx;
                int neighborZ = z + nz;
                if (neighborX >= 0 && neighborX < size && neighborZ >= 0 && neighborZ < size) {
                    total += heightMap[neighborX][neighborZ];
                    count++;
                }
            }
        }
        return (count > 0) ? total / count : heightMap[x][z];
    }
    
    /**
     * NEW: Helper for pillar detection. Counts how many neighbors are significantly shorter than the current column.
     */
    private static int countShorterNeighbors(int[][] heightMap, int x, int z, int size, int threshold) {
        int shorterCount = 0;
        int currentHeight = heightMap[x][z];
        for (int nx = -1; nx <= 1; nx++) {
            for (int nz = -1; nz <= 1; nz++) {
                if (nx == 0 && nz == 0) continue;
                int neighborX = x + nx;
                int neighborZ = z + nz;
                if (neighborX >= 0 && neighborX < size && neighborZ >= 0 && neighborZ < size) {
                    if (currentHeight > heightMap[neighborX][neighborZ] + threshold) {
                        shorterCount++;
                    }
                }
            }
        }
        return shorterCount;
    }
}