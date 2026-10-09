package vn.cuuvuc.core;

import org.bukkit.generator.ChunkGenerator;

/** Thế giới rỗng để Prison và động phủ không sinh terrain vanilla hoặc công trình ngoài quyền đất. */
public final class VoidWorldGenerator extends ChunkGenerator {
    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateBedrock() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }
}
