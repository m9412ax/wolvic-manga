package com.igalia.wolvic.manga

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dalvik.system.DelegateLastClassLoader
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import logcat.AndroidLogcatLogger
import logcat.LogPriority
import logcat.LogcatLogger
import tachiyomi.core.common.preference.AndroidPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory

/**
 * Loads installed Tachiyomi/Mihon extension APKs (keiyoushi protocol, lib 1.4/1.6)
 * against the ported source-api ABI. Trust prompt is intentionally skipped:
 * installed extensions are auto-approved per user request.
 */
object MangaSourceManager {

    private const val TAG = "MangaSource"

    private const val EXTENSION_FEATURE = "tachiyomi.extension"
    private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
    private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
    private const val METADATA_NAME = "tachiyomix.name"
    private const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"

    private val SUPPORTED_LIB_VERSIONS = listOf(1.4, 1.6)

    @Suppress("DEPRECATION")
    private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
        PackageManager.GET_META_DATA or
        PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    data class LoadedSource(
        val source: Source,
        val pkgName: String,
        val extName: String,
        val versionName: String,
        val nsfw: Boolean,
        val appLabel: String,
        val icon: android.graphics.drawable.Drawable?,
    )

    data class LoadFailure(
        val pkgName: String,
        val appLabel: String,
        val error: String,
    )

    private val loaded = mutableListOf<LoadedSource>()
    private val failures = mutableListOf<LoadFailure>()
    @Volatile
    private var initialized = false
    @Volatile
    private var injektReady = false

    @Synchronized
    fun reload(context: Context): List<LoadedSource> {
        loaded.clear()
        failures.clear()
        initialized = false
        return init(context)
    }

    @Synchronized
    fun init(context: Context): List<LoadedSource> {
        if (initialized) return loaded.toList()
        initialized = true
        val appContext = context.applicationContext

        if (LogcatLogger.loggers.isEmpty()) {
            LogcatLogger.install()
            LogcatLogger.loggers += AndroidLogcatLogger(LogPriority.DEBUG)
        }

        if (!injektReady) {
            injektReady = true
            Injekt.importModule(object : InjektModule {
                override fun InjektRegistrar.registerInjectables() {
                    addSingletonFactory<Context> { appContext }
                    // Mihon registers the Application at startup; extensions
                    // like jinmantiantang/picacomic resolve it in <clinit>.
                    addSingletonFactory<Application> { appContext as Application }
                    addSingletonFactory {
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                            coerceInputValues = true
                            explicitNulls = false
                        }
                    }
                    addSingletonFactory {
                        val netStore = AndroidPreferenceStore(
                            appContext.getSharedPreferences("manga_source_prefs", Context.MODE_PRIVATE),
                        )
                        netStore.getInt("doh_provider", -1)
                            .set(MangaSettings.get(appContext).integer(MangaSettings.NET_DOH))
                        NetworkHelper(
                            appContext,
                            NetworkPreferences(netStore, false),
                        )
                    }
                }
            })
        }

        val pm = appContext.packageManager
        val packages = try {
            pm.getInstalledPackages(PACKAGE_FLAGS)
        } catch (e: Exception) {
            Log.e(TAG, "getInstalledPackages failed", e)
            return emptyList()
        }

        for (pkg in packages) {
            if (!isExtension(pkg)) continue
            try {
                loadExtension(appContext, pm, pkg)
            } catch (e: Throwable) {
                Log.e(TAG, "extension load failed: ${pkg.packageName}", e)
                recordFailure(pm, pkg.packageName, e)
            }
        }
        Log.i(TAG, "init done: ${loaded.size} sources from ${packages.count { isExtension(it) }} extension packages, failures=${failures.size}")
        return loaded.toList()
    }

    fun getSources(): List<LoadedSource> = synchronized(this) { loaded.toList() }

    fun getFailures(): List<LoadFailure> = synchronized(this) { failures.toList() }

    private fun recordFailure(pm: PackageManager, pkgName: String, e: Throwable?) {
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkgName, 0)).toString()
        } catch (ignored: Exception) {
            pkgName
        }
        val error = e?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "未知错误"
        synchronized(this) {
            failures.removeAll { it.pkgName == pkgName }
            failures.add(LoadFailure(pkgName, label, error))
        }
    }

    fun getSource(id: Long): Source? = synchronized(this) {
        loaded.firstOrNull { it.source.id == id }?.source
    }

    /** Round-A device verification: fetch popular page 1 and log the outcome. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun testFetch(entry: LoadedSource) {
        val source = entry.source
        if (source !is CatalogueSource) {
            Log.w(TAG, "testFetch: ${entry.extName} is not a CatalogueSource")
            return
        }
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "testFetch: ${entry.extName} popular page 1 ...")
                val page = source.getPopularManga(1)
                val first = page.mangas.firstOrNull()
                Log.i(
                    TAG,
                    "testFetch OK: ${entry.extName} -> ${page.mangas.size} manga, hasNext=${page.hasNextPage}" +
                        (first?.let { ", first=${it.title} url=${it.url}" } ?: ""),
                )
            } catch (e: Throwable) {
                Log.e(TAG, "testFetch FAILED: ${entry.extName}", e)
            }
        }
    }

    private fun isExtension(pkg: PackageInfo): Boolean {
        return pkg.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }
    }

    private fun loadExtension(context: Context, pm: PackageManager, pkg: PackageInfo) {
        val appInfo = pkg.applicationInfo ?: return
        val pkgName = pkg.packageName
        val versionName = pkg.versionName ?: ""
        val appLabel = appInfo.loadLabel(pm).toString()
        val icon = try {
            appInfo.loadIcon(pm)
        } catch (e: Exception) {
            null
        }
        val metaData = appInfo.metaData
        if (metaData == null) {
            Log.w(TAG, "no meta-data: $pkgName")
            recordFailure(pm, pkgName, IllegalStateException("no meta-data"))
            return
        }

        val libVersion = metaData.getFloat(METADATA_EXTENSION_LIB)
            .takeUnless { it == 0.0f }
            ?.toString()
            ?.toDouble()
            ?: versionName.substringBeforeLast('.').toDoubleOrNull()
        if (libVersion == null || libVersion !in SUPPORTED_LIB_VERSIONS) {
            Log.w(TAG, "unsupported lib version $libVersion for $pkgName (supported: $SUPPORTED_LIB_VERSIONS)")
            recordFailure(pm, pkgName, IllegalStateException("unsupported lib version $libVersion"))
            return
        }

        val extName = metaData.getString(METADATA_NAME) ?: appLabel
        val nsfw = metaData.getInt(METADATA_NSFW, 0) == 1

        val classLoader = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                DelegateLastClassLoader(appInfo.sourceDir, null, context.classLoader)
            } else {
                PathClassLoader(appInfo.sourceDir, null, context.classLoader)
            }
        } catch (e: Exception) {
            Log.e(TAG, "classloader creation failed: $pkgName", e)
            recordFailure(pm, pkgName, e)
            return
        }

        val sourceClasses = metaData.getString(METADATA_SOURCE_CLASS)
        if (sourceClasses.isNullOrBlank()) {
            Log.w(TAG, "missing source class meta-data: $pkgName")
            recordFailure(pm, pkgName, IllegalStateException("missing source class meta-data"))
            return
        }
        Log.i(TAG, "ext meta: pkg=$pkgName class=$sourceClasses lib=$libVersion name=$extName")

        var anyLoaded = false
        for (raw in sourceClasses.split(";")) {
            val className = raw.trim().let {
                if (it.startsWith(".")) pkgName + it else it
            }
            if (className.isEmpty()) continue
            try {
                val obj = Class.forName(className, false, classLoader)
                    .getDeclaredConstructor().newInstance()
                val sources = when (obj) {
                    is Source -> listOf(obj)
                    is SourceFactory -> obj.createSources()
                    else -> {
                        Log.w(TAG, "unknown source class type: ${obj.javaClass} in $pkgName")
                        continue
                    }
                }
                for (source in sources) {
                    synchronized(this) {
                        loaded.removeAll { it.source.id == source.id }
                        loaded.add(LoadedSource(source, pkgName, extName, versionName, nsfw, appLabel, icon))
                    }
                    anyLoaded = true
                    Log.i(
                        TAG,
                        "loaded source: name=${source.name} id=${source.id} lang=${source.lang} " +
                            "pkg=$pkgName ver=$versionName lib=$libVersion nsfw=$nsfw",
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "source class load failed: $className ($pkgName)", e)
                recordFailure(pm, pkgName, e)
            }
        }
        if (!anyLoaded) {
            recordFailure(pm, pkgName, IllegalStateException("no source class instantiated"))
        } else {
            synchronized(this) { failures.removeAll { it.pkgName == pkgName } }
        }
    }
}