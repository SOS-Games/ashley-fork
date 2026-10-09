// Modified by SOS-Games, 2026: generational slot table with FIFO slot reuse, refs assigned on addEntity() and invalidated before removal listeners, entity list kept sorted by index.
// Based on Ashley 1.7.4 (Apache-2.0). See CHANGES.md for the full list of changes.

package com.badlogic.ashley.core;


import com.badlogic.ashley.utils.ImmutableArray;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.ObjectSet;
import com.badlogic.gdx.utils.Pool;

class EntityManager {
	private EntityListener listener;
	private Array<Entity> entities = new Array<Entity>(true, 16); // SOS-Games: ordered, kept sorted by Entity.index
	// SOS-Games: generational slot table. slots[i] is the entity holding index i (live or pending add), or null.
	private Array<Entity> slots = new Array<Entity>(true, 64);
	private IntArray generations = new IntArray(true, 64);
	// SOS-Games: FIFO queue of free indices (freeIndices[freeHead..size)), so slot reuse is deterministic and as late
	// as possible.
	private IntArray freeIndices = new IntArray(true, 64);
	private int freeHead;
	private ObjectSet<Entity> entitySet = new ObjectSet<Entity>();
	private ImmutableArray<Entity> immutableEntities = new ImmutableArray<Entity>(entities);
	private Array<EntityOperation> pendingOperations = new Array<EntityOperation>(false, 16);
	private EntityOperationPool entityOperationPool = new EntityOperationPool();
	
	public EntityManager(EntityListener listener) {
		this.listener = listener;
	}
	
	public void addEntity(Entity entity){
		addEntity(entity, false);
	}
	
	public void addEntity(Entity entity, boolean delayed){
		// SOS-Games: the ref is assigned right away, so entity.ref() is valid even while the add is pending.
		// Exception: re-adding (delayed) an entity of this manager whose removal is still pending. It keeps its current
		// ref until the removal applies (which invalidates it) and gets a new ref when the re-add applies.
		boolean readdAfterPendingRemoval = delayed && entity.scheduledForRemoval && ownsSlot(entity);
		if (!readdAfterPendingRemoval) {
			if (entity.index >= 0 || entitySet.contains(entity)) {
				throw new IllegalArgumentException("Entity is already registered " + entity);
			}
			assignIndex(entity);
		}

		if (delayed) {
			EntityOperation operation = entityOperationPool.obtain();
			operation.entity = entity;
			operation.type = EntityOperation.Type.Add;
			pendingOperations.add(operation);
		}
		else {
			addEntityInternal(entity);
		}
	}
	
	public void removeEntity(Entity entity){
		removeEntity(entity, false);
	}
	
	public void removeEntity(Entity entity, boolean delayed){
		if (delayed) {
			if(entity.scheduledForRemoval) {
				return;
			}
			entity.scheduledForRemoval = true;
			EntityOperation operation = entityOperationPool.obtain();
			operation.entity = entity;
			operation.type = EntityOperation.Type.Remove;
			pendingOperations.add(operation);
		}
		else {
			removeEntityInternal(entity);
		}
	}
	
	public void removeAllEntities() {
		removeAllEntities(immutableEntities);
	}
	
	public void removeAllEntities(boolean delayed) {
		removeAllEntities(immutableEntities, delayed);
	}
	
	public void removeAllEntities(ImmutableArray<Entity> entities) {
		removeAllEntities(entities, false);
	}

	public void removeAllEntities(ImmutableArray<Entity> entities, boolean delayed) {
		if (delayed) {
			for(Entity entity: entities) {
				entity.scheduledForRemoval = true;
			}
			EntityOperation operation = entityOperationPool.obtain();
			operation.type = EntityOperation.Type.RemoveAll;
			operation.entities = entities;
			pendingOperations.add(operation);
		}
		else {
			while(entities.size() > 0) {
				removeEntity(entities.first(), false);
			}
		}
	}
	
	public ImmutableArray<Entity> getEntities() {
		return immutableEntities;
	}

	/**
	 * SOS-Games: returns the entity for this ref, or null if the ref is NONE, unknown, or its entity was removed
	 * (generation mismatch). Entities whose delayed add is still pending resolve too.
	 */
	public Entity resolve(long ref) {
		if (ref == EntityRef.NONE) return null;
		int i = EntityRef.index(ref);
		if (i < 0 || i >= slots.size) return null;
		Entity e = slots.get(i);
		return e != null && generations.get(i) == EntityRef.generation(ref) ? e : null;
	}

	/** SOS-Games: true if the entity currently holds a slot (live or pending add) in this manager. */
	private boolean ownsSlot(Entity entity) {
		int i = entity.index;
		return i >= 0 && i < slots.size && slots.get(i) == entity;
	}

	/** SOS-Games: takes the oldest free slot (FIFO) or appends a new one. */
	private void assignIndex(Entity entity) {
		int i;
		if (freeHead < freeIndices.size) {
			i = freeIndices.get(freeHead++);
			if (freeHead == freeIndices.size) {
				freeIndices.clear();
				freeHead = 0;
			} else if (freeHead >= 64 && freeHead * 2 >= freeIndices.size) {
				freeIndices.removeRange(0, freeHead - 1);
				freeHead = 0;
			}
		} else {
			i = slots.size;
			slots.add(null);
			generations.add(1); // generations start at 1, so EntityRef.NONE (0) is never a valid ref
		}
		slots.set(i, entity);
		entity.index = i;
		entity.generation = generations.get(i);
	}

	/** SOS-Games: invalidates every ref to slot i and queues the slot for reuse. */
	private void releaseIndex(int i) {
		int generation = generations.get(i) + 1;
		if (generation == 0) generation = 1; // skip 0 on wrap-around
		generations.set(i, generation);
		slots.set(i, null);
	}

	/** SOS-Games: binary-search removal from an array sorted by Entity.index. */
	static boolean removeSorted(Array<Entity> array, Entity entity) {
		int lo = 0, hi = array.size - 1, key = entity.index;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			Entity e = array.get(mid);
			if (e.index < key) {
				lo = mid + 1;
			} else if (e.index > key) {
				hi = mid - 1;
			} else {
				if (e == entity) {
					array.removeIndex(mid);
					return true;
				}
				break;
			}
		}
		return array.removeValue(entity, true); // defensive fallback; not reached when the array is sorted
	}

	/** SOS-Games: binary-search insert that keeps an array sorted by Entity.index (ascending). */
	static void insertSorted(Array<Entity> array, Entity entity) {
		int lo = 0, hi = array.size - 1, key = entity.index;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (array.get(mid).index < key) {
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		array.insert(lo, entity);
	}
	
	public boolean hasPendingOperations() {
		return pendingOperations.size > 0;
	}
	
	public void processPendingOperations() {
		for (int i = 0; i < pendingOperations.size; ++i) {
			EntityOperation operation = pendingOperations.get(i); 

			switch(operation.type) {
				case Add: addEntityInternal(operation.entity); break;
				case Remove: removeEntityInternal(operation.entity); break;
				case RemoveAll:
					while(operation.entities.size() > 0) {
						removeEntityInternal(operation.entities.first());
					}
					break;
				default:
					throw new AssertionError("Unexpected EntityOperation type");
			}

			entityOperationPool.free(operation);
		}
		
		pendingOperations.clear();
	}
	
	protected void removeEntityInternal(Entity entity) {
		boolean removed = entitySet.remove(entity);

		if (removed) {
			entity.scheduledForRemoval = false;
			entity.removing = true;
			removeSorted(entities, entity); // SOS-Games: O(log n) find
			// SOS-Games: invalidate refs *before* removal listeners run. Listeners still see the Entity object and its
			// components (and entity.getIndex()), but engine.resolve(oldRef) already returns null.
			int i = entity.index;
			releaseIndex(i);
			listener.entityRemoved(entity);
			entity.removing = false;
			entity.index = -1;
			freeIndices.add(i);
		}
	}

	protected void addEntityInternal(Entity entity) {
		if (entitySet.contains(entity)) {
			throw new IllegalArgumentException("Entity is already registered " + entity);
		}

		if (entity.index < 0) {
			assignIndex(entity); // SOS-Games: normally already assigned in addEntity(); see the re-add case there
		}
		insertSorted(entities, entity); // SOS-Games: keep iteration in index order

		entitySet.add(entity);

		listener.entityAdded(entity);
	}

	private static class EntityOperation implements Pool.Poolable {
		public enum Type {
			Add,
			Remove,
			RemoveAll
		}

		public Type type;
		public Entity entity;
		public ImmutableArray<Entity> entities;

		@Override
		public void reset() {
			entity = null;
		}
	}

	private static class EntityOperationPool extends Pool<EntityOperation> {
		@Override
		protected EntityOperation newObject() {
			return new EntityOperation();
		}
	}
}
