package build.spawn.platform.local.jdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@link JDKHomeBasedPatternDetector} prunes glob tree walks — i.e. that it skips
 * whole subtrees as soon as a directory fails to match its corresponding glob segment, rather
 * than descending into every directory under the base path.
 * <p>
 * Each test plants a non-matching sibling directory containing a symlink back to one of its own
 * ancestors. Descending into that symlink triggers a {@link java.nio.file.FileSystemLoopException},
 * which — since nothing catches it — aborts the entire {@code walkFileTree} call and silently
 * drops every match already found. So if pruning regresses to "check the full glob against every
 * directory visited", these tests fail by losing the genuine match, not merely by running slower.
 *
 * @author reed.vonredwitz
 * @since Aug-2026
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class JDKHomeBasedPatternDetectorPruningTests {

    private final JDKHomeBasedPatternDetector detector = new JDKHomeBasedPatternDetector();

    /**
     * A fixed-depth pattern (no {@code **}): every segment is prunable, so the non-matching
     * sibling must never be descended into.
     */
    @Test
    void shouldPruneNonMatchingBranchWithoutDescendingIntoIt(@TempDir final Path tempDir) throws IOException {
        final var matchHome = tempDir.resolve("match-a/Contents/Home");
        Files.createDirectories(matchHome);

        plantLoopTrap(tempDir);

        final var pattern = tempDir.toAbsolutePath() + "/match-*/Contents/Home";

        final var matches = detector.expandPattern(pattern).toList();

        assertThat(matches).containsExactly(matchHome);
    }

    /**
     * Same trap, but with a {@code **} pattern — pruning only applies to the fixed-depth prefix
     * before the {@code **}, so this confirms that prefix still gets pruned even though the
     * suffix is unbounded (and would otherwise walk forever around the loop).
     */
    @Test
    void shouldPruneNonMatchingPrefixBeforeDoubleStar(@TempDir final Path tempDir) throws IOException {
        final var matchHome = tempDir.resolve("match-a/Contents/Home");
        Files.createDirectories(matchHome);

        plantLoopTrap(tempDir);

        final var pattern = tempDir.toAbsolutePath() + "/match-*/**/Home";

        final var matches = detector.expandPattern(pattern).toList();

        assertThat(matches).containsExactly(matchHome);
    }

    /**
     * Creates a "no-match" sibling directory — which doesn't match the {@code match-*} glob
     * segment — containing a symlink back to itself, so descending into it loops forever
     * (well, until {@code walkFileTree} detects the cycle and throws).
     */
    private static void plantLoopTrap(final Path tempDir) throws IOException {
        final var noMatch = tempDir.resolve("no-match");
        Files.createDirectory(noMatch);
        Files.createSymbolicLink(noMatch.resolve("loop"), noMatch);
    }
}
