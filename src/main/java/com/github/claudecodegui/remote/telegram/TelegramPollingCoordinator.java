package com.github.claudecodegui.remote.telegram;

import com.github.claudecodegui.util.PlatformUtils;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * 用本地文件锁约束同一 Bot Token 只能有一个 IDE 进程负责 polling。
 */
public class TelegramPollingCoordinator {

    private final Path lockRoot;

    public TelegramPollingCoordinator() {
        this(Path.of(PlatformUtils.getHomeDirectory(), ".codemoss", "remote-collab", "locks"));
    }

    TelegramPollingCoordinator(Path lockRoot) {
        this.lockRoot = lockRoot;
    }

    Lease tryAcquire(String botToken) throws IOException {
        if (botToken == null || botToken.trim().isEmpty()) {
            throw new IOException("Telegram Bot Token is empty");
        }

        Files.createDirectories(lockRoot);
        Path lockFile = lockRoot.resolve("telegram-" + fingerprint(botToken) + ".lock");
        FileChannel channel = FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        );

        try {
            FileLock fileLock = channel.tryLock();
            if (fileLock == null) {
                channel.close();
                return null;
            }
            writeHolderMetadata(channel);
            return new FileLease(channel, fileLock);
        } catch (OverlappingFileLockException e) {
            channel.close();
            return null;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private void writeHolderMetadata(FileChannel channel) throws IOException {
        String metadata = "pid=" + ManagementFactory.getRuntimeMXBean().getName()
            + ",ts=" + Instant.now();
        channel.truncate(0);
        channel.position(0);
        channel.write(ByteBuffer.wrap(metadata.getBytes(StandardCharsets.UTF_8)));
        channel.force(true);
    }

    private String fingerprint(String botToken) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(botToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 8 && i < bytes.length; i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }

    interface Lease {
        void release();
    }

    private static final class FileLease implements Lease {
        private final FileChannel channel;
        private final FileLock fileLock;

        private FileLease(FileChannel channel, FileLock fileLock) {
            this.channel = channel;
            this.fileLock = fileLock;
        }

        @Override
        public void release() {
            try {
                fileLock.release();
            } catch (IOException ignored) {
            }
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }
}
