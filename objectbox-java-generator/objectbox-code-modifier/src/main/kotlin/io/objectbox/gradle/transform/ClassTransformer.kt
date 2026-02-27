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

import io.objectbox.BoxStoreBuilder
import io.objectbox.logging.log
import io.objectbox.logging.logWarning
import io.objectbox.reporting.BasicBuildTracker
import javassist.ClassPool
import javassist.CtClass
import javassist.CtConstructor
import javassist.CtField
import javassist.Modifier
import javassist.NotFoundException
import javassist.bytecode.AccessFlag
import javassist.bytecode.Descriptor
import javassist.bytecode.Opcode
import javassist.bytecode.SignatureAttribute
import java.io.File
import java.net.URLDecoder

/**
 * Transforms entity class files: adds a BoxStore field and adds relation field (ToOne, ToMany) initialization to
 * constructors. Transforms cursor class files: adds a body to the attach method.
 */
class ClassTransformer(private val debug: Boolean = false) {

    // Use internal once fixed (Kotlin 1.1.4?)
    class Context(val probedClasses: List<ProbedClass>) {
        val classPool = ClassPool()
        val transformedClasses = mutableSetOf<ProbedClass>()
        val ctByProbedClass = mutableMapOf<ProbedClass, CtClass>()
        val entityTypes: Set<String> = probedClasses.filter { it.isEntity }.map { it.name }.toHashSet()
        val stats = ClassTransformerStats()

        /**
         * Bridge from entity-phase to cursor-phase for `@Embedded` hydration. Populated during
         * [transformEntity] (after synthetic flat-fields are injected onto the entity class),
         * consumed during [transformCursor] (to build the `attachEmbedded()` body). Keyed by
         * entity FQN — the cursor looks up its entity type from the `attachEmbedded(L<Entity>;)V`
         * signature descriptor, same trick as `checkEntityIsInClassPool()`.
         *
         * Why stash here rather than re-discovering in the cursor phase? Re-discovery would mean
         * walking the entity's `@Embedded` annotations twice (once per phase), loading the container
         * CtClass twice, resolving the prefix twice. None of that is expensive, but doing it twice
         * means two places where naming-rule drift could hide. Single discovery + stash → single
         * source of truth per build.
         */
        val embeddedContainersByEntity = mutableMapOf<String, List<EmbeddedTransform.EmbeddedContainer>>()

        init {
            // Notes:
            // 1) class pool should not use any ClassClassPath (problem: would infer with test Fakes)
            // 2) class pool cannot find ObjectBox classes on system path when run as Gradle plugin (OK in iJ)
            // 3) ObjectBox is separated for mainly for tests (we make this configurable, treat tests as special)
            // 4) Don't fake java.lang.Object, it may cause stack overflows because superclass != null
            val objectBoxPath = BoxStoreBuilder::class.java.protectionDomain.codeSource.location.path
            // location.path is a URL, but javassist expects a path: so decode the URL first.
            val decodedObjectBoxPath = URLDecoder.decode(objectBoxPath, "UTF-8")
            classPool.appendClassPath(decodedObjectBoxPath)
            classPool.appendClassPath(PrefixedClassPath("java.", java.lang.Object::class.java))
        }

        fun wasTransformed(probedClass: ProbedClass) = transformedClasses.contains(probedClass)
    }

    private class RelationField(
        val ctField: CtField,
        val relationName: String,
        val relationType: String,
        val targetTypeSignature: SignatureAttribute.ClassType?
    )

    fun transformOrCopyClasses(
        probedClasses: List<ProbedClass>,
        copyNonTransformed: Boolean = true
    ): ClassTransformerStats {
        val context = Context(probedClasses)

        // First define all EntityInfo (Entity_) and entity classes to ensure the real classes are used
        // (E.g. constructor transformation may introduce dummy classes)
        probedClasses.forEach { if (it.isEntityInfo) makeCtClass(context, it) }
        probedClasses.forEach { probedClass ->
            if (probedClass.isEntity) {
                makeCtClasses(context, probedClasses, probedClass)
                probedClass.interfaces.forEach {
                    // create dummy classes for interfaces to enable searching fields in super classes
                    // (javassist searches interfaces first and fails if they are not in the class pool)
                    context.classPool.makeClass(it)
                }
            }
        }

        transformEntities(context)
        // Transform Cursors after entities because this depends on entity CtClasses added to the ClassPool
        transformCursors(context)

        if (copyNonTransformed) {
            probedClasses.filter { !context.wasTransformed(it) }.forEach { (outDir, file, name) ->
                val targetFile = File(outDir, name.replace('.', '/') + ".class")
                // do not copy if path is identical as overwrite would delete, then try to copy from file
                if (file.path != targetFile.path) {
                    file.copyTo(targetFile, overwrite = true)
                }
            }
        }

        context.stats.countTransformed = context.transformedClasses.size
        context.stats.countCopied = if (copyNonTransformed) probedClasses.size - context.transformedClasses.size else 0
        context.stats.done()

        return context.stats
    }

    private fun transformEntities(context: Context) {
        context.probedClasses.filter { it.isEntity }.forEach { entityClass ->
            val ctClass = context.ctByProbedClass[entityClass]!!
            transformEntityAndBases(context, ctClass, entityClass)
        }
    }

    /**
     * Walks up the inheritance chain and transforms the @Entity and all its @BaseEntity classes starting from the top.
     * Checks that super classes of @Entity classes do not contain relations.
     */
    private fun transformEntityAndBases(context: Context, ctClassEntity: CtClass, probedClass: ProbedClass) {
        if (probedClass.superClass != null) {
            context.probedClasses.find { it.name == probedClass.superClass }?.let { superClass ->
                transformEntityAndBases(context, ctClassEntity, superClass)
            }
        }

        val ctClass = context.ctByProbedClass[probedClass]
        if (ctClass != null) {
            // relations in entity super classes are (currently) not supported, see #104
            if (ctClass != ctClassEntity && probedClass.hasRelation(context.entityTypes)
                && (probedClass.isEntity || probedClass.isBaseEntity)
            ) {
                throw TransformException(
                    "Relations in an entity super class are not supported, but " +
                            "'${ctClass.name}' is super of entity '${ctClassEntity.name}' and has relations"
                )
            }

            if (ctClass == ctClassEntity || probedClass.isBaseEntity) {
                try {
                    if (transformEntity(context, ctClassEntity, ctClass, probedClass)) {
                        context.transformedClasses.add(probedClass)
                    }
                } catch (e: Exception) {
                    throw TransformException("Could not transform class \"${ctClass.name}\" (${e.message})", e)
                }
            }
        }
    }

    private fun makeCtClass(context: Context, probedClass: ProbedClass): CtClass {
        probedClass.file.inputStream().use {
            val ctClass = context.classPool.makeClass(it)
            context.ctByProbedClass[probedClass] = ctClass
            return ctClass
        }
    }

    /**
     * Walks up inheritance chain and creates a CtClass for each super class as well as the given class. This ensures
     * all fields of super classes are known when transforming entities and entity base classes.
     */
    private fun makeCtClasses(context: Context, probedClasses: List<ProbedClass>, probedClass: ProbedClass) {
        if (probedClass.superClass != null && probedClass.superClass.isNotEmpty()) {
            val superClass = probedClasses.find { it.name == probedClass.superClass }
            if (superClass != null) {
                makeCtClasses(context, probedClasses, superClass)
            }
        }

        makeCtClass(context, probedClass)
    }

    /**
     * Transforms the given [ctClass] to ensure a BoxStore field exists and looks for relation fields and makes sure
     * they are initialized in constructors.
     */
    private fun transformEntity(
        context: Context,
        ctClassEntity: CtClass,
        ctClass: CtClass,
        entityClass: ProbedClass
    ): Boolean {
        val hasRelations = entityClass.hasRelation(context.entityTypes)
        if (debug) log("Checking to transform \"${ctClass.name}\" (has relations: $hasRelations)")
        var changed = checkBoxStoreField(ctClass, context, hasRelations)

        // ── @Embedded: inject synthetic flat-fields so JNI can set them on reads ────────────
        // Gated on `ctClass == ctClassEntity` — i.e. only on the concrete @Entity leaf, not on
        // @BaseEntity ancestors. This mirrors the relation-in-superclass restriction above
        // (L136-143) without duplicating the rejection: APT should already have rejected
        // @Embedded on a @BaseEntity, so we simply don't process it here. If a user somehow
        // bypasses APT, the synthetic fields never land → JNI's PUTFIELD fails at runtime
        // with a NoSuchFieldError — loud enough to diagnose.
        //
        // Metadata discovered here is stashed on the Context for the cursor phase to consume
        // when building the `attachEmbedded()` body. Entity transforms ALWAYS run before cursor
        // transforms (see transformOrCopyClasses() ordering), so the stash is guaranteed populated
        // by the time a cursor needs it.
        if (entityClass.hasEmbeddedRef && ctClass == ctClassEntity) {
            val containers = EmbeddedTransform.discoverEmbeddedContainers(context, ctClass, debug)
            context.embeddedContainersByEntity[ctClassEntity.name] = containers
            context.stats.embeddedContainersFound += containers.size
            val added = EmbeddedTransform.injectEmbeddedSyntheticFields(ctClass, containers, debug)
            context.stats.embeddedSyntheticFieldsAdded += added
            if (added > 0) changed = true
        }

        if (hasRelations) {
            val toOneFields =
                findRelationFields(context, ctClassEntity, ctClass, ClassConst.toOneDescriptor, ClassConst.toOne)
            context.stats.toOnesFound += toOneFields.size
            val toManyFields =
                findRelationFields(context, ctClassEntity, ctClass, ClassConst.toManyDescriptor, ClassConst.toMany)
            val listToEntityFields =
                findRelationFields(context, ctClassEntity, ctClass, ClassConst.listDescriptor, ClassConst.toMany)
            toManyFields += listToEntityFields
            context.stats.toManyFound += toManyFields.size
            if (transformConstructors(context, ctClassEntity, ctClass, toOneFields + toManyFields)) changed = true
        }
        if (changed) {
            if (debug) log("Writing transformed entity \"${ctClass.name}\"")
            ctClass.writeFile(entityClass.outDir.absolutePath)
        }
        return changed
    }

    /**
     * If there is a BoxStore field, makes sure it's not private. If there is none, adds one.
     */
    private fun checkBoxStoreField(ctClass: CtClass, context: Context, hasRelations: Boolean): Boolean {
        var changed = false
        var boxStoreField = ctClass.declaredFields.find { it.name == ClassConst.boxStoreFieldName }
        if (boxStoreField != null && Modifier.isPrivate(boxStoreField.modifiers)) {
            boxStoreField.modifiers = boxStoreField.modifiers.xor(Modifier.PRIVATE)
            context.stats.boxStoreFieldsMadeVisible++
            changed = true
        } else if (boxStoreField == null && hasRelations) {
            val code = "transient ${ClassConst.boxStoreClass} ${ClassConst.boxStoreFieldName};"
            boxStoreField = CtField.make(code, ctClass)
            ctClass.addField(boxStoreField)
            context.stats.boxStoreFieldsAdded++
            changed = true
        }
        return changed
    }

    /**
     * Finds fields that are ObjectBox relations.
     */
    private fun findRelationFields(
        context: Context, ctClassEntity: CtClass, ctClass: CtClass,
        fieldTypeDescriptor: String, relationType: String
    ): MutableList<RelationField> {
        val fields = mutableListOf<RelationField>()
        ctClass.declaredFields
            .forEach { field ->
                // Note: this detection should match the properties the annotation processor detects.
                // Note: this detection should be in sync with ClassProber#extractAllListTypes.
                // Exclude if:
                // - not ToOne/ToMany/List,
                if (field.fieldInfo.descriptor != fieldTypeDescriptor) {
                    return@forEach
                }
                // - is transient,
                if (Modifier.isTransient(field.modifiers)) {
                    return@forEach
                }
                // - is annotated with @Transient or @Convert
                val hasTransientOrConvertAnnotation =
                    field.fieldInfo.exGetAnnotation(ClassConst.transientAnnotationName) != null
                            || field.fieldInfo.exGetAnnotation(ClassConst.convertAnnotationName) != null
                if (hasTransientOrConvertAnnotation) {
                    return@forEach
                }
                // - does not hold a known @Entity class.
                val targetClassType = field.fieldInfo.exGetSingleGenericTypeArgumentOrNull()
                if (targetClassType == null || !context.entityTypes.contains(targetClassType.name)) {
                    return@forEach
                }
                // Otherwise found a relation field!
                val name = findRelationNameInEntityInfo(context, ctClassEntity, field, relationType)
                fields += RelationField(field, name, relationType, targetClassType)
            }
        return fields
    }

    private fun findRelationNameInEntityInfo(context: Context, ctClass: CtClass, field: CtField, relationType: String)
            : String {
        val entityInfoClassName = ctClass.name + '_'
        val entityInfoCtClass = try {
            context.classPool.get(entityInfoClassName)
        } catch (e: NotFoundException) {
            throw TransformException(
                "Could not find generated class \"$entityInfoClassName\", " +
                        "please ensure that ObjectBox class generation runs properly before"
            )
        }
        var name = field.name
        if (!entityInfoCtClass.fields.any { it.name == name }) {
            val suffix = when (relationType) {
                ClassConst.toOne -> "ToOne"
                ClassConst.toMany -> "ToMany"
                else -> throw TransformException("Unexpected $relationType")
            }
            if (name.endsWith(suffix)) {
                val name2 = name.dropLast(suffix.length)
                if (!entityInfoCtClass.fields.any { it.name == name2 }) {
                    throw TransformException(
                        "Could not find RelationInfo element for relation field " +
                                "\"${ctClass.name}.$name\" in generated class \"$entityInfoClassName\""
                    )
                }
                name = name2
            }
        }
        return name
    }

    /**
     * Transforms constructors of [ctClass] that do not call other constructors to add initializers for relation fields,
     * if there are none, yet. If there are [relationFields] already initialized, prints a warning.
     */
    private fun transformConstructors(
        context: Context, ctClassEntity: CtClass, ctClass: CtClass,
        relationFields: List<RelationField>
    ): Boolean {
        var changed = false
        val initializedRelationFields = mutableSetOf<String>()
        for (constructor in ctClass.constructors) {
            // Skip constructors that call another (this) constructor to avoid initializing fields multiple times.
            // This would also overwrite potential changes to relation fields made in the called constructor.
            if (!constructor.callsSuper()) { // "calls super()" == "does not call this()"
                if (debug) log("Skipping constructor ${constructor.longName} calling another constructor")
                continue
            }

            checkMakeParamCtClasses(context, constructor)
            context.stats.constructorsCheckedForTransform++
            val initializedFields = ctClass.classFile.getInitializedFields(constructor.methodInfo)
            for (field in relationFields) {
                val fieldName = field.ctField.name
                if (!initializedFields.contains(fieldName)) {
                    val code = "\$0.$fieldName = new ${field.relationType}" +
                            "(\$0, ${ctClassEntity.name}_#${field.relationName});"
                    try {
                        constructor.insertBeforeBody(code)
                    } catch (e: Exception) {
                        throw TransformException("Could not insert init code for field $fieldName in constructor", e)
                    }
                    if (field.relationType == ClassConst.toOne) context.stats.toOnesInitializerAdded++
                    else if (field.relationType == ClassConst.toMany) context.stats.toManyInitializerAdded++
                    changed = true
                } else {
                    initializedRelationFields.add(fieldName)
                }
            }
        }
        // Only print relation init warning once for each entity class.
        if (initializedRelationFields.isNotEmpty()) {
            val fieldNames = initializedRelationFields.joinToString()
            log("In '${ctClass.name}' relation fields ($fieldNames) are initialized, make sure to read ${TextSnippet.URL_RELATIONS_INIT_MAGIC}")
        }
        return changed
    }

    private fun checkMakeParamCtClasses(context: Context, constructor: CtConstructor) {
        // Plan A
        try {
            val count = Descriptor.numOfParameters(constructor.signature)
            // Start at 1, because 0 is '('
            var charIndex = 1
            for (i in 0 until count) {
                val paramPair = getParamType(constructor.signature, charIndex)

                val paramClass = paramPair.first
                if (paramClass != null) {
                    if (context.classPool.getOrNull(paramClass) == null) {
                        context.classPool.makeClass(paramClass)
                    }
                }

                check(charIndex != paramPair.second)
                charIndex = paramPair.second
            }
        } catch (e: Exception) {
            BasicBuildTracker("Transformer").trackError("Could not define class for params: ${constructor.signature}")
        }

        // Plan B in case previous code failed to define all missing types (plan B could be remove if plan A is stable)
        try {
            var lastExMsg = ""
            while (true) {
                try {
                    constructor.parameterTypes
                    break
                } catch (e: NotFoundException) {
                    val message = e.message
                    if (message != null && message != lastExMsg && !message.contains(' ')) {
                        context.classPool.makeClass(message)
                        lastExMsg = message
                    } else break
                }
            }
        } catch (e: Exception) {
            BasicBuildTracker("Transformer")
                .trackError("Could not define class for params (2): ${constructor.signature}")
        }
    }

    private fun getParamType(descriptor: String, charIndexVal: Int): Pair<String?, Int> {
        var charIndex = charIndexVal
        var c = descriptor[charIndex]
        while (c == '[') {
            c = descriptor[++charIndex]
        }

        return if (c == 'L') {
            charIndex++
            val endIndex = descriptor.indexOf(';', charIndex)
            val name = descriptor.substring(charIndex, endIndex).replace('/', '.')
            Pair(name, endIndex + 1)
        } else Pair(null, charIndex + 1)
    }

    private fun transformCursors(context: Context) {
        context.probedClasses.filter { it.isCursor }.forEach { cursorClass ->
            val ctClass = makeCtClass(context, cursorClass)
            try {
                if (transformCursor(context, ctClass, cursorClass.outDir)) {
                    context.transformedClasses.add(cursorClass)
                }
            } catch (e: Exception) {
                throw TransformException("Could not transform Cursor class \"${ctClass.name}\" (${e.message})", e)
            }
        }
    }

    /**
     * Orchestrates per-cursor transforms. A cursor may have:
     *
     *   - `attachEntity(E)`    — relation wiring stub (emitted by `cursor.ftl` when the entity
     *                            has relations but no `__boxStore` field). Transformer injects
     *                            `$1.__boxStore = $0.boxStoreForEntities;`.
     *   - `attachEmbedded(E?)` — @Embedded hydration stub (emitted by `cursor.ftl` when the
     *                            entity has `@Embedded` fields). Transformer injects the full
     *                            hydration body (null-guard + instantiate-copy-assign per container).
     *
     * Either, both, or neither may be present — they're orthogonal concerns gated by independent
     * entity properties (`hasRelations` vs `hasEmbedded`). The writeFile() happens ONCE after all
     * rewrites, so a cursor with both stubs gets a single coherent output rather than two
     * sequential writes (the second of which would lose the first's edits, since writeFile
     * serialises the in-memory CtClass state).
     */
    private fun transformCursor(context: Context, ctClass: CtClass, outDir: File): Boolean {
        var changed = false
        if (transformAttachEntity(ctClass, context.classPool)) changed = true
        if (transformAttachEmbedded(context, ctClass)) changed = true
        if (changed) {
            if (debug) log("Writing transformed cursor '${ctClass.name}'")
            ctClass.writeFile(outDir.absolutePath)
        }
        return changed
    }

    /**
     * Finds the `attachEntity` stub, validates its `(L<Entity>;)V` signature, warns if the body
     * isn't the expected 1-byte RETURN, and injects `$1.__boxStore = $0.boxStoreForEntities;`.
     * No-op (returns false) if the stub is absent OR already assigns `__boxStore` (user manually
     * implemented the wiring, respect it).
     *
     * Extracted from the pre-@Embedded monolithic `transformCursor()` so it composes with
     * [transformAttachEmbedded] — both edit the in-memory CtClass, and the caller does the
     * single writeFile().
     */
    private fun transformAttachEntity(ctClass: CtClass, classPool: ClassPool): Boolean {
        val attachCtMethod =
            ctClass.declaredMethods?.singleOrNull { it.name == ClassConst.cursorAttachEntityMethodName }
            ?: return false

        val signature = attachCtMethod.signature
        if (!signature.startsWith("(L") || !signature.endsWith(";)V") || signature.contains(',')) {
            throw TransformException(
                "${ctClass.name} The signature of ${ClassConst.cursorAttachEntityMethodName} is not as expected, but was '$signature'."
            )
        }

        val existingCode = attachCtMethod.methodInfo.codeAttribute.code
        if (existingCode.size != 1 || existingCode[0] != Opcode.RETURN.toByte()) {
            logWarning("${ctClass.name}.${ClassConst.cursorAttachEntityMethodName}  body expected to be empty, might lead to unexpected behavior.")
        }

        if (attachCtMethod.assignsBoxStoreField()) {
            log(
                "${ctClass.name}.${ClassConst.cursorAttachEntityMethodName} assigns " +
                        "${ClassConst.boxStoreFieldName}, make sure to read ${TextSnippet.URL_RELATIONS_INIT_MAGIC}."
            )
            return false // just copy, change nothing
        }

        checkEntityIsInClassPool(classPool, signature)

        val code = "\$1.${ClassConst.boxStoreFieldName} = \$0.${ClassConst.cursorBoxStoreFieldName};"
        attachCtMethod.insertAfter(code)
        return true
    }

    /**
     * Finds the `attachEmbedded` stub (M2.3 emits it with an empty body — same 1-byte-RETURN
     * invariant as `attachEntity`), looks up the entity's embedded-container metadata from the
     * Context stash (populated during phase-A in `transformEntity()`), and REPLACES the stub body
     * with the hydration sequence built by [EmbeddedTransform.buildAttachEmbeddedBody].
     *
     * No-op (returns false) if:
     *   - The stub is absent — entity has no `@Embedded` fields, nothing to hydrate. Base
     *     `Cursor.attachEmbedded(T)` no-op suffices.
     *   - The entity's metadata isn't in the Context stash — means the entity wasn't in this
     *     transformer's class set (e.g. cross-module entity). Warn + skip; the user will see
     *     empty containers at runtime (hydration never fires), which is diagnosable but not a
     *     hard crash. A hard error here could block builds where the entity genuinely lives
     *     in another module and gets its OWN transformer pass there.
     *
     * Uses `setBody()` (full replace) rather than `insertAfter()` (append before RETURN) because
     * the hydration body starts with a null-guard-and-early-return — `insertAfter` would put that
     * AFTER whatever's already there (which is just RETURN, so it'd be unreachable). `setBody()`
     * discards the stub entirely and emits fresh bytecode from the source string.
     */
    private fun transformAttachEmbedded(context: Context, ctClass: CtClass): Boolean {
        // The base Cursor declares `public void attachEmbedded(@Nullable T)` — overriding it
        // in a generic subclass causes the compiler to emit a BRIDGE method
        // (`attachEmbedded(Ljava/lang/Object;)V`) that casts-and-delegates to the concrete
        // override (`attachEmbedded(L<Entity>;)V`). Both land in `declaredMethods`, so a naive
        // name-match + `singleOrNull` sees two and returns null. Filter by ACC_BRIDGE (0x40) —
        // we want the REAL override, not the erasure bridge.
        //
        // (`attachEntity` above doesn't have this problem — the base Cursor has no method by
        // that name, so no bridge is generated. The asymmetry is: attachEntity is a NAMING
        // CONVENTION the transformer looks for, attachEmbedded is an ACTUAL BASE METHOD.)
        //
        // Also filter out the List-overload shape — the base has a `final` list-taking variant
        // that SHOULDN'T appear in declaredMethods (it's final, can't be overridden), but
        // belt-and-suspenders on the signature check in case some future refactor un-finals it.
        val attachCtMethod = ctClass.declaredMethods
            ?.singleOrNull {
                it.name == ClassConst.cursorAttachEmbeddedMethodName
                    && (it.methodInfo.accessFlags and AccessFlag.BRIDGE) == 0
                    && it.signature.startsWith("(L") && it.signature.endsWith(";)V")
                    && !it.signature.contains("java/util/List")
            }
            ?: return false

        // Extract entity FQN from the descriptor — same trick as checkEntityIsInClassPool().
        // `(Lcom/example/Bill;)V` → drop `(L` (2 chars) + drop `;)V` (3 chars) → `com/example/Bill`.
        val signature = attachCtMethod.signature
        val entityFqn = signature.drop(2).dropLast(3).replace('/', '.')

        // Phase-A stash lookup. If absent, the entity wasn't processed in THIS transformer run —
        // cross-module scenario, or the entity class was accidentally excluded from the set.
        val containers = context.embeddedContainersByEntity[entityFqn]
        if (containers == null) {
            logWarning(
                "${ctClass.name}.${ClassConst.cursorAttachEmbeddedMethodName}: entity '$entityFqn' " +
                    "was not transformed in this pass (no embedded metadata available). " +
                    "Hydration body NOT injected — @Embedded containers will stay null after reads. " +
                    "Ensure the entity class is included in the transformer's class set."
            )
            return false
        }

        // Idempotency / already-transformed check — if the stub isn't the 1-byte RETURN we expect,
        // it was either already transformed (re-run) or hand-written. Warn but proceed: setBody()
        // replaces unconditionally, which is the correct behaviour for a re-run (the metadata may
        // have changed). A hand-written body would be lost, but M2.3's contract is "the stub body
        // is owned by the transformer" — hand-written bodies are a user-error.
        val existingCode = attachCtMethod.methodInfo.codeAttribute.code
        if (existingCode.size != 1 || existingCode[0] != Opcode.RETURN.toByte()) {
            logWarning(
                "${ctClass.name}.${ClassConst.cursorAttachEmbeddedMethodName} body expected to be " +
                    "empty (APT-generated stub). Existing body will be REPLACED with hydration code. " +
                    "If you hand-wrote this method, move your logic elsewhere — the transformer owns this body."
            )
        }

        // Ensure the entity CtClass (with the synthetic flat fields that phase-A injected) is
        // in the pool — Javassist needs to resolve `$1.priceCurrency` field refs at setBody() time.
        // It almost certainly IS (phase-A just transformed it in-pool), but belt-and-suspenders.
        // Note: the container POJO types were already loaded into the pool during phase-A's
        // discoverEmbeddedContainers(), so `new MoneyEmbeddable()` resolves too.
        if (context.classPool.getOrNull(entityFqn) == null) {
            logWarning("${ctClass.name}: entity '$entityFqn' not in class pool at cursor-transform time (unexpected).")
            // Can't safely setBody without the entity type — field refs won't resolve.
            return false
        }

        val body = EmbeddedTransform.buildAttachEmbeddedBody(containers)
        if (debug) log("Injecting attachEmbedded body into ${ctClass.name}: $body")
        attachCtMethod.setBody(body)
        context.stats.embeddedAttachBodiesInjected++
        return true
    }

    private fun checkEntityIsInClassPool(classPool: ClassPool, signature: String) {
        val entityClass = signature.drop(2).dropLast(3).replace('/', '.')
        var entityCtClass: CtClass? = null
        try {
            entityCtClass = classPool.get(entityClass) // find() seems to do something else!?
        } catch (e: NotFoundException) {
            // entityCtClass just keeps null
        }
        if (entityCtClass == null) {
            println("Warning: cursor transformer did not find entity class $entityClass")
            entityCtClass = classPool.makeClass(entityClass)
            val fieldCode = "transient ${ClassConst.boxStoreClass} ${ClassConst.boxStoreFieldName};"
            entityCtClass.addField(CtField.make(fieldCode, entityCtClass))
        }
    }


}
