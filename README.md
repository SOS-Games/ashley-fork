# Ashley (SOS-Games fork)

This is a small fork of [Ashley](https://github.com/libgdx/ashley) **1.7.4**, the libGDX entity framework, made for
`jvm-dungeon-crawler`. The API is the same as upstream Ashley, with three changes:

| | Stock Ashley 1.7.4 | This fork |
|---|---|---|
| Holding on to another entity | Store the `Entity` object. Once it is removed, `PooledEngine` can recycle it as an unrelated entity. | `long ref = e.ref()`, then `engine.resolve(ref)` returns `null` once that entity is gone, even if the object or slot was reused. |
| Iteration order | Swap-remove: removing an entity moves the last one into its place. | Sorted by slot index for `getEntities()` and every family. Freed slots are reused first in, first out. |
| `addSystem` with a class already present | Replaces the old system. | Both instances are kept and both run. `getSystem(Class)` returns the first. |

See [CHANGES.md](CHANGES.md) for the exact list of changes. Coordinates: `io.github.sosgames:ashley:1.7.4-sos.1`.
Requirements: Java 17 or newer, libGDX core 1.14.0 (pulled in transitively).

## Using refs

```java
// In a component: store refs, never Entity objects.
public class Follower implements Component {
    public long leader = EntityRef.NONE;
}

follower.leader = leaderEntity.ref();             // valid right away, even if addEntity() was delayed

Entity leader = engine.resolve(follower.leader);  // null if the leader was removed
if (leader == null) follower.leader = EntityRef.NONE;
```

Rules:

* Refs are assigned when `engine.addEntity(e)` is called, even while the engine is updating.
* Refs are invalidated **before** `EntityListener.entityRemoved` runs. Removal listeners can still read the entity's
  components, but `resolve(oldRef)` is already `null`.
* `EntityRef.NONE` (`0L`) is the "no entity" value. `EntityRef.toString(ref)` gives `#index@generation` for logs.
* `src/test/java/com/badlogic/ashley/lint/ComponentEntityFieldTest.java` fails the build if any component has an
  `Entity`-typed field. Copy it into your game's tests to enforce the same rule there.

## Building

```bash
./gradlew build                 # compile, run all tests, build jars (Windows: gradlew.bat build)
./gradlew test                  # tests only
./gradlew publishToMavenLocal   # install io.github.sosgames:ashley:1.7.4-sos.1 into ~/.m2
```

Any JDK 17+ can run the build (it compiles with `--release 17`). The Gradle wrapper (8.14.3) downloads itself on
first run. Tests run with `--add-opens java.base/java.lang=ALL-UNNAMED`, which Mockito 1.10 needs on modern JDKs.

## Consuming it from jvm-dungeon-crawler

### Option A: composite build (recommended while the fork is evolving)

Clone this repo next to the game (for example `../ashley-fork`), then in the game's `settings.gradle`:

```groovy
includeBuild('../ashley-fork')
```

and in the module that uses it:

```groovy
dependencies {
    api 'io.github.sosgames:ashley:1.7.4-sos.1'
}
```

Gradle swaps the dependency for the included build automatically, so edits to the fork are picked up without
publishing. Remove any `com.badlogicgames.ashley:ashley` dependency so the classes aren't on the classpath twice.

### Option B: JitPack

After pushing to GitHub, create a tag (`git tag v1.7.4-sos.1` then `git push origin v1.7.4-sos.1`) and use:

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { api 'com.github.SOS-Games:ashley-fork:v1.7.4-sos.1' }
```

`jitpack.yml` builds with OpenJDK 21. On JitPack the build publishes under JitPack's coordinates. This setup hasn't
been tried on jitpack.io yet, so if the first build fails, check the build log at `https://jitpack.io/#SOS-Games/ashley-fork`.

### Option C: mavenLocal

Run `./gradlew publishToMavenLocal` here, add `mavenLocal()` to the game's repositories, and depend on
`io.github.sosgames:ashley:1.7.4-sos.1`.

### Option D: copy it in as an `:ecs` module

1. Copy `src/`, `LICENSE`, `NOTICE` and `CHANGES.md` into `jvm-dungeon-crawler/ecs/`.
2. In the game's `settings.gradle`, add `include 'ecs'`.
3. Create `ecs/build.gradle`:

   ```groovy
   plugins { id 'java-library' }
   dependencies {
       api "com.badlogicgames.gdx:gdx:$gdxVersion"
       testImplementation 'junit:junit:4.13.2'
       testImplementation 'org.mockito:mockito-core:1.10.19'
   }
   tasks.withType(JavaCompile).configureEach { options.release = 17 }
   test { jvmArgs '--add-opens', 'java.base/java.lang=ALL-UNNAMED' }
   ```

4. Depend on it with `api project(':ecs')`.

Keep `LICENSE` and `NOTICE` with the module, and keep the `Modified by SOS-Games` headers.

## License

Ashley is Apache License 2.0 (see [LICENSE](LICENSE) and [NOTICE](NOTICE)): Copyright 2013 Stefan Bachmann and the
contributors listed in [AUTHORS](AUTHORS). The SOS-Games modifications are also Apache-2.0. Apache-2.0 code can be
combined into a GPL-3.0 game, as long as this module's `LICENSE` and `NOTICE` are shipped and credited.
