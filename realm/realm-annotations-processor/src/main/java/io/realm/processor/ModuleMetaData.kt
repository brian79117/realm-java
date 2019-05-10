/*
 * Copyright 2015 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm.processor

import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet

import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.AnnotationValue
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement

import io.realm.annotations.RealmModule
import io.realm.annotations.RealmNamingPolicy
import io.realm.processor.nameconverter.NameConverter


/**
 * Utility class for holding metadata for the Realm modules.
 *
 *
 * Modules are inherently difficult to process because a model class can be part of multiple modules
 * that contain information required by the model class (e.g. class/field naming policies). At the
 * same time, the module will need the data from processed model classes to fully complete its
 * analysis (e.g. to ensure that only valid Realm model classes are added to the module).
 *
 *
 * For this reason, processing modules are separated into 3 steps:
 *
 *  1.
 * Pre-processing. Done by calling [.preProcess], which will do an initial parse
 * of the modules and build up all information it can before processing any model classes.
 *
 *  1.
 * Process model classes. See [ClassMetaData.generate].
 *
 *  1.
 * Post-processing. Done by calling [.postProcess]. All modules can now
 * be fully verified, and all metadata required to output module files can be generated.
 *
 *
 */
class ModuleMetaData {

    // Pre-processing
    // <FullyQualifiedModuleClassName, X>
    private val globalModules = LinkedHashSet<String>() // All modules with `allClasses = true` set
    private val specificClassesModules = LinkedHashMap<String, Set<String>>() // Modules with classes specifically named
    private val classNamingPolicy = LinkedHashMap<String, RealmNamingPolicy>()
    private val fieldNamingPolicy = LinkedHashMap<String, RealmNamingPolicy>()
    private val moduleAnnotations = HashMap<String, RealmModule>()

    // Post-processing
    // <FullyQualifiedModuleClassName, X>
    private val modules = LinkedHashMap<String, Set<ClassMetaData>>()
    private val libraryModules = LinkedHashMap<String, Set<ClassMetaData>>()

    private var shouldCreateDefaultModule: Boolean = false

    /**
     * Returns all module classes and the RealmObjects they know of.
     */
    val allModules: Map<String, Set<ClassMetaData>>
        get() {
            val allModules = LinkedHashMap<String, Set<ClassMetaData>>()
            allModules.putAll(modules)
            allModules.putAll(libraryModules)
            return allModules
        }

    /**
     * Builds all meta data structures that can be calculated before processing any model classes.
     * Any errors or messages will be posted on the provided Messager.
     *
     * @return True if meta data was correctly created and processing of model classes can continue, false otherwise.
     */
    fun preProcess(moduleClasses: Set<Element>): Boolean {

        // Tracks all module settings with `allClasses` enabled
        val globalModuleInfo = HashSet<ModulePolicyInfo>()

        // Tracks which modules a class was mentioned in by name using `classes = { ... }`
        // <Qualified
        val classSpecificModuleInfo = HashMap<String, MutableList<ModulePolicyInfo>>()

        // Check that modules are setup correctly
        for (classElement in moduleClasses) {
            val classSimpleName = classElement.simpleName.toString()

            // Check that the annotation is only applied to a class
            if (classElement.kind != ElementKind.CLASS) {
                Utils.error("The RealmModule annotation can only be applied to classes", classElement)
                return false
            }

            // Check that allClasses and classes are not set at the same time
            val moduleAnnotation = classElement.getAnnotation(RealmModule::class.java)
            Utils.note("Processing module $classSimpleName")
            if (moduleAnnotation.allClasses && hasCustomClassList(classElement)) {
                Utils.error("Setting @RealmModule(allClasses=true) will override @RealmModule(classes={...}) in $classSimpleName")
                return false
            }

            // Validate that naming policies are correctly configured.
            if (!validateNamingPolicies(globalModuleInfo, classSpecificModuleInfo, classElement as TypeElement, moduleAnnotation)) {
                return false
            }

            moduleAnnotations[classElement.qualifiedName.toString()] = moduleAnnotation
        }

        return true
    }

    /**
     * Validates that the class/field naming policy for this module is correct.
     *
     * @param globalModuleInfo list of all modules with `allClasses` set
     * @param classSpecificModuleInfo map of explicit classes and which modules they are explicitly mentioned in.
     * @param classElement class element currently being validated
     * @param moduleAnnotation annotation on this class.
     * @return `true` if everything checks out, `false` if an error was found and reported.
     */
    private fun validateNamingPolicies(globalModuleInfo: MutableSet<ModulePolicyInfo>, classSpecificModuleInfo: HashMap<String, MutableList<ModulePolicyInfo>>, classElement: TypeElement, moduleAnnotation: RealmModule): Boolean {
        val classNamePolicy = moduleAnnotation.classNamingPolicy
        val fieldNamePolicy = moduleAnnotation.fieldNamingPolicy
        val qualifiedModuleClassName = classElement.qualifiedName.toString()
        val moduleInfo = ModulePolicyInfo(qualifiedModuleClassName, classNamePolicy, fieldNamePolicy)

        // The difference between `allClasses` and a list of classes is a bit tricky at this stage
        // as we haven't processed the full list of classes yet. We therefore need to treat
        // each case specifically :(
        // We do not compare against the default module as it is always configured correctly
        // with NO_POLICY, meaning it will not trigger any errors.
        if (moduleAnnotation.allClasses) {
            // Check for conflicts with all other modules with `allClasses` set.
            for (otherModuleInfo in globalModuleInfo) {
                if (checkAndReportPolicyConflict(moduleInfo, otherModuleInfo)) {
                    return false
                }
            }

            // Check for conflicts with specifically named classes. This can happen if another
            // module is listing specific classes with another policy.
            for ((_, value) in classSpecificModuleInfo) {
                for (otherModuleInfo in value) {
                    if (checkAndReportPolicyConflict(moduleInfo, otherModuleInfo)) {
                        return false
                    }
                }
            }

            // Everything checks out. Add moduleInfo so we can track it for the next module.
            globalModuleInfo.add(moduleInfo)
            globalModules.add(qualifiedModuleClassName)

        } else {
            // We need to verify each class in the modules class list
            val classNames = getClassListFromModule(classElement)
            for (qualifiedClassName in classNames) {

                // Check that no other module with `allClasses` conflict with this specific
                // class configuration
                for (otherModuleInfo in globalModuleInfo) {
                    if (checkAndReportPolicyConflict(moduleInfo, otherModuleInfo)) {
                        return false
                    }
                }

                // Check that this specific class isn't conflicting with another module
                // specifically mentioning it using `classes = { ... }`
                val otherModules = classSpecificModuleInfo[qualifiedClassName]
                if (otherModules != null) {
                    for (otherModuleInfo in otherModules) {
                        if (checkAndReportPolicyConflict(qualifiedClassName, moduleInfo, otherModuleInfo)) {
                            return false
                        }
                    }
                }

                // Keep track of the specific class for other module checks. We only
                // need to track the latest module seen as previous errors would have been
                // caught in a previous iteration of the loop.
                if (!classSpecificModuleInfo.containsKey(qualifiedClassName)) {
                    classSpecificModuleInfo[qualifiedClassName] = ArrayList()
                }
                classSpecificModuleInfo[qualifiedClassName]!!.add(moduleInfo)
            }
            specificClassesModules[qualifiedModuleClassName] = classNames
        }

        classNamingPolicy[qualifiedModuleClassName] = classNamePolicy
        fieldNamingPolicy[qualifiedModuleClassName] = fieldNamePolicy
        return true
    }

    /**
     * All model classes have now been processed and the final validation of modules can occur.
     * Any errors or messages will be posted on the provided Messager.
     *
     * @param modelClasses all Realm model classes found by the annotation processor.
     * @return `true` if the module is valid, `false` otherwise.
     */
    fun postProcess(modelClasses: ClassCollection): Boolean {

        // Process all global modules
        for (qualifiedModuleClassName in globalModules) {
            val classData = LinkedHashSet<ClassMetaData>()
            classData.addAll(modelClasses.classes)
            defineModule(qualifiedModuleClassName, classData)
        }

        // Process all modules with specific classes
        for ((qualifiedModuleClassName, value) in specificClassesModules) {
            val classData = LinkedHashSet<ClassMetaData>()
            for (qualifiedModelClassName in value) {
                if (!modelClasses.containsQualifiedClass(qualifiedModelClassName)) {
                    Utils.error(Utils.stripPackage(qualifiedModelClassName) + " could not be added to the module. " +
                            "Only classes extending RealmObject or implementing RealmModel, which are part of this project, can be added.")
                    return false

                }
                classData.add(modelClasses.getClassFromQualifiedName(qualifiedModelClassName))
            }
            defineModule(qualifiedModuleClassName, classData)
        }

        // Check that app and library modules are not mixed
        if (modules.size > 0 && libraryModules.size > 0) {
            val sb = StringBuilder()
            sb.append("Normal modules and library modules cannot be mixed in the same project.")
            sb.append('\n')
            sb.append("Normal module(s):\n")
            for (module in modules.keys) {
                sb.append("  ")
                sb.append(module)
                sb.append('\n')
            }
            sb.append("Library module(s):\n")
            for (module in libraryModules.keys) {
                sb.append("  ")
                sb.append(module)
                sb.append('\n')
            }
            Utils.error(sb.toString())
            return false
        }

        // Create default Realm module if needed.
        // Note: Kotlin will trigger the annotation processor even if no Realm annotations are used.
        // The DefaultRealmModule should not be created in this case either.
        if (libraryModules.size == 0 && modelClasses.size() > 0) {
            shouldCreateDefaultModule = true
            val defaultModuleName = Constants.REALM_PACKAGE_NAME + "." + Constants.DEFAULT_MODULE_CLASS_NAME
            modules[defaultModuleName] = modelClasses.classes
        }

        return true
    }

    private fun defineModule(qualifiedModuleClassName: String, classData: Set<ClassMetaData>) {
        if (!classData.isEmpty()) {
            if (moduleAnnotations[qualifiedModuleClassName]!!.library) {
                libraryModules[qualifiedModuleClassName] = classData
            } else {
                modules[qualifiedModuleClassName] = classData
            }
        }
    }

    // Checks if two modules have policy conflicts. Returns true if a conflict was found and reported.
    private fun checkAndReportPolicyConflict(moduleInfo: ModulePolicyInfo, otherModuleInfo: ModulePolicyInfo): Boolean {
        return checkAndReportPolicyConflict(null, moduleInfo, otherModuleInfo)
    }

    /**
     * Check for name policy conflicts and report the error if found.
     *
     * @param className optional class name if a specific class is being checked.
     * @param moduleInfo current module.
     * @param otherModuleInfo already processed module.
     * @return `true` if any errors was reported, `false` otherwise.
     */
    private fun checkAndReportPolicyConflict(className: String?, moduleInfo: ModulePolicyInfo, otherModuleInfo: ModulePolicyInfo): Boolean {
        var foundErrors = false

        // Check class naming policy
        val classPolicy = moduleInfo.classNamePolicy
        val otherClassPolicy = otherModuleInfo.classNamePolicy
        if (classPolicy != RealmNamingPolicy.NO_POLICY
                && otherClassPolicy != RealmNamingPolicy.NO_POLICY
                && classPolicy != otherClassPolicy) {
            Utils.error(String.format("The modules %s and %s disagree on the class naming policy%s: %s vs. %s. " + "They same policy must be used.",
                    moduleInfo.qualifiedModuleClassName,
                    otherModuleInfo.qualifiedModuleClassName,
                    if (className != null) " for $className" else "",
                    classPolicy,
                    otherClassPolicy))
            foundErrors = true
        }

        // Check field naming policy
        val fieldPolicy = moduleInfo.fieldNamePolicy
        val otherFieldPolicy = otherModuleInfo.fieldNamePolicy
        if (fieldPolicy != RealmNamingPolicy.NO_POLICY
                && otherFieldPolicy != RealmNamingPolicy.NO_POLICY
                && fieldPolicy != otherFieldPolicy) {
            Utils.error(String.format("The modules %s and %s disagree on the field naming policy%s: %s vs. %s. " + "They same policy should be used.",
                    moduleInfo.qualifiedModuleClassName,
                    otherModuleInfo.qualifiedModuleClassName,
                    if (className != null) " for $className" else "",
                    fieldPolicy,
                    otherFieldPolicy))
            foundErrors = true
        }

        return foundErrors
    }

    // Detour needed to access the class elements in the array
    // See http://blog.retep.org/2009/02/13/getting-class-values-from-annotations-in-an-annotationprocessor/
    private fun getClassListFromModule(classElement: Element): Set<String> {
        val annotationMirror = getAnnotationMirror(classElement)
        val annotationValue = getAnnotationValue(annotationMirror)
        val classes = HashSet<String>()
        val moduleClasses = annotationValue!!.value as List<AnnotationValue>
        for (classMirror in moduleClasses) {
            val fullyQualifiedClassName = classMirror.value.toString()
            classes.add(fullyQualifiedClassName)
        }
        return classes
    }

    // Work-around for asking for a Class primitive array which would otherwise throw a TypeMirrorException
    // https://community.oracle.com/thread/1184190
    private fun hasCustomClassList(classElement: Element): Boolean {
        val annotationMirror = getAnnotationMirror(classElement)
        val annotationValue = getAnnotationValue(annotationMirror)
        if (annotationValue == null) {
            return false
        } else {
            val moduleClasses = annotationValue.value as List<AnnotationValue>
            return moduleClasses.size > 0
        }
    }

    private fun getAnnotationMirror(classElement: Element): AnnotationMirror? {
        var annotationMirror: AnnotationMirror? = null
        for (am in classElement.annotationMirrors) {
            if (am.annotationType.toString() == RealmModule::class.java.canonicalName) {
                annotationMirror = am
                break
            }
        }
        return annotationMirror
    }

    private fun getAnnotationValue(annotationMirror: AnnotationMirror?): AnnotationValue? {
        if (annotationMirror == null) {
            return null
        }
        var annotationValue: AnnotationValue? = null
        for ((key, value) in annotationMirror.elementValues) {
            if (key.simpleName.toString() == "classes") {
                annotationValue = value
                break
            }
        }
        return annotationValue
    }

    /**
     * Returns `true` if the DefaultRealmModule.java file should be created.
     */
    fun shouldCreateDefaultModule(): Boolean {
        return shouldCreateDefaultModule
    }

    /**
     * Only available after [.preProcess] has run.
     * Returns the module name policy the given name.
     */
    fun getClassNameFormatter(qualifiedClassName: String): NameConverter {
        // We already validated that module definitions all agree on the same name policy
        // so just find first match
        if (!globalModules.isEmpty()) {
            return Utils.getNameFormatter(classNamingPolicy[globalModules.iterator().next()])
        }

        // No global modules found, so find match in modules specifically listing the class.
        // We already validated that all modules agree on the converter, so just find first match.
        for ((key, value) in specificClassesModules) {
            if (value.contains(qualifiedClassName)) {
                return Utils.getNameFormatter(classNamingPolicy[key])
            }
        }

        // No policy was provided anywhere for this class
        return Utils.getNameFormatter(RealmNamingPolicy.NO_POLICY)
    }


    /**
     * Only available after [.preProcess] has run.
     *
     * Returns the module name policy the field names.
     *
     * @param qualifiedClassName
     */
    fun getFieldNameFormatter(qualifiedClassName: String): NameConverter {
        // We already validated that module definitions all agree on the same name policy
        // so just find first match
        if (!globalModules.isEmpty()) {
            return Utils.getNameFormatter(fieldNamingPolicy[globalModules.iterator().next()])
        }

        for ((key, value) in specificClassesModules) {
            if (value.contains(qualifiedClassName)) {
                return Utils.getNameFormatter(fieldNamingPolicy[key])
            }
        }

        return Utils.getNameFormatter(RealmNamingPolicy.NO_POLICY)
    }

    // Tuple helper class
    private inner class ModulePolicyInfo(val qualifiedModuleClassName: String, val classNamePolicy: RealmNamingPolicy, val fieldNamePolicy: RealmNamingPolicy) {

        override fun equals(o: Any?): Boolean {
            if (this === o) return true
            if (o == null || javaClass != o.javaClass) return false

            val that = o as ModulePolicyInfo?

            if (qualifiedModuleClassName != that!!.qualifiedModuleClassName) return false
            return if (classNamePolicy != that.classNamePolicy) false else fieldNamePolicy == that.fieldNamePolicy
        }

        override fun hashCode(): Int {
            var result = qualifiedModuleClassName.hashCode()
            result = 31 * result + classNamePolicy.hashCode()
            result = 31 * result + fieldNamePolicy.hashCode()
            return result
        }
    }
}
