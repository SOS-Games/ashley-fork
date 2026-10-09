# Changes in this fork (SOS-Games)

This repository is a modified copy of **Ashley 1.7.4** (libgdx/ashley, Apache License 2.0), taken from upstream commit
`0dfdf5257097e57ea9bead170e8eeb62647d57d8` (2021-02-05, "Publish releases via GHA"). That commit's `ashley/src` is
byte-for-byte identical to the published `com.badlogicgames.ashley:ashley:1.7.4` sources jar. Later upstream commits
(the unreleased 1.8.0 work) are not included.

Fork version: `io.github.sosgames:ashley:1.7.4-sos.1`.

Every modified upstream file carries a `// Modified by SOS-Games, 2026: ...` comment under its original header. New
files say `Added by SOS-Games`. Ashley's `LICENSE` and `NOTICE` are kept unchanged and are also packaged into the jar's
`META-INF/`.

## 1. Generational entity refs: `Entity.ref()` / `Engine.resolve(long)`

Stale-safe handles for holding on to other entities (targets, leaders, owners, dialogue speakers and so on).

* New class `com.badlogic.ashley.core.EntityRef`. A ref is a `long` that packs `(generation << 32) | index`.
  Generations start at 1, so `EntityRef.NONE` (`0L`) is never valid. It has helpers `of`, `index`, `generation` and
  `toString`.
* `Entity.ref()` returns the entity's current ref, or `EntityRef.NONE` if the entity is not in an engine.
  `Entity.getIndex()` returns its slot index, or -1.
* `Engine.resolve(long ref)` returns the entity, or `null` if the ref is `NONE`, unknown, or belongs to an entity that
  has been removed. This holds even when `PooledEngine` has recycled the same `Entity` object, or when a new entity
  reuses the slot, because the generation no longer matches.
* **Refs are assigned as soon as `Engine.addEntity()` is called**, including when the add is delayed because the
  engine is updating or notifying listeners. A system that spawns an entity can store `e.ref()` immediately, and
  `resolve` returns the entity while the add is still pending. Iteration and families only see it once the add is
  applied, which is the same as stock Ashley.
  * The one exception is a delayed re-add of an entity whose removal is still pending in the same engine. That case
    is supported by stock Ashley and by its tests. The entity keeps its current ref until the removal is applied
    (which invalidates the ref) and gets a new ref when the re-add is applied.
* **Refs are invalidated before removal listeners run.** Inside `EntityListener.entityRemoved`, the listener still
  gets the `Entity` object and can read its components and `getIndex()`, but `engine.resolve(oldRef)` already
  returns `null`. Code that resolves refs during removal therefore never sees a half-removed entity as alive.
* **FIFO slot reuse.** Freed indices go to the back of a queue and are reused oldest first. Reuse is deterministic
  and happens as late as possible. Generations skip 0 when they wrap around.
* Adding an entity that already holds a slot in an engine (this one or another) throws `IllegalArgumentException`
  immediately. For a delayed duplicate add, stock Ashley threw later, while the pending operations were processed.
* `PooledEngine` needed no change. Refs are invalidated in `EntityManager` before the pool frees the entity.

Files: `Entity.java`, `EntityManager.java`, `Engine.java`, new `EntityRef.java`.

## 2. Iteration ordered by slot index

* `Engine.getEntities()` and every family array (`engine.getEntitiesFor(family)`, and so every `IteratingSystem`) are
  kept sorted by `Entity.getIndex()`. Stock Ashley used unordered arrays with swap-remove, so removing an entity moved
  the last entity into its place.
* Inserts and removals use binary search. An entity that joins a family later (for example, after a component is
  added) is inserted at its index position, not appended.
* Example: after removing entity 2 of 0..7, iteration is `0 1 3 4 5 6 7` (stock: `0 1 7 3 4 5 6`). A new entity then
  reuses index 2 and iterates in that position.

Files: `EntityManager.java`, `FamilyManager.java`.

## 3. Multiple systems of the same class

* `Engine.addSystem()` no longer replaces an existing system of the same class. Every instance is kept and updated,
  ordered by priority and then by insertion order (the sort is stable).
* Adding an instance that is already registered does nothing.
* `Engine.getSystem(Class)` returns the first instance added. When that instance is removed, the next instance of the
  class (in update order) takes its place.

Files: `SystemManager.java`, `Engine.java` (javadoc only).

## 4. Tests

* `EngineTests.addTwoSystemsOfSameClass` and `SystemManagerTests.addTwoSystemsOfSameClass` were rewritten to check the
  new behavior: both instances are kept and both run, re-adding is a no-op, and removal promotes the next instance.
* New `core/EntityRefTests` (15 tests): ref packing, detached entities, stale pooled refs, refs assigned during
  delayed adds, delayed re-add after a pending removal, invalidation before removal listeners, index-ordered
  iteration and families, FIFO reuse including queue compaction, `removeAllEntities`, a 20,000-step random churn
  check, and multiple systems per class.
* New `lint/ComponentEntityFieldTest`: a reflection test that fails if any concrete `Component` on the test classpath
  declares a field (own or inherited) whose type mentions `Entity`, such as `Entity`, `Entity[]`, `Array<Entity>`,
  `Map<K, Entity>` or `List<? extends Entity>`. Components should store `long` refs instead. It includes a self-test
  with deliberately bad fixtures. You can copy it into a game's tests to apply the rule to the game's own components.
* All other upstream tests are unchanged and pass.

## 5. Build and layout

* The upstream Gradle 6 multi-project build (`ashley`, `tests` demo, `benchmarks`, Sonatype `publish.gradle`) is
  replaced by a single Gradle 8.14.3 project with the wrapper. It uses `java-library` and `maven-publish`, compiles
  with `--release 17`, depends on libGDX core `1.14.0` (`api`), and tests with JUnit 4.13.2 and Mockito 1.10.19. Tests
  run with `--add-opens java.base/java.lang=ALL-UNNAMED`, which Mockito 1.10's cglib needs on Java 16+.
* Sources moved to the standard layout: `ashley/src` became `src/main/java` and `ashley/tests` became `src/test/java`.
  `ashley_gwt.gwt.xml` moved to `src/main/resources`, unchanged.
* The upstream LWJGL demo (`tests/`), the benchmarks (which bundled third-party jars), CI workflows, the Eclipse
  formatter and the logo are not included. The upstream `README.md` was replaced. Upstream `CHANGELOG.md` and
  `AUTHORS` are kept.
