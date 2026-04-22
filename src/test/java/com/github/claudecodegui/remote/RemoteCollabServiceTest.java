package com.github.claudecodegui.remote;

import org.junit.Test;

import java.lang.reflect.Constructor;

import static org.junit.Assert.assertEquals;

public class RemoteCollabServiceTest {

    @Test
    public void shouldReportDisabledAfterShutdownClearsActiveChannel() throws Exception {
        RemoteCollabService service = newRemoteCollabService();
        service.setTaskChannel(new NoopRemoteTaskChannel());

        service.shutdown();

        assertEquals("disabled", service.getConnectionStatus());
    }

    private static RemoteCollabService newRemoteCollabService() throws Exception {
        Constructor<RemoteCollabService> constructor = RemoteCollabService.class.getDeclaredConstructor(RemoteRequestRegistry.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new RemoteRequestRegistry());
    }

    private static class NoopRemoteTaskChannel implements RemoteTaskChannel {

        @Override
        public String getChannelId() {
            return "noop";
        }

        @Override
        public RemoteConnectionStatus getConnectionStatus() {
            return RemoteConnectionStatus.CONNECTED;
        }

        @Override
        public void initialize() {
        }

        @Override
        public void shutdown() {
        }

        @Override
        public void publishTaskEvent(RemoteTaskEvent event) {
        }

        @Override
        public void publishPendingRequest(RemotePendingRequest request) {
        }
    }
}
