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

// Added by SOS-Games (not part of upstream Ashley 1.7.4). See CHANGES.md.

package com.badlogic.ashley.core;

/**
 * Helpers for the {@code long} entity handles returned by {@link Entity#ref()} and resolved by
 * {@link Engine#resolve(long)}. A ref packs a slot index (low 32 bits) and the slot's generation (high 32 bits):
 * {@code (generation << 32) | index}. Generations start at 1, so {@link #NONE} (0) is never a valid ref.
 */
public final class EntityRef {
	/** The "no entity" ref. Resolves to null. */
	public static final long NONE = 0L;

	private EntityRef () {
	}

	/** Packs an index and a generation into a ref. */
	public static long of (int index, int generation) {
		return ((long)generation << 32) | (index & 0xffffffffL);
	}

	/** @return the slot index part of a ref. */
	public static int index (long ref) {
		return (int)ref;
	}

	/** @return the generation part of a ref. */
	public static int generation (long ref) {
		return (int)(ref >>> 32);
	}

	/** @return a readable form such as {@code "#12@3"} (index 12, generation 3), or {@code "NONE"}. */
	public static String toString (long ref) {
		return ref == NONE ? "NONE" : "#" + index(ref) + "@" + generation(ref);
	}
}
