package io.github.rad1092.serdeproof.runtime;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnedDescendantTest {
    private static final Instant ROOT_START = Instant.ofEpochMilli(1000);

    @Test void refusesCurrentJvmAndProcessesOlderThanTheRoot() {
        assertNull(JvmAdapter.OwnedDescendant.observe(ProcessHandle.current(), 101, ROOT_START));
        Fake root = new Fake(101, ROOT_START, null);
        Fake stale = new Fake(102, ROOT_START.minusMillis(1), root);
        assertNull(JvmAdapter.OwnedDescendant.observe(stale.handle, 101, ROOT_START));
        assertFalse(stale.destroyed);
    }

    @Test void checksEveryParentGenerationAndTheRootIdentity() {
        Fake root = new Fake(101, ROOT_START, null);
        Fake parent = new Fake(102, ROOT_START.plusMillis(20), root);
        Fake stale = new Fake(103, ROOT_START.plusMillis(10), parent);
        assertNull(JvmAdapter.OwnedDescendant.observe(stale.handle, 101, ROOT_START));
        Fake child = new Fake(104, ROOT_START.plusMillis(30), parent);
        assertNotNull(JvmAdapter.OwnedDescendant.observe(child.handle, 101, ROOT_START));
        root.start = ROOT_START.minusMillis(100);
        assertNull(JvmAdapter.OwnedDescendant.observe(child.handle, 101, ROOT_START));
    }

    @Test void reusedPidAndMissingIdentityAreNeverTerminated() {
        Fake root = new Fake(101, ROOT_START, null);
        Fake child = new Fake(102, ROOT_START.plusMillis(10), root);
        var owned = JvmAdapter.OwnedDescendant.observe(child.handle, 101, ROOT_START);
        assertNotNull(owned);
        child.start = ROOT_START.plusMillis(20);
        owned.destroy(false);
        owned.destroy(true);
        assertFalse(child.destroyed);
        child.start = null;
        owned.destroy(true);
        assertFalse(child.destroyed);
        assertNull(JvmAdapter.OwnedDescendant.observe(child.handle, 101, ROOT_START));
    }

    @Test void unchangedOwnedIdentityCanBeTerminated() {
        Fake root = new Fake(101, ROOT_START, null);
        Fake child = new Fake(102, ROOT_START.plusMillis(10), root);
        var owned = JvmAdapter.OwnedDescendant.observe(child.handle, 101, ROOT_START);
        assertNotNull(owned);
        owned.destroy(true);
        assertTrue(child.destroyed);
    }

    private static final class Fake {
        Instant start;
        boolean destroyed;
        final ProcessHandle handle;
        Fake(long pid, Instant start, Fake parent) {
            this.start = start;
            ProcessHandle.Info info = (ProcessHandle.Info) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{ProcessHandle.Info.class},
                    (proxy, method, args) -> method.getName().equals("startInstant") ? Optional.ofNullable(this.start) : Optional.empty());
            handle = (ProcessHandle) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ProcessHandle.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "pid" -> pid;
                        case "info" -> info;
                        case "parent" -> Optional.ofNullable(parent).map(value -> value.handle);
                        case "isAlive" -> !destroyed;
                        case "destroy", "destroyForcibly" -> { destroyed = true; yield true; }
                        case "hashCode" -> Long.hashCode(pid);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
