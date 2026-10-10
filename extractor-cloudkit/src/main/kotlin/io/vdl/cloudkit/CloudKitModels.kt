package io.vdl.cloudkit

/**
 * Result of a successful cloudkit extraction.
 *
 * @property url Direct media URL (master.m3u8 for HLS, file URL for DIRECT).
 * @property kind Transport kind the downloader should engage.
 * @property referer Referer the media request MUST carry (some CDNs check it).
 * @property extractor Name of the extractor that produced the link ("Voe").
 */
public class CloudKitResult(
    public val url: String,
    public val kind: CloudKitKind,
    public val referer: String?,
    public val extractor: String,
)

public enum class CloudKitKind { HLS, DIRECT }
