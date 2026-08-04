package build.spawn.platform.local.jdk;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correctness tests for {@link JDKHomeBasedPatternDetector#expandPattern(String)} — the glob
 * parsing and matching logic that {@code JDKHomeBasedPatternDetectorPruningTests} assumes is
 * otherwise sound while it focuses on subtree pruning specifically.
 *
 * @author reed.vonredwitz
 * @since Aug-2026
 */
class JDKHomeBasedPatternDetectorGlobExpansionTests {

    private final JDKHomeBasedPatternDetector detector = new JDKHomeBasedPatternDetector();

    /**
     * A pattern with no glob metacharacters is not resolved against the filesystem at all — it
     * is returned verbatim, even when nothing exists at that path.
     */
    @Test
    void plainPathResolvesToItselfEvenWhenItDoesNotExist(@TempDir final Path tempDir) {
        final var missing = tempDir.resolve("does-not-exist");

        final var matches = detector.expandPattern(missing.toString()).toList();

        assertThat(matches).containsExactly(missing);
    }

    /**
     * An unparsable path (e.g. one containing a NUL byte, which is illegal on every platform
     * {@code java.nio.file.Path} supports) must not propagate an exception — it's just dropped.
     */
    @Test
    void invalidPathPatternYieldsEmptyStreamRatherThanThrowing() {
        final var matches = detector.expandPattern("/tmp/bad\0name").toList();

        assertThat(matches).isEmpty();
    }

    /**
     * A glob pattern with no path separator before its first metacharacter has no base directory
     * to walk from, and is rejected rather than treated as relative to some implicit root.
     */
    @Test
    void globPatternWithoutLeadingSeparatorYieldsEmptyStream() {
        final var matches = detector.expandPattern("*.jdk").toList();

        assertThat(matches).isEmpty();
    }

    /**
     * If the plain base-path portion of a glob pattern doesn't exist on disk, there's nothing to
     * walk, and no exception should surface — just no matches.
     */
    @Test
    void globPatternWithNonExistentBaseYieldsEmptyStream(@TempDir final Path tempDir) {
        final var pattern = tempDir.resolve("nope").toAbsolutePath() + "/jdk-*";

        final var matches = detector.expandPattern(pattern).toList();

        assertThat(matches).isEmpty();
    }

    /**
     * A single {@code "*"} segment matches multiple sibling directories, and excludes directories
     * whose name doesn't match — a plain correctness check independent of the loop-trap pruning
     * proof in {@code JDKHomeBasedPatternDetectorPruningTests}.
     */
    @Test
    void singleStarMatchesAllAndOnlyMatchingSiblings(@TempDir final Path tempDir) throws IOException {
        final var jdk8 = Files.createDirectories(tempDir.resolve("jdk-8"));
        final var jdk17 = Files.createDirectories(tempDir.resolve("jdk-17"));
        Files.createDirectories(tempDir.resolve("not-a-jdk"));

        final var pattern = tempDir.toAbsolutePath() + "/jdk-*";

        final var matches = detector.expandPattern(pattern).toList();

        assertThat(matches).containsExactlyInAnyOrder(jdk8, jdk17);
    }

    /**
     * {@code "?"} matches exactly one character — a directory with two extra characters where
     * the pattern only allows one must be excluded.
     */
    @Test
    void questionMarkMatchesExactlyOneCharacter(@TempDir final Path tempDir) throws IOException {
        final var single = Files.createDirectories(tempDir.resolve("jdk-8"));
        Files.createDirectories(tempDir.resolve("jdk-17"));

        final var pattern = tempDir.toAbsolutePath() + "/jdk-?";

        final var matches = detector.expandPattern(pattern).toList();

        assertThat(matches).containsExactly(single);
    }

    /**
     * {@code "[...]"} character classes are honored per standard glob semantics.
     */
    @Test
    void bracketCharacterClassMatchesOnlyIncludedCharacters(@TempDir final Path tempDir) throws IOException {
        final var eight = Files.createDirectories(tempDir.resolve("jdk-8"));
        final var nine = Files.createDirectories(tempDir.resolve("jdk-9"));
        Files.createDirectories(tempDir.resolve("jdk-7"));

        final var pattern = tempDir.toAbsolutePath() + "/jdk-[89]";

        final var matches = detector.expandPattern(pattern).toList();

        assertThat(matches).containsExactlyInAnyOrder(eight, nine);
    }

    /**
     * {@code "**"} matches across an arbitrary number of directory levels, not just one.
     */
    @Test
    void doubleStarMatchesAtVaryingDepths(@TempDir final Path tempDir) throws IOException {
        final var shallow = Files.createDirectories(tempDir.resolve("a/Home"));
        final var deep = Files.createDirectories(tempDir.resolve("b/c/d/Home"));
        Files.createDirectories(tempDir.resolve("e/NotHome"));

        final var pattern = tempDir.toAbsolutePath() + "/**/Home";

        final var matches = detector.expandPattern(pattern).toList();

        assertThat(matches).containsExactlyInAnyOrder(shallow, deep);
    }

    /**
     * A pattern with several fixed (non-{@code **}) segments only matches at exactly that depth —
     * a directory named "Home" one level shallower or deeper than the pattern specifies must not
     * be matched.
     */
    @Test
    void fixedDepthPatternDoesNotMatchWrongDepth(@TempDir final Path tempDir) throws IOException {
        final var correctDepth = Files.createDirectories(tempDir.resolve("match-a/Contents/Home"));
        // one level too shallow
        Files.createDirectories(tempDir.resolve("match-b/Home"));
        // one level too deep
        Files.createDirectories(tempDir.resolve("match-c/Contents/Extra/Home"));

        final var pattern = tempDir.toAbsolutePath() + "/match-*/Contents/Home";

        final var matches = detector.expandPattern(pattern).toList();

        assertThat(matches).containsExactly(correctDepth);
    }

    /**
     * A {@code "{...}"} group whose alternatives span a path separator (e.g. {@code "a/x"} vs.
     * {@code "b/y"}) breaks the 1-segment-per-directory-level assumption that segment-level
     * pruning relies on: naively splitting the suffix on {@code "/"} would slice the group in two
     * and turn each half into an unbalanced, unmatchable segment pattern. Confirms
     * {@link JDKHomeBasedPatternDetector} instead falls back to unpruned full-glob matching for
     * such patterns, so both alternatives are still found correctly.
     */
    @Test
    void braceGroupSpanningAPathSeparatorMatchesBothAlternatives(@TempDir final Path tempDir) throws IOException {
        final var homeA = Files.createDirectories(tempDir.resolve("a/x/Home"));
        final var homeB = Files.createDirectories(tempDir.resolve("b/y/Home"));

        final var pattern = tempDir.toAbsolutePath() + "/{a/x,b/y}/Home";

        final var matches = detector.expandPattern(pattern).toList();

        assertThat(matches).containsExactlyInAnyOrder(homeA, homeB);
    }

    /**
     * A {@code "{...}"} group that does NOT span a separator is an entirely ordinary segment and
     * must still be matched via the normal (pruned) path — distinguishing this from the fallback
     * exercised above.
     */
    @Test
    void braceGroupWithinASingleSegmentMatchesNormally(@TempDir final Path tempDir) throws IOException {
        final var zulu = Files.createDirectories(tempDir.resolve("zulu-8/Home"));
        final var temurin = Files.createDirectories(tempDir.resolve("temurin-8/Home"));
        Files.createDirectories(tempDir.resolve("other-8/Home"));

        final var pattern = tempDir.toAbsolutePath() + "/{zulu,temurin}-8/Home";

        final var matches = detector.expandPattern(pattern).toList();

        assertThat(matches).containsExactlyInAnyOrder(zulu, temurin);
    }

    /**
     * {@code FOLLOW_LINKS} is enabled on the walk, so a match reached only via a (non-looping)
     * symlink must still be found — this is the ordinary case that the loop-trap tests in
     * {@code JDKHomeBasedPatternDetectorPruningTests} are guarding against regressing.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void matchReachableOnlyThroughASymlinkIsFound(@TempDir final Path tempDir) throws IOException {
        final var realHome = Files.createDirectories(tempDir.resolve("real/Contents/Home"));
        final var linked = tempDir.resolve("linked");
        Files.createSymbolicLink(linked, tempDir.resolve("real"));

        final var pattern = tempDir.toAbsolutePath() + "/linked/Contents/Home";

        final var matches = detector.expandPattern(pattern).toList();

        assertThat(matches).containsExactly(linked.resolve("Contents/Home"));
        assertThat(Files.isSameFile(matches.get(0), realHome)).isTrue();
    }

    /**
     * A directory that can't be opened during the walk (e.g. permission denied) must not abort
     * the whole expansion with an exception — matches found elsewhere in the tree are still
     * returned. {@code walkFileTree} opens a directory stream before invoking
     * {@code preVisitDirectory}, so a matching-but-unreadable directory is itself silently
     * dropped (routed to {@code visitFileFailed} instead) rather than included as a match — this
     * pins down that this refactor didn't change that pre-existing behavior. Skips itself if the
     * test can't actually produce an unreadable directory (e.g. running as root, which bypasses
     * POSIX permission bits).
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void unreadableDirectoryIsSkippedNotFatal(@TempDir final Path tempDir) throws IOException {
        final var match = Files.createDirectories(tempDir.resolve("jdk-8"));
        final var forbidden = Files.createDirectories(tempDir.resolve("jdk-9"));
        Files.createDirectories(forbidden.resolve("Home"));
        assertThat(forbidden.toFile().setReadable(false)).isTrue();
        assertThat(forbidden.toFile().setExecutable(false)).isTrue();

        try {
            Assumptions.assumeTrue(!canList(forbidden), "cannot simulate an unreadable directory (running as root?)");

            final var pattern = tempDir.toAbsolutePath() + "/jdk-*";

            final var matches = detector.expandPattern(pattern).toList();

            assertThat(matches).containsExactly(match);
        } finally {
            forbidden.toFile().setReadable(true);
            forbidden.toFile().setExecutable(true);
        }
    }

    private static boolean canList(final Path dir) {
        try (var stream = Files.list(dir)) {
            stream.count();
            return true;
        } catch (final IOException e) {
            return false;
        }
    }
}
