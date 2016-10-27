package org.gradle.script.lang.kotlin.provider

import org.gradle.cache.CacheRepository

import org.gradle.internal.classloader.VisitableURLClassLoader
import org.gradle.internal.classpath.ClassPath
import org.gradle.internal.classpath.DefaultClassPath
import org.gradle.internal.hash.HashUtil.createCompactMD5

import org.gradle.script.lang.kotlin.KotlinBuildScript

import org.gradle.script.lang.kotlin.loggerFor
import org.gradle.script.lang.kotlin.support.KotlinBuildScriptSection
import org.gradle.script.lang.kotlin.support.compileKotlinScriptToDirectory

import org.jetbrains.kotlin.script.KotlinScriptDefinitionFromAnnotatedTemplate

import java.io.File

import kotlin.reflect.KClass

class CachingKotlinCompiler(val cacheRepository: CacheRepository) {

    private val logger = loggerFor<KotlinScriptPluginFactory>()

    fun compileBuildscriptSectionOf(scriptFile: File,
                                    buildscriptRange: IntRange,
                                    classPath: ClassPath,
                                    parentClassLoader: ClassLoader): Class<*> {
        val buildscript = scriptFile.readText().substring(buildscriptRange)
        return compileWithCache(createCompactMD5(buildscript), classPath, parentClassLoader) { outputDir ->
            compileKotlinScriptToDirectory(
                outputDir,
                File(outputDir.parentFile, "buildscript-section.gradle.kts").apply {
                    writeText(buildscript)
                },
                scriptDefinitionFromTemplate(KotlinBuildScriptSection::class, classPath),
                parentClassLoader, logger)
        }
    }

    fun compileBuildScript(scriptFile: File, classPath: ClassPath, parentClassLoader: ClassLoader): Class<*> =
        compileWithCache(hashOf(scriptFile), classPath, parentClassLoader) { outputDir ->
            compileKotlinScriptToDirectory(
                outputDir,
                scriptFile,
                scriptDefinitionFromTemplate(KotlinBuildScript::class, classPath),
                parentClassLoader, logger)
        }

    private fun hashOf(scriptFile: File) = createCompactMD5(scriptFile.readText())

    private fun compileWithCache(cacheKey: String,
                                 classPath: ClassPath,
                                 parentClassLoader: ClassLoader,
                                 compileTo: (File) -> Class<*>): Class<*> {
        // TODO: add classPath to cacheKey
        val cacheDir = cacheRepository
            .cache("gradle-script-kotlin/$cacheKey")
            .withInitializer {
                logger.info("Kotlin compilation classpath: {}", classPath)
                val scriptClass = compileTo(classesDir(it.baseDir))
                scriptClassNameFile(it.baseDir).writeText(scriptClass.name)
            }.open().run {
                close()
                baseDir
            }
        return loadClassFrom(classesDir(cacheDir), scriptClassNameFile(cacheDir).readText(), parentClassLoader)
    }

    private fun scriptClassNameFile(cacheDir: File) = File(cacheDir, "script-class-name")

    private fun classesDir(cacheDir: File) = File(cacheDir, "classes")

    private fun loadClassFrom(classesDir: File, scriptClassName: String, classLoader: ClassLoader) =
        VisitableURLClassLoader(classLoader, classPathOf(classesDir)).loadClass(scriptClassName)

    private fun classPathOf(classesDir: File) =
        DefaultClassPath.of(listOf(classesDir))

    private fun scriptDefinitionFromTemplate(template: KClass<out Any>, classPath: ClassPath) =
        KotlinScriptDefinitionFromAnnotatedTemplate(template, environment = mapOf("classPath" to classPath))
}
