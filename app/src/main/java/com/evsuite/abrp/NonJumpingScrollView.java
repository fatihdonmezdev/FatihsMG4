package com.evsuite.abrp;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;

/**
 * ScrollView that prevents automatic scroll jumps to the top when child views
 * are updated periodically by background timers.
 */
public class NonJumpingScrollView extends ScrollView {

    public NonJumpingScrollView(Context context) {
        super(context);
    }

    public NonJumpingScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NonJumpingScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        // Only scroll to child if it's an EditText explicitly focused by the user
        if (focused instanceof EditText && focused.isFocused()) {
            super.requestChildFocus(child, focused);
        }
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        // Prevent layout passes from scrolling back to top unless an EditText is focused
        View focused = findFocus();
        if (focused instanceof EditText && isDescendant(focused, child)) {
            return super.requestChildRectangleOnScreen(child, rectangle, immediate);
        }
        return false;
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        View focused = findFocus();
        if (focused instanceof EditText) {
            return super.computeScrollDeltaToGetChildRectOnScreen(rect);
        }
        return 0;
    }

    private boolean isDescendant(View descendant, View root) {
        if (descendant == null || root == null) return false;
        if (descendant == root) return true;
        android.view.ViewParent p = descendant.getParent();
        return (p instanceof View) && isDescendant((View) p, root);
    }
}
