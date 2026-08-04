package build.spawn.platform.local.jdk;

/*-
 * #%L
 * Spawn Local JDK
 * %%
 * Copyright (C) 2026 Workday, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import build.base.expression.compat.Processor;
import build.base.expression.compat.Variable;
import build.base.foundation.Exceptional;
import build.base.telemetry.TelemetryRecorder;
import build.base.telemetry.foundation.PrintStreamTelemetryRecorder;
import build.spawn.jdk.JDK;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * A {@link JDKDetector} that uses the properties defined by the
 * "java.home.properties" resource as the basis of detecting the installed {@link JDK}s.
 *
 * @author brian.oliver
 * @since Nov-2019
 */
public class JDKHomeBasedPatternDetector
    implements JDKDetector {

    /**
     * The "java.home.properties" resource.
     */
    private static final String JAVA_HOME_PROPERTIES = "java.home.properties";

    /**
     * A cache of the detected {@link JDK}s, ordering them implicitly by {@link build.base.option.JDKVersion}.
     */
    private final AtomicReference<SortedSet<JDK>> jdks;

    /**
     * The {@link TelemetryRecorder} used to record detection diagnostics.
     */
    private final TelemetryRecorder recorder;

    /**
     * Constructs a {@link JDKHomeBasedPatternDetector}, recording telemetry to {@link System#err}.
     */
    public JDKHomeBasedPatternDetector() {
        this(PrintStreamTelemetryRecorder.of(URI.create("spawn://jdk-home-detector"), System.out, System.err));
    }

    /**
     * Constructs a {@link JDKHomeBasedPatternDetector}.
     *
     * @param recorder the {@link TelemetryRecorder} used to record detection diagnostics
     */
    public JDKHomeBasedPatternDetector(final TelemetryRecorder recorder) {
        this.jdks = new AtomicReference<>();
        this.recorder = Objects.requireNonNull(recorder, "The TelemetryRecorder must not be null");
    }

    @Override
    public Stream<Path> paths() {

        // establish the JEL processor to expand ${user.home} in glob patterns
        final var processor = Processor.create(Variable.of("user.home", System.getProperty("user.home", "")));

        try {
            // use the known jdk homes properties file as the basis of JavaHome locations
            final Properties knownJdkHomes = new Properties();

            // load the jdk homes
            final InputStream inputStream =
                ClassLoader.getSystemResourceAsStream(JAVA_HOME_PROPERTIES);

            knownJdkHomes.load(inputStream);

            // map each of the java homes into a path — no subprocess verification
            return knownJdkHomes.stringPropertyNames().stream()
                .filter(key -> {
                    // keys must have an @ prefix: <os-pattern>@<unique-name>
                    // keys without @ are excluded
                    final int at = key.indexOf('@');
                    if (at < 0) {
                        return false;
                    }
                    final var osPattern = key.substring(0, at);
                    return matchesOS(System.getProperty("os.name", ""), osPattern);
                })
                .map(knownJdkHomes::getProperty)
                .map(processor::replace)
                .flatMap(this::expandPattern);
        } catch (final IOException e) {
            this.recorder.error(e, "Failed to read %s", JAVA_HOME_PROPERTIES);
            return Stream.empty();
        }
    }

    /**
     * Expands a single "java.home.properties" pattern into the {@link Path}s it refers to.
     * <p>
     * A plain path (no glob metacharacters) resolves to itself; a glob pattern is expanded by
     * walking its base directory, as per {@link #expandGlobPattern(String)}.
     *
     * @param pattern the pattern, with any {@code ${...}} variables already expanded
     * @return the matching paths, or an empty stream if the pattern is invalid or matches nothing
     */
    Stream<Path> expandPattern(final String pattern) {
        try {
            if (!isGlobPattern(pattern)) {
                return Stream.of(Paths.get(pattern));
            }
            return expandGlobPattern(pattern);
        } catch (final InvalidPathException e) {
            this.recorder.diagnostic("The path [%s] is not a valid pattern", pattern);
            return Stream.empty();
        } catch (final IOException e) {
            this.recorder.warn(e, "Failed to visit a path [%s]", pattern);
            return Stream.empty();
        }
    }

    /**
     * Determines whether a pattern contains any glob metacharacters.
     */
    private static boolean isGlobPattern(final String pattern) {
        return pattern.contains("*") || pattern.contains("?") || pattern.contains("[")
            || pattern.contains("]") || pattern.contains("{") || pattern.contains("}");
    }

    /**
     * Expands a glob pattern by splitting it into a plain base path and a glob suffix, then
     * walking the base directory — pruning subtrees that can't possibly match, per
     * {@link GlobSegments}.
     */
    private Stream<Path> expandGlobPattern(final String pattern) throws IOException {
        // find the position of the last file separator (/) before the glob metacharacters
        // begin — everything before it is a plain base path, the rest is the glob suffix
        final int baseEnd = indexOfBaseEnd(pattern);
        if (baseEnd < 0) {
            this.recorder.warn("The path [%s] is not an absolute path", pattern);
            return Stream.empty();
        }

        final Path base = Paths.get(pattern.substring(0, baseEnd));
        if (!base.toFile().exists()) {
            this.recorder.diagnostic(
                "Skipping path [%s] for pattern [%s] as the path does not exist", base, pattern);
            return Stream.empty();
        }

        final GlobSegments segments = GlobSegments.of(pattern.substring(baseEnd));
        final PathMatcher fullMatcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        final ArrayList<Path> matches = new ArrayList<>();
        Files.walkFileTree(
            base,
            EnumSet.of(FileVisitOption.FOLLOW_LINKS),
            segments.maxDepth(),
            new PruningGlobVisitor(base, fullMatcher, segments, matches, this.recorder));

        return matches.stream();
    }

    /**
     * Finds the index of the last file separator before the first glob metacharacter in a
     * pattern, i.e. the boundary between its plain base path and its glob suffix. Returns -1 if
     * the pattern has no separator before its first metacharacter.
     */
    private static int indexOfBaseEnd(final String pattern) {
        int baseEnd = -1;
        for (int i = 0; i < pattern.length(); i++) {
            final char c = pattern.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == ']' || c == '{' || c == '}') {
                break;
            }
            if (c == '/') {
                baseEnd = i;
            }
        }
        return baseEnd;
    }

    /**
     * The individual path segments of a glob suffix (the part of a pattern after its base path),
     * e.g. {@code "/zulu-*.jdk/Contents/Home"} -> {@code ["zulu-*.jdk", "Contents", "Home"]}.
     * <p>
     * These let a tree walk match (and prune) each directory level as soon as it's visited,
     * rather than only checking the full glob once a leaf is reached. {@code "**"} spans an
     * unknown number of segments, so segment-level pruning only applies to the fixed-depth
     * prefix before the first {@code "**"} (if any); beyond that, the full glob must be matched
     * against the whole path.
     * <p>
     * A {@code "{...}"} or {@code "[...]"} group can itself contain a {@code "/"} (e.g.
     * {@code "{a/x,b/y}"}), in which case a single glob segment no longer corresponds to a
     * single directory level and per-segment pruning would be unsound. When that happens, this
     * falls back to no pruning at all — every directory in the subtree is checked against the
     * full-path glob, exactly as if the whole suffix were a {@code "**"}.
     */
    private static final class GlobSegments {

        private final PathMatcher[] prefixMatchers;
        private final boolean hasDoubleStar;
        private final int segmentCount;

        private GlobSegments(final PathMatcher[] prefixMatchers, final boolean hasDoubleStar,
                             final int segmentCount) {
            this.prefixMatchers = prefixMatchers;
            this.hasDoubleStar = hasDoubleStar;
            this.segmentCount = segmentCount;
        }

        static GlobSegments of(final String globSuffix) {
            final List<String> segments = new ArrayList<>();

            // split on '/', but not one nested inside a "{...}" or "[...]" group — and note
            // if that ever happens, since it breaks the 1-segment-per-directory-level
            // assumption that segment-level pruning below relies on
            boolean groupSpansSeparator = false;
            int depth = 0;
            int start = 0;
            final String suffix = globSuffix.substring(1);
            for (int i = 0; i < suffix.length(); i++) {
                final char c = suffix.charAt(i);
                if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth = Math.max(0, depth - 1);
                } else if (c == '/') {
                    if (depth == 0) {
                        segments.add(suffix.substring(start, i));
                        start = i + 1;
                    } else {
                        groupSpansSeparator = true;
                    }
                }
            }
            segments.add(suffix.substring(start));

            if (groupSpansSeparator) {
                return new GlobSegments(new PathMatcher[0], true, segments.size());
            }

            int firstDoubleStar = segments.size();
            for (int s = 0; s < segments.size(); s++) {
                if (segments.get(s).equals("**")) {
                    firstDoubleStar = s;
                    break;
                }
            }

            final PathMatcher[] prefixMatchers = new PathMatcher[firstDoubleStar];
            for (int s = 0; s < firstDoubleStar; s++) {
                prefixMatchers[s] = FileSystems.getDefault().getPathMatcher("glob:" + segments.get(s));
            }

            return new GlobSegments(prefixMatchers, firstDoubleStar < segments.size(), segments.size());
        }

        /**
         * The maximum depth a tree walk needs to descend to find every possible match.
         * <p>
         * Patterns without {@code **} can only match at a fixed depth, so there's no need to go
         * deeper. +1 because {@link Files#walkFileTree} calls {@code preVisitDirectory} for
         * depths {@code 0..maxDepth-1} only; at exactly {@code maxDepth}, directories are
         * delivered via {@code visitFile} and {@code preVisitDirectory} never fires.
         */
        int maxDepth() {
            return hasDoubleStar ? Integer.MAX_VALUE : segmentCount + 1;
        }

        /**
         * Whether the given depth falls within the fixed-depth prefix, and so can be checked
         * against a single segment matcher rather than the full glob.
         */
        boolean isWithinPrefix(final int depth) {
            return depth <= prefixMatchers.length;
        }

        boolean matchesPrefixSegment(final int depth, final Path fileName) {
            return prefixMatchers[depth - 1].matches(fileName);
        }

        /**
         * Whether reaching the given depth, having matched every prefix segment along the way,
         * already confirms a full match without needing to check the full-path glob.
         */
        boolean isConfirmedMatch(final int depth) {
            return !hasDoubleStar && depth == segmentCount;
        }
    }

    /**
     * Walks a base directory collecting paths that match a glob, pruning subtrees as soon as a
     * directory fails to match its corresponding fixed-depth segment (see {@link GlobSegments}).
     */
    private static final class PruningGlobVisitor
        extends SimpleFileVisitor<Path> {

        private final Path base;
        private final PathMatcher fullMatcher;
        private final GlobSegments segments;
        private final ArrayList<Path> matches;
        private final TelemetryRecorder recorder;

        PruningGlobVisitor(final Path base, final PathMatcher fullMatcher, final GlobSegments segments,
                           final ArrayList<Path> matches, final TelemetryRecorder recorder) {
            this.base = base;
            this.fullMatcher = fullMatcher;
            this.segments = segments;
            this.matches = matches;
            this.recorder = recorder;
        }

        @Override
        public FileVisitResult preVisitDirectory(final Path path, final BasicFileAttributes attrs) {
            if (path.equals(base)) {
                // the base directory itself, always descend into it (relativize() would
                // otherwise report this as an empty path with a misleading getNameCount() of 1)
                return FileVisitResult.CONTINUE;
            }

            final int depth = base.relativize(path).getNameCount();

            if (segments.isWithinPrefix(depth) && !segments.matchesPrefixSegment(depth, path.getFileName())) {
                // this segment doesn't match its corresponding glob segment, so nothing under
                // it can possibly match either
                return FileVisitResult.SKIP_SUBTREE;
            }

            if (segments.isConfirmedMatch(depth)) {
                // every segment up to and including this one matched its corresponding glob
                // segment, so this is a confirmed match — no need to re-check the full-path glob
                matches.add(path);
                return FileVisitResult.SKIP_SUBTREE;
            }

            if (fullMatcher.matches(path)) {
                matches.add(path);
                return FileVisitResult.SKIP_SUBTREE;
            }

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(final Path file, final IOException exc) throws IOException {
            if (exc instanceof AccessDeniedException) {
                this.recorder.diagnostic("Access denied visiting [%s], skipping", file);
                return FileVisitResult.CONTINUE;
            }
            return super.visitFileFailed(file, exc);
        }
    }

    @Override
    public Stream<JDK> detect() {

        // detect the installed JDKs once
        this.jdks.getAndUpdate(jdk -> {
            if (jdk == null) {
                // we've yet to detect the installed JDKs

                // the currently detected JDKs
                final SortedSet<JDK> detected = new ConcurrentSkipListSet<>();

                paths()
                    .map(JDKDetector::of)
                    .filter(Exceptional::isPresent)
                    .map(Exceptional::orElseThrow)
                    .forEach(detected::add);

                return detected;
            } else {
                // use the previously detected JDKs
                return jdk;
            }
        });

        return this.jdks.get().stream();
    }

    /**
     * Determines if the specified OS pattern matches the provided OS name.
     * <p>
     * Known OS kind patterns: {@code mac}, {@code windows}, {@code unix}, {@code posix}, {@code unknown}.
     * Any other pattern is treated as a regular expression matched against the OS name (lower-case).
     *
     * @param osName  the OS name (typically from {@code System.getProperty("os.name")})
     * @param pattern the OS pattern from the properties file key prefix
     * @return {@code true} if the pattern matches the OS name
     */
    static boolean matchesOS(final String osName, final String pattern) {
        final String lower = osName.toLowerCase();
        return switch (pattern.toLowerCase()) {
            case "mac" -> lower.contains("mac");
            case "windows" -> lower.contains("windows");
            case "unix" -> lower.contains("linux") || lower.contains("freebsd") || lower.contains("openbsd");
            case "posix" -> lower.contains("sunos") || lower.contains("solaris")
                || lower.contains("hp-ux") || lower.contains("aix");
            case "unknown" -> false;
            default -> lower.matches(pattern.toLowerCase());
        };
    }
}
