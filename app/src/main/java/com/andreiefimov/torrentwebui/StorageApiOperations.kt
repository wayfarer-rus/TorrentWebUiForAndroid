package com.andreiefimov.torrentwebui

import android.content.Context
import java.io.File

internal sealed interface DirectoryChildrenResult {
    data class Available(val paths: List<String>) : DirectoryChildrenResult
    data class Unavailable(val reason: String) : DirectoryChildrenResult
}

internal sealed interface DestinationApprovalResult {
    data class Approved(val canonicalPath: String) : DestinationApprovalResult
    data class Rejected(val reason: String) : DestinationApprovalResult
    data object PersistenceFailed : DestinationApprovalResult
}

/** Backend authority used by both the normal WebUI and Consumer Onboarding directory browser. */
internal interface StorageApiOperations {
    fun volumes(): List<StorageVolume>
    fun children(parentPath: String): DirectoryChildrenResult
    fun validate(path: String): DirectoryValidationResult
    suspend fun approve(path: String): DestinationApprovalResult
}

internal class AndroidStorageApiOperations(
    private val context: Context,
    private val catalog: () -> DestinationCatalogOperations?
) : StorageApiOperations {
    override fun volumes(): List<StorageVolume> =
        StorageVolumeService.listVolumes(context).filter { it.isMounted }

    override fun children(parentPath: String): DirectoryChildrenResult {
        if (!parentPath.startsWith("/") || parentPath.startsWith("//")) {
            return DirectoryChildrenResult.Unavailable("This folder cannot be browsed.")
        }
        val parent = File(parentPath)
        val canonicalPath = try {
            parent.canonicalPath
        } catch (_: Exception) {
            return DirectoryChildrenResult.Unavailable("This folder cannot be browsed.")
        }
        if (!parent.isDirectory || !parent.canRead() ||
            !StorageVolumeService.isUnderVolumeRoot(canonicalPath, context)
        ) {
            return DirectoryChildrenResult.Unavailable(
                "This storage volume or folder is no longer available."
            )
        }
        return DirectoryChildrenResult.Available(
            DirectoryValidationService.listValidChildren(context, canonicalPath)
        )
    }

    override fun validate(path: String): DirectoryValidationResult =
        DirectoryValidationService.validate(context, path)

    override suspend fun approve(path: String): DestinationApprovalResult {
        val validation = validate(path)
        if (!validation.isValid || validation.canonicalPath == null) {
            return DestinationApprovalResult.Rejected(
                validation.rejectionReason ?: "This folder cannot be used."
            )
        }
        return if (catalog()?.addDestination(validation.canonicalPath) == true) {
            DestinationApprovalResult.Approved(validation.canonicalPath)
        } else {
            DestinationApprovalResult.PersistenceFailed
        }
    }
}
