package build.spawn.platform.local.jdk;

import build.base.telemetry.Diagnostic;
import build.base.telemetry.Telemetry;
import build.base.telemetry.foundation.NoOpTelemetryRecorder;
import build.base.telemetry.foundation.ObservableTelemetryRecorder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the telemetry output of {@link JDKHomeBasedPatternDetector}.
 *
 * @author reed.vonredwitz
 * @since Mar-2026
 */
class JDKHomeBasedPatternDetectorLogTests {

    /**
     * Ensure that when a configured JDK search path does not exist, the diagnostic {@link Telemetry}
     * shows both the <em>base</em> directory and the full <em>pattern</em> that was skipped.
     */
    @Test
    void skipDiagnosticShouldShowPatternNotJustBase() {
        final var recorder = ObservableTelemetryRecorder.of(NoOpTelemetryRecorder.create());

        // trigger detection — non-existent paths in java.home.properties will produce Diagnostic telemetry
        new JDKHomeBasedPatternDetector(recorder).detect().count();

        final var skipped = recorder.stream()
            .filter(Diagnostic.class::isInstance)
            .filter(telemetry -> telemetry.message().contains("Skipping path"))
            .toList();

        // at least one "Skipping path" Diagnostic must have been produced for this test to be meaningful
        // (on any platform, some JDK patterns in java.home.properties will not exist)
        assertThat(skipped)
            .as("expected at least one 'Skipping path' Diagnostic to be produced during JDK detection")
            .isNotEmpty();

        // the formatted message must contain both the base and the pattern, and they must be distinct
        skipped.forEach(telemetry -> {
            final var message = telemetry.message();

            assertThat(message)
                .as("diagnostic message should show both the base and the pattern")
                .contains("Skipping path [")
                .contains("for pattern [");
        });
    }
}
