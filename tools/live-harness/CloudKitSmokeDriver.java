import io.vdl.cloudkit.CloudKit;
import io.vdl.cloudkit.CloudKitResult;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import okhttp3.OkHttpClient;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Local live smoke driver for extractor-cloudkit against the fresh URL
 * matrix produced by tools/live-harness/harness.py.
 *
 * Drives CloudKit.resolve (suspend fun) from plain Java using a latch
 * Continuation, 6 workers in parallel, and prints one CSV line per row:
 * provider,server,url,extractor,kind,resultUrl | NULL | THROWN,...
 *
 * No assertions here — evidence first, decisions after reading the census.
 * The same matrix is replayed by CloudKitMatrixLiveTest in CI, this driver
 * exists for sandbox runs without the Android SDK (plain JVM, jars only).
 */
public final class CloudKitSmokeDriver {

    public static void main(String[] args) throws Exception {
        String csv = args.length > 0 ? args[0] : "tools/live-harness/matrix.csv";
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] c = line.split(",", -1);
                if (c.length >= 5 && c[4].startsWith("http")) {
                    rows.add(new String[]{c[0], c[3], c[4]});
                }
            }
        }
        System.out.println("# rows=" + rows.size());

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch done = new CountDownLatch(rows.size());
        for (String[] row : rows) {
            pool.submit(() -> {
                String out = resolve(client, row[2]);
                System.out.println(row[0] + "," + row[1] + "," + row[2] + "," + out);
                done.countDown();
            });
        }
        done.await();
        pool.shutdown();
        System.out.println("# end");
    }

    /** Calls the suspend CloudKit.resolve with a latch continuation. */
    private static String resolve(OkHttpClient client, String url) {
        try {
            Object[] box = new Object[1];
            Throwable[] err = new Throwable[1];
            CountDownLatch latch = new CountDownLatch(1);
            Object res = CloudKit.INSTANCE.resolve(url, client, new Continuation<Object>() {
                @Override public CoroutineContext getContext() {
                    return kotlin.coroutines.EmptyCoroutineContext.INSTANCE;
                }
                @Override public void resumeWith(Object o) {
                    if (o instanceof kotlin.Result.Failure) {
                        err[0] = ((kotlin.Result.Failure) o).exception;
                    } else {
                        box[0] = o;
                    }
                    latch.countDown();
                }
            });
            if (res != kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
                return render((CloudKitResult) res);
            }
            latch.await(60, TimeUnit.SECONDS);
            if (err[0] != null) {
                return "THROWN," + err[0].getClass().getSimpleName() + ":"
                        + String.valueOf(err[0].getMessage()).replace(',', ';').replaceAll("\\s+", " ");
            }
            return render((CloudKitResult) box[0]);
        } catch (Throwable t) {
            return "THROWN," + t.getClass().getSimpleName() + ":"
                    + String.valueOf(t.getMessage()).replace(',', ';').replaceAll("\\s+", " ");
        }
    }

    private static String render(CloudKitResult r) {
        if (r == null) return "NULL,,";
        return r.getExtractor() + "," + r.getKind() + "," + r.getUrl();
    }

    private CloudKitSmokeDriver() {}
}
