/*
 * Copyright (C) 2012-2013 Reece H. Dunn
 * Copyright (C) 2011 Google Inc.
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

/*
 * This file implements the Java API to eSpeak using the JNI bindings.
 *
 * Android Version: 4.0 (Ice Cream Sandwich)
 * API Version:     14
 */

package com.reecedunn.espeak;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.speech.tts.TextToSpeech;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SpeechSynthesis {
    private static final String TAG = SpeechSynthesis.class.getSimpleName();

    public static final int GENDER_UNSPECIFIED = 0;
    public static final int GENDER_MALE = 1;
    public static final int GENDER_FEMALE = 2;

    public static final int AGE_ANY = 0;
    public static final int AGE_YOUNG = 12;
    public static final int AGE_OLD = 60;

    static {
        System.loadLibrary("ttsespeak");

        nativeClassInit();
    }

    private final Context mContext;
    private final SynthReadyCallback mCallback;
    private final String mDatapath;

    private boolean mInitialized = false;

    public SpeechSynthesis(Context context, SynthReadyCallback callback) {
        // First, ensure the data directory exists, otherwise init will crash.
        final File dataPath = CheckVoiceData.getDataPath(context);

        if (!dataPath.exists()) {
            Log.e(TAG, "Missing voice data");
            dataPath.mkdirs();
        }

        mContext = context;
        mCallback = callback;
        mDatapath = dataPath.getParentFile().getPath();

        attemptInit();
    }

    @Override
    protected void finalize() {
        nativeDestroy();
    }

    public static String getVersion() {
        return nativeGetVersion();
    }

    public int getSampleRate() {
        return nativeGetSampleRate();
    }

    public int getChannelCount() {
        return nativeGetChannelCount();
    }

    public int getAudioFormat() {
        return nativeGetAudioFormat();
    }

    public int getBufferSizeInBytes() {
        final int bufferSizeInMillis = nativeGetBufferSizeInMillis();
        final int sampleRate = nativeGetSampleRate();
        return (bufferSizeInMillis * sampleRate) / 1000;
    }

    public List<Voice> getAvailableVoices() {
        final List<Voice> voices = new LinkedList<Voice>();
        final String[] results = nativeGetAvailableVoices();

        for (int i = 0; i < results.length; i += 4) {
            final String name = results[i];
            final String identifier = results[i + 1];
            final int gender = Integer.parseInt(results[i + 2]);
            final int age = Integer.parseInt(results[i + 3]);
            final Locale locale;
            if (name.equals("fa-pin")) {
                // Android locales do not support scripts, so fa-Latn is not possible for Farsi Pinglish:
                locale = null;
            }
            else if (name.equals("en-sc")) {
                // 'SC' is not a country code.
                locale = new Locale("en", "GB", "scotland");
            } else if (name.equals("en-wi")) {
                // 'WI' is not a country code.
                locale = new Locale("en", "029");
            } else if (name.equals("es-la")) {
                // 'LA' is the country code for Laos, not Latin America.
                locale = new Locale("es", "419");
            } else if (name.equals("hy-west")) {
                // 'west' is not a country code.
                locale = new Locale("hy", "", "arevmda");
            } else if (name.equals("vi-hue")) {
                // 'hue' is for the Hue Province accent/dialect (Central Vietnamese).
                locale = new Locale("vi", "VN", "hue");
            } else if (name.equals("vi-sgn")) {
                // 'sgn' is for the Saigon accent/dialect (South Vietnamese).
                locale = new Locale("vi", "VN", "saigon");
            } else if (name.equals("zh-yue")) {
                // Android/Java does not support macrolanguages.
                locale = new Locale("zh", "HK");
            } else {
                String[] parts = name.split("-");
                switch (parts.length) {
                case 1: // language
                    locale = new Locale(parts[0]);
                    break;
                case 2: // language-country
                    if (parts[1].equals("uk")) {
                        // 'uk' is the language code for Ukranian, not Great Britain.
                        parts[1] = "GB";
                    }
                    locale = new Locale(parts[0], parts[1]);
                    break;
                case 3: // language-country-variant
                    if (parts[1].equals("uk")) {
                        // 'uk' is the language code for Ukranian, not Great Britain.
                        parts[1] = "GB";
                    }
                    locale = new Locale(parts[0], parts[1], parts[2]);
                    break;
                default:
                    locale = null;
                }
            }

            if (locale != null && !locale.getISO3Language().equals("")) {
                final Voice voice = new Voice(name, identifier, gender, age, locale);
                voices.add(voice);
            }
        }

        return voices;
    }

    public void setVoice(Voice voice, VoiceVariant variant) {
        // NOTE: espeak_SetVoiceByProperties does not support specifying the
        // voice variant (e.g. klatt), but espeak_SetVoiceByName does.
        if (variant.variant == null) {
            nativeSetVoiceByProperties(voice.name, variant.gender, variant.age);
        } else {
            nativeSetVoiceByName(voice.identifier + "+" + variant.variant);
        }
    }

    /** Don't announce any punctuation characters. */
    public static int PUNCT_NONE = 0;

    /** Announce every punctuation character. */
    public static int PUNCT_ALL = 1;

    /** Announce some of the punctuation characters. */
    public static int PUNCT_SOME = 2;

    public enum UnitType {
        Percentage,
        WordsPerMinute,
        /** One of the PUNCT_* constants. */
        Punctuation,
    }

    public class Parameter {
        private final int id;
        private final int min;
        private final int max;
        private final UnitType unitType;

        private Parameter(int id, int min, int max, UnitType unitType) {
            this.id = id;
            this.min = min;
            this.max = max;
            this.unitType = unitType;
        }

        public int getMinValue() {
            return min;
        }

        public int getMaxValue() {
            return max;
        }

        public int getDefaultValue() {
            return nativeGetParameter(id, 0);
        }

        public int getValue() {
            return nativeGetParameter(id, 1);
        }

        public void setValue(int value, int scale) {
            setValue((value * scale) / 100);
        }

        public void setValue(int value) {
            nativeSetParameter(id, value);
        }

        public UnitType getUnitType() {
            return unitType;
        }
    }

    /** Speech rate. */
    public final Parameter Rate = new Parameter(1, 80, 450, UnitType.WordsPerMinute);

    /** Audio volume. */
    public final Parameter Volume = new Parameter(2, 0, 200, UnitType.Percentage);

    /** Base pitch. */
    public final Parameter Pitch = new Parameter(3, 0, 100, UnitType.Percentage);

    /** Pitch range (monotone = 0). */
    public final Parameter PitchRange = new Parameter(4, 0, 100, UnitType.Percentage);

    /** Which punctuation characters to announce. */
    public final Parameter Punctuation = new Parameter(5, 0, 2, UnitType.Punctuation);

    public void synthesize(String text, boolean isSsml) {
        nativeSynthesize(text, isSsml);
    }

    public void stop() {
        nativeStop();
    }

    private void nativeSynthCallback(byte[] audioData) {
        if (mCallback == null)
            return;

        if (audioData == null) {
            mCallback.onSynthDataComplete();
        } else {
            mCallback.onSynthDataReady(audioData);
        }
    }

    private void attemptInit() {
        if (mInitialized) {
            return;
        }

        if (!CheckVoiceData.hasBaseResources(mContext)) {
            Log.e(TAG, "Missing base resources");
            return;
        }

        if (!nativeCreate(mDatapath)) {
            Log.e(TAG, "Failed to initialize speech synthesis library");
            return;
        }

        Log.i(TAG, "Initialized synthesis library with sample rate = " + getSampleRate());

        mInitialized = true;
    }

    public static String getSampleText(Context context, Locale locale) {
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        final Configuration config = context.getResources().getConfiguration();

        final String language = getIanaLocaleCode(locale.getLanguage(), mJavaToIanaLanguageCode);
        final String country = getIanaLocaleCode(locale.getCountry(), mJavaToIanaCountryCode);
        config.locale = new Locale(language, country, locale.getVariant());

        Resources res = new Resources(context.getAssets(), metrics, config);
        return res.getString(R.string.sample_text, config.locale.getDisplayName(config.locale));
    }

    private int mNativeData;

    private static native final boolean nativeClassInit();

    private native final boolean nativeCreate(String path);

    private native final boolean nativeDestroy();

    private native final static String nativeGetVersion();

    private native final int nativeGetSampleRate();

    private native final int nativeGetChannelCount();

    private native final int nativeGetAudioFormat();

    private native final int nativeGetBufferSizeInMillis();

    private native final String[] nativeGetAvailableVoices();

    private native final boolean nativeSetVoiceByName(String name);

    private native final boolean nativeSetVoiceByProperties(String language, int gender, int age);

    private native final boolean nativeSetParameter(int parameter, int value);

    private native final int nativeGetParameter(int parameter, int current);

    private native final boolean nativeSynthesize(String text, boolean isSsml);

    private native final boolean nativeStop();

    public interface SynthReadyCallback {
        void onSynthDataReady(byte[] audioData);

        void onSynthDataComplete();
    }

    public static class VoiceVariant {
        public final String variant;
        public final int gender;
        public final int age;

        protected VoiceVariant(String variant, int age) {
            if (variant.equals("male")) {
                this.variant = null;
                this.gender = GENDER_MALE;
            } else if (variant.equals("female")) {
                this.variant = null;
                this.gender = GENDER_FEMALE;
            } else {
                this.variant = variant;
                this.gender = GENDER_UNSPECIFIED;
            }
            this.age = age;
        }

        @Override
        public String toString() {
            final String ret;
            if (gender == GENDER_MALE) {
                ret = "male";
            } else if (gender == GENDER_FEMALE) {
                ret = "female";
            } else {
                ret = variant;
            }
            if (age == AGE_YOUNG) {
                return ret + "-young";
            } else if (age == AGE_OLD) {
                return ret + "-old";
            }
            return ret;
        }
    }

    public static VoiceVariant parseVoiceVariant(String value) {
        String[] parts = value.split("-");
        int age = AGE_ANY;
        switch (parts.length) {
        case 1: // variant
            break;
        case 2: // variant-age
            age = parts[1].equals("young") ? AGE_YOUNG : AGE_OLD;
            break;
        default:
            return null;
        }
        return new VoiceVariant(parts[0], age);
    }

    public class Voice {
        public final String name;
        public final String identifier;
        public final int gender;
        public final int age;
        public final Locale locale;

        public Voice(String name, String identifier, int gender, int age, Locale locale) {
            this.name = name;
            this.identifier = identifier;
            this.gender = gender;
            this.age = age;
            this.locale = locale;
        }

        /**
         * Attempts a partial match against a query locale.
         *
         * @param query The locale to match.
         * @return A text-to-speech availability code. One of:
         *         <ul>
         *         <li>{@link TextToSpeech#LANG_NOT_SUPPORTED}
         *         <li>{@link TextToSpeech#LANG_AVAILABLE}
         *         <li>{@link TextToSpeech#LANG_COUNTRY_AVAILABLE}
         *         <li>{@link TextToSpeech#LANG_COUNTRY_VAR_AVAILABLE}
         *         </ul>
         */
        public int match(Locale query) {
            if (!locale.getISO3Language().equals(query.getISO3Language())) {
                return TextToSpeech.LANG_NOT_SUPPORTED;
            } else if (!locale.getISO3Country().equals(query.getISO3Country())) {
                return TextToSpeech.LANG_AVAILABLE;
            } else if (!locale.getVariant().equals(query.getVariant())) {
                return TextToSpeech.LANG_COUNTRY_AVAILABLE;
            } else {
                return TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE;
            }
        }

        @Override
        public String toString() {
            return locale.toString().replace('_', '-');
        }
    }

    private static String getIanaLocaleCode(String code, final Map<String, String> javaToIana) {
        final String iana = javaToIana.get(code);
        if (iana != null) {
            return iana;
        }
        return code;
    }

    private static final Map<String, String> mJavaToIanaLanguageCode = new HashMap<String, String>();
    private static final Map<String, String> mJavaToIanaCountryCode = new HashMap<String, String>();
    static {
        mJavaToIanaLanguageCode.put("afr", "af");
        mJavaToIanaLanguageCode.put("aka", "ak");
        mJavaToIanaLanguageCode.put("amh", "am");
        mJavaToIanaLanguageCode.put("arg", "an");
        mJavaToIanaLanguageCode.put("aze", "az");
        mJavaToIanaLanguageCode.put("bul", "bg");
        mJavaToIanaLanguageCode.put("bos", "bs");
        mJavaToIanaLanguageCode.put("cat", "ca");
        mJavaToIanaLanguageCode.put("ces", "cs");
        mJavaToIanaLanguageCode.put("cym", "cy");
        mJavaToIanaLanguageCode.put("dan", "da");
        mJavaToIanaLanguageCode.put("deu", "de");
        mJavaToIanaLanguageCode.put("div", "dv");
        mJavaToIanaLanguageCode.put("ell", "el");
        mJavaToIanaLanguageCode.put("eng", "en");
        mJavaToIanaLanguageCode.put("epo", "eo");
        mJavaToIanaLanguageCode.put("spa", "es");
        mJavaToIanaLanguageCode.put("est", "et");
        mJavaToIanaLanguageCode.put("fas", "fa");
        mJavaToIanaLanguageCode.put("fin", "fi");
        mJavaToIanaLanguageCode.put("fra", "fr");
        mJavaToIanaLanguageCode.put("gle", "ga");
        mJavaToIanaLanguageCode.put("hin", "hi");
        mJavaToIanaLanguageCode.put("hrv", "hr");
        mJavaToIanaLanguageCode.put("hat", "ht");
        mJavaToIanaLanguageCode.put("hun", "hu");
        mJavaToIanaLanguageCode.put("hye", "hy");
        mJavaToIanaLanguageCode.put("ind", "in"); // NOTE: The deprecated 'in' code is used by Java/Android.
        mJavaToIanaLanguageCode.put("isl", "is");
        mJavaToIanaLanguageCode.put("ita", "it");
        mJavaToIanaLanguageCode.put("kat", "ka");
        mJavaToIanaLanguageCode.put("kaz", "kk");
        mJavaToIanaLanguageCode.put("kal", "kl");
        mJavaToIanaLanguageCode.put("kan", "kn");
        mJavaToIanaLanguageCode.put("kor", "ko");
        mJavaToIanaLanguageCode.put("kur", "ku");
        mJavaToIanaLanguageCode.put("lat", "la");
        mJavaToIanaLanguageCode.put("lit", "lt");
        mJavaToIanaLanguageCode.put("lav", "lv");
        mJavaToIanaLanguageCode.put("mkd", "mk");
        mJavaToIanaLanguageCode.put("mal", "ml");
        mJavaToIanaLanguageCode.put("mlt", "mt");
        mJavaToIanaLanguageCode.put("nep", "ne");
        mJavaToIanaLanguageCode.put("nld", "nl");
        mJavaToIanaLanguageCode.put("nor", "no");
        mJavaToIanaLanguageCode.put("pan", "pa");
        mJavaToIanaLanguageCode.put("pol", "pl");
        mJavaToIanaLanguageCode.put("por", "pt");
        mJavaToIanaLanguageCode.put("ron", "ro");
        mJavaToIanaLanguageCode.put("rus", "ru");
        mJavaToIanaLanguageCode.put("kin", "rw");
        mJavaToIanaLanguageCode.put("sin", "si");
        mJavaToIanaLanguageCode.put("slk", "sk");
        mJavaToIanaLanguageCode.put("slv", "sl");
        mJavaToIanaLanguageCode.put("sqi", "sq");
        mJavaToIanaLanguageCode.put("srp", "sr");
        mJavaToIanaLanguageCode.put("swe", "sv");
        mJavaToIanaLanguageCode.put("swa", "sw");
        mJavaToIanaLanguageCode.put("tam", "ta");
        mJavaToIanaLanguageCode.put("tel", "te");
        mJavaToIanaLanguageCode.put("tsn", "tn");
        mJavaToIanaLanguageCode.put("tur", "tr");
        mJavaToIanaLanguageCode.put("tat", "tt");
        mJavaToIanaLanguageCode.put("urd", "ur");
        mJavaToIanaLanguageCode.put("vie", "vi");
        mJavaToIanaLanguageCode.put("wol", "wo");
        mJavaToIanaLanguageCode.put("zho", "zh");
        mJavaToIanaLanguageCode.put("yue", "zh");

        mJavaToIanaCountryCode.put("029", "");   // Locale.getCountry() does not map numeric country codes.
        mJavaToIanaCountryCode.put("419", "");   // Locale.getCountry() does not map numeric country codes.
        mJavaToIanaCountryCode.put("BEL", "BE");
        mJavaToIanaCountryCode.put("BRA", "BR");
        mJavaToIanaCountryCode.put("FRA", "FR");
        mJavaToIanaCountryCode.put("GBR", "GB");
        mJavaToIanaCountryCode.put("PRT", "PT");
        mJavaToIanaCountryCode.put("USA", "US");
        mJavaToIanaCountryCode.put("VNM", "VN");
    }
}
