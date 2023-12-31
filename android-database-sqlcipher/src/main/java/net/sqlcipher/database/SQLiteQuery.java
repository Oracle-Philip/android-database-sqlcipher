/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.sqlcipher.database;
import net.sqlcipher.*;

import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteMisuseException;
import android.os.SystemClock;
import android.util.Log;

/**
 * A SQLite program that represents a query that reads the resulting rows into a CursorWindow.
 * This class is used by SQLiteCursor and isn't useful itself.
 *
 * SQLiteQuery is not internally synchronized so code using a SQLiteQuery from multiple
 * threads should perform its own synchronization when using the SQLiteQuery.
 */
public class SQLiteQuery extends SQLiteProgram {
    private static final String TAG = "Cursor";

    /** The index of the unbound OFFSET parameter */
    private int mOffsetIndex;

    /** Args to bind on requery */
    private String[] mBindArgs;
    private Object[] mObjectBindArgs;

    /**
     * Create a persistent query object.
     *
     * @param db The database that this query object is associated with
     * @param query The SQL string for this query.
     * @param offsetIndex The 1-based index to the OFFSET parameter,
     */
    /* package */ SQLiteQuery(SQLiteDatabase db, String query, int offsetIndex, String[] bindArgs) {
        super(db, query);

        mOffsetIndex = offsetIndex;
        mBindArgs = bindArgs;
    }

    SQLiteQuery(SQLiteDatabase db, String query, int offsetIndex, Object[] bindArgs) {
        super(db, query);
        mOffsetIndex = offsetIndex;
        mObjectBindArgs = bindArgs;
        int length = mObjectBindArgs != null ? mObjectBindArgs.length : 0;
        mBindArgs = new String[length];
    }

    /**
     * Reads rows into a buffer. This method acquires the database lock.
     *
     * @param window The window to fill into
     * @return number of total rows in the query
     */
    /* package */
    int fillWindow(CursorWindow window,
                   int maxRead, int lastPos) {
        long timeStart = SystemClock.uptimeMillis();
        mDatabase.lock();
        try {
            acquireReference();
            try {
                window.acquireReference();
                // if the start pos is not equal to 0, then most likely window is
                // too small for the data set, loading by another thread
                // is not safe in this situation. the native code will ignore maxRead
                int numRows = native_fill_window(window,
                                                 window.getStartPosition(),
                                                 window.getRequiredPosition(),
                                                 mOffsetIndex,
                                                 maxRead, lastPos);

                // Logging
                if (SQLiteDebug.DEBUG_SQL_STATEMENTS) {
                    Log.d(TAG, "fillWindow(): " + mSql);
                }
                return numRows;
            } catch (IllegalStateException e){
                // simply ignore it
                return 0;
            } catch (SQLiteDatabaseCorruptException e) {
                mDatabase.onCorruption();
                throw e;
            } finally {
                window.releaseReference();
            }
        } finally {
            releaseReference();
            mDatabase.unlock();
        }
    }

    /**
     * Get the column count for the statement. Only valid on query based
     * statements. The database must be locked
     * when calling this method.
     *
     * @return The number of column in the statement's result set.
     */
    /* package */ int columnCountLocked() {
        acquireReference();
        try {
            return native_column_count();
        } finally {
            releaseReference();
        }
    }

    /**
     * Retrieves the column name for the given column index. The database must be locked
     * when calling this method.
     *
     * @param columnIndex the index of the column to get the name for
     * @return The requested column's name
     */
    /* package */ String columnNameLocked(int columnIndex) {
        acquireReference();
        try {
            return native_column_name(columnIndex);
        } finally {
            releaseReference();
        }
    }

    @Override
    public String toString() {
        return "SQLiteQuery: " + mSql;
    }

    /**
     * Called by SQLiteCursor when it is requeried.
     */
    /* package */ void requery() {
        if (mBindArgs != null) {
            int len = mBindArgs.length;
            try {
                if(mObjectBindArgs != null) {
                    bindArguments(mObjectBindArgs);
                } else {
                    for (int i = 0; i < len; i++) {
                        super.bindString(i + 1, mBindArgs[i]);
                    }
                }
            } catch (SQLiteMisuseException e) {
                StringBuilder errMsg = new StringBuilder("mSql " + mSql);
                for (int i = 0; i < len; i++) {
                    errMsg.append(" ");
                    errMsg.append(mBindArgs[i]);
                }
                errMsg.append(" ");
                IllegalStateException leakProgram = new IllegalStateException(
                        errMsg.toString(), e);
                throw leakProgram;
            }
        }
    }

    @Override
    public void bindNull(int index) {
        mBindArgs[index - 1] = null;
        if (!mClosed) super.bindNull(index);
    }

    @Override
    public void bindLong(int index, long value) {
        mBindArgs[index - 1] = Long.toString(value);
        if (!mClosed) super.bindLong(index, value);
    }

    @Override
    public void bindDouble(int index, double value) {
        mBindArgs[index - 1] = Double.toString(value);
        if (!mClosed) super.bindDouble(index, value);
    }

    @Override
    public void bindString(int index, String value) {
        mBindArgs[index - 1] = value;
        if (!mClosed) super.bindString(index, value);
    }

    public void bindArguments(Object[] args){
        if(args != null && args.length > 0){
            for(int i = 0; i < args.length; i++){
                Object value = args[i];
                if(value == null){
                    bindNull(i + 1);
                } else if (value instanceof Double) {
                    bindDouble(i + 1, (Double)value);
                } else if (value instanceof Float) {
                    float number = ((Number)value).floatValue();
                    bindDouble(i + 1, Double.valueOf(number));
                } else if (value instanceof Long) {
                    bindLong(i + 1, (Long)value);
                } else if(value instanceof Integer) {
                    int number = ((Number) value).intValue();
                    bindLong(i + 1, Long.valueOf(number));
                } else if (value instanceof Boolean) {
                    bindLong(i + 1, (Boolean)value ? 1 : 0);
                } else if (value instanceof byte[]) {
                    bindBlob(i + 1, (byte[])value);
                } else {
                    bindString(i + 1, value.toString());
                }
            }
        }
    }

    private final native int native_fill_window(CursorWindow window,
                                                int startPos, int requiredPos,
                                                int offsetParam, int maxRead,
                                                int lastPos);

    private final native int native_column_count();

    private final native String native_column_name(int columnIndex);
}
