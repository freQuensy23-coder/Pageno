package io.github.frequensy23.intentcaller;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;

public class PdfProvider extends ContentProvider {
    private File file;
    @Override public boolean onCreate() {
        file = new File(getContext().getCacheDir(), "caller-test.pdf");
        try (PdfDocument pdf = new PdfDocument()) {
            PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(300, 200, 1).create());
            Paint paint = new Paint();
            paint.setTextSize(20);
            page.getCanvas().drawText("Pageno caller test", 20, 100, paint);
            pdf.finishPage(page);
            try (FileOutputStream out = new FileOutputStream(file)) { pdf.writeTo(out); }
            return true;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        Log.i("PagenoCallerTest", "PDF_OPEN uid=" + android.os.Binder.getCallingUid());
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "application/pdf"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "caller-test.pdf";
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        cursor.addRow(row);
        return cursor;
    }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
