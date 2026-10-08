# Keep the public API of the library intact for consumers.
-keep class io.vdl.core.VdlDownloader { public *; }
-keep class io.vdl.core.DownloadState* { *; }
-keep class io.vdl.core.DownloadError* { *; }
-keep class io.vdl.core.TaskSnapshot { *; }
-keep class io.vdl.core.Progress { *; }
-keep class io.vdl.core.Priority { *; }
-keep class io.vdl.core.Destination* { *; }
-keep class io.vdl.core.RetryPolicy* { *; }
-keep class io.vdl.core.DownloadRequestBuilder { *; }
-keep class io.vdl.core.VdlConfig { *; }
-keep class io.vdl.core.Logger { *; }

# Kotlin metadata for public API.
-keep class kotlin.Metadata { *; }
