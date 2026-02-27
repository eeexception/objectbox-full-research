/*
 * ObjectBox Build Tools
 * Copyright (C) 2017-2025 ObjectBox Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.objectbox.gradle.transform

import java.io.File

/**
 * Stores properties about a class (byte code) file to be used during transformation.
 *
 * @see ClassProber
 * @see ClassTransformer
 */
data class ProbedClass(
    /**
     * Directory to write the transformed class file into. Must be above the top-most package as subdirectories for
     * packages are auto-created by the transformer.
     * Background: Class files may have to be written to different directories (notably JavaCompile output dir is
     * different from KotlinCompile output dir).
     */
    val outDir: File,
    /** The file containing the byte-code for this class. */
    val file: File,
    val name: String,
    val javaPackage: String,
    val superClass: String? = null,
    val isCursor: Boolean = false,
    val isEntity: Boolean = false,
    val isEntityInfo: Boolean = false,
    val isBaseEntity: Boolean = false,
    /**
     * Fully qualified names (dot notation) of type arguments of all (non-transient) List fields of this class.
     * Used to find List fields that are relations, see [hasRelation].
     */
    val listFieldTypes: List<String> = emptyList(),
    val hasToOneRef: Boolean = false,
    val hasToManyRef: Boolean = false,
    val hasBoxStoreField: Boolean = false,
    /**
     * True if this entity (or base entity) has at least one `@Embedded`-annotated field.
     *
     * Unlike [hasToOneRef]/[hasToManyRef] which are inferred from the field's *descriptor*
     * (the container type `ToOne`/`ToMany` IS the signal), embedded containers are arbitrary
     * POJOs — the only signal is the annotation on the field. The prober therefore scans
     * `FieldInfo` annotations directly; the annotation is `@Retention(CLASS)` so it survives
     * into bytecode (readable via the `invisibleTag` attribute table).
     *
     * Drives two transforms when true:
     *  1. **Entity** — inject synthetic `transient` flat-fields (names match the flattened
     *     property names APT emitted into `Entity_`), so JNI can set them on reads.
     *  2. **Cursor** — inject the `attachEmbedded()` body (null-guard + container instantiation
     *     + copy synthetic-flat → container.inner).
     *
     * Strictly orthogonal to [hasRelation] — an embedded container is not a relation.
     */
    val hasEmbeddedRef: Boolean = false,
    val interfaces: List<String> = listOf()
) {
    fun hasRelation(entityTypes: Set<String>): Boolean =
        hasToOneRef || hasToManyRef || listFieldTypes.any { entityTypes.contains(it) }
}