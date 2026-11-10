# jar-jar

Package jars for amie. A script opens one by its name, and amie takes it from here when it is nowhere local:

```kotlin
val notes = initMod("memory.jar")                            // looked for in the repositories of AMP/github.json
val pinned = initMod("github:samging/jar-jar/memory.jar@main") // this repository, a branch, tag or commit
log(notes.answer("help"))
notes.close()
```

The repository is private, so amie needs a token to read it: `GITHUB_TOKEN`, or `"token"` in `AMP/github.json`.
The sources of these jars are in [samging/amie-packages](https://github.com/samging/amie-packages), with a page for
each.

## What a package jar is

One public class that fulfils the `AmiePackage` interface: a public no-arg constructor, `List<String> run(String)`
and `void close()`. Both methods are required. Implementing the interface is the easiest way, but any class with the
same methods qualifies, inherited ones included, so no amie library is needed to compile one. amie calls `run` for
each text, `close()` once when the session ends, and `configure(File)`, when there is one, with the jar's config
folder right after the constructor.

```java
public class Greeter {
    public java.util.List<String> run(String text) { return java.util.List.of("Hello to you"); }
    public void close() {}
}
```

`META-INF/amie/package.json` inside the jar gives its name, description, `entry` class, `java`, config files and
tests. The whole contract is in the amie guide, under Writing a package and Packages from GitHub.

## Adding or updating a jar

1. Build it. In amie-packages, `./gradlew jars` puts every package in `build/jars`.
2. Check it locally: `amie packages add <jar>` and `amie packages test <jar>`.
3. Open a pull request that adds or replaces the jar at the top of this repository.
4. **Check jars** runs on the pull request, in two steps over every jar here:
   - **The check amie makes before it keeps a download.** It reads as a jar, its classes load on Java 21, its metadata
     parses and its `java` and `entry` hold, and a class fulfils `AmiePackage`, with `List<String> run(String)` and
     `close()`. Classes are loaded without being initialized, so this step runs no code of a jar.
   - **run() and close() called, as `amie packages test` calls them.** The package class is made in a class loader of
     its own with a config folder, its config files written from their templates; `configure()` is called; every test
     the metadata declares goes through `run()` and is checked against `expect`, `contains` and `lines`; then `close()`
     is called. A jar that declares no tests gets one call with `hello`, which must not throw. Each jar runs in a JVM
     of its own, with 30 seconds per call and 120 per jar, so a jar that hangs or exits fails alone.

   Missing metadata, or a required config left as its template, is a note on the run, not a failure. Tests run on Linux with no keys, so declare tests that answer without them: `help`, `status`.
5. Merge when both steps pass.

To run the steps yourself, with a JDK 21:

```bash
java .github/scripts/CheckJars.java *.jar
```

```bash
java .github/scripts/CheckJars.java --run *.jar
```

A Kotlin jar that does not bundle its standard library needs `--provided kotlin-stdlib-2.1.0.jar` before the jars, as
amie provides one.
