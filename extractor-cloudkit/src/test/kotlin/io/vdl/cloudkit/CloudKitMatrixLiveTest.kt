package io.vdl.cloudkit

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * LIVE mass smoke (opt-in: CLOUDKIT_LIVE=1 + CLOUDKIT_MATRIX=<csv path>).
 *
 * Reads the URL matrix produced by tools/live-harness/harness.py —
 * fresh embeds harvested from 5 live anime providers by replicating
 * extension (Kohi-den) flows — and drives every row through
 * [CloudKit.resolve]. The matrix rotates weekly via the live workflow,
 * so this test revalidates the extractor family against whatever the
 * providers serve today.
 *
 * Evidence per row is printed to stdout (extractor, kind, url shape);
 * the only hard assertions are:
 *  - a row whose host is claimed by an extractor must never throw
 *    (typed null or a result, never an exception), and
 *  - at least one row must fully resolve to a playable media URL
 *    (otherwise the whole kit is dead and the run is meaningless).
 *
 * Unknown hosts (mega.nz, 1fichier, transfer.it...) return null by
 * contract and are counted as "not covered" — that census is the input
 * for extractor-custom decisions.
 */
class CloudKitMatrixLiveTest {

    private fun live(): Boolean = System.getenv("CLOUDKIT_LIVE") == "1"

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private data class Row(val provider: String, val server: String, val url: String)

    private fun matrix(): List<Row> {
        val path = System.getenv("CLOUDKIT_MATRIX")
            ?: "tools/live-harness/matrix.csv"
        val file = File(path)
        assumeTrue("matrix file missing: $path", file.isFile)
        return file.readLines().drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val cols = line.split(",")
                Row(
                    provider = cols[0],
                    server = cols.getOrElse(3) { "?" },
                    url = cols.last().trim(),
                )
            }
            .filter { it.url.startsWith("http") }
    }

    @Test
    fun `matrix smoke resolves without throwing and at least one url plays`(): Unit = runBlocking {
        assumeTrue(live())
        val rows = matrix()
        assumeTrue("matrix is empty", rows.isNotEmpty())

        val byHost = rows.groupBy { row -> hostOf(row.url) }
        println("== CloudKit matrix smoke: ${rows.size} urls, ${byHost.size} hosts ==")

        var resolved = 0
        var degraded = 0
        var notCovered = 0
        val resolvedHosts = mutableSetOf<String>()

        for (row in rows) {
            val result = try {
                CloudKit.resolve(row.url, client())
            } catch (t: Throwable) {
                // A claimed host throwing is a contract violation: fail loud
                // with the full context so the harness row can be replayed.
                throw AssertionError(
                    "extractor threw for ${row.url} (provider=${row.provider} " +
                        "server=${row.server}) — typed degradation is the contract",
                    t,
                )
            }
            if (result == null) {
                // Unknown host (by contract) or known host that degraded
                // (shell challenge / cloudflare). Distinguish via matches.
                val claimed = CloudKit.claims(row.url)
                if (claimed) {
                    degraded++
                    println("  [degraded ] ${hostOf(row.url)}  <- ${row.provider}/${row.server}")
                } else {
                    notCovered++
                }
            } else {
                resolved++
                resolvedHosts.add(hostOf(row.url))
                println(
                    "  [resolved ] ${hostOf(row.url)} -> ${result.extractor} " +
                        "${result.kind} ${result.url.take(72)}",
                )
            }
        }

        println(
            "== summary: resolved=$resolved degraded=$degraded " +
                "notCovered=$notCovered total=${rows.size} ==",
        )
        println("== resolved hosts: ${resolvedHosts.sorted().joinToString()} ==")

        // The kit must prove itself alive on live matrix data.
        assertTrue(
            "no url from the matrix resolved — every extractor degraded; " +
                "check provider flows or host coverage",
            resolved > 0,
        )
    }

    private fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host ?: url
    }.getOrDefault(url)
}
