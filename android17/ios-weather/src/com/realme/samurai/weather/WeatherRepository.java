package com.realme.samurai.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Zero-Key Open-Meteo HTTPS Client + Offline Cache & Realistic Seed Engine (`WeatherRepository`).
 *
 * Endpoints:
 * - Forecast: `https://api.open-meteo.com/v1/forecast`
 * - Geocoding: `https://geocoding-api.open-meteo.com/v1/search`
 */
public final class WeatherRepository {

    private static final String PREFS_NAME = "samurai_ios26_weather_cache";
    private static final String KEY_ACTIVE_CITY_JSON = "active_city_spec";

    public interface ForecastCallback {
        void onForecastReady(WeatherModels.ForecastBundle bundle);
    }

    public interface GeocodingCallback {
        void onCitiesFound(List<WeatherModels.CitySpec> results);
    }

    private final Context mAppContext;
    private final SharedPreferences mPrefs;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public WeatherRepository(Context context) {
        mAppContext = context.getApplicationContext();
        mPrefs = mAppContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void shutdown() {
        mExecutor.shutdownNow();
    }

    public WeatherModels.CitySpec loadSelectedCity() {
        String raw = mPrefs.getString(KEY_ACTIVE_CITY_JSON, null);
        if (raw != null) {
            try {
                JSONObject o = new JSONObject(raw);
                return new WeatherModels.CitySpec(
                        o.getString("name"),
                        o.optString("region", ""),
                        o.getDouble("lat"),
                        o.getDouble("lon"),
                        o.optInt("seedT", 28),
                        o.optInt("seedCode", 1),
                        o.optBoolean("seedDay", true));
            } catch (Throwable ignored) {
            }
        }
        return WeatherModels.PRESET_CITIES.get(0); // Hyderabad default
    }

    public void saveSelectedCity(WeatherModels.CitySpec city) {
        if (city == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("name", city.name);
            o.put("region", city.region);
            o.put("lat", city.latitude);
            o.put("lon", city.longitude);
            o.put("seedT", city.seedBaseTempC);
            o.put("seedCode", city.seedWeatherCode);
            o.put("seedDay", city.seedIsDay);
            mPrefs.edit().putString(KEY_ACTIVE_CITY_JSON, o.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Immediately returns cached or realistic offline seed forecast so UI renders in 0 ms,
     * then asynchronously fetches live Open-Meteo forecast over HTTPS.
     */
    public WeatherModels.ForecastBundle getInstantCachedOrSeed(WeatherModels.CitySpec city) {
        String cachedJson = mPrefs.getString("json_" + city.cacheKey(), null);
        long cachedStamp = mPrefs.getLong("ts_" + city.cacheKey(), 0L);
        if (cachedJson != null) {
            WeatherModels.ForecastBundle parsed = parseOpenMeteoJson(city, cachedJson, false, cachedStamp);
            if (parsed != null) {
                return parsed;
            }
        }
        return buildRealisticSeedBundle(city);
    }

    public void fetchLiveForecast(WeatherModels.CitySpec city, ForecastCallback callback) {
        mExecutor.execute(() -> {
            String url = String.format(
                    Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,"
                            + "weather_code,surface_pressure,wind_speed_10m,wind_direction_10m"
                            + "&hourly=temperature_2m,weather_code,is_day,uv_index,visibility"
                            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,uv_index_max"
                            + "&timezone=auto&forecast_days=10",
                    city.latitude,
                    city.longitude);
            try {
                String body = httpGet(url, 6500);
                long now = System.currentTimeMillis();
                WeatherModels.ForecastBundle live = parseOpenMeteoJson(city, body, true, now);
                if (live != null) {
                    mPrefs.edit()
                            .putString("json_" + city.cacheKey(), body)
                            .putLong("ts_" + city.cacheKey(), now)
                            .apply();
                    mMainHandler.post(() -> {
                        if (callback != null) callback.onForecastReady(live);
                    });
                    return;
                }
            } catch (Throwable ignored) {
                // Offline or captive network — keep cached/seed data gracefully
            }
            WeatherModels.ForecastBundle fallback = getInstantCachedOrSeed(city);
            mMainHandler.post(() -> {
                if (callback != null) callback.onForecastReady(fallback);
            });
        });
    }

    public void searchCities(String rawQuery, GeocodingCallback callback) {
        final String q = rawQuery != null ? rawQuery.trim() : "";
        if (q.length() < 2) {
            mMainHandler.post(() -> {
                if (callback != null) callback.onCitiesFound(WeatherModels.PRESET_CITIES);
            });
            return;
        }

        // First match local presets immediately
        final List<WeatherModels.CitySpec> localMatches = new ArrayList<>();
        String lower = q.toLowerCase(Locale.US);
        for (WeatherModels.CitySpec c : WeatherModels.PRESET_CITIES) {
            if (c.name.toLowerCase(Locale.US).contains(lower)
                    || c.region.toLowerCase(Locale.US).contains(lower)) {
                localMatches.add(c);
            }
        }

        mExecutor.execute(() -> {
            List<WeatherModels.CitySpec> combined = new ArrayList<>(localMatches);
            try {
                String encoded = URLEncoder.encode(q, StandardCharsets.UTF_8.name());
                String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                        + encoded + "&count=8&language=en&format=json";
                String jsonStr = httpGet(url, 5500);
                JSONObject root = new JSONObject(jsonStr);
                JSONArray results = root.optJSONArray("results");
                if (results != null) {
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject item = results.optJSONObject(i);
                        if (item == null) continue;
                        String name = item.optString("name", "");
                        if (name.isEmpty()) continue;
                        String admin1 = item.optString("admin1", "");
                        String country = item.optString("country", "");
                        String region = (!admin1.isEmpty() && !country.isEmpty())
                                ? (admin1 + ", " + country)
                                : (!country.isEmpty() ? country : admin1);
                        double lat = item.getDouble("latitude");
                        double lon = item.getDouble("longitude");
                        boolean dup = false;
                        for (WeatherModels.CitySpec existing : combined) {
                            if (Math.abs(existing.latitude - lat) < 0.15
                                    && Math.abs(existing.longitude - lon) < 0.15) {
                                dup = true;
                                break;
                            }
                        }
                        if (!dup) {
                            int estTemp = Math.max(8, Math.min(35, (int) Math.round(32.0 - Math.abs(lat) * 0.42)));
                            combined.add(new WeatherModels.CitySpec(name, region, lat, lon, estTemp, 1, true));
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Offline search still returns localMatches cleanly
            }
            mMainHandler.post(() -> {
                if (callback != null) callback.onCitiesFound(combined);
            });
        });
    }

    private static String httpGet(String urlStr, int timeoutMs) throws Exception {
        java.net.URL url = new java.net.URI(urlStr).toURL();
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "SamuraiLiquidGlassWeather/2.0");
        try {
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status);
            }
            try (InputStream is = conn.getInputStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder(4096);
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static WeatherModels.ForecastBundle parseOpenMeteoJson(
            WeatherModels.CitySpec city,
            String rawJson,
            boolean liveNetwork,
            long stampMs) {
        try {
            JSONObject root = new JSONObject(rawJson);
            JSONObject current = root.getJSONObject("current");
            JSONObject hourly = root.getJSONObject("hourly");
            JSONObject daily = root.getJSONObject("daily");

            WeatherModels.ForecastBundle b = new WeatherModels.ForecastBundle();
            b.city = city;
            b.fetchedEpochMs = stampMs;
            b.fromLiveNetwork = liveNetwork;

            double curTemp = current.getDouble("temperature_2m");
            double feels = current.optDouble("apparent_temperature", curTemp);
            double rh = current.optDouble("relative_humidity_2m", 58.0);
            b.currentTempC = (int) Math.round(curTemp);
            b.feelsLikeC = (int) Math.round(feels);
            b.humidityPct = (int) Math.round(rh);
            b.dewPointC = WeatherModels.computeDewPointC(curTemp, rh);
            b.isDay = current.optInt("is_day", 1) == 1;
            b.weatherCode = current.optInt("weather_code", 1);
            b.conditionText = WeatherModels.wmoConditionLabel(b.weatherCode, b.isDay);
            b.windKmh = (float) current.optDouble("wind_speed_10m", 12.0);
            b.windDirectionDeg = current.optInt("wind_direction_10m", 65);
            b.surfacePressureHpa = (int) Math.round(current.optDouble("surface_pressure", 1012.0));

            String currentTimeIso = current.optString("time", "");
            String currentHourPrefix = currentTimeIso.length() >= 13 ? currentTimeIso.substring(0, 13) : "";

            // Parse 24-hour strip starting at current hour index
            JSONArray hTimes = hourly.getJSONArray("time");
            JSONArray hTemps = hourly.getJSONArray("temperature_2m");
            JSONArray hCodes = hourly.getJSONArray("weather_code");
            JSONArray hIsDay = hourly.optJSONArray("is_day");
            JSONArray hUv = hourly.optJSONArray("uv_index");
            JSONArray hVis = hourly.optJSONArray("visibility");

            int startIdx = 0;
            if (!currentHourPrefix.isEmpty()) {
                for (int i = 0; i < hTimes.length(); i++) {
                    String t = hTimes.optString(i, "");
                    if (t.startsWith(currentHourPrefix)) {
                        startIdx = i;
                        break;
                    }
                }
            }

            b.uvIndex = (hUv != null && startIdx < hUv.length())
                    ? (float) hUv.optDouble(startIdx, 4.0) : 4.0f;
            double visMeters = (hVis != null && startIdx < hVis.length())
                    ? hVis.optDouble(startIdx, 10000.0) : 10000.0;
            b.visibilityKm = Math.max(0.5f, Math.min(35.0f, (float) (visMeters / 1000.0)));

            int count = Math.min(24, hTimes.length() - startIdx);
            for (int k = 0; k < count; k++) {
                int idx = startIdx + k;
                String iso = hTimes.optString(idx, "");
                String label = (k == 0) ? "Now" : formatIsoHourLabel(iso);
                int t = (int) Math.round(hTemps.optDouble(idx, curTemp));
                int code = hCodes.optInt(idx, b.weatherCode);
                boolean day = (hIsDay == null) || (hIsDay.optInt(idx, 1) == 1);
                b.hourly.add(new WeatherModels.HourlyItem(label, t, code, day));
            }

            // Parse 10-day forecast
            JSONArray dTimes = daily.getJSONArray("time");
            JSONArray dCodes = daily.getJSONArray("weather_code");
            JSONArray dMax = daily.getJSONArray("temperature_2m_max");
            JSONArray dMin = daily.getJSONArray("temperature_2m_min");
            JSONArray dUvMax = daily.optJSONArray("uv_index_max");

            if (!b.isDay && dUvMax != null && dUvMax.length() > 0 && b.uvIndex < 0.2f) {
                b.uvIndex = 0.0f;
            }

            int days = Math.min(10, dTimes.length());
            for (int i = 0; i < days; i++) {
                String dateIso = dTimes.optString(i, "");
                String dayLabel = (i == 0) ? "Today" : formatIsoWeekday(dateIso);
                int code = dCodes.optInt(i, b.weatherCode);
                int hi = (int) Math.round(dMax.optDouble(i, curTemp + 4));
                int lo = (int) Math.round(dMin.optDouble(i, curTemp - 5));
                if (i == 0) {
                    b.highTempC = hi;
                    b.lowTempC = lo;
                }
                b.daily.add(new WeatherModels.DailyItem(dayLabel, code, lo, hi, i == 0));
            }

            return b;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String formatIsoHourLabel(String iso) {
        // Expects "YYYY-MM-DDTHH:MM"
        int tPos = iso.indexOf('T');
        if (tPos >= 0 && iso.length() >= tPos + 3) {
            try {
                int hour24 = Integer.parseInt(iso.substring(tPos + 1, tPos + 3));
                if (hour24 == 0) return "12 AM";
                if (hour24 < 12) return hour24 + " AM";
                if (hour24 == 12) return "12 PM";
                return (hour24 - 12) + " PM";
            } catch (NumberFormatException ignored) {
            }
        }
        return iso;
    }

    private static String formatIsoWeekday(String dateIso) {
        // Expects "YYYY-MM-DD"
        try {
            String[] parts = dateIso.split("-");
            if (parts.length == 3) {
                Calendar cal = Calendar.getInstance();
                cal.set(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]) - 1,
                        Integer.parseInt(parts[2]));
                return new SimpleDateFormat("EEE", Locale.US).format(cal.getTime());
            }
        } catch (Throwable ignored) {
        }
        return dateIso;
    }

    private static WeatherModels.ForecastBundle buildRealisticSeedBundle(WeatherModels.CitySpec city) {
        WeatherModels.ForecastBundle b = new WeatherModels.ForecastBundle();
        b.city = city;
        b.currentTempC = city.seedBaseTempC;
        b.feelsLikeC = city.seedBaseTempC + (city.seedBaseTempC >= 28 ? 2 : -1);
        b.highTempC = city.seedBaseTempC + 4;
        b.lowTempC = city.seedBaseTempC - 7;
        b.weatherCode = city.seedWeatherCode;
        b.isDay = city.seedIsDay;
        b.conditionText = WeatherModels.wmoConditionLabel(b.weatherCode, b.isDay);
        b.humidityPct = (b.weatherCode >= 51) ? 84 : 58;
        b.dewPointC = WeatherModels.computeDewPointC(b.currentTempC, b.humidityPct);
        b.uvIndex = b.isDay ? ((b.weatherCode >= 51) ? 2.0f : 6.0f) : 0.0f;
        b.windKmh = (b.weatherCode >= 51) ? 21.0f : 14.0f;
        b.windDirectionDeg = 68;
        b.surfacePressureHpa = 1012;
        b.visibilityKm = (b.weatherCode >= 51) ? 7.5f : 12.0f;
        b.fetchedEpochMs = System.currentTimeMillis();
        b.fromLiveNetwork = false;

        Calendar cal = Calendar.getInstance();
        int curHour = cal.get(Calendar.HOUR_OF_DAY);
        for (int i = 0; i < 24; i++) {
            int h = (curHour + i) % 24;
            boolean day = h >= 6 && h <= 18;
            String label;
            if (i == 0) label = "Now";
            else if (h == 0) label = "12 AM";
            else if (h < 12) label = h + " AM";
            else if (h == 12) label = "12 PM";
            else label = (h - 12) + " PM";

            double curve = Math.sin(((h - 9) / 24.0) * Math.PI * 2.0) * 3.8;
            int temp = (int) Math.round(city.seedBaseTempC - 1.5 + curve);
            int code = (i % 7 == 4 && city.seedWeatherCode == 0) ? 1 : city.seedWeatherCode;
            b.hourly.add(new WeatherModels.HourlyItem(label, temp, code, day));
        }

        SimpleDateFormat dayFmt = new SimpleDateFormat("EEE", Locale.US);
        int[] codeCycle = {
                city.seedWeatherCode, 1, 0, 2,
                (city.seedWeatherCode >= 51 ? 61 : 1), 0, 2, 1, 0, 1
        };
        int[] hiDelta = { 4, 5, 4, 3, 2, 3, 5, 4, 4, 3 };
        int[] loDelta = { -7, -6, -6, -7, -8, -7, -6, -6, -7, -7 };
        for (int d = 0; d < 10; d++) {
            String dayLabel = (d == 0) ? "Today" : dayFmt.format(cal.getTime());
            b.daily.add(new WeatherModels.DailyItem(
                    dayLabel,
                    codeCycle[d],
                    city.seedBaseTempC + loDelta[d],
                    city.seedBaseTempC + hiDelta[d],
                    d == 0));
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return b;
    }
}
