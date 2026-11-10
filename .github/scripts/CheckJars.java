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
 * Checks package jars the way amie checks one before it keeps a download (JarPackages.check in the amie runtime):
 * the jar reads, its classes load on this Java, its metadata's java holds, and a class satisfies the package
 * contract: public, not abstract, a public no-arg constructor, and a public run(String) that returns something.
 * close() is called when the class has it. When the metadata names an entry class, that class must be the one.
 *
 * The package class is picked in amie's order: the metadata's entry, else the manifest's Main-Class, else a class
 * implementing an interface named AmiePackage, else the first class of the jar that satisfies the contract.
 *
 * Classes are loaded without being initialized, so no code of a jar runs here.
 *
 *   java CheckJars.java [--provided lib.jar[:lib2.jar]] a.jar b.jar ...
 *
 * --provided puts libraries under every jar, as amie's own classes are under a package (its Kotlin standard library).
 * Exit 0 when every jar passes, 1 when one fails, 2 on wrong arguments.
 */
public class CheckJars {
    static final String META_IN_JAR = "META-INF/amie/package.json";
    static final String META_AT_ROOT = "amie-package.json";

    record Result(String jar, boolean ok, String detail, List<String> warnings) {}

    public static void main(String[] args) throws Exception {
        List<URL> provided = new ArrayList<>();
        List<String> jars = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--provided") && i + 1 < args.length) {
                for (String p : args[++i].split(File.pathSeparator)) if (!p.isBlank()) provided.add(new File(p).toURI().toURL());
            } else jars.add(args[i]);
        }
        if (jars.isEmpty()) {
            System.err.println("usage: java CheckJars.java [--provided lib.jar] a.jar b.jar ...");
            System.exit(2);
        }
        ClassLoader parent = new URLClassLoader("provided", provided.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
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
        if (metaText == null) warnings.add("no " + META_IN_JAR + ": it opens, but says nothing of its config, tests or description");
        else {
            Object parsed;
            try {
                parsed = new Json(metaText).parse();
            } catch (RuntimeException e) {
                return new Result(name, false, META_IN_JAR + " is not valid JSON: " + e.getMessage(), warnings);
            }
            if (!(parsed instanceof Map<?, ?> meta)) return new Result(name, false, META_IN_JAR + " is not a JSON object", warnings);
            if (meta.get("entry") instanceof String s && !s.isBlank()) entry = s.trim();
            if (meta.get("java") instanceof Double d) java = d.intValue();
            if (meta.get("name") instanceof String s) metaName = s;
            if (java != null && java > host) return new Result(name, false, "its metadata asks for Java " + java + " and amie runs Java " + host, warnings);
            if (meta.get("description") == null) warnings.add("its metadata has no description, which amie shows beside the jar");
        }

        // The package class, in amie's order
        List<Candidate> candidates = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader("check-" + file.getName(), new URL[]{file.toURI().toURL()}, parent)) {
            for (String c : classes) {
                Class<?> clazz;
                try {
                    clazz = Class.forName(c, false, loader);
                } catch (Throwable t) {
                    continue;
                }
                Candidate cand = contract(clazz);
                if (cand != null) candidates.add(cand);
            }
        }
        if (candidates.isEmpty())
            return new Result(name, false, "no package class: no public class with a public no-arg constructor and a public run(String) that returns its answer", warnings);
        Candidate chosen = null;
        if (entry != null) {
            String wanted = entry;
            chosen = candidates.stream().filter(c -> c.name.equals(wanted)).findFirst().orElse(null);
            if (chosen == null) return new Result(name, false, "its metadata names " + entry + " as the package class, but " + entry + " has no public no-arg constructor and run(String)", warnings);
        }
        if (chosen == null && mainClass != null) chosen = candidates.stream().filter(c -> c.name.equals(mainClass)).findFirst().orElse(null);
        if (chosen == null) chosen = candidates.stream().filter(c -> c.amiePackage).findFirst().orElse(null);
        if (chosen == null) {
            chosen = candidates.get(0);
            if (candidates.size() > 1) warnings.add(candidates.size() + " classes could be the package class and nothing picks one; " + chosen.name + " is taken. Name it as \"entry\" in the metadata");
        }
        if (!chosen.hasClose) warnings.add(chosen.name + " has no close(): amie opens it, but has nothing to call when the session ends");
        String base = file.getName().replaceFirst("(?i)\\.jar$", "");
        if (metaName != null && !base.equals(metaName) && !base.startsWith(metaName + "-"))
            warnings.add("its metadata calls it '" + metaName + "' and the file is " + file.getName());

        String detail = "package class " + chosen.name + " (" + chosen.returns + " run(String)" + (chosen.hasClose ? ", close()" : "") + ")"
            + ", needs Java " + Math.max(needs, java == null ? 0 : java)
            + (metaText == null ? ", no metadata" : ", metadata");
        return new Result(name, true, detail, warnings);
    }

    record Candidate(String name, boolean hasClose, boolean amiePackage, String returns) {}

    /** amie's contract: a concrete public class, a public no-arg constructor, run(String) returning something; close() if any */
    static Candidate contract(Class<?> clazz) {
        try {
            int mods = clazz.getModifiers();
            if (clazz.isInterface() || clazz.isAnnotation() || clazz.isEnum() || Modifier.isAbstract(mods) || !Modifier.isPublic(mods)) return null;
            Method run = null;
            boolean close = false;
            for (Method m : clazz.getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getName().equals("run") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class && m.getReturnType() != void.class) run = m;
                if (m.getName().equals("close") && m.getParameterCount() == 0) close = true;
            }
            if (run == null) return null;
            clazz.getConstructor();
            boolean amie = false;
            for (Class<?> i : clazz.getInterfaces()) if (i.getSimpleName().equals("AmiePackage")) amie = true;
            return new Candidate(clazz.getName(), close, amie, run.getReturnType().getSimpleName());
        } catch (Throwable t) {
            return null;
        }
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
