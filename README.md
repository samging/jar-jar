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

One public class with a public no-arg constructor and a public `run(String)` that returns its answer. amie calls
`close()` when the session ends, if the class has one, and `configure(File)` with the jar's config folder right after
the constructor. No amie library is needed to compile one.

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
4. **Check jars** runs on the pull request. It checks every jar here the way amie checks a download: it reads as a jar,
   its classes load on Java 21, its metadata parses and its `java` and `entry` hold, and a package class is there.
   Classes are loaded without being initialized, so no code of a jar runs in the check. A missing `close()` or missing
   metadata is a note on the run, not a failure.
5. Merge when it passes.

To run the check yourself, with a JDK 21:

```bash
java .github/scripts/CheckJars.java *.jar
```

A Kotlin jar that does not bundle its standard library needs `--provided kotlin-stdlib-2.1.0.jar` before the jars, as
amie provides one.
