/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dhruvdangi.watchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator;
import com.luckycatlabs.sunrisesunset.dto.Location;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mTimeAmPmPaint;
        Paint mSecondaryTextPaint;

        boolean mAmbient;

        Time mTime;

        float mTimeX, mTimeY;
        float mTimeAmPmX, mTimeAmPmY;
        float mTextX, mTextY;
        float mSunRadius;
        float[] colorOffset;
        int[] colorGradient;
        Paint mDialPaint;
        Paint mSunPaint;
        Paint mSunGlowPaint;
        RectF mDialRectF;
        RectF mSunRectF;
        RectF mSunGlowRectF;
        int mTextSize = 25;
        float SCALING_FACTOR = 0.90f;
        float centerX, centerY;
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mTimeY = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.digital_background));

            Typeface font = Typeface.createFromAsset(getAssets(), "fonts/JosefinSansLight.ttf");
            mTimePaint = new Paint();
            mTimePaint = createTextPaint(resources.getColor(R.color.digital_text));
            mTimePaint.setTypeface(font);
            mTimePaint.setFlags(Paint.ANTI_ALIAS_FLAG);
            mTimeAmPmPaint = new Paint();
            mTimeAmPmPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mTimeAmPmPaint.setTypeface(font);
            mTimeAmPmPaint.setFlags(Paint.ANTI_ALIAS_FLAG);
            mTime = new Time();

            mSecondaryTextPaint = new Paint();
            mSecondaryTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mSecondaryTextPaint.setTextSize(mTextSize);
            mSecondaryTextPaint.setTypeface(font);

            colorOffset = new float[]{0.062f, 0.16f, 0.25f, 0.38f, 0.437f, 0.562f, 0.75f, 0.937f, 1f};
            colorGradient = getResources().getIntArray(R.array.day_colors);
            mDialPaint = new Paint();
            mDialPaint.setStyle(Paint.Style.STROKE);
            mDialPaint.setStrokeJoin(Paint.Join.ROUND);
            mDialPaint.setStrokeCap(Paint.Cap.ROUND);
            mDialPaint.setFlags(Paint.ANTI_ALIAS_FLAG);
            mDialPaint.setStrokeWidth(1.5f);
            mSunPaint = new Paint();
            mSunPaint.setStyle(Paint.Style.FILL);
            mSunPaint.setFlags(Paint.ANTI_ALIAS_FLAG);
            mSunGlowPaint = new Paint();
            mSunGlowPaint.set(mSunPaint);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setFlags(Paint.ANTI_ALIAS_FLAG);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mTimeX = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_time_size_round : R.dimen.digital_time_size);
            mTimePaint.setTextSize(timeTextSize);
            float timeAmPmTextSize = resources.getDimension(isRound ? R.dimen.digital_time_am_pm_size_round : R.dimen.digital_time_am_pm_size);
            mTimeAmPmPaint.setTextSize(timeAmPmTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mDialPaint.setAntiAlias(!inAmbientMode);
                    mDialPaint.setColor(getResources().getColor(R.color.white));
                    mDialPaint.setShader(null);
                }
                if (!inAmbientMode){
                    Shader shader = new SweepGradient(centerX ,centerY, colorGradient, colorOffset);
                    mDialPaint.setShader(shader);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            Calendar calendar = Calendar.getInstance();
            mTime.setToNow();
//            String time = String.format("%02d:%02d", mTime.hour >= 12 ? mTime.hour - 12 : mTime.hour, mTime.minute);
            String time = new SimpleDateFormat("hh:mm a").format(calendar.getTime());
            String timeAmPm = mTime.hour >= 12 ? "PM" : "AM";

            // Draw the dial.
            if (mDialRectF == null)
            setDimensions(bounds.height(), bounds.width(), time, timeAmPm);
            canvas.drawArc(mDialRectF, 0, 360, true, mDialPaint);

            canvas.drawText(time, 0, time.length() - 2, mTimeX, mTimeY, mTimePaint);
            canvas.drawText(time, time.length() - 2, time.length(), mTimeAmPmX, mTimeAmPmY, mTimeAmPmPaint);
//            canvas.drawText(time, mTimeAmPmX, mTimeAmPmY, mTimeAmPmPaint);

            // Draw sunset time
            canvas.drawText(getSunsetText(), mTextX, mTextY, mSecondaryTextPaint);
            if (mTime.hour > 5 && mTime.hour < 19) {
                mSunPaint.setColor(getResources().getColor(R.color.sun_color));
                mSunGlowPaint.setColor(getResources().getColor(R.color.sun_glow_color));
            }
            else {
                mSunPaint.setColor(getResources().getColor(R.color.moon_color));
                mSunGlowPaint.setColor(getResources().getColor(R.color.moon_glow_color));
            }
            canvas.drawArc(mSunRectF, 0, 360, true, mSunPaint);
            canvas.drawArc(mSunGlowRectF, 0, 360, true, mSunGlowPaint);

        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private void setDimensions(int height, int width, String time, String timeAmPm) {
            float mDialRadius;
            mDialRadius = height >= width ? width * SCALING_FACTOR / 2 : height * SCALING_FACTOR / 2;
            mSunRadius = mDialRadius * 0.05f;
            centerX = width / 2;
            centerY = height / 2;

            Shader shader = new SweepGradient(centerX,centerY, colorGradient, colorOffset);
            mDialPaint.setShader(shader);
            mDialRectF =  new RectF(centerX - mDialRadius, centerY - mDialRadius, centerX + mDialRadius, centerY + mDialRadius);

            // Time's position
            Rect timeBounds = new Rect();
            mTimePaint.getTextBounds(time, 0, time.length() - 2, timeBounds);
            Rect timeAmPmBounds = new Rect();
            mTimeAmPmPaint.getTextBounds(time, time.length() - 2, time.length(), timeAmPmBounds);
            mTimeX = centerX - (timeBounds.width()  / 2) - (timeAmPmBounds.width() / 2);
            mTimeY = centerY + (timeBounds.height() / 2);
            mTimeAmPmX = mTimeX + timeBounds.width() + getResources().getDimension(R.dimen.digital_time_offset);
            mTimeAmPmY = mTimeY;

            //Secondary Text Position
            Rect sunsetBounds = new Rect();
            mSecondaryTextPaint.getTextBounds(getSunsetText(), 0, getSunsetText().length(), sunsetBounds);
            mTextX = centerX - (sunsetBounds.width() / 2);
            mTextY = centerY + timeBounds.height() + sunsetBounds.height();

            //Sun's Position
            float outerX = centerX + (float) Math.sin(2 * Math.PI * ((Calendar.getInstance().get(Calendar.HOUR_OF_DAY) + Calendar.getInstance().get(Calendar.MINUTE)*0.016 + 12) / 24.0)) * mDialRadius;
            float outerY = centerY + (float) ( -1 * Math.cos(2 * Math.PI * ((Calendar.getInstance().get(Calendar.HOUR_OF_DAY) + Calendar.getInstance().get(Calendar.MINUTE)*0.016 + 12) / 24.0)) * mDialRadius);

            mSunRectF = new RectF(outerX - mSunRadius, outerY - mSunRadius, outerX + mSunRadius, outerY + mSunRadius);
            mSunGlowRectF = new RectF(outerX - mSunRadius - 5, outerY - mSunRadius - 5, outerX + mSunRadius + 5, outerY + mSunRadius + 5);

        }

        public String getSunsetText() {
            Location location = new Location("28.4211", "77.3078");
            SunriseSunsetCalculator calculator = new SunriseSunsetCalculator(location, "India/Haryana");
//            return calculator.getOfficialSunriseForDate(Calendar.getInstance()) + " until sunset";
            return "5247 Steps";
        }
    }


    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }
}
