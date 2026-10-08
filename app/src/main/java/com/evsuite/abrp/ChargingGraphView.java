package com.evsuite.abrp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lightweight SOC/power charging curve, avoiding a heavy chart dependency on AAOS 9. */
public final class ChargingGraphView extends View {
    private final Paint grid = new Paint(1), line = new Paint(1), text = new Paint(1);
    private final List<float[]> points = new ArrayList<>();
    private final float[] socPower = new float[101];
    public ChargingGraphView(Context c, AttributeSet a) { super(c,a); java.util.Arrays.fill(socPower, Float.NaN); grid.setColor(0x5548C7D9); line.setColor(0xFFFFC857); line.setStrokeWidth(4); text.setColor(0xFFBDC4C6); text.setTextSize(20); }
    synchronized void add(Float soc, Float power) {
        if (soc == null || power == null || power >= 0) return;
        float p = Math.abs(power);
        if (!points.isEmpty() && Math.abs(points.get(points.size()-1)[0] - soc) < .1f) points.remove(points.size()-1);
        points.add(new float[]{soc,p}); if (points.size() > 500) points.remove(0);
        socPower[Math.max(0, Math.min(100, Math.round(soc)))] = p; invalidate();
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
        for (int soc=0; soc<=100; soc++) if (!Float.isNaN(socPower[soc])) {
            found = true;
            out.append(String.format(Locale.getDefault(), "%3d %%      %6.1f kW\n", soc, socPower[soc]));
        }
        return found ? out.toString().trim() : "Henüz şarj ölçümü yok";
    }
    @Override protected synchronized void onDraw(Canvas c) {
        super.onDraw(c); float left=58, right=getWidth()-12, top=12, bottom=getHeight()-38;
        float max=20; for(float[] p:points) max=Math.max(max,p[1]); max=(float)Math.ceil(max/20f)*20f;
        for(int i=0;i<=5;i++){float y=bottom-(bottom-top)*i/5f; c.drawLine(left,y,right,y,grid); c.drawText(String.format(Locale.US,"%.0f",max*i/5f),4,y+7,text);}
        for(int soc=0;soc<=100;soc+=20){float x=left+(right-left)*soc/100f; c.drawLine(x,top,x,bottom,grid); c.drawText(String.valueOf(soc),x-10,getHeight()-8,text);}
        c.drawText("kW",5,20,text); c.drawText("SOC %",right-52,getHeight()-8,text);
        for(int i=1;i<points.size();i++){float[] a=points.get(i-1),b=points.get(i); float ax=left+a[0]*(right-left)/100, ay=bottom-a[1]*(bottom-top)/max, bx=left+b[0]*(right-left)/100, by=bottom-b[1]*(bottom-top)/max; c.drawLine(ax,ay,bx,by,line); c.drawCircle(bx,by,4,line);}
    }
}
