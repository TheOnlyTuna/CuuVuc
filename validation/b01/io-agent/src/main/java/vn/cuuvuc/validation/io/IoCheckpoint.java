package vn.cuuvuc.validation.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Hook bootstrap để đường ghi Paper không phụ thuộc classloader plugin hoặc agent. */
public final class IoCheckpoint {
    /** Marker nằm sát lời gọi ghi thật, lọc đúng chunk 4,0 và region terrain của cv_sky. */
    public static void checkpoint(int bytesWritten, Path region, int chunkX, int chunkZ, String phase) throws IOException {
        Path directory = Path.of("io-probe");
        Path arm = directory.resolve("arm.txt");
        if (chunkX != 4 || chunkZ != 0 || !region.toString().replace('\\', '/').endsWith("/cv_sky/region/r.0.0.mca")
                || !Files.exists(arm)) return;
        String selected = Files.readString(arm, StandardCharsets.UTF_8).strip();
        if (!selected.equals(phase)) return;
        String marker = "phase=" + phase + "\npid=" + ProcessHandle.current().pid()
                + "\nutc=" + Instant.now() + "\nthread=" + Thread.currentThread().getName()
                + "\nregion=" + region.toAbsolutePath() + "\nchunk=4,0\nbytesWritten=" + bytesWritten
                + "\nstack=" + StackWalker.getInstance().walk(frames -> frames.map(Object::toString).toList()) + "\n";
        try (FileChannel channel = FileChannel.open(directory.resolve("marker.txt"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(marker);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            if (!new CountDownLatch(1).await(45, TimeUnit.SECONDS))
                throw new IllegalStateException("Không hard-kill trong 45 giây tại " + phase);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Chốt I/O bị ngắt tại " + phase, failure);
        }
    }
}
