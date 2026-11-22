package com.archos.mediacenter.video.leanback.cinema;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.arabic;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.dp;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.rounded;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.text;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Interactive TV Clock & Weather capsule widget.
 * Always displays Western digits (123) and adapts to Arabic (RTL) and English (LTR):
 * - Top line: Dynamic time-of-day greeting ("صباح الخير" / "مساء الخير" or "Good Morning" / "Good Evening").
 * - Main row: Time + AM/PM + Colored Vector Weather/Day-Night icon + temperature + divider + 2-line date.
 * Uses Android LocationManager (when granted) with IP/timezone fallback to fetch local Open-Meteo weather.
 */
public final class CinemaWeatherWidget extends LinearLayout {
    private static final String PREF_CACHE = "cinema_weather_cache_v1";
    private static final long WEATHER_TTL_MS = 30L * 60L * 1000L;
    private static final ExecutorService WEATHER_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CinemaWeather");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static final String[] AR_DAYS = {
            "الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"
    };
    private static final String[] AR_MONTHS = {
            "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
            "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
    };

    private final TextView greetingTv;
    private final LinearLayout mainRow;
    private final LinearLayout timeCluster;
    private final TextView timeTv;
    private final TextView amPmTv;
    private final LinearLayout weatherCluster;
    private final ImageView weatherIconView;
    private final TextView weatherTempTv;
    private final View dividerView;
    private final LinearLayout dateBox;
    private final TextView dateLine1Tv;
    private final TextView dateLine2Tv;

    private volatile boolean fetchingWeather;

    public CinemaWeatherWidget(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setFocusable(false);
        setClickable(false);

        greetingTv = text(context, 12f, true);
        greetingTv.setTextColor(0xFFE5B94C);
        LayoutParams gp = new LayoutParams(-2, -2);
        gp.bottomMargin = dp(context, 2);
        addView(greetingTv, gp);

        mainRow = new LinearLayout(context);
        mainRow.setOrientation(HORIZONTAL);
        mainRow.setGravity(Gravity.CENTER_VERTICAL);
        addView(mainRow, new LayoutParams(-2, -2));

        timeCluster = new LinearLayout(context);
        timeCluster.setOrientation(HORIZONTAL);
        timeCluster.setGravity(Gravity.CENTER_VERTICAL);

        timeTv = text(context, 22f, true);
        timeTv.setTextColor(0xFFF4F4F6);
        timeTv.setTextDirection(View.TEXT_DIRECTION_LTR);
        timeCluster.addView(timeTv, new LayoutParams(-2, -2));

        amPmTv = text(context, 13f, true);
        amPmTv.setTextColor(0xFFE5B94C);
        LayoutParams ap = new LayoutParams(-2, -2);
        ap.setMarginStart(dp(context, 4));
        timeCluster.addView(amPmTv, ap);

        weatherCluster = new LinearLayout(context);
        weatherCluster.setOrientation(HORIZONTAL);
        weatherCluster.setGravity(Gravity.CENTER_VERTICAL);
        weatherCluster.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LayoutParams wp = new LayoutParams(-2, -2);
        wp.setMarginStart(dp(context, 10));

        weatherIconView = new ImageView(context);
        weatherIconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        weatherCluster.addView(weatherIconView, new LayoutParams(dp(context, 22), dp(context, 22)));

        weatherTempTv = text(context, 14f, true);
        weatherTempTv.setTextColor(0xFFF4F4F6);
        weatherTempTv.setTextDirection(View.TEXT_DIRECTION_LTR);
        LayoutParams wtp = new LayoutParams(-2, -2);
        wtp.setMarginStart(dp(context, 5));
        weatherCluster.addView(weatherTempTv, wtp);

        timeCluster.addView(weatherCluster, wp);

        mainRow.addView(timeCluster, new LayoutParams(-2, -2));

        dividerView = new View(context);
        dividerView.setBackgroundColor(0x55A0A0AB);
        LayoutParams divLp = new LayoutParams(dp(context, 1.5f), dp(context, 26));
        divLp.setMarginStart(dp(context, 10));
        divLp.setMarginEnd(dp(context, 10));
        mainRow.addView(dividerView, divLp);

        dateBox = new LinearLayout(context);
        dateBox.setOrientation(VERTICAL);

        dateLine1Tv = text(context, 12f, true);
        dateLine1Tv.setTextColor(0xFFF4F4F6);

        dateLine2Tv = text(context, 11f, false);
        dateLine2Tv.setTextColor(0xFFA0A0AB);
        dateLine2Tv.setTextDirection(View.TEXT_DIRECTION_LTR);

        dateBox.addView(dateLine1Tv, new LayoutParams(-2, -2));
        dateBox.addView(dateLine2Tv, new LayoutParams(-2, -2));

        mainRow.addView(dateBox, new LayoutParams(-2, -2));

        updateNow();
    }

    public void updateNow() {
        try {
            Context c = getContext();
            if (c == null) return;
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
            boolean visible = prefs.getBoolean(HomePreferences.CLOCK, true);
            setVisibility(visible ? VISIBLE : GONE);
            if (!visible) return;

            boolean ar = arabic(c);
            String sizePref = prefs.getString(HomePreferences.CLOCK_SIZE, "small");
            float scale = switch (sizePref == null ? "small" : sizePref) {
                case "large" -> 1.22f;
                case "medium" -> 1.0f;
                default -> 0.85f;
            };
            int opacity = prefs.getInt(HomePreferences.CLOCK_OPACITY, 50);
            boolean showGreeting = prefs.getBoolean(HomePreferences.CLOCK_GREETING, true);
            boolean showWeather = prefs.getBoolean(HomePreferences.CLOCK_WEATHER, true);

            int padH = dp(c, Math.round(14 * scale));
            int padV = dp(c, Math.round(8 * scale));
            setPadding(padH, padV, padH, padV);

            if (opacity <= 0) {
                setBackground(null);
            } else {
                int bgColor = switch (opacity) {
                    case 25 -> 0x40121215;
                    case 75 -> 0xBF121215;
                    case 100 -> 0xFF121215;
                    default -> 0x80121215;
                };
                int borderColor = switch (opacity) {
                    case 25 -> 0x22E5B94C;
                    case 75 -> 0x44E5B94C;
                    case 100 -> 0x66E5B94C;
                    default -> 0x33E5B94C;
                };
                setBackground(rounded(bgColor, dp(c, Math.round(14 * scale)), borderColor));
            }

            float shadowRadius = opacity <= 25 ? 4f : 0f;
            int shadowColor = opacity <= 25 ? 0xCC000000 : Color.TRANSPARENT;
            greetingTv.setShadowLayer(shadowRadius, 0f, 1.5f, shadowColor);
            timeTv.setShadowLayer(shadowRadius, 0f, 1.5f, shadowColor);
            amPmTv.setShadowLayer(shadowRadius, 0f, 1.5f, shadowColor);
            weatherTempTv.setShadowLayer(shadowRadius, 0f, 1.5f, shadowColor);
            dateLine1Tv.setShadowLayer(shadowRadius, 0f, 1.5f, shadowColor);
            dateLine2Tv.setShadowLayer(shadowRadius, 0f, 1.5f, shadowColor);

            setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            setGravity(ar ? Gravity.RIGHT : Gravity.LEFT);
            mainRow.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            timeCluster.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

            greetingTv.setTextSize(12f * scale);
            timeTv.setTextSize(22f * scale);
            amPmTv.setTextSize(13f * scale);
            weatherTempTv.setTextSize(13.5f * scale);
            dateLine1Tv.setTextSize(12f * scale);
            dateLine2Tv.setTextSize(11f * scale);

            Calendar cal = Calendar.getInstance();
            Date now = cal.getTime();
            int hour24 = cal.get(Calendar.HOUR_OF_DAY);
            boolean isDay = hour24 >= 5 && hour24 < 18;
            boolean isMorning = hour24 >= 4 && hour24 < 12;

            if (showGreeting) {
                greetingTv.setVisibility(VISIBLE);
                String greet = ar
                        ? (isMorning ? "صباح الخير" : "مساء الخير")
                        : (isMorning ? "Good Morning" : "Good Evening");
                greetingTv.setText(greet);
                greetingTv.setGravity(ar ? Gravity.RIGHT : Gravity.LEFT);
                ViewGroup.LayoutParams rawGp = greetingTv.getLayoutParams();
                if (rawGp instanceof LayoutParams gp) {
                    gp.gravity = ar ? Gravity.RIGHT : Gravity.LEFT;
                    gp.bottomMargin = dp(c, 2);
                    greetingTv.setLayoutParams(gp);
                }
            } else {
                greetingTv.setVisibility(GONE);
            }

            String timeDigits = new SimpleDateFormat("h:mm", Locale.US).format(now);
            boolean isPm = cal.get(Calendar.AM_PM) == Calendar.PM;
            String amPm = ar ? (isPm ? "م" : "ص") : (isPm ? "PM" : "AM");

            timeTv.setText(timeDigits);
            amPmTv.setText(amPm);

            if (showWeather) {
                weatherCluster.setVisibility(VISIBLE);
                SharedPreferences sp = c.getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE);
                int iconSize = dp(c, Math.round(22 * scale));
                ViewGroup.LayoutParams iconLp = weatherIconView.getLayoutParams();
                if (iconLp != null && (iconLp.width != iconSize || iconLp.height != iconSize)) {
                    iconLp.width = iconSize;
                    iconLp.height = iconSize;
                    weatherIconView.setLayoutParams(iconLp);
                }
                int code = sp.getInt("wmo_code", 0);
                int cachedIsDay = sp.getInt("is_day", -1);
                boolean effectiveDay = cachedIsDay >= 0 ? (cachedIsDay == 1) : isDay;
                weatherIconView.setImageDrawable(new WeatherVectorDrawable(code, effectiveDay));

                if (sp.contains("temp_c")) {
                    int temp = sp.getInt("temp_c", 24);
                    weatherTempTv.setText(String.format(Locale.US, "%d°C", temp));
                    weatherTempTv.setVisibility(VISIBLE);
                } else {
                    weatherTempTv.setVisibility(GONE);
                }
                maybeRefreshWeatherAsync(c.getApplicationContext());
            } else {
                weatherCluster.setVisibility(GONE);
            }

            ViewGroup.LayoutParams rawDivLp = dividerView.getLayoutParams();
            if (rawDivLp instanceof LayoutParams divLp) {
                divLp.width = dp(c, 1.5f);
                divLp.height = dp(c, Math.round(26 * scale));
                dividerView.setLayoutParams(divLp);
            }

            int dow = cal.get(Calendar.DAY_OF_WEEK);
            int dayOfMonth = cal.get(Calendar.DAY_OF_MONTH);
            int month = cal.get(Calendar.MONTH);
            int year = cal.get(Calendar.YEAR);

            String line1;
            if (ar) {
                String dayName = AR_DAYS[Math.max(0, Math.min(6, dow - 1))];
                String monthName = AR_MONTHS[Math.max(0, Math.min(11, month))];
                line1 = String.format(Locale.US, "%s %d %s", dayName, dayOfMonth, monthName);
            } else {
                line1 = new SimpleDateFormat("EEEE, MMM d", Locale.US).format(now);
            }
            String line2 = String.format(Locale.US, "%04d/%02d/%02d", year, month + 1, dayOfMonth);

            dateLine1Tv.setText(line1);
            dateLine2Tv.setText(line2);
            dateBox.setGravity(ar ? Gravity.RIGHT : Gravity.LEFT);
            dateLine1Tv.setGravity(ar ? Gravity.RIGHT : Gravity.LEFT);
            dateLine2Tv.setGravity(ar ? Gravity.RIGHT : Gravity.LEFT);
        } catch (Throwable ignored) {
            // Never crash UI on clock/weather formatting
        }
    }

    private static boolean hasLocationPermission(Context c) {
        return c != null && (ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED);
    }

    private void maybeRefreshWeatherAsync(Context app) {
        try {
            if (app == null) return;
            SharedPreferences sp = app.getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE);
            long last = sp.getLong("updated_at", 0L);
            boolean hasPerm = hasLocationPermission(app);
            boolean upgradedToDeviceLoc = hasPerm && !"device".equals(sp.getString("geo_source", ""));
            if (!upgradedToDeviceLoc && System.currentTimeMillis() - last < WEATHER_TTL_MS) return;
            if (fetchingWeather) return;
            fetchingWeather = true;
            WEATHER_IO.execute(() -> {
                try {
                    double[] latLon = resolveCoordinates(app, sp);
                    String url = String.format(Locale.US,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current_weather=true",
                            latLon[0], latLon[1]);
                    JSONObject res = getJson(url, 5500);
                    if (res != null) {
                        JSONObject cw = res.optJSONObject("current_weather");
                        if (cw != null && cw.has("temperature")) {
                            int temp = (int) Math.round(cw.optDouble("temperature", 24.0));
                            int code = cw.optInt("weathercode", 0);
                            int isDayVal = cw.has("is_day") ? cw.optInt("is_day", -1) : -1;
                            sp.edit()
                                    .putInt("temp_c", temp)
                                    .putInt("wmo_code", code)
                                    .putInt("is_day", isDayVal)
                                    .putLong("updated_at", System.currentTimeMillis())
                                    .apply();
                            post(() -> {
                                if (isAttachedToWindow()) updateNow();
                            });
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    fetchingWeather = false;
                }
            });
        } catch (Throwable ignored) {
            fetchingWeather = false;
        }
    }

    private static double[] resolveCoordinates(Context app, SharedPreferences sp) {
        // 1. Prefer real device Location (GPS / Network / Passive) when location permission is granted
        if (hasLocationPermission(app)) {
            try {
                LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    Location best = null;
                    for (String p : new String[]{
                            LocationManager.GPS_PROVIDER,
                            LocationManager.NETWORK_PROVIDER,
                            LocationManager.PASSIVE_PROVIDER}) {
                        try {
                            Location l = lm.getLastKnownLocation(p);
                            if (l != null && (Math.abs(l.getLatitude()) > 0.01 || Math.abs(l.getLongitude()) > 0.01)) {
                                if (best == null || l.getTime() > best.getTime()) best = l;
                            }
                        } catch (SecurityException | IllegalArgumentException ignored) {}
                    }
                    if (best == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                                ? LocationManager.NETWORK_PROVIDER
                                : (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null);
                        if (provider != null) {
                            Location[] holder = {null};
                            CountDownLatch latch = new CountDownLatch(1);
                            try {
                                lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(app), loc -> {
                                    holder[0] = loc;
                                    latch.countDown();
                                });
                                latch.await(2500, TimeUnit.MILLISECONDS);
                                if (holder[0] != null) best = holder[0];
                            } catch (Throwable ignored) {}
                        }
                    } else if (best == null) {
                        String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                                ? LocationManager.NETWORK_PROVIDER : null;
                        if (provider != null) {
                            Location[] holder = {null};
                            CountDownLatch latch = new CountDownLatch(1);
                            try {
                                lm.requestSingleUpdate(provider, loc -> {
                                    holder[0] = loc;
                                    latch.countDown();
                                }, Looper.getMainLooper());
                                latch.await(2500, TimeUnit.MILLISECONDS);
                                if (holder[0] != null) best = holder[0];
                            } catch (Throwable ignored) {}
                        }
                    }
                    if (best != null) {
                        double lat = best.getLatitude();
                        double lon = best.getLongitude();
                        sp.edit()
                                .putLong("lat", Double.doubleToLongBits(lat))
                                .putLong("lon", Double.doubleToLongBits(lon))
                                .putString("geo_source", "device")
                                .putLong("geo_at", System.currentTimeMillis())
                                .apply();
                        return new double[]{lat, lon};
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 2. Cached coordinates
        try {
            if (sp.contains("lat") && sp.contains("lon")
                    && System.currentTimeMillis() - sp.getLong("geo_at", 0L) < 7L * 86400000L) {
                return new double[]{
                        Double.longBitsToDouble(sp.getLong("lat", Double.doubleToLongBits(24.7136))),
                        Double.longBitsToDouble(sp.getLong("lon", Double.doubleToLongBits(46.6753)))
                };
            }
            // 3. IP Geolocation fallback
            JSONObject geo = getJson("https://ipwho.is/", 4000);
            if (geo != null && geo.optBoolean("success", true) && geo.has("latitude") && geo.has("longitude")) {
                double lat = geo.optDouble("latitude", 0);
                double lon = geo.optDouble("longitude", 0);
                if (Math.abs(lat) > 0.01 || Math.abs(lon) > 0.01) {
                    sp.edit()
                            .putLong("lat", Double.doubleToLongBits(lat))
                            .putLong("lon", Double.doubleToLongBits(lon))
                            .putString("geo_source", "ip")
                            .putLong("geo_at", System.currentTimeMillis())
                            .apply();
                    return new double[]{lat, lon};
                }
            }
            JSONObject geo2 = getJson("https://get.geojs.io/v1/ip/geo.json", 4000);
            if (geo2 != null && geo2.has("latitude") && geo2.has("longitude")) {
                double lat = Double.parseDouble(geo2.optString("latitude", "0"));
                double lon = Double.parseDouble(geo2.optString("longitude", "0"));
                if (Math.abs(lat) > 0.01 || Math.abs(lon) > 0.01) {
                    sp.edit()
                            .putLong("lat", Double.doubleToLongBits(lat))
                            .putLong("lon", Double.doubleToLongBits(lon))
                            .putString("geo_source", "ip")
                            .putLong("geo_at", System.currentTimeMillis())
                            .apply();
                    return new double[]{lat, lon};
                }
            }
        } catch (Throwable ignored) {}

        // 4. Timezone city fallback
        try {
            String tz = TimeZone.getDefault().getID();
            if (tz != null) {
                if (tz.contains("Cairo")) return new double[]{30.0444, 31.2357};
                if (tz.contains("Dubai") || tz.contains("Muscat")) return new double[]{25.2048, 55.2708};
                if (tz.contains("Baghdad")) return new double[]{33.3152, 44.3661};
                if (tz.contains("Kuwait")) return new double[]{29.3759, 47.9774};
                if (tz.contains("Qatar")) return new double[]{25.2854, 51.5310};
                if (tz.contains("Amman")) return new double[]{31.9454, 35.9284};
                if (tz.contains("Casablanca")) return new double[]{33.5731, -7.5898};
                if (tz.contains("Algiers")) return new double[]{36.7538, 3.0588};
            }
        } catch (Throwable ignored) {}
        return new double[]{24.7136, 46.6753};
    }

    private static JSONObject getJson(String urlStr, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "LocalCinema/1.0");
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Crisp anti-aliased multi-color vector weather icon matching WMO codes and day/night. */
    private static final class WeatherVectorDrawable extends Drawable {
        private final int code;
        private final boolean isDay;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        WeatherVectorDrawable(int code, boolean isDay) {
            this.code = code;
            this.isDay = isDay;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float w = b.width();
            float h = b.height();
            if (w <= 0 || h <= 0) return;
            canvas.save();
            canvas.translate(b.left, b.top);

            if (code == 0) {
                if (isDay) {
                    drawSun(canvas, w * 0.50f, h * 0.50f, Math.min(w, h) * 0.24f, Math.min(w, h) * 0.42f);
                } else {
                    drawMoonAndStars(canvas, w * 0.48f, h * 0.52f, Math.min(w, h) * 0.28f);
                }
            } else if (code == 1 || code == 2) {
                if (isDay) {
                    drawSun(canvas, w * 0.65f, h * 0.34f, Math.min(w, h) * 0.16f, Math.min(w, h) * 0.29f);
                } else {
                    drawMoonAndStars(canvas, w * 0.68f, h * 0.34f, Math.min(w, h) * 0.19f);
                }
                drawCloud(canvas, w * 0.42f, h * 0.62f, w * 0.68f, 0xFF93C5FD);
            } else if (code == 3 || code == 45 || code == 48) {
                drawCloud(canvas, w * 0.54f, h * 0.42f, w * 0.56f, 0xFF64748B);
                drawCloud(canvas, w * 0.46f, h * 0.56f, w * 0.72f, 0xFF94A3B8);
                if (code == 45 || code == 48) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setStrokeWidth(Math.max(1.5f, h * 0.07f));
                    paint.setColor(0xFFCBD5E1);
                    canvas.drawLine(w * 0.20f, h * 0.78f, w * 0.80f, h * 0.78f, paint);
                    canvas.drawLine(w * 0.28f, h * 0.90f, w * 0.72f, h * 0.90f, paint);
                }
            } else if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) {
                drawCloud(canvas, w * 0.50f, h * 0.44f, w * 0.74f, 0xFF60A5FA);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(Math.max(1.6f, w * 0.075f));
                paint.setColor(0xFF38BDF8);
                canvas.drawLine(w * 0.34f, h * 0.72f, w * 0.28f, h * 0.89f, paint);
                canvas.drawLine(w * 0.52f, h * 0.74f, w * 0.46f, h * 0.92f, paint);
                canvas.drawLine(w * 0.70f, h * 0.72f, w * 0.64f, h * 0.89f, paint);
            } else if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
                drawCloud(canvas, w * 0.50f, h * 0.44f, w * 0.74f, 0xFF93C5FD);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFE0F2FE);
                float r = Math.max(1.5f, Math.min(w, h) * 0.065f);
                canvas.drawCircle(w * 0.33f, h * 0.78f, r, paint);
                canvas.drawCircle(w * 0.50f, h * 0.85f, r, paint);
                canvas.drawCircle(w * 0.67f, h * 0.78f, r, paint);
            } else if (code >= 95) {
                drawCloud(canvas, w * 0.50f, h * 0.42f, w * 0.76f, 0xFF475569);
                Path bolt = new Path();
                bolt.moveTo(w * 0.54f, h * 0.55f);
                bolt.lineTo(w * 0.40f, h * 0.75f);
                bolt.lineTo(w * 0.51f, h * 0.75f);
                bolt.lineTo(w * 0.43f, h * 0.94f);
                bolt.lineTo(w * 0.64f, h * 0.70f);
                bolt.lineTo(w * 0.52f, h * 0.70f);
                bolt.close();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFACC15);
                canvas.drawPath(bolt, paint);
            } else {
                if (isDay) {
                    drawSun(canvas, w * 0.65f, h * 0.34f, Math.min(w, h) * 0.16f, Math.min(w, h) * 0.29f);
                }
                drawCloud(canvas, w * 0.46f, h * 0.58f, w * 0.70f, 0xFF94A3B8);
            }

            canvas.restore();
        }

        private void drawSun(Canvas canvas, float cx, float cy, float coreR, float rayOuterR) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(Math.max(1.5f, coreR * 0.32f));
            paint.setColor(0xFFFACC15);
            float rayInnerR = coreR * 1.35f;
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4.0;
                canvas.drawLine(
                        cx + (float) Math.cos(a) * rayInnerR,
                        cy + (float) Math.sin(a) * rayInnerR,
                        cx + (float) Math.cos(a) * rayOuterR,
                        cy + (float) Math.sin(a) * rayOuterR,
                        paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFACC15);
            canvas.drawCircle(cx, cy, coreR, paint);
        }

        private void drawMoonAndStars(Canvas canvas, float cx, float cy, float r) {
            Path moon = new Path();
            moon.addCircle(cx, cy, r, Path.Direction.CW);
            Path cutout = new Path();
            cutout.addCircle(cx + r * 0.45f, cy - r * 0.28f, r * 0.85f, Path.Direction.CW);
            moon.op(cutout, Path.Op.DIFFERENCE);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFE5B94C);
            canvas.drawPath(moon, paint);

            // Two crisp stars in silver-gold
            paint.setColor(0xFFE2E8F0);
            drawStar(canvas, cx + r * 0.85f, cy - r * 0.68f, r * 0.26f);
            drawStar(canvas, cx + r * 1.15f, cy + r * 0.05f, r * 0.18f);
        }

        private void drawStar(Canvas canvas, float sx, float sy, float sr) {
            Path star = new Path();
            star.moveTo(sx, sy - sr);
            star.lineTo(sx + sr * 0.28f, sy - sr * 0.28f);
            star.lineTo(sx + sr, sy);
            star.lineTo(sx + sr * 0.28f, sy + sr * 0.28f);
            star.lineTo(sx, sy + sr);
            star.lineTo(sx - sr * 0.28f, sy + sr * 0.28f);
            star.lineTo(sx - sr, sy);
            star.lineTo(sx - sr * 0.28f, sy - sr * 0.28f);
            star.close();
            canvas.drawPath(star, paint);
        }

        private void drawCloud(Canvas canvas, float cx, float cy, float width, int color) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            float h = width * 0.42f;
            float left = cx - width * 0.5f;
            float right = cx + width * 0.5f;
            float bottom = cy + h * 0.35f;
            float baseR = h * 0.44f;
            canvas.drawRoundRect(left, bottom - baseR * 1.7f, right, bottom, baseR, baseR, paint);
            canvas.drawCircle(cx - width * 0.14f, bottom - baseR * 1.35f, width * 0.24f, paint);
            canvas.drawCircle(cx + width * 0.15f, bottom - baseR * 1.10f, width * 0.18f, paint);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter colorFilter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
