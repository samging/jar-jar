import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Checks package jars against the AmiePackage contract, the interface the website's upload check holds a jar to
 * (packageRepository's AmiePackage : AutoCloseable): a public, concrete class with a public no-arg constructor,
 * a public run(String) returning a List of lines, and a public close(). Implementing AmiePackage is the easiest way;
 * any class with those methods qualifies, inherited ones included. Like amie before it keeps a download, it also
 * checks that the jar reads, its classes load on this Java, its metadata's java holds, and that the entry class the
 * metadata names is that class.
 *
 * The package class is picked in amie's order: the metadata's entry, else the manifest's Main-Class, else a class
 * implementing an interface named AmiePackage, else the first class of the jar that satisfies the contract.
 *
 * Classes are loaded without being initialized, so no code of a jar runs in the check.
 *
 * With --run, each jar that passes the check is then opened and tested the way `amie packages test` does it, every
 * jar in a JVM of its own so one that hangs or exits can't take the others with it: the config files it declares are
 * written from their templates into a config folder of its own, which is the first root of its class loader; the
 * amie.package.* properties are set; the package class is made and configure() called with the folder; each test the
 * metadata declares goes through run() and is checked against expect, contains and lines (with none declared, one call
 * with "hello" that must not throw); and close() is called. A required config left as its template, which amie would
 * ask to be filled in before opening the jar, is a note here, so a jar that needs a key can still show it answers.
 *
 *   java CheckJars.java [--provided lib.jar[:lib2.jar]] [--run] a.jar b.jar ...
 *
 * --provided puts libraries under every jar, as amie's own classes are under a package (its Kotlin standard library).
 * Exit 0 when every jar passes, 1 when one fails, 2 on wrong arguments.
 */
public class CheckJars {
    static final String META_IN_JAR = "META-INF/amie/package.json";
    static final String META_AT_ROOT = "amie-package.json";

    /** How long one run() may take, and one jar's whole test run */
    static final long CALL_SECONDS = 30, JAR_SECONDS = 120;
    static final String MARK = "@@check\t";

    /** What the check found; [entry] is the package class and [meta] the parsed metadata, when it passes */
    record Result(String jar, boolean ok, String detail, List<String> warnings, String entry, Map<?, ?> meta) {
        Result(String jar, boolean ok, String detail, List<String> warnings) { this(jar, ok, detail, warnings, null, null); }
    }

    public static void main(String[] args) throws Exception {
        List<URL> provided = new ArrayList<>();
        String providedArg = null;
        List<String> jars = new ArrayList<>();
        boolean run = false;
        String runOne = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--provided") && i + 1 < args.length) {
                providedArg = args[++i];
                for (String p : providedArg.split(File.pathSeparator)) if (!p.isBlank()) provided.add(new File(p).toURI().toURL());
            } else if (args[i].equals("--run")) run = true;
            else if (args[i].equals("--run-one") && i + 1 < args.length) runOne = args[++i];
            else jars.add(args[i]);
        }
        ClassLoader providedLoader = new URLClassLoader("provided", provided.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        if (runOne != null) {
            System.exit(runOne(new File(runOne), providedLoader));
            return;
        }
        if (run && !jars.isEmpty()) {
            System.exit(runAll(jars, providedLoader, providedArg));
            return;
        }
        if (jars.isEmpty()) {
            System.err.println("usage: java CheckJars.java [--provided lib.jar] a.jar b.jar ...");
            System.exit(2);
        }
        ClassLoader parent = providedLoader;
        int host = Runtime.version().feature();
        System.out.println("Checking " + jars.size() + " jar(s) on Java " + host + (provided.isEmpty() ? "" : ", with " + provided.size() + " provided librar" + (provided.size() == 1 ? "y" : "ies")));

        List<Result> results = new ArrayList<>();
        for (String jar : jars) {
            Result r;
            try {
                r = check(new File(jar), parent, host);
            } catch (Throwable t) {
                r = new Result(jar, false, "could not be inspected: " + t, List.of());
            }
            results.add(r);
            System.out.println((r.ok ? "ok    " : "FAIL  ") + r.jar + "  " + r.detail);
            for (String w : r.warnings) System.out.println("      note: " + w);
            if (System.getenv("GITHUB_ACTIONS") != null) {
                if (!r.ok) System.out.println("::error file=" + r.jar + "::" + r.jar + ": " + r.detail);
                for (String w : r.warnings) System.out.println("::warning file=" + r.jar + "::" + r.jar + ": " + w);
            }
        }
        long failed = results.stream().filter(r -> !r.ok).count();
        System.out.println(failed == 0 ? "All " + results.size() + " jar(s) pass." : failed + " of " + results.size() + " jar(s) fail.");
        summary(results, host);
        System.exit(failed == 0 ? 0 : 1);
    }

    static Result check(File file, ClassLoader parent, int host) throws IOException {
        String name = file.getPath();
        List<String> warnings = new ArrayList<>();
        if (!file.isFile()) return new Result(name, false, "no such file", warnings);
        List<String> classes = new ArrayList<>();
        int major = 0;
        String mainClass;
        String metaText = null;
        try (JarFile jar = new JarFile(file)) {
            mainClass = jar.getManifest() == null ? null : jar.getManifest().getMainAttributes().getValue("Main-Class");
            for (JarEntry e : Collections.list(jar.entries())) {
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (n.equals(META_IN_JAR) || (metaText == null && n.equals(META_AT_ROOT))) {
                    try (InputStream in = jar.getInputStream(e)) { metaText = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                }
                if (!n.endsWith(".class") || n.endsWith("module-info.class") || n.endsWith("package-info.class")) continue;
                try (DataInputStream in = new DataInputStream(jar.getInputStream(e))) {
                    if (in.readInt() == 0xCAFEBABE) {
                        in.readUnsignedShort();
                        major = Math.max(major, in.readUnsignedShort());
                    }
                }
                if (!n.startsWith("META-INF/")) classes.add(n.substring(0, n.length() - 6).replace('/', '.'));
            }
        } catch (IOException e) {
            return new Result(name, false, "not a readable jar: " + e.getMessage(), warnings);
        }
        int needs = major >= 49 ? major - 44 : 0;
        if (needs > host) return new Result(name, false, "its classes need Java " + needs + " or newer and amie runs Java " + host, warnings);

        // The metadata, when the jar has some: it must parse, and what it says must hold
        String entry = null;
        Integer java = null;
        String metaName = null;
        Map<?, ?> metaMap = null;
        if (metaText == null) warnings.add("no " + META_IN_JAR + ": it opens, but says nothing of its config, tests or description");
        else {
            Object parsed;
            try {
                parsed = new Json(metaText).parse();
            } catch (RuntimeException e) {
                return new Result(name, false, META_IN_JAR + " is not valid JSON: " + e.getMessage(), warnings);
            }
            if (!(parsed instanceof Map<?, ?> meta)) return new Result(name, false, META_IN_JAR + " is not a JSON object", warnings);
            metaMap = meta;
            if (meta.get("entry") instanceof String s && !s.isBlank()) entry = s.trim();
            if (meta.get("java") instanceof Double d) java = d.intValue();
            if (meta.get("name") instanceof String s) metaName = s;
            if (java != null && java > host) return new Result(name, false, "its metadata asks for Java " + java + " and amie runs Java " + host, warnings);
            if (meta.get("description") == null) warnings.add("its metadata has no description, which amie shows beside the jar");
        }

        // The package class, in amie's order
        List<Candidate> candidates = new ArrayList<>();
        List<String> nearMisses = new ArrayList<>();
        Map<String, String> missed = new LinkedHashMap<>();
        try (URLClassLoader loader = new URLClassLoader("check-" + file.getName(), new URL[]{file.toURI().toURL()}, parent)) {
            for (String c : classes) {
                Class<?> clazz;
                try {
                    clazz = Class.forName(c, false, loader);
                } catch (Throwable t) {
                    continue;
                }
                Candidate cand = contract(clazz);
                if (cand == null) continue;
                if (cand.problem == null) candidates.add(cand);
                else { nearMisses.add(cand.name + " " + cand.problem); missed.put(cand.name, cand.problem); }
            }
        }
        if (candidates.isEmpty()) {
            String near = nearMisses.isEmpty() ? "" : ": " + String.join("; ", nearMisses.subList(0, Math.min(3, nearMisses.size())));
            return new Result(name, false, "no package class, no public class with a no-arg constructor, List run(String) and close()" + near, warnings);
        }
        Candidate chosen = null;
        if (entry != null) {
            String wanted = entry;
            chosen = candidates.stream().filter(c -> c.name.equals(wanted)).findFirst().orElse(null);
            if (chosen == null) return new Result(name, false, "its metadata names " + entry + " as the package class, but " + entry
                + (missed.containsKey(entry) ? " " + missed.get(entry) : " is not a public class with a no-arg constructor, List run(String) and close()"), warnings);
        }
        if (chosen == null && mainClass != null) chosen = candidates.stream().filter(c -> c.name.equals(mainClass)).findFirst().orElse(null);
        if (chosen == null) chosen = candidates.stream().filter(c -> c.amiePackage).findFirst().orElse(null);
        if (chosen == null) {
            chosen = candidates.get(0);
            if (candidates.size() > 1) warnings.add(candidates.size() + " classes could be the package class and nothing picks one; " + chosen.name + " is taken. Name it as \"entry\" in the metadata");
        }
        String base = file.getName().replaceFirst("(?i)\\.jar$", "");
        if (metaName != null && !base.equals(metaName) && !base.startsWith(metaName + "-"))
            warnings.add("its metadata calls it '" + metaName + "' and the file is " + file.getName());

        String detail = "package class " + chosen.name + " (" + chosen.returns + " run(String), close()" + (chosen.amiePackage ? ", implements AmiePackage" : "") + ")"
            + ", needs Java " + Math.max(needs, java == null ? 0 : java)
            + (metaText == null ? ", no metadata" : ", metadata");
        return new Result(name, true, detail, warnings, chosen.name, metaMap);
    }

    /** A class with a public run(String); [problem] is what keeps it from the contract, null when nothing does */
    record Candidate(String name, boolean amiePackage, String returns, String problem) {}

    /**
     * The AmiePackage contract: a concrete public class with a public no-arg constructor, a public run(String) that
     * returns a List, and a public close(). Null for a class with no run(String) at all, which is no near miss
     */
    static Candidate contract(Class<?> clazz) {
        try {
            int mods = clazz.getModifiers();
            if (clazz.isInterface() || clazz.isAnnotation() || clazz.isEnum() || Modifier.isAbstract(mods) || !Modifier.isPublic(mods)) return null;
            Method run = null;
            boolean close = false;
            for (Method m : clazz.getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getName().equals("run") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) run = m;
                if (m.getName().equals("close") && m.getParameterCount() == 0) close = true;
            }
            if (run == null) return null;
            boolean amie = false;
            for (Class<?> c = clazz; c != null; c = c.getSuperclass())
                for (Class<?> i : c.getInterfaces()) if (i.getSimpleName().equals("AmiePackage")) amie = true;
            String returns = run.getReturnType().getSimpleName();
            List<String> problems = new ArrayList<>();
            if (!List.class.isAssignableFrom(run.getReturnType())) problems.add("has run(String) returning " + returns + ", not a List of lines");
            if (!close) problems.add("has no close()");
            try { clazz.getConstructor(); } catch (NoSuchMethodException e) { problems.add("has no public no-arg constructor"); }
            return new Candidate(clazz.getName(), amie, returns, problems.isEmpty() ? null : String.join(" and ", problems));
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- --run: open each jar as amie does and call run() and close() ----------

    record Step(String kind, boolean ok, String text) {}

    /** Checks each jar, then tests the ones that pass, each in a JVM of its own; the exit code */
    static int runAll(List<String> jars, ClassLoader parent, String providedArg) throws Exception {
        int host = Runtime.version().feature();
        System.out.println("Calling run() and close() of " + jars.size() + " jar(s) on Java " + host + ", each in a JVM of its own");
        List<String[]> rows = new ArrayList<>();
        int failed = 0;
        for (String jar : jars) {
            Result check;
            try {
                check = check(new File(jar), parent, host);
            } catch (Throwable t) {
                check = new Result(jar, false, "could not be inspected: " + t, List.of());
            }
            List<Step> steps = new ArrayList<>();
            List<String> output = new ArrayList<>();
            boolean ok;
            String verdict;
            if (!check.ok) {
                ok = false;
                verdict = "not run: " + check.detail;
            } else {
                List<String> cmd = new ArrayList<>(self());
                cmd.add(1, "-Djava.awt.headless=true");
                if (providedArg != null) { cmd.add("--provided"); cmd.add(providedArg); }
                cmd.add("--run-one");
                cmd.add(jar);
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                p.getOutputStream().close();
                Thread reader = new Thread(() -> {
                    try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        for (String line; (line = in.readLine()) != null; ) {
                            if (line.startsWith(MARK)) {
                                String[] f = line.substring(MARK.length()).split("\t", 3);
                                synchronized (steps) { steps.add(new Step(f[0], f[1].equals("ok"), f.length > 2 ? f[2] : "")); }
                            } else synchronized (output) { output.add(line); }
                        }
                    } catch (IOException ignored) {
                    }
                });
                reader.start();
                boolean ended = p.waitFor(JAR_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                if (!ended) p.destroyForcibly().waitFor();
                reader.join(5000);
                long tests = steps.stream().filter(s -> s.kind.equals("test")).count();
                long passed = steps.stream().filter(s -> s.kind.equals("test") && s.ok).count();
                boolean stepsOk = steps.stream().noneMatch(s -> !s.ok && !s.kind.equals("note"));
                boolean opened = steps.stream().anyMatch(s -> s.kind.equals("open") && s.ok);
                boolean closed = steps.stream().anyMatch(s -> s.kind.equals("close") && s.ok);
                boolean done = steps.stream().anyMatch(s -> s.kind.equals("done"));
                String during = steps.stream().filter(s -> s.kind.equals("start")).map(Step::text).reduce((a, b) -> b).orElse("opening");
                ok = ended && done && stepsOk && opened && closed;
                String closeText = steps.stream().filter(s -> s.kind.equals("close")).map(Step::text).findFirst().orElse("close() not reached");
                verdict = !ended ? "did not finish within " + JAR_SECONDS + " s; it was stopped"
                    : !opened ? steps.stream().filter(s -> s.kind.equals("open")).map(Step::text).findFirst().orElse("its JVM ended before the package was made (exit " + p.exitValue() + ")")
                    : !done ? "its JVM ended with exit " + p.exitValue() + " during " + during
                    : passed + " of " + tests + " test(s) pass, " + closeText;
            }
            if (!ok) failed++;
            System.out.println((ok ? "ok    " : "FAIL  ") + jar + "  " + verdict);
            StringBuilder cell = new StringBuilder();
            for (Step s : steps) {
                if (s.kind.equals("done") || s.kind.equals("start")) continue;
                String label = s.kind.equals("note") ? "note" : s.ok ? "pass" : "fail";
                if (s.kind.equals("open") && !s.ok && !opened(steps)) { if (System.getenv("GITHUB_ACTIONS") != null) System.out.println("::error file=" + jar + "::" + jar + ": " + s.text); continue; }
                if (s.kind.equals("open") || s.kind.equals("close")) { if (s.ok) continue; label = "fail"; }
                System.out.println("      " + label + "  " + s.text);
                cell.append("<br>").append(label).append(": ").append(s.text.replace("|", "\\|").replace("<", "&lt;"));
                if (System.getenv("GITHUB_ACTIONS") != null) {
                    if (s.kind.equals("note")) System.out.println("::warning file=" + jar + "::" + jar + ": " + s.text);
                    else if (!s.ok) System.out.println("::error file=" + jar + "::" + jar + ": " + s.text);
                }
            }
            if (!ok && System.getenv("GITHUB_ACTIONS") != null && steps.stream().noneMatch(s -> !s.ok && !s.kind.equals("note")))
                System.out.println("::error file=" + jar + "::" + jar + ": " + verdict);
            if (!ok && !output.isEmpty()) {
                System.out.println("      what it printed, the last lines:");
                output.subList(Math.max(0, output.size() - 8), output.size()).forEach(l -> System.out.println("        " + l));
            }
            rows.add(new String[]{jar, ok ? "ok" : "fails", verdict.replace("|", "\\|") + cell});
        }
        System.out.println(failed == 0 ? "All " + jars.size() + " jar(s) answer and close." : failed + " of " + jars.size() + " jar(s) fail.");
        String path = System.getenv("GITHUB_STEP_SUMMARY");
        if (path != null) {
            StringBuilder md = new StringBuilder("### run() and close(), as `amie packages test` calls them (Java " + host + ")\n\n| Jar | | What happened |\n|---|---|---|\n");
            for (String[] row : rows) md.append("| `").append(row[0]).append("` | ").append(row[1]).append(" | ").append(row[2]).append(" |\n");
            Files.writeString(Path.of(path), md.toString(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
        return failed == 0 ? 0 : 1;
    }

    static boolean opened(List<Step> steps) { return steps.stream().anyMatch(s -> s.kind.equals("open") && s.ok); }

    /** The command that starts this checker again: the source file when it was launched from one, else its class */
    static List<String> self() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var source = CheckJars.class.getProtectionDomain().getCodeSource();
        URL at = source == null ? null : source.getLocation();
        if (at != null && at.getPath().endsWith(".java")) return new ArrayList<>(List.of(java, Path.of(at.toURI()).toString()));
        return new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"), CheckJars.class.getName()));
    }

    static void say(String kind, boolean ok, String text) {
        System.out.println(MARK + kind + "\t" + (ok ? "ok" : "fail") + "\t" + text.replace('\n', ' ').replace('\t', ' '));
        System.out.flush();
    }

    /** In the child JVM: one jar opened and tested as amie does it; its exit code */
    static int runOne(File file, ClassLoader parent) {
        int host = Runtime.version().feature();
        Result check;
        try {
            check = check(file, parent, host);
        } catch (Throwable t) {
            say("open", false, "could not be inspected: " + t);
            return 1;
        }
        if (!check.ok) { say("open", false, check.detail); return 1; }
        Map<?, ?> meta = check.meta == null ? Map.of() : check.meta;
        String base = file.getName().replaceFirst("(?i)\\.jar$", "");
        boolean allOk = true;
        Object instance;
        Class<?> clazz;
        URLClassLoader loader;
        try {
            // The config folder, with the declared files written from their templates, as amie does on the first open
            File configDir = Files.createTempDirectory("amie-config-").resolve(base).toFile();
            configDir.mkdirs();
            if (meta.get("config") instanceof List<?> configs) for (Object o : configs) {
                if (!(o instanceof Map<?, ?> c) || !(c.get("file") instanceof String f)) continue;
                File target = new File(configDir, f);
                if (c.get("template") instanceof String t) {
                    target.getParentFile().mkdirs();
                    Files.writeString(target.toPath(), t, StandardCharsets.UTF_8);
                    if (Boolean.TRUE.equals(c.get("required"))) say("note", true, f + " is required and only its template is there: amie asks for it to be filled in before it opens the jar");
                } else if (Boolean.TRUE.equals(c.get("required"))) say("note", true, f + " is required and has no template, so it is missing here");
            }
            if (meta.get("env") instanceof List<?> envs) for (Object o : envs) {
                if (o instanceof Map<?, ?> e && e.get("name") instanceof String n && Boolean.TRUE.equals(e.get("required")) && (System.getenv(n) == null || System.getenv(n).isEmpty()))
                    say("note", true, "the variable " + n + " is required and not set: amie would not open the jar without it");
            }
            loader = new URLClassLoader("amie-package-" + file.getName(), new URL[]{configDir.toURI().toURL(), file.toURI().toURL()}, parent);
            System.setProperty("amie.package.config", configDir.getAbsolutePath());
            System.setProperty("amie.package.name", file.getName());
            System.setProperty("amie.package.config." + base, configDir.getAbsolutePath());
            Thread.currentThread().setContextClassLoader(loader);
            clazz = Class.forName(check.entry, true, loader);
            instance = clazz.getConstructor().newInstance();
            configure(instance, clazz, configDir);
            say("open", true, clazz.getName() + " made" + (hasConfigure(clazz) ? " and configured" : ""));
        } catch (Throwable t) {
            say("open", false, "the package could not be made: " + describe(t));
            return 1;
        }

        Method run, close = null;
        try {
            run = clazz.getMethod("run", String.class);
            try { close = clazz.getMethod("close"); } catch (NoSuchMethodException ignored) {}
        } catch (NoSuchMethodException e) {
            say("open", false, "run(String) went missing: " + e);
            return 1;
        }

        List<Map<?, ?>> tests = new ArrayList<>();
        if (meta.get("tests") instanceof List<?> declared) for (Object o : declared) if (o instanceof Map<?, ?> t && t.get("input") instanceof String) tests.add(t);
        boolean smoke = tests.isEmpty();
        if (smoke) tests.add(Map.of("name", "answers hello", "input", "hello"));
        var calls = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "amie-package-call"); t.setDaemon(true); t.setContextClassLoader(loader); return t; });
        for (Map<?, ?> t : tests) {
            String input = (String) t.get("input");
            String title = t.get("name") instanceof String n ? n + ": \"" + input + "\"" : "\"" + input + "\"";
            say("start", true, "run(\"" + input + "\")");
            long begun = System.nanoTime();
            Object instanceRef = instance;
            var call = calls.submit(() -> run.invoke(instanceRef, input));
            try {
                List<String> answer = lines(call.get(CALL_SECONDS, java.util.concurrent.TimeUnit.SECONDS));
                long ms = (System.nanoTime() - begun) / 1_000_000;
                String reason = checkAnswer(t, answer);
                String shown = String.join(" | ", answer);
                if (shown.length() > 100) shown = shown.substring(0, 100) + "...";
                say("test", reason == null, title + " -> " + (answer.isEmpty() ? "no lines" : shown) + (reason == null ? "" : " (" + reason + ")") + ", " + ms + " ms");
                if (reason != null) allOk = false;
            } catch (java.util.concurrent.TimeoutException e) {
                call.cancel(true);
                say("test", false, title + " did not answer within " + CALL_SECONDS + " s");
                allOk = false;
                break;
            } catch (Throwable e) {
                say("test", false, title + " threw " + describe(e));
                allOk = false;
            }
        }
        if (close == null) say("close", true, "no close() to call");
        else {
            Method closeRef = close;
            Object instanceRef = instance;
            say("start", true, "close()");
            var call = calls.submit(() -> closeRef.invoke(instanceRef));
            try {
                call.get(CALL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                say("close", true, "close() called");
            } catch (java.util.concurrent.TimeoutException e) {
                say("close", false, "close() did not return within " + CALL_SECONDS + " s");
                allOk = false;
            } catch (Throwable e) {
                say("close", false, "close() threw " + describe(e));
                allOk = false;
            }
        }
        try { loader.close(); } catch (IOException ignored) {}
        say("done", true, "");
        return allOk ? 0 : 1;
    }

    static boolean hasConfigure(Class<?> clazz) {
        for (Method m : clazz.getMethods()) if (m.getName().equals("configure") && m.getParameterCount() == 1 && !Modifier.isStatic(m.getModifiers())) return true;
        return false;
    }

    /** amie's configure(): File, Path, String, or a Map of dir and the folder's files */
    static void configure(Object instance, Class<?> clazz, File dir) throws Exception {
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals("configure") || m.getParameterCount() != 1 || Modifier.isStatic(m.getModifiers())) continue;
            Class<?> type = m.getParameterTypes()[0];
            Object arg = null;
            if (type == File.class) arg = dir;
            else if (type == Path.class) arg = dir.toPath();
            else if (type == String.class) arg = dir.getAbsolutePath();
            else if (type.isAssignableFrom(Map.class)) {
                Map<String, String> files = new LinkedHashMap<>();
                files.put("dir", dir.getAbsolutePath());
                File[] list = dir.listFiles();
                if (list != null) for (File f : list) if (f.isFile()) files.put(f.getName(), f.getAbsolutePath());
                arg = files;
            }
            if (arg == null) { say("note", true, "configure(" + type.getSimpleName() + ") is not called: amie passes File, Path, String or Map"); return; }
            m.invoke(instance, arg);
            return;
        }
    }

    /** amie's lines(): a collection or array one line per element, a string one line, null none */
    static List<String> lines(Object result) {
        List<String> out = new ArrayList<>();
        if (result == null) return out;
        if (result instanceof String s) out.add(s);
        else if (result instanceof Iterable<?> it) for (Object o : it) out.add(String.valueOf(o));
        else if (result instanceof Object[] a) for (Object o : a) out.add(String.valueOf(o));
        else out.add(result.toString());
        return out;
    }

    /** amie's test check: expect the whole answer, contains some line, lines how many; null when it passes */
    static String checkAnswer(Map<?, ?> t, List<String> answer) {
        if (t.get("expect") instanceof String e && !String.join("\n", answer).equals(e) && !answer.equals(List.of(e))) return "expected \"" + e + "\"";
        if (t.get("contains") instanceof String c && answer.stream().noneMatch(l -> l.contains(c))) return "no line contains \"" + c + "\"";
        if (t.get("lines") instanceof Double n && answer.size() != n.intValue()) return "expected " + n.intValue() + " line(s), got " + answer.size();
        return null;
    }

    static String describe(Throwable t) {
        while ((t instanceof java.lang.reflect.InvocationTargetException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    /** A table on the workflow run's page */
    static void summary(List<Result> results, int host) {
        String path = System.getenv("GITHUB_STEP_SUMMARY");
        if (path == null) return;
        StringBuilder md = new StringBuilder("### Package jars, checked as amie checks a download (Java " + host + ")\n\n| Jar | | What was found |\n|---|---|---|\n");
        for (Result r : results) {
            String notes = r.warnings.isEmpty() ? "" : "<br>" + String.join("<br>", r.warnings.stream().map(w -> "note: " + w).toList());
            md.append("| `").append(r.jar).append("` | ").append(r.ok ? "ok" : "fails").append(" | ").append(r.detail.replace("|", "\\|")).append(notes.replace("|", "\\|")).append(" |\n");
        }
        try {
            Files.writeString(Path.of(path), md.toString(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    /** Just enough JSON to read package.json: objects, arrays, strings, numbers, true, false, null */
    static final class Json {
        private final String s;
        private int i;

        Json(String s) { this.s = s; }

        Object parse() {
            Object v = value();
            space();
            if (i != s.length()) throw error("text after the end");
            return v;
        }

        private Object value() {
            space();
            if (i >= s.length()) throw error("it ends early");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (c == '-' || Character.isDigit(c)) return number();
            throw error("unexpected '" + c + "'");
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            space();
            if (peek('}')) { i++; return m; }
            while (true) {
                space();
                if (!peek('"')) throw error("a key in quotes was expected");
                String k = string();
                space();
                expect(':');
                m.put(k, value());
                space();
                if (peek(',')) { i++; continue; }
                expect('}');
                return m;
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            space();
            if (peek(']')) { i++; return l; }
            while (true) {
                l.add(value());
                space();
                if (peek(',')) { i++; continue; }
                expect(']');
                return l;
            }
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (i >= s.length()) break;
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 't' -> b.append('\t');
                        case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw error("a short \\u escape");
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> b.append(e);
                    }
                } else b.append(c);
            }
            throw error("a string is not closed");
        }

        private Double number() {
            int start = i;
            if (peek('-')) i++;
            while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
            try {
                return Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw error("a bad number");
            }
        }

        private void space() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

        private void expect(char c) {
            if (!peek(c)) throw error("'" + c + "' was expected");
            i++;
        }

        private RuntimeException error(String what) {
            int line = 1;
            for (int k = 0; k < Math.min(i, s.length()); k++) if (s.charAt(k) == '\n') line++;
            return new IllegalArgumentException(what + " (line " + line + ")");
        }
    }
}
