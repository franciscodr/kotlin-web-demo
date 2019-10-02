/*
 * Copyright 2000-2016 JetBrains s.r.o.
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

package org.jetbrains.webdemo.kotlin

import org.apache.commons.logging.LogFactory
import org.jetbrains.webdemo.ArrowVersionsManager
import org.jetbrains.webdemo.KotlinVersionsManager
import org.jetbrains.webdemo.kotlin.classloader.ChildFirstURLClassLoader
import java.net.URL
import java.nio.file.Path
import java.util.*
import kotlin.properties.Delegates

object KotlinWrappersManager {
    private val log = LogFactory.getLog(KotlinWrappersManager::class.java)
    private val INITIALIZER_CLASSNAME = "org.jetbrains.webdemo.kotlin.impl.KotlinWrapperImpl"
    private val wrappers = HashMap<String, HashMap<String, KotlinWrapper>>()

    var defaultWrapper by Delegates.notNull<KotlinWrapper>()
        private set
    var wrappersDir by Delegates.notNull<Path>()
        private set

    fun init(wrappersDir: Path, javaLibraries: List<Path>, relativeClassDirectoryPath: Path) {
        this.wrappersDir = wrappersDir
        for (arrowVersionConfig in ArrowVersionsManager.arrowVersionConfigs) {
            val kotlinWrappers = HashMap<String, KotlinWrapper>()
            for (supportedKotlinVersion in arrowVersionConfig.supportedKotlinVersions) {
                try {
                    val classPath = getForKotlinWrapperClassLoaderURLs(supportedKotlinVersion, wrappersDir, relativeClassDirectoryPath)
                    val kotlinClassLoader = ChildFirstURLClassLoader(classPath, Thread.currentThread().contextClassLoader)
                    val kotlinWrapper = kotlinClassLoader.loadClass(INITIALIZER_CLASSNAME).newInstance() as KotlinWrapper
                    val kotlinVersionConfig = KotlinVersionsManager.kotlinVersionConfigs.find { it.version == supportedKotlinVersion }
                    kotlinWrapper.init(javaLibraries, arrowVersionConfig, kotlinVersionConfig)
                    kotlinWrappers[supportedKotlinVersion] = kotlinWrapper
                    if (arrowVersionConfig.latestStable && kotlinVersionConfig?.latestStable!!) {
                        defaultWrapper = kotlinWrapper
                    }
                } catch (e: Throwable) {
                    log.error("Can't initialize kotlin version " + supportedKotlinVersion, e)
                }

            }
            wrappers[arrowVersionConfig.version] = kotlinWrappers
        }
    }

    fun getArrowVersions(): Set<String> = wrappers.keys

    fun getKotlinVersions(arrowVersion: String?): Set<String> = wrappers[arrowVersion]?.keys.orEmpty()

    fun getAllWrappers(): Collection<KotlinWrapper> = wrappers.values.fold(
            emptyList(),
            { list, wrappers -> list.plus(wrappers.values) })

    fun getKotlinWrapper(arrowVersion: String?, kotlinVersion: String?): KotlinWrapper? {
        if (arrowVersion == null && kotlinVersion == null) return defaultWrapper;
        return wrappers[arrowVersion]?.get(kotlinVersion)
    }

    private fun getForKotlinWrapperClassLoaderURLs(kotlinVersion: String, wrappersDir: Path, relativeClassDirectoryPath: Path): Array<URL> {
        val wrapperDir = wrappersDir.resolve(kotlinVersion)
        val classesDir = wrapperDir.resolve(relativeClassDirectoryPath)
        val jarsDir = wrapperDir.resolve("kotlin")

        val urls = ArrayList<URL>()

        for (jarFile in jarsDir.toFile().list()) {
            urls.add(jarsDir.resolve(jarFile).toUri().toURL())
        }
        urls.add(classesDir.toUri().toURL())
        return urls.toTypedArray()
    }
}
