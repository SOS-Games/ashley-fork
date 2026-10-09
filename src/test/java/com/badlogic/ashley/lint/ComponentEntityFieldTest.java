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

// Added by SOS-Games: lint that keeps raw Entity references out of components. See CHANGES.md.

package com.badlogic.ashley.lint;

import static org.junit.Assert.*;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.badlogic.ashley.core.Component;
import com.badlogic.ashley.core.Entity;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;

/**
 * Fails if any {@link Component} on the classpath (main and test classes of this build) declares a field whose type mentions
 * {@link Entity}: {@code Entity}, {@code Entity[]}, {@code Array<Entity>}, {@code Map<String, Entity>} and so on, including
 * inherited fields.
 * <p>
 * Components must hold {@code long} refs from {@link Entity#ref()} and resolve them with
 * {@code Engine.resolve(long)}. A raw Entity field keeps pointing at a pooled Entity object after it is removed and reused.
 * <p>
 * Copy this class into your game's test sources to apply the same rule to your own components (it scans every class
 * directory on the test classpath).
 */
public class ComponentEntityFieldTest {

	@Test
	public void noComponentHasEntityField () throws Exception {
		List<String> violations = new ArrayList<String>();
		int checked = 0;
		for (Class<?> type : componentClassesOnClasspath()) {
			if (type.getName().startsWith(Fixtures.class.getName())) continue; // deliberate bad examples below
			checked++;
			violations.addAll(entityFields(type));
		}
		assertTrue("expected to find components to check", checked > 0);
		assertTrue("Components must store long refs (Entity.ref()), not Entity references:\n  "
			+ String.join("\n  ", violations), violations.isEmpty());
	}

	@Test
	public void lintDetectsEntityFields () {
		assertEquals(1, entityFields(Fixtures.Direct.class).size());
		assertEquals(1, entityFields(Fixtures.ArrayField.class).size());
		assertEquals(1, entityFields(Fixtures.GdxArray.class).size());
		assertEquals(1, entityFields(Fixtures.JavaList.class).size());
		assertEquals(1, entityFields(Fixtures.MapValue.class).size());
		assertEquals(1, entityFields(Fixtures.Wildcard.class).size());
		assertEquals(1, entityFields(Fixtures.Inherited.class).size());
		assertEquals(Collections.emptyList(), entityFields(Fixtures.Clean.class));
	}

	/** @return "Class.field : type" descriptions of every field (own or inherited) of the class whose type mentions Entity. */
	public static List<String> entityFields (Class<?> type) {
		List<String> result = new ArrayList<String>();
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				if (field.isSynthetic()) continue;
				if (mentionsEntity(field.getGenericType(), 0)) {
					result.add(type.getName() + "." + field.getName()
						+ (c != type ? " (inherited from " + c.getName() + ")" : "") + " : " + field.getGenericType().getTypeName());
				}
			}
		}
		return result;
	}

	private static boolean mentionsEntity (Type t, int depth) {
		if (depth > 8) return false;
		if (t instanceof Class) {
			Class<?> c = (Class<?>)t;
			while (c.isArray()) c = c.getComponentType();
			return Entity.class.isAssignableFrom(c);
		}
		if (t instanceof ParameterizedType) {
			ParameterizedType p = (ParameterizedType)t;
			if (mentionsEntity(p.getRawType(), depth + 1)) return true;
			for (Type arg : p.getActualTypeArguments()) if (mentionsEntity(arg, depth + 1)) return true;
			return false;
		}
		if (t instanceof GenericArrayType) return mentionsEntity(((GenericArrayType)t).getGenericComponentType(), depth + 1);
		if (t instanceof WildcardType) {
			WildcardType w = (WildcardType)t;
			for (Type b : w.getUpperBounds()) if (mentionsEntity(b, depth + 1)) return true;
			for (Type b : w.getLowerBounds()) if (mentionsEntity(b, depth + 1)) return true;
			return false;
		}
		if (t instanceof TypeVariable) {
			for (Type b : ((TypeVariable<?>)t).getBounds()) if (mentionsEntity(b, depth + 1)) return true;
		}
		return false;
	}

	/** Loads (without initializing) every concrete Component class found in class directories on java.class.path. */
	public static List<Class<?>> componentClassesOnClasspath () throws Exception {
		List<Class<?>> result = new ArrayList<Class<?>>();
		ClassLoader loader = ComponentEntityFieldTest.class.getClassLoader();
		for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
			File root = new File(entry);
			if (!root.isDirectory()) continue; // skip jars (libraries)
			List<File> files = new ArrayList<File>();
			collectClassFiles(root, files);
			for (File file : files) {
				String rel = root.toURI().relativize(file.toURI()).getPath();
				String name = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
				if (name.endsWith("module-info") || name.endsWith("package-info")) continue;
				Class<?> c;
				try {
					c = Class.forName(name, false, loader);
				} catch (Throwable ignored) {
					continue;
				}
				if (Component.class.isAssignableFrom(c) && !c.isInterface() && !Modifier.isAbstract(c.getModifiers())) {
					result.add(c);
				}
			}
		}
		return result;
	}

	private static void collectClassFiles (File dir, List<File> out) {
		File[] children = dir.listFiles();
		if (children == null) return;
		for (File f : children) {
			if (f.isDirectory()) collectClassFiles(f, out);
			else if (f.getName().endsWith(".class")) out.add(f);
		}
	}

	/** Deliberately bad components used to prove the lint works; excluded from the classpath scan. */
	static class Fixtures {
		static class Direct implements Component { Entity target; }
		static class ArrayField implements Component { Entity[] targets; }
		static class GdxArray implements Component { Array<Entity> targets; }
		static class JavaList implements Component { List<? extends Entity> targets; long ok; }
		static class MapValue implements Component { ObjectMap<String, Entity> byName; }
		static class Wildcard implements Component { List<? super Entity> sink; }
		static class Inherited extends Direct { int extra; }
		static class Clean implements Component { long targetRef; int[] counts; Array<String> names; }
	}
}
