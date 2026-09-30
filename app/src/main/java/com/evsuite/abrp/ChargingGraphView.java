package com.evsuite.abrp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Lightweight SOC/power charging curve, avoiding a heavy chart dependency on AAOS 9. */
public final class ChargingGraphView extends View {
    private final Paint grid = new Paint(1), line = new Paint(1), text = new Paint(1);
    private final List<float[]> points = new ArrayList<>();
    public ChargingGraphView(Context c, AttributeSet a) { super(c,a); grid.setColor(0x3348C7D9); line.setColor(0xFFFFC857); line.setStrokeWidth(4); text.setColor(0xFFBDC4C6); text.setTextSize(24); }
    synchronized void add(Float soc, Float power) {
        if (soc == null || power == null || power >= 0) return;
        float p = Math.abs(power);
        if (!points.isEmpty() && Math.abs(points.get(points.size()-1)[0] - soc) < .1f) points.remove(points.size()-1);
        points.add(new float[]{soc,p}); if (points.size() > 500) points.remove(0); invalidate();
    }
    @Override protected synchronized void onDraw(Canvas c) {
        super.onDraw(c); float w=getWidth(), h=getHeight()-34;
        for(int i=0;i<=4;i++) c.drawLine(0,h*i/4,w,h*i/4,grid);
        c.drawText("SOC % →   Şarj gücü (kW)",12,getHeight()-5,text);
        float max=20; for(float[] p:points) max=Math.max(max,p[1]);
        for(int i=1;i<points.size();i++){float[] a=points.get(i-1),b=points.get(i); c.drawLine(a[0]*w/100,h-a[1]*h/max,b[0]*w/100,h-b[1]*h/max,line);}
    }
}
