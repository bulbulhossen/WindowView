package com.jmedeisis.windowview;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.support.annotation.NonNull;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.jmedeisis.windowview.sensor.TiltSensor;

/**
 * An ImageView that automatically pans in response to device tilt.
 * Currently only supports {@link android.widget.ImageView.ScaleType#CENTER_CROP}.
 */
public class WindowView extends ImageView implements TiltSensor.TiltListener {

    private float latestPitch;
    private float latestRoll;

    protected TiltSensor sensor;

    private static final float DEFAULT_MAX_PITCH_DEGREES = 30;
    private static final float DEFAULT_MAX_ROLL_DEGREES = 30;
    private static final float DEFAULT_HORIZONTAL_ORIGIN_DEGREES = 0;
    private static final float DEFAULT_VERTICAL_ORIGIN_DEGREES = 0;
    private float maxPitchDeg;
    private float maxRollDeg;
    private float horizontalOriginDeg;
    private float verticalOriginDeg;

    private static final int DEFAULT_SENSOR_SAMPLING_PERIOD_US = SensorManager.SENSOR_DELAY_GAME;
    private int sensorSamplingPeriod;

    /** Determines the basis in which device orientation is measured. */
    public enum OrientationMode {
        /** Measures absolute yaw / pitch / roll (i.e. relative to the world). */
        ABSOLUTE,
        /**
         * Measures yaw / pitch / roll relative to the starting orientation.
         * The starting orientation is determined upon receiving the first sensor data,
         * but can be manually reset at any time using {@link #resetOrientationOrigin(boolean)}.
         */
        RELATIVE
    }
    private static final OrientationMode DEFAULT_ORIENTATION_MODE = OrientationMode.RELATIVE;
    private OrientationMode orientationMode;

    private static final float DEFAULT_MAX_CONSTANT_TRANSLATION_DP = 150;
    private float maxConstantTranslation;

    /** Determines the relationship between change in device tilt and change in image translation. */
    public enum TranslateMode {
        /**
         * The image is translated by a constant amount per unit of device tilt.
         * Generally preferable when viewing multiple adjacent WindowViews that have different
         * contents but should move in tandem.
         * <p>
         * Same amount of tilt will result in the same translation for two images of differing size.
         */
        CONSTANT,
        /**
         * The image is translated proportional to its off-view size. Generally preferable when
         * viewing a single WindowView, this mode ensures that the full image can be 'explored'
         * within a fixed tilt amount range.
         * <p>
         * Same amount of tilt will result in different translation for two images of differing size.
         */
        PROPORTIONAL
    }
    private static final TranslateMode DEFAULT_TRANSLATE_MODE = TranslateMode.PROPORTIONAL;
    private TranslateMode translateMode;

    // layout
    protected boolean heightMatches;
    protected float widthDifference;
    protected float heightDifference;

    public WindowView(Context context) {
        super(context);
        init(context, null);
    }

    public WindowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public WindowView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public WindowView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs);
    }

    protected void init(Context context, AttributeSet attrs){
        sensorSamplingPeriod = DEFAULT_SENSOR_SAMPLING_PERIOD_US;
        maxPitchDeg = DEFAULT_MAX_PITCH_DEGREES;
        maxRollDeg = DEFAULT_MAX_ROLL_DEGREES;
        verticalOriginDeg = DEFAULT_VERTICAL_ORIGIN_DEGREES;
        horizontalOriginDeg = DEFAULT_HORIZONTAL_ORIGIN_DEGREES;
        orientationMode = DEFAULT_ORIENTATION_MODE;
        translateMode = DEFAULT_TRANSLATE_MODE;
        maxConstantTranslation = DEFAULT_MAX_CONSTANT_TRANSLATION_DP *
                getResources().getDisplayMetrics().density;

        if(null != attrs){
            final TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.WindowView);
            sensorSamplingPeriod = a.getInt(R.styleable.WindowView_sensor_sampling_period,
                    sensorSamplingPeriod);
            maxPitchDeg = a.getFloat(R.styleable.WindowView_max_pitch, maxPitchDeg);
            maxRollDeg = a.getFloat(R.styleable.WindowView_max_roll, maxRollDeg);
            verticalOriginDeg = a.getFloat(R.styleable.WindowView_vertical_origin,
                    verticalOriginDeg);
            horizontalOriginDeg = a.getFloat(R.styleable.WindowView_horizontal_origin,
                    horizontalOriginDeg);

            int orientationModeIndex = a.getInt(R.styleable.WindowView_orientation_mode, -1);
            if(orientationModeIndex >= 0){
                orientationMode = OrientationMode.values()[orientationModeIndex];
            }
            int translateModeIndex = a.getInt(R.styleable.WindowView_translate_mode, -1);
            if(translateModeIndex >= 0){
                translateMode = TranslateMode.values()[translateModeIndex];
            }

            maxConstantTranslation = a.getDimension(R.styleable.WindowView_max_constant_translation,
                    maxConstantTranslation);
            a.recycle();
        }

        if(!isInEditMode()) {
            sensor = new TiltSensor(context, orientationMode == OrientationMode.RELATIVE);
            sensor.addListener(this);
        }

        setScaleType(ScaleType.CENTER_CROP);
    }

    /*
     * LIFE-CYCLE
     * Registering for sensor events should be tied to Activity / Fragment lifecycle events.
     * However, this would mean that WindowView cannot be independent. We tie into a few
     * lifecycle-esque View events that allow us to make WindowView completely independent.
     *
     * Un-registering from sensor events is done aggressively to minimise battery drain and
     * performance impact.
     * ---------------------------------------------------------------------------------------------
     */
    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus){
        super.onWindowFocusChanged(hasWindowFocus);
        if(hasWindowFocus){
            sensor.startTracking(sensorSamplingPeriod);
        } else {
            sensor.stopTracking();
        }
    }

    @Override
    protected void onAttachedToWindow(){
        super.onAttachedToWindow();
        if(!isInEditMode()) sensor.startTracking(sensorSamplingPeriod);
    }

    @Override
    protected void onDetachedFromWindow(){
        super.onDetachedFromWindow();
        sensor.stopTracking();
    }

    /*
     * DRAWING & LAYOUT
     * ---------------------------------------------------------------------------------------------
     */
    @Override
    protected void onDraw(@NonNull Canvas canvas){
        // -1 -> 1
        float xOffset = 0f;
        float yOffset = 0f;
        if(heightMatches){
            // only let user tilt horizontally
            xOffset = (-horizontalOriginDeg +
                    clampAbsoluteFloating(horizontalOriginDeg, latestRoll, maxRollDeg)) / maxRollDeg;
        } else {
            // only let user tilt vertically
            yOffset = (verticalOriginDeg -
                    clampAbsoluteFloating(verticalOriginDeg, latestPitch, maxPitchDeg)) / maxPitchDeg;
        }
        canvas.save();
        switch(translateMode){
            case CONSTANT:
                canvas.translate(
                        clampAbsoluteFloating(0, maxConstantTranslation * xOffset, widthDifference / 2),
                        clampAbsoluteFloating(0, maxConstantTranslation * yOffset, heightDifference / 2));
                break;
            case PROPORTIONAL:
                canvas.translate(Math.round((widthDifference / 2) * xOffset),
                        Math.round((heightDifference / 2) * yOffset));
                break;
        }
        super.onDraw(canvas);
        canvas.restore();
    }

    protected float clampAbsoluteFloating(float origin, float value, float maxAbsolute){
        return value < origin ?
                Math.max(value, origin - maxAbsolute) : Math.min(value, origin + maxAbsolute);
    }

    /** See {@link TranslateMode}. */
    public void setTranslateMode(TranslateMode translateMode){
        this.translateMode = translateMode;
    }

    public TranslateMode getTranslateMode(){
        return translateMode;
    }

    /** Maximum image translation from center when using {@link TranslateMode#CONSTANT}. */
    public void setMaxConstantTranslation(float maxConstantTranslation){
        this.maxConstantTranslation = maxConstantTranslation;
    }

    public float getMaxConstantTranslation(){
        return maxConstantTranslation;
    }

    /** Maximum angle (in degrees) from origin for vertical tilts. */
    public void setMaxPitch(float maxPitch) {
        this.maxPitchDeg = maxPitch;
    }

    public float getMaxPitch() {
        return maxPitchDeg;
    }

    /** Maximum angle (in degrees) from origin for horizontal tilts. */
    public void setMaxRoll(float maxRoll) {
        this.maxRollDeg = maxRoll;
    }

    public float getMaxRoll() {
        return maxRollDeg;
    }

    /**
     * Horizontal origin (in degrees). When {@link #latestRoll} equals this value, the image
     * is centered horizontally.
     */
    public void setHorizontalOrigin(float horizontalOrigin) {
        this.horizontalOriginDeg = horizontalOrigin;
    }

    public float getHorizontalOrigin() {
        return horizontalOriginDeg;
    }

    /**
     * Vertical origin (in degrees). When {@link #latestPitch} equals this value, the image
     * is centered vertically.
     */
    public void setVerticalOrigin(float verticalOrigin) {
        this.verticalOriginDeg = verticalOrigin;
    }

    public float getVerticalOrigin() {
        return verticalOriginDeg;
    }

    @Override
    public void setImageDrawable(Drawable drawable){
        super.setImageDrawable(drawable);
        recalculateImageDimensions();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh){
        super.onSizeChanged(w, h, oldw, oldh);
        recalculateImageDimensions();
    }

    protected void recalculateImageDimensions(){
        Drawable drawable = getDrawable();
        if(null == drawable) return;

        ScaleType scaleType = getScaleType();
        float width = getWidth();
        float height = getHeight();
        float imageWidth = drawable.getIntrinsicWidth();
        float imageHeight = drawable.getIntrinsicHeight();

        heightMatches = !widthRatioGreater(width, height, imageWidth, imageHeight);

        switch (scaleType){
            case CENTER_CROP:
                if(heightMatches){
                    imageWidth *= height / imageHeight;
                    imageHeight = height;
                } else {
                    imageWidth = width;
                    imageHeight *= width / imageWidth;
                }
                widthDifference = imageWidth - width;
                heightDifference = imageHeight - height;
                break;
            default:
                widthDifference = 0;
                heightDifference = 0;
                break;
        }
    }

    private static boolean widthRatioGreater(float width, float height,
                                             float otherWidth, float otherHeight){
        return height / otherHeight < width / otherWidth;
    }

    @Override
    public void setScaleType(ScaleType scaleType){
        if(ScaleType.CENTER_CROP != scaleType)
            throw new IllegalArgumentException("Image scale type " + scaleType +
                    " is not supported by WindowView. Use CENTER_CROP instead.");
        super.setScaleType(scaleType);
    }

    /*
     * SENSOR DATA
     * ---------------------------------------------------------------------------------------------
     */
    @Override
    public void onTiltUpdate(float yaw, float pitch, float roll) {
        this.latestPitch = pitch;
        this.latestRoll = roll;
        invalidate();
    }

    public void addTiltListener(TiltSensor.TiltListener listener){
        sensor.addListener(listener);
    }

    public void removeTiltListener(TiltSensor.TiltListener listener){
        sensor.removeListener(listener);
    }

    /**
     * Manually resets the orientation origin. Has no effect unless {@link #getOrientationMode()}
     * is {@link OrientationMode#RELATIVE}.
     *
     * @param immediate if false, the sensor values smoothly interpolate to the new origin.
     */
    public void resetOrientationOrigin(boolean immediate){
        sensor.resetOrigin(immediate);
    }

    /**
     * Determines the mapping of orientation to image offset.
     * See {@link OrientationMode}.
     */
    public void setOrientationMode(OrientationMode orientationMode){
        this.orientationMode = orientationMode;
        sensor.setTrackRelativeOrientation(orientationMode == OrientationMode.RELATIVE);
        sensor.resetOrigin(true);
    }

    public OrientationMode getOrientationMode(){
        return orientationMode;
    }

    /**
     * @param samplingPeriodUs see {@link SensorManager#registerListener(SensorEventListener, Sensor, int)}
     */
    public void setSensorSamplingPeriod(int samplingPeriodUs){
        this.sensorSamplingPeriod = samplingPeriodUs;
        if(sensor.isTracking()){
            sensor.stopTracking();
            sensor.startTracking(this.sensorSamplingPeriod);
        }
    }

    /** @return sensor sampling period (in microseconds). */
    public int getSensorSamplingPeriod(){
        return sensorSamplingPeriod;
    }
}
