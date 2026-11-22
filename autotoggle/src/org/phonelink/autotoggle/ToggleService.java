package org.phonelink.autotoggle;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks DokeOS System Manager into {@code App Management -> Auto Start
 * Management} and forces each requested row through off and back on, which is
 * what makes the vendor re-register it against the installed package.
 */
public final class ToggleService extends AccessibilityService {

    static final String TAG = "ATOGGLE";
    static final String ACTION_TOGGLE = "org.phonelink.autotoggle.TOGGLE";
    static final String MANAGER = "com.blackview.systemmanager";
    static final String MANAGER_ACTIVITY = "com.blackview.systemmanager.MainActivity";
    static final String ACTION_LIST = "org.phonelink.autotoggle.LIST";
    static final String AUTO_START_ACTIVITY =
            "com.freeme.freemanager.autoboot.AutoStartMainActivity";
    /** Rows on this phone run to a few hundred, so allow a long scan. */
    private static final int MAX_PAGES = 40;
    /** Rows keep their order, so a page is stable enough to be worth saving. */
    private static final long PAGE_SETTLE = 450;

    /** Which page the list is showing, or -1 when unknown (freshly opened). */
    private int page = -1;

    private android.content.SharedPreferences pages() {
        return getSharedPreferences("pages", MODE_PRIVATE);
    }

    private static String pageKey(String label) {
        return label.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void rememberPage(String label, int index) {
        if (index >= 0) {
            pages().edit().putInt(pageKey(label), index).apply();
        }
    }

    private int savedPage(String label) {
        return pages().getInt(pageKey(label), -1);
    }

    /** Scroll the stored number of pages instead of scanning from the top. */
    private void moveToPage(int target) throws InterruptedException {
        while (page >= 0 && page < target) {
            if (!scrollForward()) {
                return;
            }
            sleep(PAGE_SETTLE);
        }
        while (page > target) {
            if (!scrollBackward()) {
                return;
            }
            sleep(PAGE_SETTLE);
        }
    }

    private static ToggleService instance;
    private static volatile String[] pending;

    static void requestList() {
        ToggleService running = instance;
        if (running == null) {
            Log.i(TAG, "service not connected; cannot list");
            return;
        }
        running.listAll();
    }

    static void request(String[] labels) {
        ToggleService running = instance;
        if (running == null) {
            pending = labels;
            Log.i(TAG, "service not connected yet; queued " + labels.length + " labels");
            return;
        }
        running.toggleAll(labels);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        Log.i(TAG, "service connected");
        String[] queued = pending;
        if (queued != null) {
            pending = null;
            toggleAll(queued);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Polling, not event driven: the vendor list redraws without telling us.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    private void toggleAll(final String[] labels) {
        final long begin = SystemClock.elapsedRealtime();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    flow(labels);
                } catch (Throwable t) {
                    Log.i(TAG, "flow=" + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
                Log.i(TAG, "done in " + (SystemClock.elapsedRealtime() - begin) + " ms");
            }
        }, "autotoggle").start();
    }

    private void listAll() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(new Intent(Intent.ACTION_MAIN)
                            .setComponent(new ComponentName(MANAGER, AUTO_START_ACTIVITY))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    sleep(1800);
                    java.util.LinkedHashSet<String> rows = new java.util.LinkedHashSet<>();
                    page = -1;
                    scrollToTop();
                    for (int page = 0; page < MAX_PAGES; page++) {
                        collectRows(getRootInActiveWindow(), rows);
                        if (!scrollForward()) {
                            break;
                        }
                        sleep(900);
                    }
                    for (String row : rows) {
                        Log.i(TAG, "ROW " + row);
                    }
                    Log.i(TAG, "rows=" + rows.size());
                } catch (Throwable t) {
                    Log.i(TAG, "list=" + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }, "autolist").start();
    }

    private void collectRows(AccessibilityNodeInfo root, java.util.LinkedHashSet<String> out) {
        if (root == null) {
            return;
        }
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            nodes.add(node);
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        for (AccessibilityNodeInfo box : nodes) {
            if (!box.isCheckable()) {
                continue;
            }
            Rect span = new Rect();
            box.getBoundsInScreen(span);
            String best = null;
            int bestOverlap = 0;
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isCheckable()) {
                    continue;
                }
                CharSequence label = node.getText();
                if (label == null) {
                    label = node.getContentDescription();
                }
                if (label == null || label.toString().trim().isEmpty()) {
                    continue;
                }
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                int overlap = Math.min(bounds.bottom, span.bottom)
                        - Math.max(bounds.top, span.top);
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = label.toString().trim();
                }
            }
            if (best != null) {
                out.add((box.isChecked() ? "on  " : "off ") + best);
            }
        }
    }

    private void flow(String[] labels) throws InterruptedException {
        // The list has its own exported page, so no App Management taps are
        // needed; fall back to the old walk only if the page is not there.
        startActivity(new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(MANAGER, AUTO_START_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        sleep(1800);
        page = -1;

        if (find("Auto Start Management", 3000) == null) {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName(MANAGER, MANAGER_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            sleep(1500);
            AccessibilityNodeInfo entry = find("App Management", 5000);
            if (entry == null) {
                Log.i(TAG, "failed=no App Management");
                return;
            }
            click(entry);
            AccessibilityNodeInfo auto = find("Auto Start Management", 6000);
            if (auto == null) {
                Log.i(TAG, "failed=no Auto Start Management");
                return;
            }
            click(auto);
            sleep(1500);
        }

        int ok = 0;
        int opened = 0;
        for (String label : labels) {
            if (retoggle(label)) {
                ok++;
                if (open(label)) {
                    opened++;
                }
            }
        }
        Log.i(TAG, "summary=" + ok + "/" + labels.length + " re-registered, "
                + opened + " opened");
    }

    private boolean retoggle(String label) throws InterruptedException {
        Row row = findRowScrolling(label);
        if (row == null) {
            Log.i(TAG, label + "=not found");
            return false;
        }
        AccessibilityNodeInfo box = nudgeIntoView(label, row).box;
        if (box == null || !box.isCheckable()) {
            Log.i(TAG, label + "=no switch");
            return false;
        }
        boolean was = box.isChecked();
        tap(box);
        AccessibilityNodeInfo after = waitChecked(label, !was, 3000);
        if (after == null) {
            Log.i(TAG, label + "=tap ignored (stale entry?)");
            return false;
        }
        Log.i(TAG, label + " was=" + was);
        if (was) {
            // It was on, so that tap took it off; the off/on pair is what makes
            // the vendor write the entry again, and it has to end on.
            tap(after);
            AccessibilityNodeInfo end = waitChecked(label, true, 3000);
            Log.i(TAG, label + " end=" + (end != null ? "on" : "off"));
            return end != null;
        }
        Log.i(TAG, label + " end=on");
        return true;
    }

    /**
     * Presses the app once, which is the step DokeOS wants after a row is
     * granted. Back returns to the list so the next label still resolves.
     */
    private boolean open(String label) throws InterruptedException {
        String pkg = packageFor(label);
        if (pkg == null) {
            Log.i(TAG, label + "=no launchable package");
            return false;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Log.i(TAG, pkg + "=no launcher activity");
            return false;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        long deadline = SystemClock.elapsedRealtime() + 4000;
        boolean shown = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && pkg.contentEquals(root.getPackageName())) {
                shown = true;
                break;
            }
            sleep(150);
        }
        Log.i(TAG, label + " opened " + pkg + (shown ? "" : " (unconfirmed)"));
        performGlobalAction(GLOBAL_ACTION_BACK);
        sleep(400);
        page = -1;
        return true;
    }

    private String packageFor(String label) {
        PackageManager pm = getPackageManager();
        for (android.content.pm.ApplicationInfo info : pm.getInstalledApplications(0)) {
            CharSequence name = info.loadLabel(pm);
            if (name == null || !name.toString().trim().equalsIgnoreCase(label.trim())) {
                continue;
            }
            return info.packageName;
        }
        return null;
    }

    // --- accessibility helpers ---

    private AccessibilityNodeInfo find(String text, long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo hit = root == null ? null : search(root, text);
            if (hit != null) {
                return hit;
            }
            sleep(200);
        } while (SystemClock.elapsedRealtime() < deadline);
        return null;
    }

    /**
     * The vendor list remembers its scroll position, so a search that only
     * looks at the current page (or a handful of pages) misses rows silently.
     * Always rewind to the first row, then scan forward the whole list.
     */
    /** A row's switch plus the label the vendor draws beside it. */
    private static final class Row {
        final String label;
        final AccessibilityNodeInfo box;

        Row(String label, AccessibilityNodeInfo box) {
            this.label = label;
            this.box = box;
        }
    }

    /**
     * The row name is not one text node: the list is drawn as a label and a
     * Switch with nothing tying them together, so text search misses rows that
     * are plainly on screen. Pair them by vertical overlap instead, which is
     * also what the LIST report prints.
     */
    private java.util.List<Row> visibleRows() {
        java.util.List<Row> out = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return out;
        }
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            nodes.add(node);
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        for (AccessibilityNodeInfo box : nodes) {
            if (!box.isCheckable()) {
                continue;
            }
            Rect span = new Rect();
            box.getBoundsInScreen(span);
            String best = null;
            int bestOverlap = 0;
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isCheckable()) {
                    continue;
                }
                CharSequence label = node.getText();
                if (label == null) {
                    label = node.getContentDescription();
                }
                if (label == null || label.toString().trim().isEmpty()) {
                    continue;
                }
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                int overlap = Math.min(bounds.bottom, span.bottom)
                        - Math.max(bounds.top, span.top);
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = label.toString().trim();
                }
            }
            if (best != null) {
                out.add(new Row(best, box));
            }
        }
        return out;
    }

    private Row rowOnPage(String label) {
        for (Row row : visibleRows()) {
            if (row.label.toLowerCase().contains(label.toLowerCase())) {
                return row;
            }
        }
        return null;
    }

    private Row findRowScrolling(String label) throws InterruptedException {
        // Consecutive labels live near each other, so the current page is
        // usually the cheapest hit.
        Row hit = rowOnPage(label);
        if (hit != null) {
            return hit;
        }
        if (page < 0) {
            scrollToTop();
        }
        int hint = savedPage(label);
        if (hint >= 0) {
            moveToPage(hint);
            hit = rowOnPage(label);
            if (hit != null) {
                return hit;
            }
        }
        for (int i = 0; i < MAX_PAGES && hit == null; i++) {
            if (!scrollForward()) {
                break;
            }
            sleep(PAGE_SETTLE);
            hit = rowOnPage(label);
            if (hit != null) {
                rememberPage(label, page);
                return hit;
            }
        }
        if (hit == null) {
            scrollToTop();
            for (int i = 0; i < MAX_PAGES && hit == null; i++) {
                if (!scrollForward()) {
                    break;
                }
                sleep(PAGE_SETTLE);
                hit = rowOnPage(label);
                if (hit != null) {
                    rememberPage(label, page);
                }
            }
        }
        return hit;
    }

    /** Half a page of scroll when the switch sits at the screen edge. */
    private Row nudgeIntoView(String label, Row row) throws InterruptedException {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        for (int attempt = 0; attempt < 3; attempt++) {
            Rect span = new Rect();
            row.box.getBoundsInScreen(span);
            if (span.height() > 0 && span.top >= 0 && span.bottom <= screenHeight) {
                return row;
            }
            boolean lowerHalf = span.centerY() > screenHeight / 2;
            if (lowerHalf ? !scrollForward() : !scrollBackward()) {
                return row;
            }
            sleep(500);
            Row again = rowOnPage(label);
            if (again == null) {
                return row;
            }
            row = again;
        }
        return row;
    }

    private void scrollToTop() throws InterruptedException {
        for (int i = 0; i < MAX_PAGES; i++) {
            if (!scrollBackward()) {
                break;
            }
            sleep(250);
        }
        page = 0;
    }

    private AccessibilityNodeInfo search(AccessibilityNodeInfo root, String text) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            CharSequence label = node.getText();
            if (label == null) {
                label = node.getContentDescription();
            }
            if (label != null && label.toString().toLowerCase().contains(text.toLowerCase())) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    /** The row's Switch is drawn asynchronously; retry rather than give up. */
    private AccessibilityNodeInfo switchNearOrWait(AccessibilityNodeInfo row)
            throws InterruptedException {
        for (int attempt = 0; attempt < 5; attempt++) {
            AccessibilityNodeInfo box = switchNear(row);
            if (box != null) {
                return box;
            }
            sleep(300);
        }
        return null;
    }

    /** Waits for the switch to reach the expected state instead of sleeping. */
    private AccessibilityNodeInfo waitChecked(String label, boolean expected, long timeoutMs)
            throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            Row row = rowOnPage(label);
            if (row != null && row.box != null && row.box.isChecked() == expected) {
                return row.box;
            }
            sleep(150);
        } while (SystemClock.elapsedRealtime() < deadline);
        return null;
    }

    /**
     * A row label can be visible while its switch is only half on screen (or
     * just past the fold), which makes the vertical-overlap lookup fail even
     * though the row exists. Nudge the list by half a page and look again.
     */
    private AccessibilityNodeInfo switchForRow(String label, AccessibilityNodeInfo row)
            throws InterruptedException {
        AccessibilityNodeInfo box = switchNearOrWait(row);
        if (box != null) {
            return box;
        }
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        for (int attempt = 0; attempt < 4 && box == null; attempt++) {
            Rect span = new Rect();
            row.getBoundsInScreen(span);
            boolean lowerHalf = span.centerY() > screenHeight / 2;
            if (lowerHalf ? !scrollForward() : !scrollBackward()) {
                break;
            }
            sleep(700);
            AccessibilityNodeInfo again = find(label, 1500);
            if (again == null) {
                break;
            }
            row = again;
            box = switchNearOrWait(again);
        }
        return box;
    }

    private AccessibilityNodeInfo switchNear(AccessibilityNodeInfo row) {
        if (row == null) {
            return null;
        }
        Rect span = new Rect();
        row.getBoundsInScreen(span);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (node.isCheckable()) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.top < span.bottom && bounds.bottom > span.top) {
                    return node;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    private boolean click(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        AccessibilityNodeInfo parent = node.getParent();
        return parent != null && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private void tap(AccessibilityNodeInfo node) {
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            click(node.getParent());
        }
    }

    private boolean scrollForward() {
        AccessibilityNodeInfo scroller = firstScrollable(getRootInActiveWindow());
        boolean moved = scroller != null
                && scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        if (moved && page >= 0) {
            page++;
        }
        return moved;
    }

    private boolean scrollBackward() {
        AccessibilityNodeInfo scroller = firstScrollable(getRootInActiveWindow());
        boolean moved = scroller != null
                && scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        if (moved && page > 0) {
            page--;
        }
        return moved;
    }

    private AccessibilityNodeInfo firstScrollable(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (node.isScrollable()) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[;\\n]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
