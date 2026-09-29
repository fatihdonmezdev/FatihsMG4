package com.evsuite.abrp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** Static, low-opacity light behind the glass panels; no blur passes or animation loop. */
public final class PremiumBackdropView extends View {
    private final Paint cyan = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gold = new Paint(Paint.ANTI_ALIAS_FLAG);

    public PremiumBackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float radius = Math.max(1, Math.max(w, h) * 0.65f);
        cyan.setShader(new RadialGradient(w * 0.70f, 0, radius,
                new int[]{0x2419565B, 0x00000000}, null, Shader.TileMode.CLAMP));
        gold.setShader(new RadialGradient(w, h, radius * 0.65f,
                new int[]{0x1C8B693A, 0x00000000}, null, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), cyan);
        canvas.drawRect(0, 0, getWidth(), getHeight(), gold);
    }
}
