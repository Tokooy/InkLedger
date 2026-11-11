package cn.inkledger.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

final class LedgerPager extends FrameLayout {
    interface Pages {
        View create(int position);
        void settled(int position, View view);
    }

    private final Pages pages;
    private final int count;
    private final int slop;
    private final int minimumVelocity;
    private View current;
    private View incoming;
    private int position;
    private int target;
    private float downX;
    private float downY;
    private float drag;
    private boolean dragging;
    private boolean vertical;
    private VelocityTracker velocity;
    private ValueAnimator animator;

    LedgerPager(Context context, int count, Pages pages) {
        super(context);
        this.count = count;
        this.pages = pages;
        var configuration = ViewConfiguration.get(context);
        slop = configuration.getScaledTouchSlop();
        minimumVelocity = Math.max(350, configuration.getScaledMinimumFlingVelocity());
    }

    void initialize(int position, View view) {
        this.position = position;
        current = view;
        addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    boolean moving() { return dragging || animator != null; }

    void goTo(int next) {
        if (next == position || moving() || getWidth() == 0) return;
        prepare(next);
        settle(true);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (animator != null) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                downX = event.getX();
                downY = event.getY();
                vertical = false;
            }
            case MotionEvent.ACTION_MOVE -> {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) vertical = true;
                if (!vertical && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    dragging = true;
                    velocity = VelocityTracker.obtain();
                    velocity.addMovement(event);
                    move(dx);
                    return true;
                }
            }
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (animator != null) return true;
        if (!dragging) return super.onTouchEvent(event);
        velocity.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE -> move(event.getX() - downX);
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity.computeCurrentVelocity(1000);
                float speed = velocity.getXVelocity();
                velocity.recycle();
                velocity = null;
                boolean finish = event.getActionMasked() == MotionEvent.ACTION_UP && incoming != null
                        && (Math.abs(drag) >= getWidth() * .32f
                        || (Math.abs(drag) > slop * 2 && Math.signum(speed) == Math.signum(drag)
                            && Math.abs(speed) > minimumVelocity));
                dragging = false;
                settle(finish);
            }
        }
        return true;
    }

    private void move(float dx) {
        int next = position + (dx < 0 ? 1 : -1);
        if (next < 0 || next >= count) {
            removeIncoming();
            translate(dx * .16f);
        } else {
            if (incoming == null || target != next) prepare(next);
            translate(Math.max(-getWidth(), Math.min(getWidth(), dx)));
        }
    }

    private void prepare(int next) {
        removeIncoming();
        target = next;
        incoming = pages.create(next);
        incoming.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        addView(incoming, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        translate(drag);
    }

    private void translate(float x) {
        drag = x;
        current.setTranslationX(x);
        if (incoming != null) incoming.setTranslationX(x + Math.signum(target - position) * getWidth());
    }

    private void settle(boolean advance) {
        float end = advance ? -Math.signum(target - position) * getWidth() : 0;
        animator = ValueAnimator.ofFloat(drag, end);
        animator.setDuration(250);
        animator.setInterpolator(new DecelerateInterpolator(1.65f));
        animator.addUpdateListener(value -> translate((float) value.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (advance) {
                    removeView(current);
                    current = incoming;
                    incoming = null;
                    position = target;
                    current.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
                } else {
                    removeIncoming();
                }
                translate(0);
                animator = null;
                pages.settled(position, current);
            }
        });
        animator.start();
    }

    private void removeIncoming() {
        if (incoming == null) return;
        removeView(incoming);
        incoming = null;
    }
}
