package com.evsuite.abrp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Modernized SOC/power charging curve with gradient fill and crisp scales on AAOS. */
public final class ChargingGraphView extends View {
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curvePath = new Path();
    private final Path areaPath = new Path();

    private final List<float[]> points = new ArrayList<>();
    private final float[] socPower = new float[101];

    public ChargingGraphView(Context c, AttributeSet a) {
        super(c, a);
        java.util.Arrays.fill(socPower, Float.NaN);

        gridPaint.setColor(0x2E48C7D9); // subtle cyan grid
        gridPaint.setStrokeWidth(1.5f);

        linePaint.setColor(0xFFFFC857); // vibrant gold/amber
        linePaint.setStrokeWidth(4.5f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        areaPaint.setStyle(Paint.Style.FILL);

        dotPaint.setColor(0xFFFFC857);
        dotPaint.setStyle(Paint.Style.FILL);

        dotInnerPaint.setColor(0xFF14191C);
        dotInnerPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(0xFF9EACB0);
        textPaint.setTextSize(22f);
    }

    synchronized void add(Float soc, Float power) {
        if (soc == null || power == null || power >= 0) return;
        float p = Math.abs(power);
        if (!points.isEmpty() && Math.abs(points.get(points.size() - 1)[0] - soc) < .1f) {
            points.remove(points.size() - 1);
        }
        points.add(new float[]{soc, p});
        if (points.size() > 500) points.remove(0);
        socPower[Math.max(0, Math.min(100, Math.round(soc)))] = p;
        invalidate();
    }

    synchronized void setPoints(List<ChargeSessionTracker.Point> values) {
        points.clear();
        java.util.Arrays.fill(socPower, Float.NaN);
        for (ChargeSessionTracker.Point value : values) {
            if (value.socPercent == null) continue;
            points.add(new float[]{value.socPercent, value.powerKw});
            socPower[Math.max(0, Math.min(100, Math.round(value.socPercent)))] = value.powerKw;
        }
        invalidate();
    }

    synchronized String tableText() {
        StringBuilder out = new StringBuilder("SOC       Şarj gücü\n");
        boolean found = false;
        for (int soc = 0; soc <= 100; soc++) {
            if (!Float.isNaN(socPower[soc])) {
                found = true;
                out.append(String.format(Locale.getDefault(), "%3d %%      %6.1f kW\n", soc, socPower[soc]));
            }
        }
        return found ? out.toString().trim() : "Henüz şarj ölçümü yok";
    }

    @Override
    protected synchronized void onDraw(Canvas c) {
        super.onDraw(c);
        float padLeft = 64f;
        float padRight = getWidth() - 20f;
        float padTop = 26f;
        float padBottom = getHeight() - 44f;

        // Baseline maximum ceiling is at least 100 kW, rounded up in steps of 20 kW if higher
        float peakPower = 0f;
        for (float[] p : points) {
            if (p[1] > peakPower) peakPower = p[1];
        }
        float maxKw = Math.max(100f, (float) Math.ceil(peakPower / 20f) * 20f);

        // Draw horizontal grid lines (0, 20%, 40%, 60%, 80%, 100% of maxKw)
        int steps = 5;
        for (int i = 0; i <= steps; i++) {
            float y = padBottom - (padBottom - padTop) * i / (float) steps;
            c.drawLine(padLeft, y, padRight, y, gridPaint);
            String label = String.format(Locale.US, "%.0f", maxKw * i / (float) steps);
            c.drawText(label, 8f, y + 7f, textPaint);
        }

        // Draw vertical grid lines for SOC (0%, 20%, 40%, 60%, 80%, 100%)
        for (int soc = 0; soc <= 100; soc += 20) {
            float x = padLeft + (padRight - padLeft) * soc / 100f;
            c.drawLine(x, padTop, x, padBottom, gridPaint);
            String socLabel = soc + "%";
            float textW = textPaint.measureText(socLabel);
            c.drawText(socLabel, Math.max(padLeft, Math.min(padRight - textW, x - textW / 2f)), getHeight() - 12f, textPaint);
        }

        // Top labels
        c.drawText("kW", 8f, padTop - 8f, textPaint);
        c.drawText("SOC %", padRight - textPaint.measureText("SOC %"), padTop - 8f, textPaint);

        if (points.isEmpty()) return;

        curvePath.reset();
        areaPath.reset();

        float firstX = 0f;
        float lastX = 0f;

        for (int i = 0; i < points.size(); i++) {
            float[] pt = points.get(i);
            float px = padLeft + pt[0] * (padRight - padLeft) / 100f;
            float py = padBottom - pt[1] * (padBottom - padTop) / maxKw;

            if (i == 0) {
                curvePath.moveTo(px, py);
                areaPath.moveTo(px, padBottom);
                areaPath.lineTo(px, py);
                firstX = px;
            } else {
                curvePath.lineTo(px, py);
                areaPath.lineTo(px, py);
            }
            lastX = px;
        }

        areaPath.lineTo(lastX, padBottom);
        areaPath.lineTo(firstX, padBottom);
        areaPath.close();

        // Area gradient fill
        LinearGradient gradient = new LinearGradient(
            0, padTop, 0, padBottom,
            new int[]{0x45FFC857, 0x05FFC857},
            null,
            Shader.TileMode.CLAMP
        );
        areaPaint.setShader(gradient);
        c.drawPath(areaPath, areaPaint);

        // Draw main line
        c.drawPath(curvePath, linePaint);

        // Draw end point halo dot
        if (!points.isEmpty()) {
            float[] last = points.get(points.size() - 1);
            float lx = padLeft + last[0] * (padRight - padLeft) / 100f;
            float ly = padBottom - last[1] * (padBottom - padTop) / maxKw;
            c.drawCircle(lx, ly, 7f, dotPaint);
            c.drawCircle(lx, ly, 3.5f, dotInnerPaint);
        }
    }
}
