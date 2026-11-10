# jar-jar

Package jars for [amie](https://github.com/samging/amieCli). Each jar adds something a script can ask in plain text:
the Mac, a browser, files, Google apps, music, speech, a memory. The sources are in
[samging/amie-packages](https://github.com/samging/amie-packages).

## Using a jar

A script opens a jar by its name. When the jar is not on this computer, amie downloads it from here, checks it and
keeps it in `AMP/pluginDir`.

```kotlin
val notes = initMod("memory.jar")                              // looked for here when it is nowhere local
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

Any class with the same methods qualifies, inherited ones included, so no amie library is needed to build one.
`META-INF/amie/package.json` inside the jar can give its name, description, config files and tests.

```java
public class Greeter {
    public java.util.List<String> run(String text) { return java.util.List.of("Hello to you"); }
    public void close() {}
}
```

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

## Adding or updating a jar

1. Build it. In amie-packages, `./gradlew jars` puts every package in `build/jars`.
2. Check it: `amie packages add <file.jar>` checks it has a package class, and `amie packages test <jar>` runs the
   tests it declares, then calls `close()`.
3. Open a pull request that adds or replaces the jar at the top of this repository.
4. Merge it, and every amie that asks for the jar gets the new one.
