package cn.inkledger.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

final class Ledger extends SQLiteOpenHelper {
    private static Ledger instance;

    static synchronized Ledger get(Context context) {
        if (instance == null) instance = new Ledger(context.getApplicationContext());
        return instance;
    }

    private Ledger(Context context) {
        super(context, "ink-ledger.db", null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("""
                CREATE TABLE expenses (
                    _id INTEGER PRIMARY KEY AUTOINCREMENT,
                    cents INTEGER NOT NULL CHECK(cents > 0),
                    merchant TEXT NOT NULL,
                    category TEXT NOT NULL,
                    source TEXT NOT NULL,
                    paid_at INTEGER NOT NULL
                )
                """);
        db.execSQL("CREATE INDEX expenses_by_date ON expenses(paid_at DESC)");
        db.execSQL("CREATE TABLE recognized (image_key TEXT PRIMARY KEY, order_key TEXT UNIQUE)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Missing database migration");
    }

    boolean insert(PaymentParser.Result expense, String imageKey, String orderKey) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues recognized = new ContentValues();
            recognized.put("image_key", imageKey);
            if (orderKey != null) recognized.put("order_key", orderKey);
            if (db.insertWithOnConflict("recognized", null, recognized, SQLiteDatabase.CONFLICT_IGNORE) == -1) {
                return false;
            }
            ContentValues values = new ContentValues();
            values.put("cents", expense.cents());
            values.put("merchant", expense.merchant());
            values.put("category", expense.category().name());
            values.put("source", expense.source());
            values.put("paid_at", expense.paidAt());
            db.insertOrThrow("expenses", null, values);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    List<Expense> month(YearMonth month) {
        ZoneId zone = ZoneId.systemDefault();
        long start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        List<Expense> expenses = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("expenses",
                new String[]{"_id", "cents", "merchant", "category", "source", "paid_at"},
                "paid_at >= ? AND paid_at < ?",
                new String[]{Long.toString(start), Long.toString(end)}, null, null, "paid_at DESC, _id DESC")) {
            while (cursor.moveToNext()) {
                Expense.Category category;
                try {
                    category = Expense.Category.valueOf(cursor.getString(3));
                } catch (IllegalArgumentException ignored) {
                    category = Expense.Category.UNKNOWN;
                }
                expenses.add(new Expense(cursor.getLong(0), cursor.getLong(1), cursor.getString(2),
                        category, cursor.getString(4), cursor.getLong(5)));
            }
        }
        return expenses;
    }

    void recategorize(long id, Expense.Category category) {
        ContentValues values = new ContentValues();
        values.put("category", category.name());
        getWritableDatabase().update("expenses", values, "_id = ?",
                new String[]{Long.toString(id)});
    }

    void delete(long id) {
        getWritableDatabase().delete("expenses", "_id = ?", new String[]{Long.toString(id)});
    }
}
