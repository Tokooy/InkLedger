package cn.inkledger.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.Arrays;
import java.util.Map;
import java.util.function.IntConsumer;

final class Charts {
    static final int BLUE = 0xff3760fa;
    static final int INK = 0xff161c2e;
    static final int SECONDARY = 0xff626b80;
    static final int LINE = 0xffe9edf5;

    private Charts() {}

    static final class Ring extends View {
        private final Map<Expense.Category, Long> totals;
        private final long total;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Ring(Context context, Map<Expense.Category, Long> totals, long total) {
            super(context);
            this.totals = totals;
            this.total = total;
            setContentDescription("分类占比。支出合计 " + Expense.money(total));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(cx, cy) - 16 * density;
            if (radius <= 0) return;
            RectF circle = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(15 * density);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setColor(LINE);
            canvas.drawCircle(cx, cy, radius, paint);

            float start = -90;
            int count = totals.size();
            if (total > 0) {
                for (Map.Entry<Expense.Category, Long> entry : totals.entrySet()) {
                    float angle = 360f * entry.getValue() / total;
                    float gap = count > 1 ? Math.min(3f, angle * .25f) : 0;
                    paint.setColor(entry.getKey().color);
                    canvas.drawArc(circle, start + gap / 2, angle - gap, false, paint);
                    start += angle;
                }
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(SECONDARY);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextSize(12 * density);
            canvas.drawText("支出合计", cx, cy - 10 * density, paint);
            paint.setColor(INK);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            paint.setTextSize(24 * density);
            String label = Expense.money(total);
            float width = paint.measureText(label);
            if (width > radius * 1.65f) paint.setTextSize(paint.getTextSize() * radius * 1.65f / width);
            canvas.drawText(label, cx, cy + 20 * density, paint);
        }
    }

    static final class Days extends View {
        private final long[] values;
        private final long max;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final IntConsumer select;
        private int selected;

        Days(Context context, long[] values, int selected, IntConsumer select) {
            super(context);
            this.values = values;
            this.selected = selected;
            this.select = select;
            max = Math.max(1, Arrays.stream(values).max().orElse(0));
            setContentDescription("每日支出柱状图，使用日期两侧按钮切换并读取具体金额");
        }

        void setSelectedDay(int day) {
            selected = day;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth();
            if (w <= 0) return;
            float top = 14 * d;
            float baseline = getHeight() - 23 * d;
            float step = w / values.length;
            for (int i = 0; i <= 3; i++) {
                float y = top + (baseline - top) * i / 3;
                paint.setColor(LINE);
                paint.setStrokeWidth(d);
                canvas.drawLine(0, y, w, y, paint);
            }

            for (int i = 0; i < values.length; i++) {
                float x = i * step;
                float space = step * .21f;
                boolean highlighted = selected == i + 1;
                if (highlighted) {
                    paint.setColor(0xffeef2ff);
                    canvas.drawRoundRect(x, top - 6 * d, x + step, baseline + 3 * d, 5 * d, 5 * d, paint);
                }
                paint.setColor(highlighted ? BLUE : values[i] == 0 ? 0xffdce3f0 : 0xff9cb0ff);
                float h = values[i] == 0 ? 2 * d : Math.max(4 * d, (baseline - top) * values[i] / max);
                canvas.drawRoundRect(x + space, baseline - h, x + step - space, baseline, 2 * d, 2 * d, paint);
            }
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextSize(10 * d);
            paint.setColor(SECONDARY);
            paint.setTextAlign(Paint.Align.CENTER);
            for (int day : new int[]{1, 8, 15, 22, values.length}) {
                canvas.drawText(Integer.toString(day), (day - .5f) * step, getHeight() - 4 * d, paint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                int day = Math.min(values.length, Math.max(1, (int) (event.getX() * values.length / getWidth()) + 1));
                setSelectedDay(day);
                select.accept(day);
                performClick();
                return true;
            }
            return event.getActionMasked() == MotionEvent.ACTION_MOVE;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    static final class HeroWaves extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        HeroWaves(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(getResources().getDisplayMetrics().density);
            paint.setColor(Color.argb(32, 255, 255, 255));
            for (int i = 0; i < 8; i++) {
                float radius = h * (.53f + .105f * i);
                canvas.drawCircle(w * 1.02f, -h * .03f, radius, paint);
            }
        }
    }
}
