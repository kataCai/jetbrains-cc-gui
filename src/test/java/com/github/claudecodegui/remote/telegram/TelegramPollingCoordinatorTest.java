package com.github.claudecodegui.remote.telegram;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class TelegramPollingCoordinatorTest {

    @Test
    public void shouldAllowOnlyOneLeasePerBotTokenAtATime() throws Exception {
        Path lockRoot = Files.createTempDirectory("telegram-polling-coordinator");
        TelegramPollingCoordinator firstCoordinator = new TelegramPollingCoordinator(lockRoot);
        TelegramPollingCoordinator secondCoordinator = new TelegramPollingCoordinator(lockRoot);

        TelegramPollingCoordinator.Lease firstLease = firstCoordinator.tryAcquire("bot-token");
        TelegramPollingCoordinator.Lease secondLease = secondCoordinator.tryAcquire("bot-token");

        assertNotNull(firstLease);
        assertNull(secondLease);

        firstLease.release();

        TelegramPollingCoordinator.Lease thirdLease = secondCoordinator.tryAcquire("bot-token");

        assertNotNull(thirdLease);
        thirdLease.release();
    }
}
