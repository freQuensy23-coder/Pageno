package io.github.frequensy23.telegramprobe;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.*;

public class Probe extends Instrumentation {
    private Bundle args;
    private File out;
    private final AtomicReference<Activity> resumed = new AtomicReference<>();
    private static final String READER = "io.github.frequensy23.pageno/com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity";
    public void onCreate(Bundle arguments) { args = arguments; start(); }
    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor fd = getUiAutomation().executeShellCommand(command);
             InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            return bytes.toString("UTF-8");
        }
    }
    private void save(String name, String text) throws Exception {
        try (FileOutputStream f = new FileOutputStream(new File(out, name))) { f.write(text.getBytes("UTF-8")); }
    }
    private void shot(String name) throws Exception {
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Screenshot unavailable");
        try (FileOutputStream f = new FileOutputStream(new File(out, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        bitmap.recycle();
    }
    private boolean clickText(String text) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        if (root == null) return false;
        for (AccessibilityNodeInfo found : root.findAccessibilityNodeInfosByText(text)) {
            AccessibilityNodeInfo node = found;
            while (node != null) {
                if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                node = node.getParent();
            }
        }
        return false;
    }
    private Set<Integer> roots(String dump) {
        Set<Integer> ids = new HashSet<>();
        for (String block : dump.split("\\* Recent #\\d+:")) {
            if (!block.contains("mActivityComponent=" + READER)) continue;
            Matcher m = Pattern.compile("Task\\{[^}\\n]*#(\\d+)").matcher(block);
            if (m.find()) ids.add(Integer.parseInt(m.group(1)));
        }
        return ids;
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            boolean fixed = "fixed".equals(args.getString("mode"));
            Context target = getTargetContext();
            out = new File(target.getExternalFilesDir(null), "pageno-probe-" + args.getString("mode"));
            out.mkdirs();
            Application app = (Application) target.getApplicationContext();
            runOnMainSync(() -> app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                public void onActivityResumed(Activity a) { resumed.set(a); }
                public void onActivityCreated(Activity a, Bundle b) {}
                public void onActivityStarted(Activity a) {}
                public void onActivityPaused(Activity a) {}
                public void onActivityStopped(Activity a) {}
                public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                public void onActivityDestroyed(Activity a) {}
            }));
            Intent launch = new Intent(Intent.ACTION_MAIN).setClassName(target.getPackageName(), "org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            runOnMainSync(() -> target.startActivity(launch));
            for (int i = 0; i < 300 && resumed.get() == null; i++) Thread.sleep(100);
            Activity telegram = resumed.get();
            require(telegram != null, "Telegram Activity did not resume");
            int telegramTask = telegram.getTaskId();
            save("target.txt", target.getPackageName() + "\n" + telegram.getClass().getName() + "\ntask=" + telegramTask);
            File cache = new File(target.getFilesDir(), "cache"); cache.mkdirs();
            File file = new File(cache, "Telegram-Pageno-test.pdf");
            PdfDocument pdf = new PdfDocument();
            try {
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(400, 300, 1).create());
                Paint paint = new Paint(); paint.setTextSize(22);
                page.getCanvas().drawText("PDF opened by Telegram", 25, 140, paint);
                pdf.finishPage(page);
                try (FileOutputStream f = new FileOutputStream(file)) { pdf.writeTo(f); }
            } finally { pdf.close(); }
            Method opener = null;
            Class<?> utilities = target.getClassLoader().loadClass("org.telegram.messenger.AndroidUtilities");
            for (Method m : utilities.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("openForView") && p.length == 6 && p[0] == File.class && p[3] == Activity.class) opener = m;
            }
            require(opener != null, "Original Telegram openForView method unavailable");
            final Method method = opener;
            save("method.txt", method.toString());
            for (int count = 1; count <= (fixed ? 2 : 1); count++) {
                if (count > 1) {
                    shell("am start -n " + target.getPackageName() + "/org.telegram.ui.LaunchActivity");
                    Thread.sleep(1000);
                }
                AtomicReference<Throwable> failure = new AtomicReference<>();
                runOnMainSync(() -> {
                    try { require(Boolean.TRUE.equals(method.invoke(null, file, file.getName(), "application/pdf", telegram, null, false)), "Telegram rejected file"); }
                    catch (Throwable e) { failure.set(e); }
                });
                if (failure.get() != null) throw new RuntimeException(failure.get());
                Thread.sleep(1500);
                if (shell("dumpsys activity activities").contains("com.android.internal.app.ResolverActivity")) {
                    shot("chooser-" + count);
                    require(clickText("Pageno"), "Cannot select Pageno in Android resolver");
                    Thread.sleep(500);
                    require(clickText("Just once"), "Cannot confirm Pageno selection");
                }
                Thread.sleep(5000);
                String recents = shell("dumpsys activity recents");
                String activities = shell("dumpsys activity activities");
                save("recents-" + count + ".txt", recents);
                save("activities-" + count + ".txt", activities);
                Set<Integer> ids = roots(recents);
                if (fixed) {
                    require(ids.size() == count, "Expected " + count + " Pageno root tasks: " + ids);
                    require(!ids.contains(telegramTask), "Reader belongs to Telegram task");
                } else {
                    require(ids.isEmpty(), "Baseline unexpectedly has a Pageno root task");
                    require(Pattern.compile("ActivityRecord\\{[^\\n]*" + Pattern.quote(READER) + "[^\\n]* t" + telegramTask + "(?: |\\})").matcher(activities).find(), "Baseline did not reproduce reader in Telegram task");
                }
                shot("pdf-" + count);
                shell("input keyevent 187"); Thread.sleep(1000); shot("recents-" + count);
                shell("input keyevent 4"); Thread.sleep(500);
                shell("am start -n " + target.getPackageName() + "/org.telegram.ui.LaunchActivity");
                Thread.sleep(1000);
                String back = shell("dumpsys activity activities"); save("return-" + count + ".txt", back);
                require(resumed.get() != null && resumed.get().getTaskId() == telegramTask, "Telegram task was lost");
                shot("telegram-return-" + count);
            }
            result.putString("stream", "PASS " + args.getString("mode") + ": original Telegram openForView; task=" + telegramTask + "\n");
            save("result.txt", result.getString("stream"));
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            StringWriter stack = new StringWriter(); e.printStackTrace(new PrintWriter(stack));
            result.putString("stream", "FAIL " + stack);
            try { if (out != null) { save("failure.txt", stack.toString()); shot("failure"); } } catch (Throwable ignored) {}
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
