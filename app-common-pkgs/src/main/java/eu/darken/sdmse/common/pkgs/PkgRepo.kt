package eu.darken.sdmse.common.pkgs

import eu.darken.sdmse.common.collections.mutate
import eu.darken.sdmse.common.coroutine.AppScope
import eu.darken.sdmse.common.debug.Bugs
import eu.darken.sdmse.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.sdmse.common.debug.logging.log
import eu.darken.sdmse.common.debug.logging.logTag
import eu.darken.sdmse.common.files.core.GatewaySwitch
import eu.darken.sdmse.common.flow.replayingShare
import eu.darken.sdmse.common.pkgs.features.Installed
import eu.darken.sdmse.common.pkgs.pkgops.PkgOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PkgRepo @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    pkgEventListener: PackageEventListener,
    private val pkgSources: Set<@JvmSuppressWildcards PkgDataSource>,
    private val gatewaySwitch: GatewaySwitch,
    private val pkgOps: PkgOps,
) {

    data class CacheContainer(
        val isInitialized: Boolean = false,
        val pkgData: Map<Pkg.Id, CachedInfo> = emptyMap(),
    )

    private val cacheLock = Mutex()
    private val pkgCache = MutableStateFlow(CacheContainer())

    val pkgs: Flow<Collection<Installed>> = pkgCache
        .filter { it.isInitialized }
        .map { it.pkgData }
        .map { cachedInfo -> cachedInfo.values.mapNotNull { it.data } }
        .onStart {
            cacheLock.withLock {
                if (!pkgCache.value.isInitialized) {
                    log(TAG) { "Init due to pkgs subscription" }
                    load()
                }
            }
        }
        .replayingShare(appScope)

    init {
        pkgEventListener.events
            .onEach {
                log(TAG) { "Refreshing package cache due to event: $it" }
                reload()
            }
            .launchIn(appScope)
    }


    suspend fun reload() = cacheLock.withLock {
        log(TAG) { "reload()" }
        load()
    }

    private suspend fun load() {
        log(TAG) { "load()" }
        pkgCache.value = CacheContainer(
            isInitialized = true,
            pkgData = generatePkgcache()
        )
    }

    private suspend fun generatePkgcache(): Map<Pkg.Id, CachedInfo> {
        log(TAG) { "Generating package cache" }
        return gatewaySwitch.useRes {
            pkgOps.useRes {
                pkgSources
                    .map { source ->
                        source.getPkgs().also {
                            log(TAG) { "${it.size} pkgs from $source" }
                        }
                    }
                    .flatten()
                    .distinctBy { it.id }
                    .map {
                        if (Bugs.isTrace) log(TAG, VERBOSE) { "Installed package: $it" }
                        CachedInfo(id = it.id, data = it)
                    }
                    .associateBy { it.id }
                    .also { log(TAG) { "Pkgs total: ${it.size}" } }
            }
        }
    }

    private suspend fun queryCache(pkgId: Pkg.Id): CachedInfo = cacheLock.withLock {
        if (!pkgCache.value.isInitialized) {
            log(TAG) { "Package cache doesn't exist yet..." }
            load()
        }

        pkgCache.value.pkgData[pkgId]?.let { return@withLock it }

        log(TAG, VERBOSE) { "Cache miss for $pkgId" }

        return CachedInfo(
            id = pkgId,
            data = null,
        ).also {
            pkgCache.value = pkgCache.value.copy(
                pkgData = pkgCache.value.pkgData.mutate {
                    this[pkgId] = it
                }
            )
        }
    }

    suspend fun isInstalled(pkgId: Pkg.Id): Boolean = queryCache(pkgId).isInstalled

    suspend fun getPkg(pkgId: Pkg.Id): Installed? = queryCache(pkgId).data

    data class CachedInfo(
        val id: Pkg.Id,
        val data: Installed?,
    ) {
        val isInstalled: Boolean
            get() = data != null
    }

    companion object {
        private val TAG = logTag("PkgRepo")
    }
}