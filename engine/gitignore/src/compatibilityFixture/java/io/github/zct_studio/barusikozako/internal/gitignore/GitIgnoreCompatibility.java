// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.internal.gitignore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class GitIgnoreCompatibility {
    private record Fixture(String rules, String... paths) {}

    public static void main(String[] args) throws Exception {
        var fixtures = List.of(
            new Fixture("*.log\n!important.log\nimportant.log\n", "a.log", "important.log", "sub/a.log", "a.txt"),
            new Fixture("dir/\n!dir/keep\nplain/\n", "plain", "dir/", "dir/keep", "other/dir/keep"),
            new Fixture("dir/*\n!dir/keep/\n", "dir/", "dir/keep/", "dir/keep/a", "dir/no/a"),
            new Fixture("/root\nconfig/file\n", "root", "sub/root", "config/file", "a/config/file"),
            new Fixture("a/**/b\n**/cache\nlogs/**\n", "a/b", "a/c/b", "a/c/d/b", "cache", "a/cache",
                "logs/", "logs/a", "logs/sub/a"),
            new Fixture("# comment\n\\#name\n\\!name\nwith space\ntrailing  \nkept\\ \n",
                "#name", "!name", "with space", "withspace", "trailing", "trailing ", "kept", "kept "),
            new Fixture("a?.[0-9]\n[!a]x\n", "ab.1", "a.1", "aa.a", "bx", "ax", "sub/bx"),
            new Fixture("\uFEFFroot\r\nsub/\r\n", "root", "sub/", "sub/a", "other"),
            new Fixture("?.txt\n??.bin\n[[:alpha:]][[:alpha:]].dat\n世界\n", "e.txt", "é.txt", "中.txt", ".txt",
                "é.bin", "é.dat", "en.dat", "dir/世界"),
            new Fixture("literal\\*\nfoo\t\n", "literal*", "literal-a", "foo", "foo\t"),
            new Fixture("data/ \nother/\t\n", "data/", "data/a", "other/", "other/a", "other/\t"),
            new Fixture("[a-c].dat\n[[:digit:]].txt\n[a&&b].bin\n[^a].n\n",
                "b.dat", "z.dat", "1.txt", "a.txt", "a.bin", "b.bin", "&.bin", "c.bin", "x.n", "a.n"),
            new Fixture("x/**/**/file\na**b\nfoo/***/bar\n", "x/file", "x/y/file", "x/y/z/file", "ab", "axxb",
                "foo/bar", "foo/z/bar", "foo/a/z/bar"),
            new Fixture("space\\  \\ \n", "space", "space ", "space  ", "space   ")
        );
        var directory = Files.createTempDirectory("barusikozako-ignore-");
        var failures = new ArrayList<String>();
        int comparisons = 0;
        try {
            git(directory, "init", "-q");
            for (var fixture : fixtures) {
                Files.writeString(directory.resolve(".gitignore"), fixture.rules(), UTF_8);
                var accepted = gitIgnored(directory, fixture.paths());
                var rules = GitIgnoreRules.parse(new ByteArrayInputStream(fixture.rules().getBytes(UTF_8)));
                for (String path : fixture.paths()) {
                    boolean isDirectory = path.endsWith("/");
                    String input = isDirectory ? path.substring(0, path.length() - 1) : path;
                    boolean ignored = rules.ignores(input, isDirectory);
                    if (ignored != accepted.contains(input)) {
                        failures.add(path + " Git=" + accepted.contains(input) + " parser=" + ignored
                            + " rules=" + fixture.rules().replace("\n", "|"));
                    }
                    comparisons++;
                }
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    // Windows 下 Git 可能为元数据目录设置隐藏属性
                    if (Files.getFileStore(path).supportsFileAttributeView("dos")) {
                        Files.setAttribute(path, "dos:hidden", false);
                    }
                    Files.delete(path);
                }
            }
        }
        verifyJar(args.length > 0);
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
        System.out.println("Git check-ignore comparisons: " + comparisons);
    }

    private static void git(Path directory, String... args) throws IOException, InterruptedException {
        var cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        var process = new ProcessBuilder(cmd).directory(directory.toFile()).inheritIO().start();
        if (process.waitFor() != 0) throw new IOException("Git fixture initialization failed");
    }

    private static Set<String> gitIgnored(Path directory, String[] paths) throws IOException, InterruptedException {
        for (String path : paths) {
            if (path.endsWith("/")) Files.createDirectories(directory.resolve(path));
        }
        var gitPaths = Arrays.stream(paths)
            .map(path -> path.endsWith("/") ? path.substring(0, path.length() - 1) : path)
            .toList();
        var process = new ProcessBuilder("git", "-c", "core.ignoreCase=false", "-c", "core.excludesFile="
            + directory.resolve("no-global-ignore"), "check-ignore", "--no-index", "--stdin", "-z")
            .directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var input = process.getOutputStream()) {
            input.write((String.join("\0", gitPaths) + "\0").getBytes(UTF_8));
        }
        String output = new String(process.getInputStream().readAllBytes(), UTF_8);
        if (process.waitFor() > 1) throw new IOException("git check-ignore failed");
        return output.isEmpty() ? Set.of() : Set.of(output.split("\0"));
    }

    private static void verifyJar(boolean coexistence) throws Exception {
        var path = Path.of(GitIgnoreRules.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var jar = new ZipFile(path.toFile())) {
            if (jar.stream().anyMatch(e -> e.getName().startsWith("org/eclipse/jgit/")
                    || e.getName().startsWith("org/slf4j/") || e.getName().startsWith("META-INF/services/"))) {
                throw new AssertionError("Unrelocated or unexpected entries in parser JAR");
            }
            if (jar.getEntry("META-INF/barusikozako/gitignore/LICENSE-jgit.txt") == null
                    || jar.getEntry("META-INF/barusikozako/gitignore/NOTICE-jgit.txt") == null) {
                throw new AssertionError("Missing JGit license");
            }
        }
        if (coexistence) {
            var loader = GitIgnoreRules.class.getClassLoader();
            var original = Class.forName("org.eclipse.jgit.ignore.FastIgnoreRule", true, loader);
            var isolated = io.github.zct_studio.barusikozako.internal.gitignore.jgit.ignore.FastIgnoreRule.class;
            if (original.getProtectionDomain().getCodeSource().getLocation().equals(
                    isolated.getProtectionDomain().getCodeSource().getLocation())) {
                throw new AssertionError("Full and private JGit should come from distinct JARs");
            }
            var rule = original.getConstructor(String.class).newInstance("*.mca");
            if (!Boolean.TRUE.equals(original.getMethod("isMatch", String.class, boolean.class)
                    .invoke(rule, "region/test.mca", false))) {
                throw new AssertionError("Original JGit matcher did not execute");
            }
            System.out.println("Full and private JGit coexist in the same class loader");
        }
    }
}
