package es.in2.desmos.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Empties node A's broker after every integration test class, so no class inherits the entities
 * another one left behind.
 * <p>
 * Registered for the whole integration-test source set by auto-detection, not by
 * {@code @ExtendWith} on each class -- see {@code src/integrationTest/resources/META-INF/services}.
 * Deliberately implicit: the failure this prevents happened precisely because isolation relied on
 * whoever wrote the class remembering to clean up, and the next class added would inherit that same
 * gap.
 * <p>
 * Runs after the class's own {@code @AfterAll} methods (JUnit extension callbacks wrap the user
 * lifecycle), so the existing per-id cleanups still run first and are simply redundant, not broken.
 * <p>
 * Node A only. Node B's broker sits behind the containerised node B, which has its own app, its own
 * subscription and its own live queues, so wiping it doubles the blast radius without touching the
 * cross-class bleed that affects node A's shared Postgres.
 */
public class ScorpioResetExtension implements AfterAllCallback {

    private static final Logger log = LoggerFactory.getLogger(ScorpioResetExtension.class);

    @Override
    public void afterAll(ExtensionContext context) {
        // Classes that never touch a broker still land here. Wiping an already-empty broker is a
        // handful of cheap queries, which is a better trade than maintaining an opt-in list that
        // would drift out of date.
        try {
            ScorpioReset.wipe(ContainerManager.getBaseUriForScorpioA());
        } catch (RuntimeException e) {
            // Rethrown on purpose, even though it reports this (possibly green) class as failing.
            // A broker left dirty is the precondition for a confusing failure several classes
            // later, attributed to an innocent test -- which is the exact failure mode that led
            // here. Better to blame the class that actually failed to clean up.
            log.error("Scorpio wipe after {} failed; later classes would see leftover entities",
                    context.getDisplayName(), e);
            throw e;
        }
    }
}
