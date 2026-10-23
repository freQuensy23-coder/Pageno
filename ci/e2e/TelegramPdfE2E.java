package io.github.frequensy23.pageno.e2e;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end test that runs inside the official Telegram APK. The APK is only re-signed
 * so this instrumentation may attach; its bytecode is unchanged.
 *
 * Telegram's own AndroidUtilities.openForView is called from Telegram's own resumed
 * Activity. That is the code Telegram runs when a document is tapped in a chat: an implicit
 * ACTION_VIEW with a Telegram FileProvider URI, sent with startActivityForResult(intent, 500).
 * The test then reads the real window-manager state. It reports results in the
 * AndroidJUnitRunner status format, so `am instrument -r` and Firebase Test Lab both parse them.
 *
 * Every opened PDF must:
 *  - be shown by a Pageno reader that is the root of its own task, separate from Telegram's;
 *  - leave no Pageno activity inside Telegram's task;
 *  - show the document title in a Pageno window;
 *  - survive a return to Telegram from Recents and from the launcher icon. Telegram's
 *    LaunchActivity is singleTask, so a reader inside Telegram's task is destroyed;
 *  - come back to the front from Recents with the document still shown.
 */
public final class TelegramPdfE2E extends Instrumentation {
    private static final String TAG = "PagenoE2E";
    static final String PAGENO = "io.github.frequensy23.pageno";
    static final String READER = PAGENO + "/com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity";
    private static final long OPEN_TIMEOUT_MS = 30_000;
    private static final long SETTLE_TIMEOUT_MS = 12_000;

    private static final Pattern RECORD = Pattern.compile(
        "ActivityRecord\\{(\\S+) u\\d+ (\\S+) t(-?\\d+)( f)?\\}");
    private static final Pattern RESUMED = Pattern.compile(
        "(?m)^\\s*(?:m?ResumedActivity: |topResumedActivity=)ActivityRecord\\{(\\S+) u\\d+ (\\S+) t(-?\\d+)");
    private static final Pattern RECENT_TASK = Pattern.compile("\\* Recent #\\d+: Task\\{\\S+ #(\\d+)");
    private static final Pattern REAL_ACTIVITY = Pattern.compile("mActivityComponent=(\\S+)");

    private Bundle args;
    private Context target;
    private UiAutomation ui;
    private File out;
    private Activity telegram;
    private int telegramTask;
    private String telegramPackage;
    private final AtomicReference<Activity> telegramResumed = new AtomicReference<>();
    private final List<String> notes = new ArrayList<>();
    private final List<String> disabledHandlers = new ArrayList<>();
    private int step;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        args = arguments;
        start();
    }

    @Override
    public void onStart() {
        String[] scenarios = args.getString("scenario", "open_once").split(",");
        int failures = 0;
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < scenarios.length; i++) {
            String scenario = scenarios[i].trim();
            Bundle status = new Bundle();
            status.putString(REPORT_KEY_IDENTIFIER, "AndroidJUnitRunner");
            status.putString("class", getClass().getName());
            status.putString("test", scenario);
            status.putInt("numtests", scenarios.length);
            status.putInt("current", i + 1);
            sendStatus(1, new Bundle(status));
            try {
                runScenario(scenario);
                status.putString("stream", "PASS " + scenario + "\n" + joinNotes());
                sendStatus(0, status);
                summary.append("E2E_RESULT ").append(scenario).append(" PASS\n");
            } catch (Skip skip) {
                status.putString("stack", skip.getMessage());
                status.putString("stream", "SKIP " + scenario + ": " + skip.getMessage() + "\n");
                sendStatus(-4, status);
                summary.append("E2E_RESULT ").append(scenario).append(" SKIP ").append(skip.getMessage()).append('\n');
            } catch (Throwable error) {
                failures++;
                String stack = stackTrace(error);
                try { evidence("failure"); } catch (Throwable ignored) { }
                try { save("failure.txt", stack + "\n\n" + joinNotes()); } catch (Throwable ignored) { }
                status.putString("stack", stack);
                status.putString("stream", "FAIL " + scenario + "\n" + stack + "\n" + joinNotes());
                sendStatus(-2, status);
                summary.append("E2E_RESULT ").append(scenario).append(" FAIL ")
                    .append(String.valueOf(error.getMessage()).replace('\n', ' ')).append('\n');
            } finally {
                restoreHandlers();
                removeTelegramTasks();
            }
        }
        Log.i(TAG, summary.toString());
        Bundle result = new Bundle();
        result.putString("stream", summary + (failures == 0 ? "OK\n" : "FAILURES: " + failures + "\n"));
        finish(Activity.RESULT_OK, result);
    }

    private void runScenario(String scenario) throws Exception {
        setUp(scenario);
        switch (scenario) {
            case "open_once": onlyPagenoHandlesPdf(); openOnce(null); break;
            case "open_twice": onlyPagenoHandlesPdf(); openTwice(); break;
            case "chooser_just_once": chooserWithPageno(); openOnce("Just once"); break;
            default: throw new IllegalArgumentException("Unknown scenario " + scenario);
        }
    }

    // PDF handler setup, done through the shell so the test also runs where no host script does.

    private List<String> pdfHandlers() throws Exception {
        String query = shell("cmd package query-activities --brief -a android.intent.action.VIEW -t application/pdf -d content://"
            + telegramPackage + ".provider/media/test.pdf");
        List<String> packages = new ArrayList<>();
        for (String line : query.split("\n")) {
            line = line.trim();
            int slash = line.indexOf('/');
            if (slash > 0 && !packages.contains(line.substring(0, slash))) packages.add(line.substring(0, slash));
        }
        return packages;
    }

    /** Pageno as the only PDF viewer, as after choosing "Always" for it. */
    private void onlyPagenoHandlesPdf() throws Exception {
        for (String pkg : pdfHandlers()) {
            if (pkg.equals(PAGENO)) continue;
            note("disable other PDF viewer " + pkg + ": " + shell("pm disable-user --user 0 " + pkg).trim());
            disabledHandlers.add(pkg);
        }
        List<String> handlers = pdfHandlers();
        require(handlers.size() == 1 && handlers.get(0).equals(PAGENO),
            "PRECONDITION: Pageno must be the only PDF viewer, found " + handlers);
    }

    /** Several PDF viewers, so Android asks which one to use. */
    private void chooserWithPageno() throws Exception {
        List<String> handlers = pdfHandlers();
        note("PDF viewers: " + handlers);
        require(handlers.contains(PAGENO), "PRECONDITION: Pageno does not handle PDFs: " + handlers);
        if (handlers.size() < 2) throw new Skip("no other PDF viewer is installed, so Android shows no chooser");
    }

    private void restoreHandlers() {
        for (String pkg : disabledHandlers) {
            try { shell("pm enable --user 0 " + pkg); } catch (Throwable ignored) { }
        }
        disabledHandlers.clear();
    }

    // A single document opened from Telegram, then Telegram <-> PDF switching.
    private void openOnce(String chooserButton) throws Exception {
        Opened reader = openFromTelegram("Telegram-e2e-once", chooserButton);
        List<String> problems = new ArrayList<>(placementProblems(reader));
        if (!problems.isEmpty()) fail(problems);

        returnToTelegramFromRecents();
        problems.addAll(telegramShownAndReaderAlive(reader, "Recents"));
        if (problems.isEmpty()) {
            bringReaderToFront(reader);
            problems.addAll(readerShown(reader, "switching back to the PDF from Recents"));
        }
        if (problems.isEmpty()) {
            returnToTelegramFromLauncher();
            problems.addAll(telegramShownAndReaderAlive(reader, "launcher icon"));
        }
        evidence("end");
        if (!problems.isEmpty()) fail(problems);
    }

    // Two documents opened one after another from the same Telegram task.
    private void openTwice() throws Exception {
        Opened first = openFromTelegram("Telegram-e2e-first", null);
        List<String> problems = new ArrayList<>(placementProblems(first));
        if (!problems.isEmpty()) fail(problems);
        returnToTelegramFromRecents();
        problems.addAll(telegramShownAndReaderAlive(first, "Recents"));
        if (!problems.isEmpty()) fail(problems);

        Opened second = openFromTelegram("Telegram-e2e-second", null);
        problems.addAll(placementProblems(second));
        if (second.task == first.task) {
            problems.add("SECOND_PDF_REUSED_FIRST_TASK: both PDFs share task #" + first.task);
        }
        evidence("end");
        if (!problems.isEmpty()) fail(problems);
    }

    private void setUp(String scenario) throws Exception {
        target = getTargetContext();
        telegramPackage = target.getPackageName();
        ui = getUiAutomation();
        AccessibilityServiceInfo info = ui.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        ui.setServiceInfo(info);
        out = new File(target.getExternalFilesDir(null), "pageno-e2e/" + scenario);
        out.mkdirs();
        notes.clear();
        step = 0;

        Application app = (Application) target.getApplicationContext();
        runOnMainSync(() -> app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity a) { telegramResumed.set(a); }
            public void onActivityPaused(Activity a) { telegramResumed.compareAndSet(a, null); }
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityStarted(Activity a) { }
            public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        }));
        removeTelegramTasks();
        // Start Telegram the way the launcher does, so its task has the usual base intent.
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n "
            + launcherComponent().flattenToShortString());
        for (int i = 0; i < 300 && telegramResumed.get() == null; i++) Thread.sleep(100);
        telegram = telegramResumed.get();
        require(telegram != null, "Telegram did not resume");
        telegramTask = telegram.getTaskId();
        note("device=" + Build.MANUFACTURER + " " + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT
            + " telegram=" + telegramPackage + " activity=" + telegram.getClass().getName()
            + " task=" + telegramTask);
    }

    /** Asks Telegram to open a PDF exactly as it does for a document tapped in a chat. */
    private Opened openFromTelegram(String name, String chooserButton) throws Exception {
        File file = createPdf(name);
        WmState before = WmState.read(this);
        Method opener = null;
        Class<?> utilities = target.getClassLoader().loadClass("org.telegram.messenger.AndroidUtilities");
        for (Method m : utilities.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("openForView") && p.length == 6 && p[0] == File.class && p[3] == Activity.class) {
                opener = m;
            }
        }
        require(opener != null, "Telegram's AndroidUtilities.openForView(File, ...) was not found");
        final Method method = opener;
        final Activity from = telegramResumed.get() != null ? telegramResumed.get() : telegram;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Object> returned = new AtomicReference<>();
        runOnMainSync(() -> {
            try { returned.set(method.invoke(null, file, file.getName(), "application/pdf", from, null, false)); }
            catch (Throwable e) { failure.set(e); }
        });
        if (failure.get() != null) throw new RuntimeException("Telegram openForView threw", failure.get());
        require(Boolean.TRUE.equals(returned.get()), "Telegram openForView returned " + returned.get());
        note("opened " + file + " via " + method);

        long deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS;
        boolean chooserHandled = false;
        WmState state;
        while (true) {
            state = WmState.read(this);
            Rec resumed = state.resumed;
            if (resumed != null && resumed.component.equals(READER) && !before.records.containsKey(resumed.id)) break;
            if (chooserVisible()) {
                if (chooserButton == null) {
                    evidence("unexpected-chooser");
                    throw new AssertionError("PRECONDITION: Android showed an app chooser; "
                        + "this scenario needs Pageno as the only PDF handler");
                }
                if (!chooserHandled) {
                    evidence("chooser");
                    require(clickTextWithin("Pageno", 10_000), "Cannot select Pageno in the app chooser");
                    require(clickTextWithin(chooserButton, 10_000), "Cannot press \"" + chooserButton + "\" in the app chooser");
                    chooserHandled = true;
                }
            }
            if (System.currentTimeMillis() > deadline) {
                evidence("no-reader");
                throw new AssertionError("NO_READER: no new Pageno reader resumed within "
                    + OPEN_TIMEOUT_MS / 1000 + " s; resumed=" + resumed + "\n" + state.describeTasks());
            }
            Thread.sleep(400);
        }
        if (chooserButton != null) require(chooserHandled, "PRECONDITION: the app chooser never appeared");
        // Judge the settled state. A reader may first appear in Telegram's task and move itself
        // out; only the state a user is left with counts.
        long firstSeen = System.currentTimeMillis();
        long settleDeadline = firstSeen + SETTLE_TIMEOUT_MS;
        while (System.currentTimeMillis() < settleDeadline) {
            Thread.sleep(500);
            WmState next = WmState.read(this);
            if (next.resumed == null || !next.resumed.component.equals(READER)
                || before.records.containsKey(next.resumed.id)) continue;
            state = next;
            if (state.resumed.task != telegramTask && state.inTask(telegramTask, PAGENO + "/").isEmpty()
                && System.currentTimeMillis() - firstSeen >= 2000) break;
        }
        Opened opened = new Opened(state.resumed.id, state.resumed.task, name);
        opened.state = state;
        note("reader " + opened.id + " task=" + opened.task + " telegramTask=" + telegramTask
            + " settled after " + (System.currentTimeMillis() - firstSeen) + " ms");
        evidence("opened-" + name);
        return opened;
    }

    private List<String> placementProblems(Opened reader) throws Exception {
        List<String> problems = new ArrayList<>();
        WmState state = reader.state;
        if (reader.task == telegramTask) {
            problems.add("READER_IN_TELEGRAM_TASK: the PDF is shown inside Telegram's task #" + telegramTask
                + " instead of a separate Pageno window");
        }
        List<Rec> inTelegram = state.inTask(telegramTask, PAGENO + "/");
        if (!inTelegram.isEmpty()) {
            problems.add("PAGENO_ACTIVITY_IN_TELEGRAM_TASK: " + inTelegram);
        }
        String root = state.realActivity.get(reader.task);
        if (root == null || !root.equals(READER)) {
            problems.add("READER_NOT_TASK_ROOT: task #" + reader.task + " root is " + root
                + " (Recents shows this window as " + root + ")");
        }
        if (!waitForText(reader.title, 20_000)) {
            problems.add("PDF_NOT_DISPLAYED: no Pageno window shows \"" + reader.title + "\"");
        }
        if (!problems.isEmpty()) problems.add("TASKS:\n" + state.describeTasks());
        return problems;
    }

    private List<String> telegramShownAndReaderAlive(Opened reader, String how) throws Exception {
        List<String> problems = new ArrayList<>();
        WmState state = waitForResumedPackage(telegramPackage, 8_000);
        Rec resumed = state.resumed;
        if (resumed == null || !resumed.component.startsWith(telegramPackage + "/")) {
            problems.add("TELEGRAM_NOT_SHOWN_FROM_" + how.toUpperCase().replace(' ', '_')
                + ": returning to Telegram shows " + resumed);
        }
        Rec record = state.records.get(reader.id);
        if (record == null || record.finishing || record.task < 0) {
            problems.add("PDF_CLOSED_BY_RETURN_TO_TELEGRAM_FROM_" + how.toUpperCase().replace(' ', '_')
                + ": reader " + reader.id + " is " + record);
        }
        evidence("telegram-from-" + how.replace(' ', '-'));
        if (!problems.isEmpty()) problems.add("TASKS:\n" + state.describeTasks());
        return problems;
    }

    private List<String> readerShown(Opened reader, String how) throws Exception {
        List<String> problems = new ArrayList<>();
        WmState state = waitForResumedPackage(PAGENO, 8_000);
        if (state.resumed == null || !state.resumed.id.equals(reader.id)) {
            problems.add("READER_NOT_RESTORED: after " + how + " the resumed activity is " + state.resumed);
        }
        if (!waitForText(reader.title, 10_000)) {
            problems.add("PDF_NOT_DISPLAYED_AFTER_SWITCH: \"" + reader.title + "\" is not shown after " + how);
        }
        evidence("reader-again");
        return problems;
    }

    /** Equivalent of tapping Telegram's card in Recents. */
    private void returnToTelegramFromRecents() throws Exception {
        moveTaskToFront(telegramTask);
    }

    /** Equivalent of tapping Telegram's launcher icon. */
    private void returnToTelegramFromLauncher() throws Exception {
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n "
            + launcherComponent().flattenToShortString());
        note("started Telegram from its launcher component");
    }

    /** Equivalent of tapping the reader's card in Recents. */
    private void bringReaderToFront(Opened reader) throws Exception {
        moveTaskToFront(reader.task);
    }

    private void moveTaskToFront(int task) {
        ui.adoptShellPermissionIdentity("android.permission.REORDER_TASKS");
        try {
            target.getSystemService(ActivityManager.class).moveTaskToFront(task, 0);
        } finally {
            ui.dropShellPermissionIdentity();
        }
        note("moved task #" + task + " to front");
    }

    /** Removes Telegram's tasks, including foreign activities left in them (e.g. a chooser). */
    private void removeTelegramTasks() {
        if (target == null) return;
        for (ActivityManager.AppTask task : target.getSystemService(ActivityManager.class).getAppTasks()) {
            try { task.finishAndRemoveTask(); } catch (Throwable ignored) { }
        }
    }

    private ComponentName launcherComponent() {
        Intent launch = target.getPackageManager().getLaunchIntentForPackage(telegramPackage);
        require(launch != null && launch.getComponent() != null, "Telegram has no launcher activity");
        return launch.getComponent();
    }

    private WmState waitForResumedPackage(String pkg, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        WmState state;
        do {
            state = WmState.read(this);
            if (state.resumed != null && state.resumed.component.startsWith(pkg + "/")) return state;
            Thread.sleep(300);
        } while (System.currentTimeMillis() < deadline);
        return state;
    }

    private File createPdf(String name) throws Exception {
        File dir = null;
        try {
            Class<?> loader = target.getClassLoader().loadClass("org.telegram.messenger.FileLoader");
            int documents = loader.getField("MEDIA_DIR_DOCUMENT").getInt(null);
            dir = (File) loader.getMethod("getDirectory", int.class).invoke(null, documents);
        } catch (Throwable e) {
            note("Telegram documents directory unavailable: " + e);
        }
        if (dir == null || !(dir.isDirectory() || dir.mkdirs())) dir = new File(target.getFilesDir(), "cache");
        dir.mkdirs();
        File file = new File(dir, name + ".pdf");
        PdfDocument pdf = new PdfDocument();
        try {
            PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(420, 300, 1).create());
            Paint paint = new Paint();
            paint.setTextSize(24);
            page.getCanvas().drawText("Opened from Telegram: " + name, 20, 150, paint);
            pdf.finishPage(page);
            try (FileOutputStream stream = new FileOutputStream(file)) { pdf.writeTo(stream); }
        } finally {
            pdf.close();
        }
        return file;
    }

    // Accessibility helpers.

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        for (AccessibilityWindowInfo window : ui.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null) roots.add(root);
        }
        AccessibilityNodeInfo active = ui.getRootInActiveWindow();
        if (active != null) roots.add(active);
        return roots;
    }

    private boolean chooserVisible() {
        for (AccessibilityNodeInfo root : roots()) {
            CharSequence pkg = root.getPackageName();
            if (pkg == null || pkg.toString().equals(PAGENO) || pkg.toString().equals(telegramPackage)) continue;
            if (!root.findAccessibilityNodeInfosByText("Pageno").isEmpty()) return true;
        }
        return false;
    }

    private boolean clickText(String text) {
        for (AccessibilityNodeInfo root : roots()) {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                for (AccessibilityNodeInfo n = node; n != null; n = n.getParent()) {
                    if (n.isClickable() && n.isEnabled() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        note("clicked \"" + text + "\" in " + root.getPackageName());
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean clickTextWithin(String text, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            // The chooser rebuilds its list while it loads, so nodes can go stale between tries.
            if (clickText(text)) return true;
            Thread.sleep(300);
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    private boolean waitForText(String text, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            for (AccessibilityNodeInfo root : roots()) {
                if (root.getPackageName() != null && root.getPackageName().toString().equals(PAGENO)
                    && !root.findAccessibilityNodeInfosByText(text).isEmpty()) return true;
            }
            Thread.sleep(300);
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    private String dumpWindows() {
        StringBuilder text = new StringBuilder();
        for (AccessibilityWindowInfo window : ui.getWindows()) {
            text.append("WINDOW ").append(window).append('\n');
            dumpNode(window.getRoot(), "  ", text);
        }
        return text.toString();
    }

    private static void dumpNode(AccessibilityNodeInfo node, String indent, StringBuilder text) {
        if (node == null) return;
        text.append(indent).append(node.getPackageName()).append(' ').append(node.getClassName())
            .append(" text=").append(node.getText()).append(" desc=").append(node.getContentDescription())
            .append(" id=").append(node.getViewIdResourceName())
            .append(node.isClickable() ? " clickable" : "").append('\n');
        for (int i = 0; i < node.getChildCount(); i++) dumpNode(node.getChild(i), indent + "  ", text);
    }

    // Evidence and plumbing.

    private void evidence(String label) throws Exception {
        String prefix = String.format("%02d-%s", ++step, label);
        save(prefix + "-activities.txt", shell("dumpsys activity activities"));
        save(prefix + "-recents.txt", shell("dumpsys activity recents"));
        save(prefix + "-windows.txt", dumpWindows());
        Bitmap bitmap = ui.takeScreenshot();
        if (bitmap != null) {
            try (FileOutputStream f = new FileOutputStream(new File(out, prefix + ".png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, f);
            }
            bitmap.recycle();
        }
    }

    String shell(String command) throws Exception {
        try (ParcelFileDescriptor fd = getUiAutomation().executeShellCommand(command);
             InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            return bytes.toString("UTF-8");
        }
    }

    private void save(String name, String text) throws Exception {
        try (FileOutputStream f = new FileOutputStream(new File(out, name))) { f.write(text.getBytes("UTF-8")); }
    }

    private void note(String text) {
        Log.i(TAG, text);
        notes.add(text);
    }

    private String joinNotes() {
        StringBuilder text = new StringBuilder();
        for (String note : notes) text.append("  ").append(note).append('\n');
        return text.toString();
    }

    private static void fail(List<String> problems) {
        StringBuilder text = new StringBuilder();
        for (String problem : problems) text.append(problem).append('\n');
        throw new AssertionError(text.toString().trim());
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static String stackTrace(Throwable error) {
        StringWriter text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private static final class Skip extends RuntimeException {
        Skip(String message) { super(message); }
    }

    private static final class Opened {
        final String id;
        final int task;
        final String title;
        WmState state;
        Opened(String id, int task, String title) { this.id = id; this.task = task; this.title = title; }
    }

    static final class Rec {
        final String id;
        final String component;
        final int task;
        final boolean finishing;
        Rec(String id, String component, int task, boolean finishing) {
            this.id = id; this.component = component; this.task = task; this.finishing = finishing;
        }
        @Override public String toString() {
            return component + " #" + id + " t" + task + (finishing ? " finishing" : "");
        }
    }

    /** Activity records and tasks parsed from dumpsys; every record names its task (tNN). */
    static final class WmState {
        final Map<String, Rec> records = new LinkedHashMap<>();
        final Map<Integer, String> realActivity = new HashMap<>();
        Rec resumed;

        static WmState read(TelegramPdfE2E test) throws Exception {
            WmState state = new WmState();
            String activities = test.shell("dumpsys activity activities");
            Matcher m = RECORD.matcher(activities);
            while (m.find()) {
                // A record can be printed several times; it is finishing if any print says so.
                Rec old = state.records.get(m.group(1));
                int task = Integer.parseInt(m.group(3));
                if (old != null && task < 0) task = old.task;
                boolean finishing = m.group(4) != null || (old != null && old.finishing);
                state.records.put(m.group(1), new Rec(m.group(1), m.group(2), task, finishing));
            }
            Matcher resumed = RESUMED.matcher(activities);
            if (resumed.find()) state.resumed = state.records.get(resumed.group(1));
            String recents = test.shell("dumpsys activity recents");
            for (String block : recents.split("(?=\\* Recent #)")) {
                Matcher id = RECENT_TASK.matcher(block);
                Matcher real = REAL_ACTIVITY.matcher(block);
                if (id.find() && real.find()) state.realActivity.put(Integer.parseInt(id.group(1)), real.group(1));
            }
            return state;
        }

        List<Rec> inTask(int task, String componentPrefix) {
            List<Rec> found = new ArrayList<>();
            for (Rec rec : records.values()) {
                if (rec.task == task && !rec.finishing && rec.component.startsWith(componentPrefix)) found.add(rec);
            }
            return found;
        }

        String describeTasks() {
            Map<Integer, List<Rec>> tasks = new LinkedHashMap<>();
            for (Rec rec : records.values()) {
                if (rec.task < 0) continue;
                List<Rec> list = tasks.get(rec.task);
                if (list == null) tasks.put(rec.task, list = new ArrayList<>());
                list.add(rec);
            }
            StringBuilder text = new StringBuilder("  resumed=" + resumed + "\n");
            for (Map.Entry<Integer, List<Rec>> task : tasks.entrySet()) {
                text.append("  task #").append(task.getKey()).append(" root=")
                    .append(realActivity.get(task.getKey())).append(": ").append(task.getValue()).append('\n');
            }
            return text.toString();
        }
    }
}
