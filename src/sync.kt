/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package org.jetbrains.settingsRepository

import com.intellij.configurationStore.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.ApplicationImpl
import com.intellij.openapi.application.invokeAndWaitIfNeed
import com.intellij.openapi.components.StateStorage
import com.intellij.openapi.components.stateStore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.runModalTask
import com.intellij.openapi.project.Project
import com.intellij.util.SmartList
import com.intellij.util.messages.MessageBus
import gnu.trove.THashSet
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import java.util.*

internal class SyncManager(private val icsManager: IcsManager, private val autoSyncManager: AutoSyncManager) {
    @Volatile
    var writeAndDeleteProhibited = false
        private set

    private fun runSyncTask(onAppExit: Boolean, project: Project?, task: (indicator: ProgressIndicator) -> Unit) {
        icsManager.runInAutoCommitDisabledMode {
            if (!onAppExit) {
                ApplicationManager.getApplication()!!.saveSettings()
            }

            try {
                writeAndDeleteProhibited = true
                runModalTask(icsMessage("task.sync.title"), project = project, task = task)
            } finally {
                writeAndDeleteProhibited = false
            }
        }
    }

    fun sync(
        syncType: SyncType,
        project: Project? = null,
        localRepositoryInitializer: (() -> Unit)? = null,
        onAppExit: Boolean = false
    ): Boolean {
        var exception: Throwable? = null
        var restartApplication = false
        var updateResult: UpdateResult? = null
        var isReadOnlySourcesChanged = false
        runSyncTask(onAppExit, project) { indicator ->
            indicator.isIndeterminate = true

            if (!onAppExit) {
                autoSyncManager.waitAutoSync(indicator)
            }

            val repositoryManager = icsManager.repositoryManager

            fun updateRepository() {
                when (syncType) {
                    SyncType.MERGE -> {
                        updateResult = repositoryManager.pull(indicator)
                        var doPush = true
                        if (localRepositoryInitializer != null) {
                            // must be performed only after initial pull, so, local changes will be relative to remote files
                            localRepositoryInitializer()
                            if (!repositoryManager.commit(
                                    indicator,
                                    syncType
                                ) || repositoryManager.getAheadCommitsCount() == 0
                            ) {
                                // avoid error during findRemoteRefUpdatesFor on push - if localRepositoryInitializer specified and nothing to commit (failed or just no files to commit (empty local configuration - no files)),
                                // so, nothing to push
                                doPush = false
                            }
                        }
                        if (doPush) {
                            repositoryManager.push(indicator)
                        }
                    }
                    SyncType.OVERWRITE_LOCAL -> {
                        // we don't push - probably, repository will be modified/removed (user can do something, like undo) before any other next push activities (so, we don't want to disturb remote)
                        updateResult = repositoryManager.resetToTheirs(indicator)
                    }
                    SyncType.OVERWRITE_REMOTE -> {
                        updateResult = repositoryManager.resetToMy(indicator, localRepositoryInitializer)
                        if (repositoryManager.getAheadCommitsCount() > 0) {
                            repositoryManager.push(indicator)
                        }
                    }
                }
            }

            if (localRepositoryInitializer == null) {
                try {
                    // we commit before even if sync "OVERWRITE_LOCAL" - preserve history and ability to undo
                    repositoryManager.commit(indicator, syncType)
                    // well, we cannot commit? No problem, upcoming action must do something smart and solve the situation
                } catch (e: ProcessCanceledException) {
                    LOG.warn("Canceled")
                    return@runSyncTask
                } catch (e: Throwable) {
                    LOG.error(e)

                    // "RESET_TO_*" will do "reset hard", so, probably, error will be gone, so, we can continue operation
                    if (syncType == SyncType.MERGE) {
                        exception = e
                        return@runSyncTask
                    }
                }
            }

            if (indicator.isCanceled) {
                return@runSyncTask
            }

            try {
                if (repositoryManager.hasUpstream()) {
                    updateRepository()
                }

                isReadOnlySourcesChanged = updateCloudSchemes(icsManager, indicator)
            } catch (e: ProcessCanceledException) {
                LOG.debug("Canceled")
                return@runSyncTask
            } catch (e: Throwable) {
                if (e !is AuthenticationException && e !is NoRemoteRepositoryException && e !is CannotResolveConflictInTestMode) {
                    LOG.error(e)
                }
                exception = e
                return@runSyncTask
            }

            if (updateResult != null) {
                val app = ApplicationManager.getApplication()
                restartApplication = updateStoragesFromStreamProvider(
                    icsManager, app.stateStore as ComponentStoreImpl, updateResult!!, app.messageBus,
                    reloadAllSchemes = syncType == SyncType.OVERWRITE_LOCAL
                )
            }
        }

        if (!onAppExit && restartApplication) {
            // disable auto sync on exit
            autoSyncManager.enabled = false
            // force to avoid saveAll & confirmation
            (ApplicationManager.getApplication() as ApplicationImpl).exit(true, true, true)
        } else if (exception != null) {
            throw exception!!
        }
        return updateResult != null || isReadOnlySourcesChanged
    }
}

internal fun updateCloudSchemes(icsManager: IcsManager, indicator: ProgressIndicator? = null): Boolean {
    val changedRootDirs = icsManager.readOnlySourcesManager.update(indicator) ?: return false
    val schemeManagersToReload = SmartList<SchemeManagerImpl<*, *>>()
    icsManager.schemeManagerFactory.value.process {
        val fileSpec = toRepositoryPath(it.fileSpec, it.roamingType)
        if (changedRootDirs.contains(fileSpec)) {
            schemeManagersToReload.add(it)
        }
    }

    if (schemeManagersToReload.isNotEmpty()) {
        for (schemeManager in schemeManagersToReload) {
            schemeManager.reload()
        }
    }

    return schemeManagersToReload.isNotEmpty()
}


internal fun updateStoragesFromStreamProvider(
    icsManager: IcsManager,
    store: ComponentStoreImpl,
    updateResult: UpdateResult,
    messageBus: MessageBus,
    reloadAllSchemes: Boolean = false
): Boolean {
    val (changed, deleted) = (store.storageManager as StateStorageManagerImpl).getCachedFileStorages(
        updateResult.changed,
        updateResult.deleted,
        ::toIdeaPath
    )

    val schemeManagersToReload = SmartList<SchemeManagerImpl<*, *>>()
    icsManager.schemeManagerFactory.value.process {
        if (reloadAllSchemes) {
            schemeManagersToReload.add(it)
        } else {
            for (path in updateResult.changed) {
                if (it.fileSpec == toIdeaPath(path)) {
                    schemeManagersToReload.add(it)
                }
            }
            for (path in updateResult.deleted) {
                if (it.fileSpec == toIdeaPath(path)) {
                    schemeManagersToReload.add(it)
                }
            }
        }
    }

    if (changed.isEmpty() && deleted.isEmpty() && schemeManagersToReload.isEmpty()) {
        return false
    }

    return invokeAndWaitIfNeed {
        val changedComponentNames = LinkedHashSet<String>()
        updateStateStorage(changedComponentNames, changed, false)
        updateStateStorage(changedComponentNames, deleted, true)

        for (schemeManager in schemeManagersToReload) {
            schemeManager.reload()
        }

        if (changedComponentNames.isEmpty()) {
            return@invokeAndWaitIfNeed false
        }

        val notReloadableComponents = store.getNotReloadableComponents(changedComponentNames)
        val changedStorageSet = THashSet<StateStorage>(changed)
        changedStorageSet.addAll(deleted)
        runBatchUpdate(messageBus) {
            store.reinitComponents(changedComponentNames, changedStorageSet, notReloadableComponents)
        }
        return@invokeAndWaitIfNeed !notReloadableComponents.isEmpty() && askToRestart(
            store,
            notReloadableComponents,
            null,
            true
        )
    }
}

private fun updateStateStorage(
    changedComponentNames: MutableSet<String>,
    stateStorages: Collection<StateStorage>,
    deleted: Boolean
) {
    for (stateStorage in stateStorages) {
        try {
            (stateStorage as XmlElementStorage).updatedFromStreamProvider(changedComponentNames, deleted)
        } catch (e: Throwable) {
            LOG.error(e)
        }
    }
}

enum class SyncType {
    MERGE,
    OVERWRITE_LOCAL,
    OVERWRITE_REMOTE
}

class NoRemoteRepositoryException(cause: Throwable) : RuntimeException(cause.message, cause)

class CannotResolveConflictInTestMode : RuntimeException()