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
import android.graphics.Color;
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
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator;
import com.luckycatlabs.sunrisesunset.dto.Location;

import java.lang.ref.WeakReference;
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
        Paint mSunsetTimePaint;

        boolean mAmbient;

        Time mTime;

        float mTimeX;
        float mTimeY;
        float[] colorOffset;
        int[] colorGradient;
        Paint dialPaint;
        RectF mDialRectF;
        int mTextX, mTextY;
        int mTextSize = 35;
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

            Typeface font = Typeface.createFromAsset(getAssets(),"fonts/quicksand.ttf");
            mTimePaint = new Paint();
            mTimePaint = createTextPaint(resources.getColor(R.color.digital_text));
            mTimePaint.setTypeface(font);
            mTime = new Time();

            mSunsetTimePaint = new Paint();
            mSunsetTimePaint = createTextPaint(resources.getColor(R.color.digital_text));
            mSunsetTimePaint.setTextSize(mTextSize);
            mSunsetTimePaint.setTypeface(font);

            colorOffset = new float[]{0, 0.10f, 0.25f, 0.40f, 0.50f, 0.87f, 1f};
            colorGradient = getResources().getIntArray(R.array.day_colors);
            dialPaint = new Paint();
            dialPaint.setStyle(Paint.Style.STROKE);
            dialPaint.setColor(Color.CYAN);
            dialPaint.setStrokeWidth(3);
            dialPaint.setAntiAlias(true);

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
            paint.setAntiAlias(true);
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
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTimePaint.setTextSize(textSize);
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
                    mTimePaint.setAntiAlias(!inAmbientMode);
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

            String time = String.format("%d:%02d", mTime.hour, mTime.minute);

            // Draw the dial.
            if (mDialRectF == null)
            setDimensions(bounds.height(), bounds.width(), time);
            canvas.drawArc(mDialRectF, 0, 360, true, dialPaint);

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();

            canvas.drawText(time, mTimeX, mTimeY, mTimePaint);

            // Draw sunset time
            canvas.drawText("9 hours until sunset", mTextX, mTextY, mSunsetTimePaint);
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

        private void setDimensions(int height, int width, String time) {
            int radius, top, bottom, right, left;
            int offsetX = 10;
            int offsetY = 30;
            Rect textBounds = new Rect();
            mSunsetTimePaint.measureText("Hello");
            mSunsetTimePaint.getTextBounds(getSunsetText(), 0, getSunsetText().length(),textBounds);
            int dialHeight = height - textBounds.height();
            if (dialHeight >= width) {
                Log.d("qwerty","yo");
                offsetY += (dialHeight - width) / 2;
                radius = (width - offsetX * 2) / 2;
                top = offsetY;
                bottom = (radius * 2) + offsetY;
                right = radius * 2;
                left = offsetX;
            } else {
                Log.d("qwerty","noooo");
                radius = (dialHeight - offsetY * 2) / 2;
                offsetX += (width - radius * 2) / 2;
                top = offsetY;
                bottom = offsetY + radius * 2;
                right = offsetX + (radius * 2);
                left = offsetX;
            }

            Shader shader = new SweepGradient(offsetX + radius, offsetY + radius, colorGradient, colorOffset);
            dialPaint.setShader(shader);
            mDialRectF =  new RectF(left, top, right, bottom);
            mTextX = (width - textBounds.width()) / 2;
            mTextY = height;
            mTimePaint.getTextBounds(time, 0, time.length(), textBounds);
            mTimeX = offsetX + radius - (textBounds.width() / 2);
            mTimeY = offsetY + radius + (textBounds.height() / 2);

            Log.d("WatchText", "width:"+width + " TextWidth:" + textBounds.width());
        }

        public String getSunsetText() {
            Location location = new Location("39.9522222", "-75.1641667");
            SunriseSunsetCalculator calculator = new SunriseSunsetCalculator(location, "America/New_York");
            return calculator.getOfficialSunriseForDate(Calendar.getInstance());
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