package io.github.sabopura.hushutter;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * One UI 風のスイッチ。タップはカード全体で受けるので、これは表示専用。
 * 青いカードの上に置くときは、つまみとトラックの色を入れかえる（白いトラックに青いつまみ）。
 */
public class PillSwitch extends View {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private float mPosition;
    private boolean mChecked;
    private ValueAnimator mAnimator;
    private int mTrackOff;
    private int mTrackOn;
    private int mThumbOff;
    private int mThumbOn;

    public PillSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTrackOff = context.getColor(R.color.switch_off);
        mTrackOn = context.getColor(R.color.on_blue);
        mThumbOff = context.getColor(R.color.on_blue);
        mThumbOn = context.getColor(R.color.blue);
    }

    void setChecked(boolean checked, boolean animate) {
        if (mAnimator != null) mAnimator.cancel();
        mChecked = checked;
        float target = checked ? 1f : 0f;
        if (!animate) {
            mPosition = target;
            invalidate();
            return;
        }
        mAnimator = ValueAnimator.ofFloat(mPosition, target).setDuration(220);
        mAnimator.setInterpolator(new DecelerateInterpolator());
        mAnimator.addUpdateListener(a -> {
            mPosition = (float) a.getAnimatedValue();
            invalidate();
        });
        mAnimator.start();
    }

    boolean isChecked() {
        return mChecked;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = h / 2f;
        mRect.set(0, 0, w, h);
        mPaint.setColor(blend(mTrackOff, mTrackOn, mPosition));
        canvas.drawRoundRect(mRect, r, r, mPaint);
        float inset = h * 0.125f;
        float thumbR = r - inset;
        float cx = r + (w - 2 * r) * mPosition;
        mPaint.setColor(blend(mThumbOff, mThumbOn, mPosition));
        canvas.drawCircle(cx, r, thumbR, mPaint);
    }

    private static int blend(int from, int to, float t) {
        int a = (int) (((from >>> 24) & 0xff) * (1 - t) + ((to >>> 24) & 0xff) * t);
        int r = (int) (((from >> 16) & 0xff) * (1 - t) + ((to >> 16) & 0xff) * t);
        int g = (int) (((from >> 8) & 0xff) * (1 - t) + ((to >> 8) & 0xff) * t);
        int b = (int) ((from & 0xff) * (1 - t) + (to & 0xff) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
