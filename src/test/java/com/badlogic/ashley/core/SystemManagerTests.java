// Modified by SOS-Games, 2026: addTwoSystemsOfSameClass updated for multiple systems per class.
// Based on Ashley 1.7.4 (Apache-2.0). See CHANGES.md for the full list of changes.

package com.badlogic.ashley.core;

import static org.junit.Assert.*;

import org.junit.Test;

import com.badlogic.ashley.core.SystemManager.SystemListener;
import com.badlogic.ashley.utils.ImmutableArray;
import com.badlogic.gdx.utils.Array;

public class SystemManagerTests {

	private static class SystemListenerSpy implements SystemListener {
		public int addedCount = 0;
		public int removedCount = 0;
		
		@Override
		public void systemAdded (EntitySystem system) {
			system.addedToEngine(null);
			++addedCount;
		}

		@Override
		public void systemRemoved (EntitySystem system) {
			system.removedFromEngine(null);
			removedCount++;
		}
	}
	
	private static class EntitySystemMock extends EntitySystem {
		public int addedCalls = 0;
		public int removedCalls = 0;

		private Array<Integer> updates;

		public EntitySystemMock () {
			super();
		}

		public EntitySystemMock (Array<Integer> updates) {
			super();

			this.updates = updates;
		}

		@Override
		public void update (float deltaTime) {
			if (updates != null) {
				updates.add(priority);
			}
		}

		@Override
		public void addedToEngine (Engine engine) {
			++addedCalls;
		}

		@Override
		public void removedFromEngine (Engine engine) {
			++removedCalls;
		}
	}

	private static class EntitySystemMockA extends EntitySystemMock {

		public EntitySystemMockA () {
			super();
		}

		public EntitySystemMockA (Array<Integer> updates) {
			super(updates);
		}
	}

	private static class EntitySystemMockB extends EntitySystemMock {

		public EntitySystemMockB () {
			super();
		}

		public EntitySystemMockB (Array<Integer> updates) {
			super(updates);
		}
	}
	
	@Test
	public void addAndRemoveSystem () {
		EntitySystemMockA systemA = new EntitySystemMockA();
		EntitySystemMockB systemB = new EntitySystemMockB();
		
		SystemListenerSpy systemSpy = new SystemListenerSpy();
		SystemManager manager = new SystemManager(systemSpy);

		assertNull(manager.getSystem(EntitySystemMockA.class));
		assertNull(manager.getSystem(EntitySystemMockB.class));

		manager.addSystem(systemA);
		manager.addSystem(systemB);

		assertNotNull(manager.getSystem(EntitySystemMockA.class));
		assertNotNull(manager.getSystem(EntitySystemMockB.class));
		assertEquals(1, systemA.addedCalls);
		assertEquals(1, systemB.addedCalls);

		manager.removeSystem(systemA);
		manager.removeSystem(systemB);

		assertNull(manager.getSystem(EntitySystemMockA.class));
		assertNull(manager.getSystem(EntitySystemMockB.class));
		assertEquals(1, systemA.removedCalls);
		assertEquals(1, systemB.removedCalls);

		manager.addSystem(systemA);
		manager.addSystem(systemB);
		manager.removeAllSystems();

		assertNull(manager.getSystem(EntitySystemMockA.class));
		assertNull(manager.getSystem(EntitySystemMockB.class));
		assertEquals(2, systemA.removedCalls);
		assertEquals(2, systemB.removedCalls);
	}

	@Test
	public void getSystems () {
		SystemListenerSpy systemSpy = new SystemListenerSpy();
		SystemManager manager = new SystemManager(systemSpy);
		EntitySystemMockA systemA = new EntitySystemMockA();
		EntitySystemMockB systemB = new EntitySystemMockB();

		assertEquals(0, manager.getSystems().size());

		manager.addSystem(systemA);
		manager.addSystem(systemB);

		assertEquals(2, manager.getSystems().size());
		assertEquals(2, systemSpy.addedCount);
		
		manager.removeSystem(systemA);
		manager.removeSystem(systemB);
		
		assertEquals(0, manager.getSystems().size());
		assertEquals(2, systemSpy.addedCount);
		assertEquals(2, systemSpy.removedCount);
	}
	
	@Test
	public void addTwoSystemsOfSameClass () {
		SystemListenerSpy systemSpy = new SystemListenerSpy();
		SystemManager manager = new SystemManager(systemSpy);
		EntitySystemMockA system1 = new EntitySystemMockA();
		EntitySystemMockA system2 = new EntitySystemMockA();

		assertEquals(0, manager.getSystems().size());

		manager.addSystem(system1);
		
		assertEquals(1, manager.getSystems().size());
		assertEquals(system1, manager.getSystem(EntitySystemMockA.class));
		assertEquals(1, systemSpy.addedCount);
		
		manager.addSystem(system2);

		// SOS-Games: a second instance of the same class is added alongside the first instead of replacing it.
		assertEquals(2, manager.getSystems().size());
		assertSame(system1, manager.getSystems().get(0));
		assertSame(system2, manager.getSystems().get(1));
		assertSame(system1, manager.getSystem(EntitySystemMockA.class));
		assertEquals(2, systemSpy.addedCount);
		assertEquals(0, systemSpy.removedCount);

		// Re-adding an instance that is already registered is a no-op.
		manager.addSystem(system2);
		assertEquals(2, manager.getSystems().size());
		assertEquals(2, systemSpy.addedCount);

		// Removing the first instance promotes the next one for getSystem(Class).
		manager.removeSystem(system1);
		assertEquals(1, manager.getSystems().size());
		assertSame(system2, manager.getSystem(EntitySystemMockA.class));
		assertEquals(1, systemSpy.removedCount);
	}

	@Test
	public void systemUpdateOrder () {
		Array<Integer> updates = new Array<Integer>();

		SystemListenerSpy systemSpy = new SystemListenerSpy();
		SystemManager manager = new SystemManager(systemSpy);
		EntitySystemMock system1 = new EntitySystemMockA(updates);
		EntitySystemMock system2 = new EntitySystemMockB(updates);

		system1.priority = 2;
		system2.priority = 1;

		manager.addSystem(system1);
		manager.addSystem(system2);

		ImmutableArray<EntitySystem> systems = manager.getSystems();
		assertEquals(system2, systems.get(0));
		assertEquals(system1, systems.get(1));
	}
}
