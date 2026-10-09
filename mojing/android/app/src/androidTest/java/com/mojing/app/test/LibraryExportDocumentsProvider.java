package com.mojing.app.test;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

/** Test-only SAF fixture for UUID-owned library and chat export recovery checks. */
public final class LibraryExportDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.mojing.app.test.library.documents";
    public static final String ROOT_ID = "aex02-root";
    private static final String MODE_NORMAL = "normal";
    private static final String MODE_FAIL = "fail";
    private static final String MODE_HOLD = "hold";
    private static final String MODE_FAIL_WRITE = "fail-write";
    private static final Pattern OWNED_NAME = Pattern.compile(
            "^(mojing-sampling-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\.(json|txt|docx)|mojing-character-draft-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\.json|aex02-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\.json|aex0[34]-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\.(json|txt)|aex09-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\.zip|mojing-png-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\.png)$");

    private static boolean isPngDocument(String name) {
        return name != null && name.matches("^mojing-png-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\.png$");
    }

    private static final String[] ROOT_COLUMNS = new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_ICON,
    };
    private static final String[] DOCUMENT_COLUMNS = new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    private final Map<String, Fixture> fixtures = new ConcurrentHashMap<>();

    private static final class Fixture {
        final String name;
        volatile String mode = MODE_NORMAL;
        volatile long bytes;
        volatile boolean opened;
        volatile boolean closed;
        volatile boolean deleted;
        volatile boolean failureSent;
        volatile ParcelFileDescriptor returned;
        volatile ParcelFileDescriptor reader;
        volatile ParcelFileDescriptor writer;
        volatile Thread consumer;
        volatile int readOpens;
        volatile int readPrefix;
        volatile CountDownLatch release = new CountDownLatch(0);

        Fixture(String name) { this.name = name; }
    }

    private Context providerContext() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("provider context unavailable");
        return context;
    }

    private File root() {
        File root = new File(providerContext().getCacheDir(), "aex02-library-export-saf");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("cannot create provider root");
        return root;
    }

    @Override public boolean onCreate() { root(); return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor result = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        result.newRow()
                .add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(DocumentsContract.Root.COLUMN_TITLE, "资料导出验收")
                .add(DocumentsContract.Root.COLUMN_FLAGS,
                        DocumentsContract.Root.FLAG_LOCAL_ONLY | DocumentsContract.Root.FLAG_SUPPORTS_CREATE)
                .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "application/json\ntext/plain\napplication/zip\nimage/png\napplication/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_save);
        return result;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        return document(documentId, projection);
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        if (!ROOT_ID.equals(parentDocumentId)) throw new FileNotFoundException(parentDocumentId);
        MatrixCursor result = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        File[] files = root().listFiles();
        if (files != null) for (File file : files) if (file.isFile()) addRow(result, file, file.getName());
        return result;
    }

    @Override public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        if (isPngDocument(displayName)) android.util.Log.i("MoJingPngFixture", "CreateDocument MIME=" + mimeType);
        boolean samplingMime = displayName.startsWith("mojing-sampling-") &&
                ("application/octet-stream".equals(mimeType) || "*/*".equals(mimeType) ||
                (displayName.endsWith(".txt") ? "text/plain" : displayName.endsWith(".docx") ?
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : "application/json").equals(mimeType));
        boolean draftJsonMime = displayName.startsWith("mojing-character-draft-") &&
                ("application/json".equals(mimeType) || "application/octet-stream".equals(mimeType) || "*/*".equals(mimeType));
        boolean pngMime = isPngDocument(displayName) &&
                ("image/png".equals(mimeType) || "application/octet-stream".equals(mimeType) || "*/*".equals(mimeType));
        if (!ROOT_ID.equals(parentDocumentId) || !OWNED_NAME.matcher(displayName).matches() ||
                !(samplingMime || draftJsonMime || pngMime || (displayName.endsWith(".txt") ? "text/plain" : displayName.endsWith(".zip") ? "application/zip" : "application/json").equals(mimeType))) {
            throw new FileNotFoundException("expected owned UUID JSON/TXT document");
        }
        Fixture fixture = fixtures.computeIfAbsent(displayName, Fixture::new);
        File target = file(displayName);
        try {
            if (!target.createNewFile()) throw new FileNotFoundException("already exists: " + displayName);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        fixture.deleted = false;
        fixture.bytes = 0L;
        fixture.opened = false;
        fixture.closed = false;
        return displayName;
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!OWNED_NAME.matcher(documentId).matches()) throw new FileNotFoundException(documentId);
        Fixture fixture = fixtures.computeIfAbsent(documentId, Fixture::new);
        File target = file(documentId);
        if (!target.isFile()) throw new FileNotFoundException(documentId);
        if (mode.indexOf('w') < 0 && documentId.startsWith("aex04-")) {
            int ordinal = ++fixture.readOpens;
            if ("fail-first".equals(fixture.mode) || ("fail-second".equals(fixture.mode) && ordinal == 2)) {
                throw new FileNotFoundException("AEX04 forced open failure " + ordinal);
            }
            if (("hold-first".equals(fixture.mode) && ordinal == 1) ||
                    (("hold-second".equals(fixture.mode) || "fail-read-second".equals(fixture.mode)) && ordinal == 2)) {
                return openControlledReader(fixture, target);
            }
        }
        if (MODE_FAIL.equals(fixture.mode)) throw new FileNotFoundException("AEX02 forced open failure");
        if (MODE_FAIL_WRITE.equals(fixture.mode) && mode.indexOf('w') >= 0) return openFailingWriter(fixture, target);
        if (MODE_HOLD.equals(fixture.mode) && mode.indexOf('w') >= 0) {
            return openHeldWriter(fixture, target, signal);
        }
        try {
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode));
            fixture.opened = true;
            fixture.returned = descriptor;
            return descriptor;
        } catch (FileNotFoundException e) { throw e; }
    }

    private ParcelFileDescriptor openControlledReader(final Fixture fixture, final File target)
            throws FileNotFoundException {
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createReliablePipe(); }
        catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        fixture.reader = pipe[0];
        fixture.writer = pipe[1];
        fixture.opened = true;
        fixture.closed = false;
        fixture.release = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            try (FileInputStream input = new FileInputStream(target)) {
                FileOutputStream output = new FileOutputStream(fixture.writer.getFileDescriptor());
                byte[] buffer = new byte[8192];
                int remaining = fixture.readPrefix;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
                    if (count < 0) break;
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
                if (!fixture.release.await(45L, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IOException("AEX04 controlled read timeout");
                if ("fail-read-second".equals(fixture.mode)) {
                    fixture.writer.closeWithError("AEX04 forced stream failure");
                } else if (!fixture.deleted) {
                    int count;
                    while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
                }
            } catch (IOException ignored) {
                // Cancellation can close the reader before the remaining fixture is sent.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                closeQuietly(fixture.writer);
                fixture.closed = true;
            }
        }, "aex04-import-producer-" + fixture.name);
        fixture.consumer = producer;
        producer.setDaemon(true);
        producer.start();
        return pipe[0];
    }

    private ParcelFileDescriptor openFailingWriter(final Fixture fixture, final File target)
            throws FileNotFoundException {
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createReliablePipe(); }
        catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        fixture.reader = pipe[0]; fixture.writer = pipe[1]; fixture.opened = true;
        Thread consumer = new Thread(() -> {
            try (FileOutputStream output = new FileOutputStream(target, false)) {
                ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);
                byte[] prefix = new byte[8192];
                int count = input.read(prefix);
                if (count <= 0) throw new IOException("missing export prefix");
                output.write(prefix, 0, count); output.flush(); fixture.bytes = count;
                pipe[0].closeWithError("AEX09 forced output pipe failure"); fixture.failureSent = true;
            } catch (IOException ignored) {
            } finally { closeQuietly(pipe[0]); fixture.closed = true; }
        }, "aex09-export-consumer-" + fixture.name);
        fixture.consumer = consumer; consumer.setDaemon(true); consumer.start();
        return pipe[1];
    }

    private ParcelFileDescriptor openHeldWriter(final Fixture fixture, final File target, CancellationSignal signal)
            throws FileNotFoundException {
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createReliablePipe(); }
        catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        fixture.reader = pipe[0];
        fixture.writer = pipe[1];
        fixture.returned = pipe[1];
        fixture.opened = true;
        fixture.release = new CountDownLatch(1);
        if (signal != null) signal.setOnCancelListener(() -> {
            fixture.release.countDown();
            closeQuietly(fixture.reader);
            closeQuietly(fixture.writer);
        });
        Thread consumer = new Thread(() -> {
            try {
                fixture.release.await(60L, java.util.concurrent.TimeUnit.SECONDS);
                if (fixture.release.getCount() != 0L) throw new IOException("AEX02 hold timeout");
                try (ParcelFileDescriptor.AutoCloseInputStream input =
                             new ParcelFileDescriptor.AutoCloseInputStream(fixture.reader);
                     FileOutputStream output = new FileOutputStream(target, false)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    long total = 0L;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        output.write(buffer, 0, read);
                        total += read;
                    }
                    output.flush();
                    fixture.bytes = total;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The app may cancel/close the writer while the fixture is holding the export.
            } finally {
                closeQuietly(fixture.reader);
                closeQuietly(fixture.writer);
                fixture.closed = true;
            }
        }, "aex02-library-consumer-" + fixture.name);
        fixture.consumer = consumer;
        consumer.setDaemon(true);
        consumer.start();
        return pipe[1];
    }

    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        File target = file(documentId);
        if (!target.isFile() || !target.delete()) throw new FileNotFoundException(documentId);
        Fixture fixture = fixtures.get(documentId);
        if (fixture != null) fixture.deleted = true;
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        return ROOT_ID.equals(parentDocumentId) && OWNED_NAME.matcher(documentId).matches();
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("prepareRead".equals(method)) {
            if (arg == null || !(arg.startsWith("aex04-") || isPngDocument(arg)) || !OWNED_NAME.matcher(arg).matches())
                throw new IllegalArgumentException("expected AEX04 or mojing-png UUID document");
            String mode = extras == null ? null : extras.getString("mode");
            if (!java.util.Arrays.asList("normal", "fail-first", "fail-second", "hold-first", "hold-second", "fail-read-second").contains(mode) ||
                    (isPngDocument(arg) && !"normal".equals(mode)))
                throw new IllegalArgumentException("invalid read mode");
            byte[] bytes = extras.getByteArray("bytes");
            if (bytes == null || bytes.length > 512 * 1024) throw new IllegalArgumentException("fixture exceeds 512KiB");
            Fixture fixture = fixtures.computeIfAbsent(arg, Fixture::new);
            if (fixture.consumer != null && fixture.consumer.isAlive()) throw new IllegalStateException("previous stream still active");
            fixture.mode = mode;
            fixture.readOpens = 0;
            fixture.readPrefix = extras.getInt("prefix", 0);
            fixture.returned = null;
            fixture.closed = false;
            fixture.opened = false;
            fixture.deleted = false;
            try (FileOutputStream output = new FileOutputStream(file(arg), false)) { output.write(bytes); }
            catch (IOException e) { throw new IllegalStateException(e); }
            providerContext().getContentResolver().notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT_ID), null);
            return Bundle.EMPTY;
        }
        if ("prepareMode".equals(method)) {
            if (!OWNED_NAME.matcher(arg == null ? "" : arg).matches()) throw new IllegalArgumentException("expected owned UUID JSON/TXT document");
            String mode = extras == null ? null : extras.getString("mode");
            if (!MODE_NORMAL.equals(mode) && !MODE_FAIL.equals(mode) && !MODE_HOLD.equals(mode) &&
                    !(MODE_FAIL_WRITE.equals(mode) && arg.startsWith("aex09-"))) throw new IllegalArgumentException("invalid export mode");
            Fixture fixture = fixtures.computeIfAbsent(arg, Fixture::new);
            fixture.mode = mode;
            if (MODE_HOLD.equals(mode)) fixture.release = new CountDownLatch(1);
            Bundle result = new Bundle();
            result.putString("mode", mode);
            return result;
        }
        if ("status".equals(method)) {
            Fixture fixture = requireFixture(arg);
            String field = extras == null ? null : extras.getString("field");
            Bundle result = new Bundle();
            if (field == null || field.length() == 0) {
                result.putLong("bytes", fixture.bytes);
                result.putBoolean("opened", fixture.opened);
                result.putBoolean("closed", isClosed(fixture));
                result.putInt("readOpens", fixture.readOpens);
                result.putBoolean("failureSent", fixture.failureSent);
            } else if ("bytes".equals(field)) result.putLong("bytes", fixture.bytes);
            else if ("opened".equals(field)) result.putBoolean("opened", fixture.opened);
            else if ("closed".equals(field)) result.putBoolean("closed", isClosed(fixture));
            else throw new IllegalArgumentException("field must be bytes, opened, or closed");
            return result;
        }
        if ("release".equals(method)) {
            Fixture fixture = requireFixture(arg);
            fixture.release.countDown();
            return Bundle.EMPTY;
        }
        if ("read".equals(method)) {
            Fixture fixture = requireFixture(arg);
            File target = file(arg);
            if (!target.isFile()) throw new IllegalArgumentException("document missing");
            if (target.length() > 512L * 1024L) throw new IllegalArgumentException("read exceeds 512KiB control limit");
            try (FileInputStream input = new FileInputStream(target)) {
                byte[] bytes = new byte[(int) target.length()];
                int count = input.read(bytes);
                Bundle result = new Bundle();
                result.putByteArray("bytes", count <= 0 ? new byte[0] : java.util.Arrays.copyOf(bytes, count));
                return result;
            } catch (IOException e) { throw new IllegalStateException(e); }
        }
        if ("deleteOwned".equals(method)) {
            if (!OWNED_NAME.matcher(arg == null ? "" : arg).matches()) throw new IllegalArgumentException("expected owned UUID JSON/TXT document");
            Fixture fixture = fixtures.remove(arg);
            if (fixture != null) {
                fixture.deleted = true;
                fixture.release.countDown();
                closeQuietly(fixture.reader);
                closeQuietly(fixture.writer);
                Thread consumer = fixture.consumer;
                if (consumer != null && consumer != Thread.currentThread()) {
                    try { consumer.join(2_000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            }
            Bundle result = new Bundle();
            result.putBoolean("deleted", file(arg).isFile() && file(arg).delete());
            return result;
        }
        if ("revokeControl".equals(method)) {
            providerContext().revokeUriPermission("com.mojing.app", UriCompat.control(AUTHORITY),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return Bundle.EMPTY;
        }
        return super.call(method, arg, extras);
    }

    private Fixture requireFixture(String name) {
        if (!OWNED_NAME.matcher(name == null ? "" : name).matches()) throw new IllegalArgumentException("expected owned UUID JSON/TXT document");
        Fixture fixture = fixtures.get(name);
        if (fixture == null) throw new IllegalArgumentException("fixture not prepared: " + name);
        return fixture;
    }

    private boolean isClosed(Fixture fixture) {
        if (fixture.closed) return true;
        // A Binder-transferred PFD may already be invalid while its producer is still running.
        if (fixture.name.startsWith("aex04-")) return false;
        if (MODE_HOLD.equals(fixture.mode) || MODE_FAIL_WRITE.equals(fixture.mode)) return false;
        ParcelFileDescriptor descriptor = fixture.returned;
        if (descriptor == null) return false;
        try {
            boolean closed = !descriptor.getFileDescriptor().valid();
            if (closed) fixture.bytes = file(fixture.name).length();
            return closed;
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private Cursor document(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        if (ROOT_ID.equals(documentId)) addRow(result, root(), ROOT_ID);
        else addRow(result, file(documentId), documentId);
        return result;
    }

    private File file(String documentId) {
        if (ROOT_ID.equals(documentId)) return root();
        if (!OWNED_NAME.matcher(documentId == null ? "" : documentId).matches()) throw new IllegalArgumentException("invalid document id");
        File candidate = new File(root(), documentId);
        try {
            if (!candidate.getCanonicalFile().getParentFile().equals(root().getCanonicalFile())) throw new IllegalArgumentException("document escapes provider root");
        } catch (IOException e) { throw new IllegalArgumentException(e); }
        return candidate;
    }

    private void addRow(MatrixCursor result, File file, String documentId) {
        int flags = file.isDirectory()
                ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                : DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
        result.newRow()
                .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.isDirectory() ? "资料导出验收" : file.getName())
                .add(DocumentsContract.Document.COLUMN_MIME_TYPE, file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : file.getName().endsWith(".docx") ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : file.getName().endsWith(".txt") ? "text/plain" : file.getName().endsWith(".zip") ? "application/zip" : file.getName().endsWith(".png") ? "image/png" : "application/json")
                .add(DocumentsContract.Document.COLUMN_FLAGS, flags)
                .add(DocumentsContract.Document.COLUMN_SIZE, file.isFile() ? file.length() : null)
                .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try { descriptor.close(); } catch (IOException ignored) { }
    }

    private static final class UriCompat {
        static android.net.Uri control(String authority) { return android.net.Uri.parse("content://" + authority); }
    }
}
