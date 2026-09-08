package com.freedomfighter.readersbooks.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.freedomfighter.readersbooks.App

/**
 * The shelf for the launcher's "book" tile (signature-protected): content://…/books lists
 * the books most recently opened first; content://…/books/<id> opened with ACTION_VIEW
 * shows that book at its current page.
 */
class BooksProvider : ContentProvider() {
    private val app get() = context!!.applicationContext as App
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        if (MATCHER.match(uri) != BOOKS) throw IllegalArgumentException("unknown uri $uri")
        return MatrixCursor(arrayOf("_id", "id", "title", "progress", "opened", "format")).apply {
            app.library.books.value.forEachIndexed { i, b -> addRow(arrayOf(i, b.id, b.title, b.progress, b.opened, b.format.name.lowercase())) }
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.readersbooks.book"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.freedomfighter.readersbooks"
        private const val BOOKS = 1
        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply { addURI(AUTHORITY, "books", BOOKS) }
    }
}
