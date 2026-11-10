# jar-jar

Package jars for [amie](https://github.com/samging/amieCli). Each jar adds something a script can ask in plain text:
the Mac, a browser, files, Google apps, music, speech, a memory. The sources are in
[samging/amie-packages](https://github.com/samging/amie-packages), with a page for each.

## Using a jar

A script opens a jar by its name. When the jar is not on this computer, amie downloads it from here, checks it and
keeps it in `AMP/pluginDir`.

```kotlin
val notes = initMod("memory.jar")                              // looked for in the repositories of AMP/github.json
val pinned = initMod("github:samging/jar-jar/memory.jar@main") // this repository, at a branch, tag or commit
log(notes.answer("help"))
notes.close()
```

From the terminal:

```bash
amie packages search google
```

```bash
amie packages add spotify.jar
```

```bash
amie packages info spotify.jar
```

```bash
amie packages setup spotify.jar
```

The repository is private, so amie needs a token to read it: `GITHUB_TOKEN`, or `"token"` in `AMP/github.json`.

## What a package jar is

One public class that fulfils the `AmiePackage` interface:

```kotlin
interface AmiePackage : AutoCloseable {
    fun run(input: String): List<String>
    override fun close()
}
```

- A public no-arg constructor.
- `run(String)` is called for each text and returns the answer, one line per entry.
- `close()` is called once when the session ends, to let go of what the jar holds.
- `configure(File)`, when there is one, is called right after the constructor with the jar's config folder,
  `AMP/pluginDir/config/<jar>/`.

Both `run` and `close` are required. Implementing the interface is the easiest way, but any class with the same
methods qualifies, inherited ones included, so no amie library is needed to compile one.

```java
public class Greeter {
    public java.util.List<String> run(String text) { return java.util.List.of("Hello to you"); }
    public void close() {}
}
```

`META-INF/amie/package.json` inside the jar gives its name, description, `entry` class, `java`, config files and
tests. The whole contract is in the amie book, under
[Writing a package](https://samging.github.io/amieCli/learn/#writing-packages) and
[Packages from GitHub](https://samging.github.io/amieCli/learn/#github-packages).

## The jars

| Jar | What it does |
|---|---|
| `app-scanner.jar` | Which packages this Mac can use, and the installed apps that have one |
| `browser.jar` | Opens sites and searches, says what is on the page, reads the news |
| `face.jar` | A small animated face floating over everything |
| `files.jar` | Finds, opens, reads and creates files in your home folder, never deleting any |
| `gmail-reader.jar` | Reads a Gmail mailbox, read-only |
| `google-calendar.jar` | Google Calendar: today, the week, search, create and delete events |
| `google-docs.jar` | Google Docs: read, create, append and replace |
| `google-drive.jar` | Google Drive: list, search, read, upload |
| `google-sheets.jar` | Google Sheets: read, write and append rows |
| `google-tasks.jar` | Google Tasks: lists, add, done, delete |
| `google-translate.jar` | Translates text with Google Translate, free and without a key by default |
| `mac.jar` | The Mac: apps, volume, dark mode, screenshots, clipboard, timers |
| `mail.jar` | Email through the Mac's Mail app |
| `memory.jar` | What a companion remembers |
| `microphone.jar` | Records from the microphone |
| `speech.jar` | Speech on ONNX models, on this computer |
| `spotify.jar` | Music in the Spotify app |
| `text-window.jar` | A very small window that shows text and returns what the person types |

`amie packages info <jar>` shows what a jar needs, such as a key or a config file, and whether it opens here.
`amie packages setup <jar>` asks for each key its config files need, and `amie packages config` shows every jar's
files and whether each is filled in.

## Adding or updating a jar

1. Build it. In amie-packages, `./gradlew jars` puts every package in `build/jars`.
2. Check it locally: `amie packages add <file.jar>` checks it has a package class, and `amie packages test <jar>` runs
   the tests it declares, then calls `close()`.
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

   Missing metadata, or a required config left as its template, is a note on the run, not a failure. Tests run on
   Linux with no keys, so declare tests that answer without them: `help`, `status`.
5. Merge when both steps pass, and every amie that asks for the jar gets the new one.

To run the steps yourself, with a JDK 21:

```bash
java .github/scripts/CheckJars.java *.jar
```

```bash
java .github/scripts/CheckJars.java --run *.jar
```

A Kotlin jar that does not bundle its standard library needs `--provided kotlin-stdlib-2.1.0.jar` before the jars, as
amie provides one.
