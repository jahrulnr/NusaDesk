package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Locks the read-only shape of the messaging/telephony/contacts slice: no
 * source interface and no adapter exposes a side-effecting method. The
 * side-effecting bridge methods ({@code sms.send}, {@code phone.call}) are
 * not part of this surface — they live in the comms capability module with
 * their own permission checks.
 */
public class MessagingReadOnlySurfaceTest {

    private static final Class<?>[] SOURCE_INTERFACES = {
            ContactsSource.class,
            CallLogSource.class,
            SmsSource.class,
            TelephonyInfoSource.class,
            TelephonyCellSource.class
    };

    private static final Class<?>[] ADAPTERS = {
            AndroidContactsSource.class,
            AndroidCallLogSource.class,
            AndroidSmsSource.class,
            AndroidTelephonyInfoSource.class,
            AndroidTelephonyCellSource.class
    };

    @Test
    public void sourceInterfacesExposeOnlyReadMethods() {
        for (Class<?> source : SOURCE_INTERFACES) {
            for (Method method : source.getDeclaredMethods()) {
                assertTrue("source method must be a read: " + source.getName() + "."
                                + method.getName(),
                        method.getName().startsWith("read"));
                assertNoSideEffectVerbs(source, method);
                assertTrue("source read must be public: " + source.getName(),
                        Modifier.isPublic(method.getModifiers()));
            }
        }
    }

    @Test
    public void adaptersExposeOnlyTheirReadOperation() {
        for (Class<?> adapter : ADAPTERS) {
            for (Method method : adapter.getDeclaredMethods()) {
                if (method.isSynthetic() || !Modifier.isPublic(method.getModifiers())) {
                    // Only the public surface is guest-visible; private
                    // helpers are internal and do not widen the contract.
                    continue;
                }
                assertNoSideEffectVerbs(adapter, method);
                assertTrue("adapter method must be a read: " + adapter.getName() + "."
                                + method.getName(),
                        method.getName().equals("read")
                                || method.getName().startsWith("read"));
            }
        }
    }

    private static void assertNoSideEffectVerbs(Class<?> owner, Method method) {
        String name = method.getName().toLowerCase();
        for (String verb : new String[]{"send", "call", "write", "insert", "update",
                "delete", "create", "dial", "place", "compose", "notify"}) {
            assertFalse("no side-effecting method: " + owner.getName() + "." + name,
                    name.contains(verb));
        }
    }

}
