package com.mojing.app.test;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.content.res.AssetFileDescriptor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.net.Uri;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.regex.Pattern;

/** A real DocumentsProvider fixture for SAF acceptance; files are test-owned cache data. */
public final class MediaBundleDocumentsProvider extends DocumentsProvider {
    private static final Pattern RETRY_DOCUMENT = Pattern.compile("^aex06-[0-9a-f-]{36}\\.zip$");
    private static final java.util.concurrent.ConcurrentHashMap<String, RetryRead> retryReads = new java.util.concurrent.ConcurrentHashMap<>();
    private static final class RetryRead {
        final String mode;
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        volatile int opens, bytes;
        volatile boolean closed, failureSent;
        RetryRead(String mode) { this.mode = mode; }
    }
    public static final String AUTHORITY = "com.mojing.app.test.media.documents";
    public static final String ROOT_ID = "media-bundle";
    private static final String SLOW_PREFIX = "slow-";
    private static final Pattern SLOW_DOCUMENT = Pattern.compile(
            "^slow-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
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

    private File root() {
        File root = new File(providerContext().getCacheDir(), "media-bundle-saf");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("cannot create provider root");
        return root;
    }

    private android.content.Context providerContext() {
        android.content.Context context = getContext();
        if (context == null) throw new IllegalStateException("provider context unavailable");
        return context;
    }

    @Override public boolean onCreate() { root(); return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor result = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        MatrixCursor.RowBuilder row = result.newRow();
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID);
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID);
        row.add(DocumentsContract.Root.COLUMN_TITLE, "媒体包验收");
        row.add(DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_LOCAL_ONLY | DocumentsContract.Root.FLAG_SUPPORTS_CREATE);
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "application/zip\napplication/octet-stream");
        row.add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_save);
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

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File file = file(documentId);
        if (!file.isFile()) throw new FileNotFoundException(documentId);
        RetryRead retry = retryReads.get(documentId);
        if (retry != null && mode.startsWith("r")) {
            synchronized (retry) { retry.opens++; }
            if ("fail-open".equals(retry.mode)) { retry.closed = true; throw new FileNotFoundException("AEX06 forced open failure"); }
            if ("fail-read".equals(retry.mode)) return openRetryPipe(file, retry);
        }
        if (documentId.startsWith(SLOW_PREFIX)) return openSlowDocument(file, documentId, signal);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        if (!ROOT_ID.equals(parentDocumentId)) throw new FileNotFoundException(parentDocumentId);
        String safe = displayName.replaceAll("[^A-Za-z0-9._-]", "_");
        File target = new File(root(), safe);
        try {
            if (!target.createNewFile()) throw new FileNotFoundException("already exists: " + safe);
        } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        return safe;
    }

    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        File target = file(documentId);
        if (!target.isFile() || !target.delete()) throw new FileNotFoundException(documentId);
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        return ROOT_ID.equals(parentDocumentId) && file(documentId).getParentFile().equals(root());
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("prepareRead".equals(method)) {
            if (arg == null || !RETRY_DOCUMENT.matcher(arg).matches() || extras == null)
                throw new IllegalArgumentException("expected AEX06 UUID fixture");
            String mode = extras.getString("mode", "normal");
            if (!java.util.Arrays.asList("normal", "fail-open", "fail-read").contains(mode))
                throw new IllegalArgumentException("unknown retry mode");
            RetryRead old = retryReads.get(arg);
            if (old != null && old.opens > 0 && !old.closed && !"normal".equals(old.mode))
                throw new IllegalStateException("previous read active");
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(file(arg))) {
                output.write(java.util.Objects.requireNonNull(extras.getByteArray("bytes")));
            } catch (IOException e) { throw new IllegalStateException(e); }
            retryReads.put(arg, new RetryRead(mode));
            providerContext().getContentResolver().notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT_ID), null);
            return Bundle.EMPTY;
        }
        if ("readStatus".equals(method) || "releaseRead".equals(method)) {
            if (arg == null || !RETRY_DOCUMENT.matcher(arg).matches()) throw new IllegalArgumentException("expected AEX06 UUID fixture");
            RetryRead state = java.util.Objects.requireNonNull(retryReads.get(arg));
            if ("releaseRead".equals(method)) state.release.countDown();
            Bundle result = new Bundle();
            result.putInt("opens", state.opens); result.putInt("bytes", state.bytes);
            result.putBoolean("closed", state.closed); result.putBoolean("failureSent", state.failureSent);
            return result;
        }
        if ("prepare".equals(method)) {
            if (arg == null || extras == null) throw new IllegalArgumentException("missing fixture");
            File fixture = file(arg);
            try {
                if (!fixture.createNewFile()) throw new IllegalArgumentException("fixture already exists");
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(fixture)) {
                    output.write(extras.getByteArray("bytes"));
                }
            } catch (IOException e) { throw new IllegalStateException(e); }
            Uri uri = DocumentsContract.buildDocumentUri(AUTHORITY, arg);
            Bundle result = new Bundle();
            result.putParcelable("uri", uri);
            return result;
        }
        if ("revokeControl".equals(method)) {
            providerContext().revokeUriPermission("com.mojing.app", Uri.parse("content://" + AUTHORITY),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return Bundle.EMPTY;
        }
        if ("deleteOwned".equals(method)) {
            if (arg == null || !arg.matches("^(slow-|media-app-fixture-|ui-export-|aex06-)[0-9a-f-]{36}(\\.zip)?$"))
                throw new IllegalArgumentException("expected test-owned UUID document");
            File target = file(arg);
            try {
                if (!target.getCanonicalFile().getParentFile().equals(root().getCanonicalFile()))
                    throw new IllegalArgumentException("document escapes provider root");
            } catch (IOException e) { throw new IllegalArgumentException(e); }
            Bundle result = new Bundle();
            result.putBoolean("deleted", target.isFile() && target.delete());
            RetryRead state = retryReads.remove(arg);
            if (state != null) state.release.countDown();
            return result;
        }
        if ("cleanupSlowDocument".equals(method)) {
            if (arg == null || !SLOW_DOCUMENT.matcher(arg).matches())
                throw new IllegalArgumentException("slow document id must contain a UUID");
            File target = file(arg);
            try {
                if (!target.getCanonicalFile().getParentFile().equals(root().getCanonicalFile()))
                    throw new IllegalArgumentException("document escapes provider root");
            } catch (IOException e) { throw new IllegalArgumentException(e); }
            Bundle result = new Bundle();
            result.putBoolean("deleted", target.isFile() && target.delete());
            return result;
        }
        return super.call(method, arg, extras);
    }

    private ParcelFileDescriptor openRetryPipe(File fixture, RetryRead state) throws FileNotFoundException {
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createReliablePipe(); }
        catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        Thread producer = new Thread(() -> {
            // A partial private archive is copied before a real reliable-pipe error is sent.
            // Keep the read end owned by the receiver; do not close it in this process.
            try (java.io.FileInputStream input = new java.io.FileInputStream(fixture)) {
                byte[] prefix = new byte[(int)Math.max(1L, Math.min(8192L, fixture.length() / 2L))];
                int count = input.read(prefix);
                ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                output.write(prefix, 0, count); output.flush(); state.bytes = count;
                if (!state.release.await(30L, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("release timeout");
                state.failureSent = true;
                pipe[1].closeWithError("AEX06 forced reliable pipe failure");
            } catch (Exception e) {
                try { pipe[1].closeWithError("AEX06 fixture pipe aborted"); } catch (IOException ignored) { }
            } finally { state.closed = true; }
        }, "aex06-media-retry");
        producer.setDaemon(true); producer.start(); return pipe[0];
    }

    private ParcelFileDescriptor openSlowDocument(
            final File fixture,
            final String documentId,
            final CancellationSignal signal
    ) throws FileNotFoundException {
        if (!SLOW_DOCUMENT.matcher(documentId).matches()) throw new FileNotFoundException(documentId);
        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createReliablePipe();
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        final ParcelFileDescriptor reader = pipe[0];
        final ParcelFileDescriptor writer = pipe[1];
        if (signal != null) signal.setOnCancelListener(() -> {
            try { reader.close(); } catch (IOException ignored) { }
        });
        if (signal != null && signal.isCanceled()) {
            try { reader.close(); } catch (IOException ignored) { }
            try { writer.close(); } catch (IOException ignored) { }
            throw new FileNotFoundException("cancelled: " + documentId);
        }
        Thread producer = new Thread(() -> {
            try (RandomAccessFile input = new RandomAccessFile(fixture, "r");
                 ParcelFileDescriptor.AutoCloseOutputStream output =
                         new ParcelFileDescriptor.AutoCloseOutputStream(writer)) {
                byte[] buffer = new byte[1024];
                int chunks = 0;
                while (chunks < 101) {
                    int read = input.read(buffer);
                    if (read < 0) {
                        input.seek(0L);
                        read = input.read(buffer);
                    }
                    if (read <= 0) {
                        buffer[0] = 0;
                        read = 1;
                    }
                    output.write(buffer, 0, read);
                    output.flush();
                    chunks++;
                    Thread.sleep(50L);
                }
            } catch (IOException ignored) {
                // Reader cancellation closes the reliable pipe and ends the producer cleanly.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                try { reader.close(); } catch (IOException ignored) { }
            }
        }, "media-bundle-slow-" + documentId);
        producer.setDaemon(true);
        producer.start();
        return reader;
    }

    private Cursor document(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        if (ROOT_ID.equals(documentId)) addRow(result, root(), ROOT_ID);
        else addRow(result, file(documentId), documentId);
        return result;
    }

    private File file(String documentId) {
        File candidate = new File(root(), documentId);
        try {
            if (!candidate.getCanonicalFile().getParentFile().equals(root().getCanonicalFile()))
                throw new IllegalArgumentException("document escapes provider root");
        } catch (IOException e) { throw new IllegalArgumentException(e); }
        return candidate;
    }

    private void addRow(MatrixCursor result, File file, String documentId) {
        int flags = file.isDirectory() ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : 0;
        String mime = file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "application/zip";
        MatrixCursor.RowBuilder row = result.newRow();
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId);
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.isDirectory() ? "媒体包验收" : file.getName());
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mime);
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags);
        row.add(DocumentsContract.Document.COLUMN_SIZE, file.isFile() ? file.length() : null);
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }
}
