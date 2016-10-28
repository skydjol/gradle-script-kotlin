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

import org.gradle.configuration.ScriptPluginFactory
import org.gradle.configuration.ScriptPluginFactoryProvider

import javax.inject.Inject

class KotlinScriptPluginFactoryProvider @Inject constructor(
    val classPathProvider: KotlinScriptClassPathProvider,
    val kotlinCompiler: CachingKotlinCompiler) : ScriptPluginFactoryProvider {

    override fun getFor(fileName: String): ScriptPluginFactory? =
        when {
            fileName.endsWith(".kts") -> KotlinScriptPluginFactory(classPathProvider, kotlinCompiler)
            else -> null
        }
}

