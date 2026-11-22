package com.programme.rotations;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Donne accès en lecture seule aux fichiers partagés (PDF du programme) à l'application
 * choisie (WhatsApp, Gmail…), uniquement pendant le partage. Aucun autre fichier n'est accessible.
 */
public class FilesProvider extends ContentProvider {
    public static final String AUTHORITY = "com.programme.rotations.fichiers";
    public static final String DIR = "partage";

    static File dir(android.content.Context ctx) {
        File d = new File(ctx.getCacheDir(), DIR);
        d.mkdirs();
        return d;
    }

    File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null) throw new FileNotFoundException();
        File f = new File(dir(getContext()), name);
        try {
            if (!f.getCanonicalPath().startsWith(dir(getContext()).getCanonicalPath() + File.separator) || !f.isFile())
                throw new FileNotFoundException(name);
        } catch (IOException e) {
            throw new FileNotFoundException(name);
        }
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String p = String.valueOf(uri.getLastPathSegment()).toLowerCase();
        if (p.endsWith(".pdf")) return "application/pdf";
        if (p.endsWith(".json")) return "application/json";
        return "text/plain";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
