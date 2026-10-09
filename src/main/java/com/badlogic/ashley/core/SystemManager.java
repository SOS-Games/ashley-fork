// Modified by SOS-Games, 2026: several systems of the same class may coexist.
// Based on Ashley 1.7.4 (Apache-2.0). See CHANGES.md for the full list of changes.

package com.badlogic.ashley.core;

import java.util.Comparator;

import com.badlogic.ashley.signals.Listener;
import com.badlogic.ashley.signals.Signal;
import com.badlogic.ashley.utils.ImmutableArray;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;

class SystemManager {
	private SystemComparator systemComparator = new SystemComparator();
	private Array<EntitySystem> systems = new Array<EntitySystem>(true, 16);
	private ImmutableArray<EntitySystem> immutableSystems = new ImmutableArray<EntitySystem>(systems);
	private ObjectMap<Class<?>, EntitySystem> systemsByClass = new ObjectMap<Class<?>, EntitySystem>();
	private SystemListener listener;
	
	public SystemManager(SystemListener listener) {
		this.listener = listener;
	}
	
	public void addSystem(EntitySystem system){
		// SOS-Games: several instances of one class may coexist; getSystem(Class) returns the first one added.
		// Adding the same instance twice is a no-op.
		if (systems.contains(system, true)) {
			return;
		}

		Class<? extends EntitySystem> systemType = system.getClass();
		systems.add(system);
		if (!systemsByClass.containsKey(systemType)) {
			systemsByClass.put(systemType, system);
		}
		systems.sort(systemComparator); // stable sort: equal priorities keep insertion order
		listener.systemAdded(system);
	}
	
	public void removeSystem(EntitySystem system){
		if(systems.removeValue(system, true)) {
			Class<? extends EntitySystem> systemType = system.getClass();
			if (systemsByClass.get(systemType) == system) {
				// SOS-Games: promote the next instance of the same class (in update order), if any.
				systemsByClass.remove(systemType);
				for (EntitySystem other : systems) {
					if (other.getClass() == systemType) {
						systemsByClass.put(systemType, other);
						break;
					}
				}
			}
			listener.systemRemoved(system);
		}
	}

	public void removeAllSystems() {
		while(systems.size > 0) {
			removeSystem(systems.first());
		}
	}
	
	@SuppressWarnings("unchecked")
	public <T extends EntitySystem> T getSystem(Class<T> systemType) {
		return (T) systemsByClass.get(systemType);
	}
	
	public ImmutableArray<EntitySystem> getSystems() {
		return immutableSystems;
	}
	
	private static class SystemComparator implements Comparator<EntitySystem>{
		@Override
		public int compare(EntitySystem a, EntitySystem b) {
			return a.priority > b.priority ? 1 : (a.priority == b.priority) ? 0 : -1;
		}
	}
	
	interface SystemListener {
		void systemAdded(EntitySystem system);
		void systemRemoved(EntitySystem system);
	}
}
