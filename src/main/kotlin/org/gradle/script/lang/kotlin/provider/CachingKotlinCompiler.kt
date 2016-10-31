/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.script.lang.kotlin.provider

import org.gradle.cache.CacheRepository
import org.gradle.cache.internal.CacheKeyBuilder
import org.gradle.cache.internal.CacheKeyBuilder.CacheKeySpec

import org.gradle.internal.classloader.VisitableURLClassLoader
import org.gradle.internal.classpath.ClassPath
import org.gradle.internal.classpath.DefaultClassPath
import org.gradle.internal.logging.progress.ProgressLoggerFactory

import org.gradle.script.lang.kotlin.KotlinBuildScript

import org.gradle.script.lang.kotlin.loggerFor
import org.gradle.script.lang.kotlin.support.KotlinBuildScriptSection
import org.gradle.script.lang.kotlin.support.compileKotlinScriptToDirectory

import org.jetbrains.kotlin.script.KotlinScriptDefinitionFromAnnotatedTemplate

import java.io.File

import kotlin.reflect.KClass

class CachingKotlinCompiler(
    val cacheKeyBuilder: CacheKeyBuilder,
    val cacheRepository: CacheRepository,
    val progressLoggerFactory: ProgressLoggerFactory,
    val recompileScripts: Boolean) {

    private val logger = loggerFor<KotlinScriptPluginFactory>()

    private val cacheKeyPrefix = CacheKeySpec.withPrefix("gradle-script-kotlin")

    fun compileBuildscriptSectionOf(scriptFile: File,
                                    buildscriptRange: IntRange,
                                    classPath: ClassPath,
                                    parentClassLoader: ClassLoader): Class<*> {
        val buildscript = scriptFile.readText().substring(buildscriptRange)
        return compileWithCache(cacheKeyPrefix + buildscript, classPath, parentClassLoader) { cacheDir ->
            ScriptCompilationSpec(
                buildscriptSectionFileFor(buildscript, cacheDir),
                KotlinBuildScriptSection::class,
                scriptFile.name + " buildscript block")
        }
    }

    fun compileBuildScript(scriptFile: File, classPath: ClassPath, parentClassLoader: ClassLoader): Class<*> =
        compileWithCache(cacheKeyPrefix + scriptFile, classPath, parentClassLoader) {
            ScriptCompilationSpec(scriptFile, KotlinBuildScript::class, scriptFile.name)
        }

    private fun compileWithCache(cacheKeySpec: CacheKeySpec,
                                 classPath: ClassPath,
                                 parentClassLoader: ClassLoader,
                                 compilationSpecFrom: (File) -> ScriptCompilationSpec): Class<*> {
        val cacheDir = cacheRepository
            .cache(cacheKeyFor(cacheKeySpec + parentClassLoader))
            .withProperties(mapOf("version" to "1"))
            .let { if (recompileScripts) it.withValidator { false } else it }
            .withInitializer { cache ->
                val cacheDir = cache.baseDir
                val scriptClass =
                    compileTo(classesDirOf(cacheDir), compilationSpecFrom(cacheDir), classPath, parentClassLoader)
                writeClassNameTo(cacheDir, scriptClass.name)
            }.open().run {
                close()
                baseDir
            }
        return loadClassFrom(classesDirOf(cacheDir), readClassNameFrom(cacheDir), parentClassLoader)
    }

    data class ScriptCompilationSpec(val scriptFile: File, val scriptTemplate: KClass<out Any>, val description: String)

    private fun compileTo(outputDir: File,
                          spec: ScriptCompilationSpec,
                          classPath: ClassPath,
                          parentClassLoader: ClassLoader): Class<*> =
        withProgressLoggingFor(spec.description) {
            logger.info("Kotlin compilation classpath for {}: {}", spec.description, classPath)
            compileKotlinScriptToDirectory(
                outputDir,
                spec.scriptFile,
                scriptDefinitionFromTemplate(spec.scriptTemplate, classPath),
                parentClassLoader, logger)
        }

    private fun buildscriptSectionFileFor(buildscript: String, outputDir: File) =
        File(outputDir, "buildscript-section.gradle.kts").apply {
            writeText(buildscript)
        }

    private fun cacheKeyFor(spec: CacheKeySpec) = cacheKeyBuilder.build(spec)

    private fun writeClassNameTo(cacheDir: File, className: String) =
        scriptClassNameFile(cacheDir).writeText(className)

    private fun readClassNameFrom(cacheDir: File) =
        scriptClassNameFile(cacheDir).readText()

    private fun scriptClassNameFile(cacheDir: File) = File(cacheDir, "script-class-name")

    private fun classesDirOf(cacheDir: File) = File(cacheDir, "classes")

    private fun loadClassFrom(classesDir: File, scriptClassName: String, classLoader: ClassLoader) =
        VisitableURLClassLoader(classLoader, classPathOf(classesDir)).loadClass(scriptClassName)

    private fun classPathOf(classesDir: File) =
        DefaultClassPath.of(listOf(classesDir))

    private fun scriptDefinitionFromTemplate(template: KClass<out Any>, classPath: ClassPath) =
        KotlinScriptDefinitionFromAnnotatedTemplate(template, environment = mapOf("classPath" to classPath))

    private fun <T> withProgressLoggingFor(description: String, action: () -> T): T {
        val operation = progressLoggerFactory
            .newOperation(javaClass)
            .start("Compiling script into cache", "Compiling $description into local build cache")
        try {
            return action()
        } finally {
            operation.completed()
        }
    }
}
