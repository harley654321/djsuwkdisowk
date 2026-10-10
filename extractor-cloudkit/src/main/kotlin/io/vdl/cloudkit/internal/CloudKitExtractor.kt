package io.vdl.cloudkit.internal

import io.vdl.cloudkit.CloudKitResult

/**
 * Contract every cloudkit extractor satisfies: domain-based ownership
 * ([matches]) plus never-throwing resolution ([resolve] -> null on
 * degradation). All extractors share the same [CloudHttp] seam.
 */
internal interface CloudKitExtractor {
    fun matches(url: String): Boolean
    suspend fun resolve(url: String): CloudKitResult?
}
