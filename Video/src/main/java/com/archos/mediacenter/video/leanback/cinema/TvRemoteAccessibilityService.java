package com.archos.mediacenter.video.leanback.cinema;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** Routes explicit commands from the active paired controller to the active TV window. */
public final class TvRemoteAccessibilityService extends AccessibilityService {
    private static volatile TvRemoteAccessibilityService connected;
    private AccessibilityNodeInfo virtualFocus;
    private AccessibilityNodeInfo inputAtVirtualFocus;
    private View pointer;
    private float pointerX, pointerY;
    private final android.os.Handler pointerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable hidePointer = this::removePointer;

    static boolean available() { return connected != null; }

    static boolean launchApp(android.content.Intent intent) {
        var service = connected;
        if (service == null) return false;
        try {
            service.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (SecurityException | android.content.ActivityNotFoundException unavailable) {
            return false;
        }
    }

    @Override protected void onServiceConnected() {
        connected = this;
        if (CinemaLinkServer.backgroundEnabled(this)) {
            CinemaLinkServer.startFor(getApplication());
            TvLocalAdbBridge.reconnectIfEnabledAsync(this);
        }
        CinemaLinkServer.broadcastRemoteStatus(this);
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        removePointer();
        if (connected == this) connected = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        removePointer();
        if (connected == this) connected = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    static boolean dispatch(int key, boolean held) {
        var service = connected;
        if (service == null) return false;
        return switch (key) {
            case KeyEvent.KEYCODE_HOME -> service.performGlobalAction(GLOBAL_ACTION_HOME);
            case KeyEvent.KEYCODE_BACK -> service.performGlobalAction(GLOBAL_ACTION_BACK);
            case KeyEvent.KEYCODE_SLEEP -> android.os.Build.VERSION.SDK_INT >= 28 && service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            case KeyEvent.KEYCODE_APP_SWITCH -> service.performGlobalAction(GLOBAL_ACTION_RECENTS);
            case KeyEvent.KEYCODE_SETTINGS -> {
                try {
                    service.startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                    yield true;
                } catch (Exception ignored) { yield false; }
            }
            case KeyEvent.KEYCODE_DPAD_UP -> service.move(View.FOCUS_UP);
            case KeyEvent.KEYCODE_DPAD_DOWN -> service.move(View.FOCUS_DOWN);
            case KeyEvent.KEYCODE_DPAD_LEFT -> service.move(View.FOCUS_LEFT);
            case KeyEvent.KEYCODE_DPAD_RIGHT -> service.move(View.FOCUS_RIGHT);
            case KeyEvent.KEYCODE_DPAD_CENTER -> service.activate(held);
            case KeyEvent.KEYCODE_ENTER -> service.submit();
            case KeyEvent.KEYCODE_DEL -> service.delete();
            default -> false;
        };
    }

    private AccessibilityNodeInfo directionalFocus() {
        var input = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (virtualFocus != null && java.util.Objects.equals(input, inputAtVirtualFocus)
                && virtualFocus.refresh() && virtualFocus.isVisibleToUser()) return virtualFocus;
        virtualFocus = null;
        return input != null ? input : findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
    }

    private boolean move(int direction) {
        var current = directionalFocus();
        if (current == null) {
            var root = getRootInActiveWindow();
            if (root == null) return false;
            for (var node : visibleTargets(root)) {
                if (node.isFocused() || node.isSelected()) {
                    current = node;
                    break;
                }
            }
            if (current == null) return focusFirst(root);
        }
        var next = current.focusSearch(direction);
        if (next != null && !next.equals(current) && next.isVisibleToUser()
                && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            virtualFocus = null;
            return true;
        }
        var reference = new Rect();
        current.getBoundsInScreen(reference);
        var roots = new java.util.ArrayList<AccessibilityNodeInfo>();
        var active = getRootInActiveWindow();
        if (active != null) roots.add(active);
        for (var window : getWindows()) {
            if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                    || window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            var root = window.getRoot();
            if (root != null && !roots.contains(root)) roots.add(root);
        }
        var candidates = new java.util.ArrayList<java.util.Map.Entry<AccessibilityNodeInfo, Double>>();
        for (var root : roots) {
            for (var candidate : visibleTargets(root)) {
                Rect bounds = new Rect();
                candidate.getBoundsInScreen(bounds);
                if (candidate.equals(current) || bounds.isEmpty() || bounds.contains(reference)
                        || reference.contains(bounds)) continue;
                double score = directionalDistance(direction, reference, bounds);
                if (Double.isFinite(score)) candidates.add(java.util.Map.entry(candidate, score));
            }
        }
        candidates.sort(java.util.Map.Entry.comparingByValue());
        for (var entry : candidates) {
            var candidate = entry.getKey();
            if (candidate.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                virtualFocus = null;
                return true;
            }
            if (candidate.isClickable()
                    && candidate.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
                inputAtVirtualFocus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                virtualFocus = candidate;
                if (candidate.getViewIdResourceName() != null)
                    android.util.Log.d("TV_REMOTE_RX", "Spatial focus: " + candidate.getViewIdResourceName());
                return true;
            }
        }
        int action = switch (direction) {
            case View.FOCUS_UP -> android.R.id.accessibilityActionScrollUp;
            case View.FOCUS_DOWN -> android.R.id.accessibilityActionScrollDown;
            case View.FOCUS_LEFT -> android.R.id.accessibilityActionScrollLeft;
            default -> android.R.id.accessibilityActionScrollRight;
        };
        return performOnAncestor(current, action);
    }

    private static double directionalDistance(int direction, Rect from, Rect to) {
        boolean vertical = direction == View.FOCUS_UP || direction == View.FOCUS_DOWN;
        int primary = switch (direction) {
            case View.FOCUS_UP -> from.centerY() - to.centerY();
            case View.FOCUS_DOWN -> to.centerY() - from.centerY();
            case View.FOCUS_LEFT -> from.centerX() - to.centerX();
            default -> to.centerX() - from.centerX();
        };
        if (primary <= 0) return Double.POSITIVE_INFINITY;
        int alignment = vertical ? Math.min(Math.abs(from.left - to.left), Math.abs(from.right - to.right))
                : Math.min(Math.abs(from.top - to.top), Math.abs(from.bottom - to.bottom));
        return primary + alignment * 2.0;
    }

    private static java.util.List<AccessibilityNodeInfo> visibleTargets(AccessibilityNodeInfo root) {
        var result = new java.util.ArrayList<AccessibilityNodeInfo>();
        var pending = new java.util.ArrayDeque<AccessibilityNodeInfo>();
        pending.add(root);
        for (int visited = 0; !pending.isEmpty() && visited < 600; visited++) {
            var node = pending.removeFirst();
            if (node.isVisibleToUser() && node.isEnabled() && (node.isFocusable() || node.isClickable()))
                result.add(node);
            for (int i = 0; i < node.getChildCount(); i++) {
                var child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
        }
        return result;
    }

    private boolean focusFirst(AccessibilityNodeInfo root) {
        var pending = new java.util.ArrayDeque<AccessibilityNodeInfo>();
        pending.add(root);
        for (int visited = 0; !pending.isEmpty() && visited < 256; visited++) {
            var node = pending.removeFirst();
            if (node.isVisibleToUser() && node.isFocusable() && node.isEnabled()
                    && node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return true;
            for (int i = 0; i < node.getChildCount(); i++) {
                var child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
        }
        return false;
    }

    private boolean activate(boolean held) {
        var node = directionalFocus();
        if (performOnAncestor(node, held ? AccessibilityNodeInfo.ACTION_LONG_CLICK
                : AccessibilityNodeInfo.ACTION_CLICK)) return true;
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) return false;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return false;
        Path path = new Path();
        path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, held ? 700 : 45)).build(),
                null, null);
    }

    static boolean pointerCommand(String action, double dx, double dy) {
        var service = connected;
        if (service == null) return false;
        if ("hide".equals(action)) {
            service.removePointer();
            return true;
        }
        if (!"show".equals(action) && !"move".equals(action) && !"click".equals(action)) return false;
        service.movePointer(dx, dy);
        if ("click".equals(action)) {
            if (service.clickAtPointer()) return true;
            Path path = new Path();
            path.moveTo(service.pointerX, service.pointerY);
            service.dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 45)).build(), null, null);
        }
        return true;
    }

    private boolean clickAtPointer() {
        var root = getRootInActiveWindow();
        if (root == null) return false;
        var targets = new java.util.ArrayList<java.util.Map.Entry<AccessibilityNodeInfo, Long>>();
        for (var node : visibleTargets(root)) {
            if (!node.isClickable()) continue;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.contains((int) pointerX, (int) pointerY)) {
                targets.add(java.util.Map.entry(node, (long) bounds.width() * bounds.height()));
            }
        }
        targets.sort(java.util.Map.Entry.comparingByValue());
        for (var target : targets) {
            if (target.getKey().performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    private void movePointer(double dx, double dy) {
        var manager = getSystemService(android.view.WindowManager.class);
        var screen = new android.graphics.Point();
        manager.getDefaultDisplay().getRealSize(screen);
        int size = Math.max(16, Math.round(getResources().getDisplayMetrics().density * 18));
        if (pointer == null) {
            pointerX = screen.x / 2f;
            pointerY = screen.y / 2f;
        }
        pointerX = (float) Math.max(size, Math.min(screen.x - size, pointerX + dx * screen.x));
        pointerY = (float) Math.max(size, Math.min(screen.y - size, pointerY + dy * screen.y));
        var params = new android.view.WindowManager.LayoutParams(size, size,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        params.x = Math.round(pointerX - size / 2f);
        params.y = Math.round(pointerY - size / 2f);
        if (pointer == null) {
            var circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(0x70171717);
            circle.setStroke(Math.max(2, size / 10), 0xffe5b84b);
            pointer = new View(this);
            pointer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            pointer.setBackground(circle);
            manager.addView(pointer, params);
        } else manager.updateViewLayout(pointer, params);
        pointerHandler.removeCallbacks(hidePointer);
        pointerHandler.postDelayed(hidePointer, 15000);
    }

    private void removePointer() {
        pointerHandler.removeCallbacks(hidePointer);
        if (pointer == null) return;
        getSystemService(android.view.WindowManager.class).removeViewImmediate(pointer);
        pointer = null;
    }

    static boolean editText(int move, int delete, String insert) {
        var service = connected;
        var node = service == null ? null : service.editor();
        if (node == null || node.isPassword()) return false;
        String value = node.getText() == null || node.isShowingHintText() ? "" : node.getText().toString();
        int caret = node.getTextSelectionEnd() < 0 ? value.length() : node.getTextSelectionEnd();
        int start = Math.max(0, Math.min(value.length(), caret + move));
        int end = Math.min(value.length(), start + delete);
        if (move == 0 && delete == 0 && !insert.isEmpty() && node.getTextSelectionStart() >= 0) {
            start = Math.min(start, node.getTextSelectionStart());
        }
        if ((delete != 0 || !insert.isEmpty())
                && !setText(node, value.substring(0, start) + insert + value.substring(end))) return false;
        Bundle selection = new Bundle();
        selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start + insert.length());
        selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, start + insert.length());
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection);
    }

    static boolean typeText(String text) {
        var service = connected;
        return service != null && setText(service.editor(), text);
    }

    static boolean submitEditor() {
        var service = connected;
        return service != null && service.editor() != null && service.submit();
    }

    private AccessibilityNodeInfo editor() {
        var node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        return node != null && node.isEditable() && node.isEnabled() && node.isVisibleToUser() ? node : null;
    }

    private static boolean setText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private boolean delete() {
        var node = editor();
        if (node == null || node.isPassword() || node.getText() == null) return false;
        String text = node.getText().toString();
        int from = node.getTextSelectionStart(), to = node.getTextSelectionEnd();
        if (from < 0 || to < from || to > text.length()) from = to = text.length();
        if (from == to && from > 0) from = text.offsetByCodePoints(from, -1);
        if (from == to || !setText(node, text.substring(0, from) + text.substring(to))) return false;
        Bundle selection = new Bundle();
        selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, from);
        selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, from);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection);
        return true;
    }

    private boolean submit() {
        var node = editor();
        if (node == null) return false;
        if (android.os.Build.VERSION.SDK_INT >= 30
                && node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) {
            android.util.Log.d("TV_REMOTE_RX", "Submit requested through editor action");
            CharSequence original = node.getText() == null ? null : node.getText().toString();
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                var current = editor();
                if (current == null || !current.equals(node)
                        || !android.text.TextUtils.equals(original, current.getText())) return;
                for (var window : getWindows()) {
                    if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        clickEditorSubmit(current);
                        break;
                    }
                }
            }, 180);
            return true;
        }
        // Prefer the IME action key when visible. The editor and keyboard are separate windows.
        for (var window : getWindows()) {
            if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    && clickSubmit(window.getRoot())) return true;
        }
        return clickEditorSubmit(node);
    }

    private boolean clickEditorSubmit(AccessibilityNodeInfo node) {
        for (var window : getWindows()) {
            var root = window.getRoot();
            if (root != null && node.getWindowId() == root.getWindowId() && clickSubmit(root)) return true;
        }
        return clickSubmit(getRootInActiveWindow());
    }

    private boolean clickSubmit(AccessibilityNodeInfo root) {
        if (root == null) return false;
        var pending = new java.util.ArrayDeque<AccessibilityNodeInfo>();
        pending.add(root);
        for (int visited = 0; !pending.isEmpty() && visited < 400; visited++) {
            var node = pending.removeFirst();
            String label = String.valueOf(node.getContentDescription()).strip().toLowerCase(java.util.Locale.ROOT);
            String text = String.valueOf(node.getText()).strip().toLowerCase(java.util.Locale.ROOT);
            var actions = java.util.Set.of("search", "بحث", "ابحث", "go", "done", "submit", "enter", "🔍");
            if (!node.isEditable() && node.isEnabled() && node.isVisibleToUser()
                    && (actions.contains(label) || actions.contains(text))
                    && performOnAncestor(node, AccessibilityNodeInfo.ACTION_CLICK)) {
                android.util.Log.d("TV_REMOTE_RX", "Submit accepted by visible action");
                return true;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                var child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
        }
        return false;
    }

    private boolean performOnAncestor(AccessibilityNodeInfo node, int action) {
        for (int i = 0; node != null && i < 16; i++, node = node.getParent()) {
            if (node.isVisibleToUser() && node.isEnabled() && node.performAction(action)) return true;
        }
        return false;
    }
}
