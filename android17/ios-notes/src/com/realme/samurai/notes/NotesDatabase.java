package com.realme.samurai.notes;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * SQLite-backed persistence layer (`NotesDatabase`) for iOS 26 Liquid Glass Notes.
 *
 * Pre-seeds `"Welcome to iOS 26 Liquid Glass OS"` & `"Quick Checklist"` starter notes and default
 * folders (`Personal`, `Work`, `Ideas`) on first launch.
 */
public final class NotesDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "samurai_ios26_notes.db";
    private static final int DB_VERSION = 1;

    public static final String FILTER_ALL = "All Notes";
    public static final String FILTER_PINNED = "Pinned";

    public static final class ChecklistItem {
        public String text;
        public boolean checked;

        public ChecklistItem(String text, boolean checked) {
            this.text = text != null ? text : "";
            this.checked = checked;
        }
    }

    public static final class NoteRecord {
        public long id = -1L;
        public String title = "";
        public String body = "";
        public List<ChecklistItem> checklist = new ArrayList<>();
        public String folder = "Personal";
        public boolean pinned = false;
        public long updatedAt = System.currentTimeMillis();
        public long createdAt = System.currentTimeMillis();

        public String buildPreviewSnippet() {
            String cleanBody = body != null ? body.trim().replace('\n', ' ') : "";
            if (!checklist.isEmpty()) {
                int done = 0;
                String firstUnchecked = null;
                for (ChecklistItem ci : checklist) {
                    if (ci.checked) {
                        done++;
                    } else if (firstUnchecked == null && !ci.text.trim().isEmpty()) {
                        firstUnchecked = ci.text.trim();
                    }
                }
                String prefix = "\u2611 " + done + "/" + checklist.size() + " done";
                if (firstUnchecked != null) {
                    return prefix + " \u00B7 " + firstUnchecked;
                }
                if (!cleanBody.isEmpty()) {
                    return prefix + " \u00B7 " + cleanBody;
                }
                return prefix;
            }
            return cleanBody.isEmpty() ? "No additional text" : cleanBody;
        }

        public String toShareablePlainText() {
            StringBuilder sb = new StringBuilder();
            if (!title.trim().isEmpty()) {
                sb.append(title.trim()).append("\n\n");
            }
            if (!checklist.isEmpty()) {
                for (ChecklistItem ci : checklist) {
                    sb.append(ci.checked ? "[\u2713] " : "[ ] ").append(ci.text).append('\n');
                }
                sb.append('\n');
            }
            if (!body.trim().isEmpty()) {
                sb.append(body.trim());
            }
            return sb.toString().trim();
        }
    }

    public NotesDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE folders ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT UNIQUE NOT NULL,"
                + "sort_order INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE TABLE notes ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL,"
                + "body TEXT NOT NULL,"
                + "checklist_json TEXT NOT NULL,"
                + "folder TEXT NOT NULL,"
                + "pinned INTEGER NOT NULL DEFAULT 0,"
                + "updated_at INTEGER NOT NULL,"
                + "created_at INTEGER NOT NULL)");

        seedStarterContent(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Schema v1
    }

    private void seedStarterContent(SQLiteDatabase db) {
        insertFolderRaw(db, "Personal", 1);
        insertFolderRaw(db, "Work", 2);
        insertFolderRaw(db, "Ideas", 3);

        long now = System.currentTimeMillis();

        List<ChecklistItem> welcomeCheck = new ArrayList<>();
        welcomeCheck.add(new ChecklistItem("Experience 90 Hz AGSL Liquid Glass optics", true));
        welcomeCheck.add(new ChecklistItem("Tune Refraction & Frost sliders in iOS Styler", true));
        welcomeCheck.add(new ChecklistItem("Try Scientific mode in Calculator", false));
        welcomeCheck.add(new ChecklistItem("Check live 10-day forecast in Weather", false));
        insertNoteRaw(
                db,
                "Welcome to iOS 26 Liquid Glass OS",
                "Crafted clean-room for RMX1931 with spherical meniscus refraction, 7-band ROYGCBP dispersion, and tactile AW8697 haptics.\n\n"
                        + "\u2022 Tap any golden circle above to complete checklist items\n"
                        + "\u2022 Filter notes by folder pills or search instantly below\n"
                        + "\u2022 Share notes anywhere from the floating formatting bar",
                serializeChecklist(welcomeCheck),
                "Personal",
                true,
                now);

        List<ChecklistItem> quickCheck = new ArrayList<>();
        quickCheck.add(new ChecklistItem("Review morning architecture notes", true));
        quickCheck.add(new ChecklistItem("Prepare sprint demo highlights", false));
        quickCheck.add(new ChecklistItem("Pick up espresso beans on the way home", false));
        insertNoteRaw(
                db,
                "Quick Checklist",
                "Daily priorities & errands:",
                serializeChecklist(quickCheck),
                "Work",
                false,
                now - 1_800_000L);

        insertNoteRaw(
                db,
                "Design & Reading Notes",
                "\u25B8 Spatial UI Principles\n"
                        + "\u2022 Optical surfaces should guide hierarchy through light, depth, and spring momentum.\n"
                        + "\u2022 Keep touch targets generous and haptics crisp.",
                "[]",
                "Ideas",
                false,
                now - 7_200_000L);
    }

    private static void insertFolderRaw(SQLiteDatabase db, String name, int order) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("sort_order", order);
        db.insertWithOnConflict("folders", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static void insertNoteRaw(
            SQLiteDatabase db,
            String title,
            String body,
            String checklistJson,
            String folder,
            boolean pinned,
            long ts) {
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        cv.put("body", body);
        cv.put("checklist_json", checklistJson);
        cv.put("folder", folder);
        cv.put("pinned", pinned ? 1 : 0);
        cv.put("updated_at", ts);
        cv.put("created_at", ts);
        db.insert("notes", null, cv);
    }

    public List<String> getCustomFolders() {
        List<String> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT name FROM folders ORDER BY sort_order ASC, id ASC", null)) {
            while (c.moveToNext()) {
                list.add(c.getString(0));
            }
        }
        if (list.isEmpty()) {
            list.add("Personal");
            list.add("Work");
            list.add("Ideas");
        }
        return list;
    }

    public boolean createFolder(String rawName) {
        if (rawName == null) return false;
        String clean = rawName.trim();
        if (clean.isEmpty() || FILTER_ALL.equalsIgnoreCase(clean) || FILTER_PINNED.equalsIgnoreCase(clean)) {
            return false;
        }
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("name", clean);
        cv.put("sort_order", 100);
        long id = db.insertWithOnConflict("folders", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        return id != -1L;
    }

    public List<NoteRecord> queryNotes(String folderFilter, String searchQuery) {
        List<NoteRecord> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        StringBuilder sql = new StringBuilder(
                "SELECT id, title, body, checklist_json, folder, pinned, updated_at, created_at FROM notes WHERE 1=1");
        List<String> args = new ArrayList<>();

        if (FILTER_PINNED.equals(folderFilter)) {
            sql.append(" AND pinned = 1");
        } else if (folderFilter != null && !folderFilter.isEmpty() && !FILTER_ALL.equals(folderFilter)) {
            sql.append(" AND folder = ?");
            args.add(folderFilter);
        }

        if (searchQuery != null && !searchQuery.trim().isEmpty()) {
            String like = "%" + searchQuery.trim() + "%";
            sql.append(" AND (title LIKE ? OR body LIKE ? OR checklist_json LIKE ? OR folder LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }

        sql.append(" ORDER BY pinned DESC, updated_at DESC");

        try (Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                NoteRecord rec = new NoteRecord();
                rec.id = c.getLong(0);
                rec.title = c.getString(1);
                rec.body = c.getString(2);
                rec.checklist = parseChecklist(c.getString(3));
                rec.folder = c.getString(4);
                rec.pinned = c.getInt(5) == 1;
                rec.updatedAt = c.getLong(6);
                rec.createdAt = c.getLong(7);
                out.add(rec);
            }
        }
        return out;
    }

    public NoteRecord getNoteById(long id) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id, title, body, checklist_json, folder, pinned, updated_at, created_at FROM notes WHERE id = ?",
                new String[] { String.valueOf(id) })) {
            if (c.moveToFirst()) {
                NoteRecord rec = new NoteRecord();
                rec.id = c.getLong(0);
                rec.title = c.getString(1);
                rec.body = c.getString(2);
                rec.checklist = parseChecklist(c.getString(3));
                rec.folder = c.getString(4);
                rec.pinned = c.getInt(5) == 1;
                rec.updatedAt = c.getLong(6);
                rec.createdAt = c.getLong(7);
                return rec;
            }
        }
        return null;
    }

    public long saveNote(NoteRecord note) {
        if (note == null) return -1L;
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        note.updatedAt = now;

        String effectiveTitle = note.title != null ? note.title.trim() : "";
        if (effectiveTitle.isEmpty()) {
            // Derive fallback title from body or checklist if untitled
            if (note.body != null && !note.body.trim().isEmpty()) {
                String firstLine = note.body.trim().split("\\n")[0].trim();
                effectiveTitle = firstLine.length() > 42 ? firstLine.substring(0, 42) + "\u2026" : firstLine;
            } else if (!note.checklist.isEmpty() && !note.checklist.get(0).text.trim().isEmpty()) {
                effectiveTitle = note.checklist.get(0).text.trim();
            } else {
                effectiveTitle = "New Note";
            }
            note.title = effectiveTitle;
        }

        ContentValues cv = new ContentValues();
        cv.put("title", note.title);
        cv.put("body", note.body != null ? note.body : "");
        cv.put("checklist_json", serializeChecklist(note.checklist));
        cv.put("folder", (note.folder != null && !note.folder.isEmpty()) ? note.folder : "Personal");
        cv.put("pinned", note.pinned ? 1 : 0);
        cv.put("updated_at", note.updatedAt);

        if (note.id > 0L) {
            db.update("notes", cv, "id = ?", new String[] { String.valueOf(note.id) });
            return note.id;
        } else {
            note.createdAt = now;
            cv.put("created_at", now);
            note.id = db.insert("notes", null, cv);
            return note.id;
        }
    }

    public void deleteNote(long id) {
        if (id <= 0L) return;
        SQLiteDatabase db = getWritableDatabase();
        db.delete("notes", "id = ?", new String[] { String.valueOf(id) });
    }

    public static String serializeChecklist(List<ChecklistItem> list) {
        if (list == null || list.isEmpty()) return "[]";
        JSONArray arr = new JSONArray();
        try {
            for (ChecklistItem item : list) {
                JSONObject o = new JSONObject();
                o.put("t", item.text != null ? item.text : "");
                o.put("c", item.checked);
                arr.put(o);
            }
        } catch (Throwable ignored) {
        }
        return arr.toString();
    }

    public static List<ChecklistItem> parseChecklist(String json) {
        List<ChecklistItem> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(new ChecklistItem(o.optString("t", ""), o.optBoolean("c", false)));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
