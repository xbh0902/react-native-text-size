package com.github.amarcruz.rntextsize;

import android.content.res.AssetManager;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.BoringLayout;
import android.text.Layout;
import android.text.Spannable;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.DisplayMetricsHolder;
import com.facebook.react.uimanager.PixelUtil;
import com.facebook.react.views.text.ReactFontManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RNTextSizeModule extends ReactContextBaseJavaModule {
    private static final String TAG = "RNTextSize";
    private static final boolean _DEBUG = true;
    private static final float SPACING_ADDITION = 0f;
    private static final float SPACING_MULTIPLIER = 1f;

    private static final String E_MISSING_TEXT = "E_MISSING_TEXT";
    private static final String E_MISSING_PARAMETER = "E_MISSING_PARAMETER";
    private static final String E_INVALID_FONT_SPEC = "E_INVALID_FONT_SPEC";
    private static final String E_UNKNOWN_ERROR = "E_UNKNOWN_ERROR";

    // It's important to pass the ANTI_ALIAS_FLAG flag to the constructor rather than setting it
    // later by calling setFlags. This is because the latter approach triggers a bug on Android 4.4.2.
    // The bug is that unicode emoticons aren't measured properly which causes text to be clipped.
    private static final TextPaint sTextPaintInstance = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);

    private ReactApplicationContext mReactContext;

    RNTextSizeModule(ReactApplicationContext reactContext) {
        super(reactContext);
        mReactContext = reactContext;
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        final Map<String, Object> fontSizes = new HashMap<>();

        fontSizes.put("default", RNTextSizeConf.getDefaultFontSize());
        fontSizes.put("button", 14.0f);
        fontSizes.put("label", 16.0f);
        fontSizes.put("smallSystem", 12.0f);
        fontSizes.put("system", 14.0f);

        constants.put("FontSize", fontSizes);
        return constants;
    }

    /**
     * Based on ReactTextShadowNode.java
     */
    @SuppressWarnings("unused")
    @ReactMethod
    public void measure(@Nullable final ReadableMap specs, final Promise promise) {
        final RNTextSizeConf conf = getConf(specs, promise, true);
        if (conf == null) {
            return;
        }

        final String _text = conf.getString("text");
        if (_text == null) {
            promise.reject(E_MISSING_TEXT, "Missing required text.");
            return;
        }

        final float density = getCurrentDensity();
        final boolean includeFontPadding = conf.includeFontPadding;
        final WritableMap result = Arguments.createMap();

        if (_text.isEmpty()) {
            // RN 0.56 consistently sets the height at 14dp divided by the density
            // plus 1 if includeFontPadding when text is empty, so we do the same.
            float height = (14.0f / density) + (includeFontPadding ? 1 : 0);
            result.putInt("width", 0);
            result.putDouble("height", height);
            result.putInt("lastLineWidth", 0);
            result.putInt("lineCount", 0);
            promise.resolve(result);
            return;
        }

        final Spannable text = RNTextSizeSpannedText.spannedFromSpecsAndText(mReactContext, conf, _text);
        final TextPaint textPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);

        // No width or width <= 0 becomes an unconstrained width
        float width = conf.getFloatOrNaN("width");
        if (!Float.isNaN(width) && width > 0) {
            width = width * density;                // always DIP
        } else {
            width = Float.MAX_VALUE;
        }

        Layout layout = null;
        try {
            final BoringLayout.Metrics boring = BoringLayout.isBoring(text, textPaint);
            int hintWidth = (int) width;

            if (boring == null) {
                // Not boring, ie. the text is multiline or contains unicode characters.
                final float desiredWidth = Layout.getDesiredWidth(text, textPaint);
                if (desiredWidth <= width) {
                    hintWidth = (int) Math.ceil(desiredWidth);
                }
            } else if (boring.width <= width) {
                // Single-line and width unknown or bigger than the width of the text.
                layout = BoringLayout.make(
                        text,
                        textPaint,
                        boring.width,
                        Layout.Alignment.ALIGN_NORMAL,
                        SPACING_MULTIPLIER,
                        SPACING_ADDITION,
                        boring,
                        includeFontPadding);
            }

            if (layout == null) {
                Log.d(TAG,"Creating non-boring layout of width: " + hintWidth);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    final int textBreakStrategy = conf.getTextBreakStrategy();
                    Log.d(TAG, "Using textBreakStrategy = " + textBreakStrategy);

                    layout = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, hintWidth)
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setBreakStrategy(textBreakStrategy)
                            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                            .setIncludePad(includeFontPadding)
                            .setLineSpacing(SPACING_ADDITION, SPACING_MULTIPLIER)
                            .build();
                } else {
                    layout = new StaticLayout(
                            text,
                            textPaint,
                            hintWidth,
                            Layout.Alignment.ALIGN_NORMAL,
                            SPACING_MULTIPLIER,
                            SPACING_ADDITION,
                            includeFontPadding
                    );
                }
            }

            final int lineCount = layout.getLineCount();
            float rectWidth;
            float lastWidth = 0f; // also for lastLineWidth

            // go more faster?
            if (conf.getBooleanOrTrue("usePreciseWidth")) {
                // Layout.getWidth() returns the configured max width, we must
                // go slow to get the used one (and with the text trimmed).
                rectWidth = 0f;
                for (int i = 0; i < lineCount; i++) {
                    lastWidth = layout.getLineMax(i);
                    if (lastWidth > rectWidth) {
                        rectWidth = lastWidth;
                    }
                }
            } else {
                rectWidth = layout.getWidth();
            }

            result.putDouble("width", Math.min(rectWidth / density, width));
            result.putDouble("height", layout.getHeight() / density);
            result.putDouble("lastLineWidth", lastWidth / density);
            result.putInt("lineCount", lineCount);

            if (_DEBUG) {
                result.putInt("_topPadding", layout.getTopPadding());
                result.putInt("_bottomPadding", layout.getBottomPadding());
                result.putDouble("_rectWidth", rectWidth);
            }
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject(E_UNKNOWN_ERROR, e);
        }
    }

    // https://stackoverflow.com/questions/3654321/measuring-text-height-to-be-drawn-on-canvas-android
    @SuppressWarnings("unused")
    @ReactMethod
    public void getTextBounds(@Nullable final ReadableMap specs, final Promise promise) {
        final RNTextSizeConf conf = getConf(specs, promise, true);
        if (conf == null) {
            return;
        }
        final String text = conf.getString("text");
        if (text == null) {
            promise.reject(E_MISSING_TEXT, "Missing required text.");
            return;
        }
        final TextPaint textPaint = sTextPaintInstance;
        final Rect bounds = new Rect();

        resetPaintWithFont(textPaint, conf);
        textPaint.getTextBounds(text, 0, text.length(), bounds);

        final float density = getCurrentDensity();
        final WritableMap result = Arguments.createMap();
        result.putDouble("width", bounds.width() / density);
        result.putDouble("height", bounds.height() / density);

        promise.resolve(result);
    }

    /**
     * See https://material.io/design/typography/#type-scale
     *
     * TODO:
     * Send PR to RN for supporting textTransform, like the iOS one in
     * https://github.com/facebook/react-native/commit/8621d4b79731e13a0c6e397abd93c193c6219000
     */
    @SuppressWarnings("unused")
    @ReactMethod
    public void specsForTextStyles(final Promise promise) {
        WritableMap result = Arguments.createMap();

        result.putMap("h1", makeFontSpecs("-light", 96, -1.5));
        result.putMap("h2", makeFontSpecs("-light", 60, -0.5));
        result.putMap("h3", makeFontSpecs(null, 48, 0));
        result.putMap("h4", makeFontSpecs(null, 34, 0.25));
        result.putMap("h5", makeFontSpecs(null, 24, 0));
        result.putMap("h6", makeFontSpecs("-medium", 20, 0.15));
        result.putMap("subtitle1", makeFontSpecs(null, 16, 0.15));
        result.putMap("subtitle2", makeFontSpecs("-medium", 14, 0.1));
        result.putMap("body1", makeFontSpecs(null, 16, 0.5));
        result.putMap("body2", makeFontSpecs(null, 14, 0.25));
        result.putMap("button", makeFontSpecs("-medium", 14, 0.75));
        result.putMap("caption", makeFontSpecs(null, 12, 0.4));
        result.putMap("overline", makeFontSpecs(null, 10, 1.5));

        promise.resolve(result);
    }

    /**
     * https://stackoverflow.com/questions/27631736
     * /meaning-of-top-ascent-baseline-descent-bottom-and-leading-in-androids-font
     */
    @SuppressWarnings("unused")
    @ReactMethod
    public void fontFromSpecs(@Nullable final ReadableMap specs, final Promise promise) {
        final RNTextSizeConf conf = getConf(specs, promise);
        if (conf == null) {
            return;
        }
        final TextPaint textPaint = sTextPaintInstance;
        final Typeface typeface = resetPaintWithFont(textPaint, conf);

        if (typeface != null) {
            promise.resolve(fontInfoFromTypeface(textPaint, typeface, conf));
        } else {
            promise.reject(E_INVALID_FONT_SPEC, "Invalid font specification.");
        }
    }

    @SuppressWarnings("unused")
    @ReactMethod
    public void fontFamilyNames(final Promise promise) {
        final boolean lollipop = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
        final WritableArray names = Arguments.createArray();

        names.pushString("sans-serif");
        names.pushString("sans-serif-condensed");
        if (lollipop) {
            names.pushString("sans-serif-thin");
            names.pushString("sans-serif-light");
            names.pushString("sans-serif-medium");
            names.pushString("sans-serif-black");
            names.pushString("sans-serif-smallcaps");
            names.pushString("sans-serif-condensed-light");
        } else {
            // SDK 16
            names.pushString("sans-serif-light");
        }
        names.pushString("serif");
        names.pushString("monospace");
        if (lollipop) {
            names.pushString("serif-monospace");
            names.pushString("casual");
            names.pushString("cursive");
        }

        getFontsInAssets(names);
        promise.resolve(names);
    }

    /**
     * TODO:
     * Maybe some day?
     */
    @SuppressWarnings("unused")
    @ReactMethod
    public void fontNamesForFamilyName(final String ignored, final Promise promise) {
        promise.resolve(null);
    }

    // ============================================================================
    //
    //      Non-exposed instance & static methods
    //
    // ============================================================================

    @Nullable
    private RNTextSizeConf getConf(final ReadableMap specs, final Promise promise, boolean forText) {
        if (specs == null) {
            promise.reject(E_MISSING_PARAMETER, "Missing parameter object.");
            return null;
        }
        return new RNTextSizeConf(specs, forText);
    }

    @Nullable
    private RNTextSizeConf getConf(final ReadableMap specs, final Promise promise) {
        return getConf(specs, promise, false);
    }

    /**
     * Make a Typeface from the supplied specification.
     * @param conf Font specification.
     */
    @NonNull
    private Typeface createTypefaceFromConf(
            @NonNull final RNTextSizeConf conf
    ) {
        final String fontFamily = conf.fontFamily;
        final int style = conf.fontStyle;
        Typeface typeface;

        // Unfortunately, RN will return a valid font Typeface (usually Roboto) even if the font is
        // not installed or has type error in its name. In any case we check if it is null.
        if (fontFamily != null) {
            final AssetManager assetManager = mReactContext.getAssets();
            typeface = ReactFontManager.getInstance().getTypeface(fontFamily, style, assetManager);
        } else {
            typeface = null;
        }

        return typeface != null ? typeface : Typeface.defaultFromStyle(style);
    }

    /**
     * Reinitialize the TextPaint object with the required font.
     * @param conf Font specification.
     * @return The typeface used to draw in the TextPaint, null if error.
     */
    private Typeface resetPaintWithFont(
            @NonNull final TextPaint paint,
            @NonNull final RNTextSizeConf conf,
            @NonNull Typeface typeface
    ) {
        paint.reset();
        paint.setTypeface(typeface);

        final int fontSize = (int) Math.ceil(conf.allowFontScaling
                ? PixelUtil.toPixelFromSP(conf.fontSize)
                : PixelUtil.toPixelFromDIP(conf.fontSize));
        paint.setTextSize(fontSize);
        Log.d(TAG, "paint -> fontSize " + conf.fontSize +
                ", textSize(): " + paint.getTextSize() + ", allowFontScaling " + conf.allowFontScaling);

        // No need to reset letterSpacing after paint.reset()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (!Float.isNaN(conf.letterSpacing)) {
                final float letterSpacing = conf.allowFontScaling
                        ? PixelUtil.toPixelFromSP(conf.letterSpacing)
                        : PixelUtil.toPixelFromDIP(conf.letterSpacing);
                paint.setLetterSpacing(letterSpacing / paint.getTextSize());
            }
        }

        return typeface;
    }

    private Typeface resetPaintWithFont(@NonNull final TextPaint paint, @NonNull final RNTextSizeConf conf) {
        return resetPaintWithFont(paint, conf, createTypefaceFromConf(conf));
    }

    /**
     * This is for 'fontFromFontStyle', makes the minimal info required.
     * @param suffix The font variant
     * @param fontSize Font size in SP
     * @param letterSpacing Sugest this to user
     * @return map with specs
     */
    private WritableMap makeFontSpecs(String suffix, int fontSize, double letterSpacing) {
        final WritableMap map = Arguments.createMap();
        final String roboto = "sans-serif";

        map.putString("fontFamily", suffix != null ? (roboto + suffix) : roboto);
        map.putString("fontStyle", "normal");
        map.putString("fontWeight", "normal"); // the font determines the weight
        map.putInt("fontSize", fontSize);

        if (RNTextSizeConf.supportLetterSpacing()) {
            map.putDouble("letterSpacing", letterSpacing);
        }

        return map;
    }

    @NonNull
    private WritableMap fontInfoFromTypeface(
            @NonNull final TextPaint textPaint,
            @NonNull final Typeface typeface,
            @NonNull final RNTextSizeConf conf
    ) {
        // Info is always in unscaled values
        final float density = getCurrentDensity();
        final Paint.FontMetrics metrics = new Paint.FontMetrics();
        final float lineHeight = textPaint.getFontMetrics(metrics);

        final WritableMap info = Arguments.createMap();
        info.putString("fontFamily", conf.getString("fontFamily"));
        info.putString("fontWeight", typeface.isBold() ? "bold" : "normal");
        info.putString("fontStyle", typeface.isItalic() ? "italic" : "normal");
        info.putDouble("fontSize", textPaint.getTextSize() / density);
        info.putDouble("leading", metrics.leading / density);
        info.putDouble("ascender", metrics.ascent / density);
        info.putDouble("descender", metrics.descent / density);
        info.putDouble("top", metrics.top / density);
        info.putDouble("bottom", metrics.bottom / density);
        info.putDouble("lineHeight", lineHeight / density);
        info.putInt("_hash", typeface.hashCode());
        return info;
    }

    /**
     * Retuns the current density.
     */
    private float getCurrentDensity() {
        //noinspection deprecation
        return DisplayMetricsHolder.getWindowDisplayMetrics().density;
    }

    private static final String[] FILE_EXTENSIONS = {".ttf", ".otf"};
    private static final String FONTS_ASSET_PATH = "fonts";

    private String[] fontsInAssets = null;

    /**
     * Set the font names in assets/fonts into the target array.
     * @param destArr Target
     */
    private void getFontsInAssets(@NonNull WritableArray destArr) {
        String[] srcArr = fontsInAssets;

        if (srcArr == null) {
            final AssetManager assetManager = mReactContext.getAssets();
            ArrayList<String> tmpArr = new ArrayList<>();

            if (assetManager != null) {
                try {
                    String[] list = assetManager.list(FONTS_ASSET_PATH);

                    for (String spec : list) {
                        addFamilyToArray(tmpArr, spec);
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }

            Collections.sort(tmpArr, String.CASE_INSENSITIVE_ORDER);
            fontsInAssets = srcArr = tmpArr.toArray(new String[0]);
        }

        for (String name : srcArr) {
            destArr.pushString(name);
        }
    }

    private void addFamilyToArray(
            @NonNull final List<String> outArr,
            @NonNull final String spec
    ) {
        for (String ext : FILE_EXTENSIONS) {
            if (spec.endsWith(ext)) {
                final String name = spec.substring(0, spec.length() - ext.length());

                if (!outArr.contains(name)) {
                    outArr.add(name);
                }
                break;
            }
        }
    }
}
