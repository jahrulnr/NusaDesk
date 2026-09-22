package gh.nusashell.nusadesk.infrastructure.proot.termux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Registry of every generated Termux-compat command the guest installs.
 *
 * <p>Each capability domain owns one {@code Termux<Domain>Commands} class that
 * returns its {@link TermuxCommand} list. Registering a new domain is the only
 * wiring a later wave needs: add the domain class to {@link #REGISTRY} below
 * and remove the graduated names from {@link #deliberatelyAbsent()} so the
 * generated doc stops listing them as absent.</p>
 */
public final class TermuxCommandCatalog {

    /**
     * Domain catalogs, in documentation order. Add a new domain by appending
     * its {@code commands} method reference here.
     */
    private static final List<Supplier<List<TermuxCommand>>> REGISTRY =
            Collections.unmodifiableList(Arrays.asList(
                    TermuxCoreCommands::commands,
                    TermuxDeviceStateCommands::commands,
                    TermuxCommsCommands::commands,
                    TermuxSensorCommands::commands,
                    TermuxNetworkCommands::commands,
                    TermuxInfraredCommands::commands,
                    TermuxStorageCommands::commands,
                    TermuxTextCommands::commands,
                    TermuxSpeechCommands::commands,
                    TermuxCaptureCommands::commands,
                    TermuxMediaCommands::commands,
                    TermuxNfcCommands::commands,
                    TermuxUsbCommand::commands,
                    TermuxFingerprintCommand::commands));

    /**
     * Termux commands deliberately not installed even though a bridge method
     * exists or could exist: they need a side effect the bridge does not
     * expose, a capability outside the current allowlist, or hardware this
     * product does not claim. The coordinator shrinks this list as domain
     * catalogs land.
     */
    private static final List<String> DELIBERATELY_ABSENT =
            Collections.unmodifiableList(Arrays.asList(
                    // Every upstream client command is installed; the list
                    // stays for the ones a future decision removes.
                    ));

    private TermuxCommandCatalog() {
    }

    /**
     * Every registered command across all domain catalogs, in registration
     * order. A name collision between catalogs is a wiring bug and fails the
     * whole catalog rather than silently shadowing a command.
     */
    public static List<TermuxCommand> all() {
        List<TermuxCommand> all = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Supplier<List<TermuxCommand>> catalog : REGISTRY) {
            for (TermuxCommand command : catalog.get()) {
                if (!seen.add(command.name())) {
                    throw new IllegalStateException(
                            "duplicate Termux command in catalogs: "
                                    + command.name());
                }
                all.add(command);
            }
        }
        return Collections.unmodifiableList(all);
    }

    /** Every installed command name; also the set the stale-file sweep keeps. */
    public static Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        for (TermuxCommand command : all()) {
            names.add(command.name());
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * Look up one command by name.
     *
     * @throws IllegalArgumentException when the name is not registered
     */
    public static TermuxCommand require(String name) {
        for (TermuxCommand command : all()) {
            if (command.name().equals(name)) {
                return command;
            }
        }
        throw new IllegalArgumentException("unknown command: " + name);
    }

    /** Command names the generated doc lists as deliberately absent. */
    public static List<String> deliberatelyAbsent() {
        return DELIBERATELY_ABSENT;
    }
}
