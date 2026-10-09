/*******************************************************************************
 * Copyright 2026 SOS-Games
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

// Added by SOS-Games: tests for generational refs, index-ordered iteration and FIFO slot reuse. See CHANGES.md.

package com.badlogic.ashley.core;

import static org.junit.Assert.*;

import org.junit.Test;

import com.badlogic.ashley.utils.ImmutableArray;

public class EntityRefTests {
	private static final float DT = 1f / 60f;

	public static class Tag implements Component {
		public int id;
	}

	public static class Vel implements Component {
	}

	private static Entity tagged (Engine engine, int id) {
		Entity e = engine.createEntity();
		Tag t = engine.createComponent(Tag.class);
		t.id = id;
		e.add(t);
		return e;
	}

	private static String ids (ImmutableArray<Entity> entities) {
		StringBuilder sb = new StringBuilder();
		for (Entity e : entities) {
			if (sb.length() > 0) sb.append(' ');
			sb.append(e.getComponent(Tag.class).id);
		}
		return sb.toString();
	}

	@Test
	public void refPacking () {
		long ref = EntityRef.of(7, 3);
		assertEquals(7, EntityRef.index(ref));
		assertEquals(3, EntityRef.generation(ref));
		assertNotEquals(EntityRef.NONE, EntityRef.of(0, 1));
		long big = EntityRef.of(Integer.MAX_VALUE, -1);
		assertEquals(Integer.MAX_VALUE, EntityRef.index(big));
		assertEquals(-1, EntityRef.generation(big));
	}

	@Test
	public void refOfDetachedEntityIsNone () {
		Engine engine = new Engine();
		Entity e = new Entity();
		assertEquals(EntityRef.NONE, e.ref());
		assertEquals(-1, e.getIndex());
		assertNull(engine.resolve(EntityRef.NONE));
		assertNull(engine.resolve(EntityRef.of(5, 1)));
		assertNull(engine.resolve(-1L));
	}

	@Test
	public void resolveLiveAndRemoved () {
		Engine engine = new Engine();
		Entity a = new Entity();
		Entity b = new Entity();
		engine.addEntity(a);
		engine.addEntity(b);
		long ra = a.ref(), rb = b.ref();
		assertNotEquals(EntityRef.NONE, ra);
		assertNotEquals(ra, rb);
		assertSame(a, engine.resolve(ra));
		assertSame(b, engine.resolve(rb));

		engine.removeEntity(a);
		assertNull(engine.resolve(ra));
		assertEquals(EntityRef.NONE, a.ref());
		assertSame(b, engine.resolve(rb));

		// A ref from another engine does not resolve to the wrong entity once generations differ.
		Engine other = new Engine();
		assertNull(other.resolve(rb));
	}

	@Test
	public void staleRefOnPooledEngineResolvesNull () {
		PooledEngine engine = new PooledEngine();
		Entity e = tagged(engine, 0);
		engine.addEntity(e);
		long old = e.ref();
		engine.removeEntity(e);

		Entity reused = engine.createEntity();
		assertSame("pool should hand back the same Java object", e, reused);
		engine.addEntity(reused);
		assertEquals(EntityRef.index(old), reused.getIndex());
		assertEquals(EntityRef.generation(old) + 1, EntityRef.generation(reused.ref()));
		assertNull(engine.resolve(old));
		assertSame(reused, engine.resolve(reused.ref()));
	}

	@Test
	public void refAssignedImmediatelyWhenAddIsDelayed () {
		final Engine engine = new Engine();
		final long[] refDuringUpdate = {EntityRef.NONE};
		final Entity[] spawned = {null};
		final boolean[] inEntitiesDuringUpdate = {true};
		engine.addSystem(new EntitySystem() {
			@Override
			public void update (float deltaTime) {
				if (spawned[0] != null) return;
				Entity e = new Entity();
				engine.addEntity(e); // delayed: engine is updating
				spawned[0] = e;
				refDuringUpdate[0] = e.ref();
				inEntitiesDuringUpdate[0] = engine.getEntities().size() > 0;
				// Resolves already while the add is pending.
				assertSame(e, engine.resolve(e.ref()));
			}
		});
		engine.update(DT);
		assertNotEquals(EntityRef.NONE, refDuringUpdate[0]);
		assertFalse("add should still have been pending during update", inEntitiesDuringUpdate[0]);
		assertEquals(1, engine.getEntities().size());
		assertEquals(refDuringUpdate[0], spawned[0].ref());
		assertSame(spawned[0], engine.resolve(refDuringUpdate[0]));
	}

	@Test
	public void delayedAddOrderMatchesRefOrder () {
		final Engine engine = new Engine();
		final Entity[] created = new Entity[3];
		engine.addSystem(new EntitySystem() {
			boolean done;

			@Override
			public void update (float deltaTime) {
				if (done) return;
				done = true;
				for (int i = 0; i < 3; i++) {
					created[i] = tagged(engine, i);
					engine.addEntity(created[i]);
				}
			}
		});
		engine.update(DT);
		assertEquals("0 1 2", ids(engine.getEntities()));
		for (int i = 0; i < 3; i++) assertEquals(i, created[i].getIndex());
	}

	@Test(expected = IllegalArgumentException.class)
	public void addingEntityOfAnotherEngineThrows () {
		Engine a = new Engine();
		Engine b = new Engine();
		Entity e = new Entity();
		a.addEntity(e);
		b.addEntity(e);
	}

	@Test
	public void readdAfterPendingRemovalGetsNewRef () {
		final Engine engine = new Engine();
		final Entity e = new Entity();
		engine.addEntity(e);
		final long first = e.ref();
		final long[] refWhilePending = {EntityRef.NONE};
		engine.addSystem(new EntitySystem() {
			boolean done;

			@Override
			public void update (float deltaTime) {
				if (done) return;
				done = true;
				engine.removeEntity(e); // delayed
				engine.addEntity(e); // delayed re-add
				refWhilePending[0] = e.ref();
			}
		});
		engine.update(DT);
		assertEquals("keeps its current ref until the removal applies", first, refWhilePending[0]);
		assertEquals(1, engine.getEntities().size());
		assertNull(engine.resolve(first));
		assertNotEquals(first, e.ref());
		assertSame(e, engine.resolve(e.ref()));
	}

	@Test
	public void refInvalidatedBeforeRemovalListenersRun () {
		final Engine engine = new Engine();
		final Entity e = new Entity();
		e.add(new Tag());
		engine.addEntity(e);
		final long ref = e.ref();
		final Object[] seen = new Object[3];
		EntityListener listener = new EntityListener() {
			@Override
			public void entityAdded (Entity entity) {
			}

			@Override
			public void entityRemoved (Entity entity) {
				seen[0] = engine.resolve(ref);
				seen[1] = entity.getComponent(Tag.class);
				seen[2] = entity.getIndex();
			}
		};
		engine.addEntityListener(Family.all(Tag.class).get(), listener);
		engine.removeEntity(e);
		assertNull("resolve(ref) must already be null inside entityRemoved", seen[0]);
		assertNotNull("components are still readable inside entityRemoved", seen[1]);
		assertEquals(EntityRef.index(ref), seen[2]);
	}

	@Test
	public void iterationIsInIndexOrderAfterRemovalAndReuse () {
		PooledEngine engine = new PooledEngine();
		Entity[] es = new Entity[8];
		for (int i = 0; i < 8; i++) {
			es[i] = tagged(engine, i);
			if (i % 2 == 0) es[i].add(engine.createComponent(Vel.class));
			engine.addEntity(es[i]);
		}
		ImmutableArray<Entity> tagVel = engine.getEntitiesFor(Family.all(Tag.class, Vel.class).get());
		ImmutableArray<Entity> tags = engine.getEntitiesFor(Family.all(Tag.class).get());

		engine.removeEntity(es[2]);
		assertEquals("0 1 3 4 5 6 7", ids(engine.getEntities()));
		assertEquals("0 1 3 4 5 6 7", ids(tags));
		assertEquals("0 4 6", ids(tagVel));

		// Joining a family later inserts by index, not at the end.
		es[1].add(engine.createComponent(Vel.class));
		assertEquals("0 1 4 6", ids(tagVel));

		// The freed slot is reused, so the newcomer iterates in that slot's position.
		Entity newcomer = tagged(engine, 8);
		engine.addEntity(newcomer);
		assertEquals(2, newcomer.getIndex());
		assertEquals("0 1 8 3 4 5 6 7", ids(engine.getEntities()));

		// A family created after the fact is also in index order.
		ImmutableArray<Entity> late = engine.getEntitiesFor(Family.one(Tag.class).get());
		assertEquals("0 1 8 3 4 5 6 7", ids(late));
	}

	@Test
	public void freeSlotsAreReusedFifo () {
		Engine engine = new Engine();
		Entity[] es = new Entity[5];
		for (int i = 0; i < 5; i++) {
			es[i] = new Entity();
			engine.addEntity(es[i]);
		}
		engine.removeEntity(es[3]);
		engine.removeEntity(es[1]);
		engine.removeEntity(es[4]);
		Entity a = new Entity(), b = new Entity(), c = new Entity(), d = new Entity();
		engine.addEntity(a);
		engine.addEntity(b);
		engine.addEntity(c);
		engine.addEntity(d);
		assertEquals(3, a.getIndex());
		assertEquals(1, b.getIndex());
		assertEquals(4, c.getIndex());
		assertEquals(5, d.getIndex());
	}

	@Test
	public void freeListCompactionPreservesFifo () {
		Engine engine = new Engine();
		Entity[] es = new Entity[300];
		for (int i = 0; i < es.length; i++) {
			es[i] = new Entity();
			engine.addEntity(es[i]);
		}
		// Free slots in a scrambled but known order.
		int[] order = new int[es.length];
		for (int i = 0; i < es.length; i++) order[i] = (i * 7) % es.length;
		for (int i : order) engine.removeEntity(es[i]);
		// Interleave reuse with more removals so the queue compacts while non-empty.
		int next = 0;
		for (int i = 0; i < 150; i++) {
			Entity e = new Entity();
			engine.addEntity(e);
			assertEquals(order[next++], e.getIndex());
		}
		Entity extra = new Entity();
		engine.addEntity(extra);
		assertEquals(order[next++], extra.getIndex());
		engine.removeEntity(extra); // goes to the back of the queue
		for (int i = next; i < es.length; i++) {
			Entity e = new Entity();
			engine.addEntity(e);
			assertEquals(order[i], e.getIndex());
		}
		Entity last = new Entity();
		engine.addEntity(last);
		assertEquals(order[150], last.getIndex());
		Entity fresh = new Entity();
		engine.addEntity(fresh);
		assertEquals(es.length, fresh.getIndex());
	}

	@Test
	public void heavyChurnKeepsRefsConsistent () {
		PooledEngine engine = new PooledEngine(16, 256, 16, 256);
		java.util.Random rnd = new java.util.Random(42);
		java.util.ArrayList<Entity> live = new java.util.ArrayList<Entity>();
		java.util.ArrayList<Long> dead = new java.util.ArrayList<Long>();
		Family family = Family.all(Tag.class).get();
		ImmutableArray<Entity> tags = engine.getEntitiesFor(family);
		for (int step = 0; step < 20000; step++) {
			if (live.isEmpty() || rnd.nextInt(100) < 52) {
				Entity e = tagged(engine, step);
				engine.addEntity(e);
				live.add(e);
			} else {
				Entity e = live.remove(rnd.nextInt(live.size()));
				dead.add(e.ref());
				engine.removeEntity(e);
			}
		}
		for (Entity e : live) assertSame(e, engine.resolve(e.ref()));
		for (long ref : dead) assertNull(engine.resolve(ref));
		assertEquals(live.size(), engine.getEntities().size());
		assertEquals(live.size(), tags.size());
		for (int i = 1; i < tags.size(); i++) {
			assertTrue(tags.get(i - 1).getIndex() < tags.get(i).getIndex());
			assertTrue(engine.getEntities().get(i - 1).getIndex() < engine.getEntities().get(i).getIndex());
		}
	}

	@Test
	public void removeAllEntitiesInvalidatesEveryRef () {
		Engine engine = new Engine();
		long[] refs = new long[10];
		for (int i = 0; i < 10; i++) {
			Entity e = new Entity();
			engine.addEntity(e);
			refs[i] = e.ref();
		}
		engine.removeAllEntities();
		for (long r : refs) assertNull(engine.resolve(r));
		Entity e = new Entity();
		engine.addEntity(e);
		assertEquals("first freed slot is reused first", 0, e.getIndex());
	}

	@Test
	public void twoInstancesOfOneSystemClassBothRun () {
		Engine engine = new Engine();
		CountingSystem first = new CountingSystem(0);
		CountingSystem second = new CountingSystem(0);
		CountingSystem early = new CountingSystem(-1);
		engine.addSystem(first);
		engine.addSystem(second);
		engine.addSystem(early);
		assertEquals(3, engine.getSystems().size());
		assertSame(early, engine.getSystems().get(0));
		assertSame(first, engine.getSystems().get(1));
		assertSame(second, engine.getSystems().get(2));
		assertSame(first, engine.getSystem(CountingSystem.class));
		engine.update(DT);
		engine.update(DT);
		assertEquals(2, first.calls);
		assertEquals(2, second.calls);
		assertEquals(2, early.calls);
		engine.removeSystem(first);
		assertSame("next instance in update order is promoted", early, engine.getSystem(CountingSystem.class));
	}

	private static class CountingSystem extends EntitySystem {
		int calls;

		CountingSystem (int priority) {
			super(priority);
		}

		@Override
		public void update (float deltaTime) {
			calls++;
		}
	}
}
