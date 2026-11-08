package com.realme.samurai.weather;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Clean-room immutable forecast models, WMO weather condition mapping, and built-in city presets
 * (`WeatherModels`) for iOS 26 Liquid Glass Weather.
 */
public final class WeatherModels {

    public static final int SKY_CLEAR_DAY = 0;
    public static final int SKY_PARTLY_CLOUDY_DAY = 1;
    public static final int SKY_RAIN_STORM = 2;
    public static final int SKY_CLEAR_NIGHT = 3;
    public static final int SKY_CLOUDY_NIGHT = 4;

    public static final int ICON_SUN = 0;
    public static final int ICON_PARTLY_CLOUDY = 1;
    public static final int ICON_CLOUD = 2;
    public static final int ICON_RAIN = 3;
    public static final int ICON_STORM = 4;
    public static final int ICON_MOON = 5;
    public static final int ICON_MOON_CLOUD = 6;

    private WeatherModels() {}

    public static final class CitySpec {
        public final String name;
        public final String region;
        public final double latitude;
        public final double longitude;
        public final int seedBaseTempC;
        public final int seedWeatherCode;
        public final boolean seedIsDay;

        public CitySpec(
                String name,
                String region,
                double latitude,
                double longitude,
                int seedBaseTempC,
                int seedWeatherCode,
                boolean seedIsDay) {
            this.name = name;
            this.region = region;
            this.latitude = latitude;
            this.longitude = longitude;
            this.seedBaseTempC = seedBaseTempC;
            this.seedWeatherCode = seedWeatherCode;
            this.seedIsDay = seedIsDay;
        }

        public String cacheKey() {
            return String.format(java.util.Locale.US, "%.3f_%.3f", latitude, longitude);
        }
    }

    public static final List<CitySpec> PRESET_CITIES = Collections.unmodifiableList(Arrays.asList(
            new CitySpec("Hyderabad", "Telangana, India", 17.3850, 78.4867, 29, 1, true),
            new CitySpec("Mumbai", "Maharashtra, India", 19.0760, 72.8777, 31, 2, true),
            new CitySpec("Bengaluru", "Karnataka, India", 12.9716, 77.5946, 25, 2, true),
            new CitySpec("New Delhi", "Delhi, India", 28.6139, 77.2090, 28, 0, true),
            new CitySpec("Cupertino", "California, United States", 37.3230, -122.0322, 21, 0, true),
            new CitySpec("London", "United Kingdom", 51.5074, -0.1278, 13, 61, true),
            new CitySpec("Tokyo", "Japan", 35.6762, 139.6503, 18, 0, false),
            new CitySpec("New York", "New York, United States", 40.7128, -74.0060, 16, 1, true),
            new CitySpec("Dubai", "United Arab Emirates", 25.2048, 55.2708, 34, 0, true)
    ));

    public static final class HourlyItem {
        public final String label;
        public final int tempC;
        public final int weatherCode;
        public final boolean isDay;

        public HourlyItem(String label, int tempC, int weatherCode, boolean isDay) {
            this.label = label;
            this.tempC = tempC;
            this.weatherCode = weatherCode;
            this.isDay = isDay;
        }
    }

    public static final class DailyItem {
        public final String dayLabel;
        public final int weatherCode;
        public final int lowTempC;
        public final int highTempC;
        public final boolean isToday;

        public DailyItem(String dayLabel, int weatherCode, int lowTempC, int highTempC, boolean isToday) {
            this.dayLabel = dayLabel;
            this.weatherCode = weatherCode;
            this.lowTempC = lowTempC;
            this.highTempC = highTempC;
            this.isToday = isToday;
        }
    }

    public static final class ForecastBundle {
        public CitySpec city;
        public int currentTempC;
        public int feelsLikeC;
        public int highTempC;
        public int lowTempC;
        public int weatherCode;
        public boolean isDay;
        public String conditionText = "Mostly Clear";
        public int humidityPct;
        public int dewPointC;
        public float uvIndex;
        public float windKmh;
        public int windDirectionDeg;
        public int surfacePressureHpa;
        public float visibilityKm;
        public List<HourlyItem> hourly = new ArrayList<>();
        public List<DailyItem> daily = new ArrayList<>();
        public long fetchedEpochMs;
        public boolean fromLiveNetwork;
    }

    public static String wmoConditionLabel(int code, boolean isDay) {
        if (code == 0) return isDay ? "Sunny" : "Clear Sky";
        if (code == 1) return isDay ? "Mostly Sunny" : "Mostly Clear";
        if (code == 2) return "Partly Cloudy";
        if (code == 3) return "Overcast";
        if (code >= 45 && code <= 48) return "Misty Fog";
        if (code >= 51 && code <= 57) return "Light Drizzle";
        if (code >= 61 && code <= 65) return "Rain";
        if (code == 66 || code == 67) return "Freezing Rain";
        if (code >= 71 && code <= 77) return "Snowfall";
        if (code >= 80 && code <= 82) return "Rain Showers";
        if (code >= 95) return "Thunderstorms";
        return "Partly Cloudy";
    }

    public static int resolveSkyMode(int code, boolean isDay) {
        if (code >= 51 && code <= 99) {
            return SKY_RAIN_STORM;
        }
        if (!isDay) {
            return (code >= 2) ? SKY_CLOUDY_NIGHT : SKY_CLEAR_NIGHT;
        }
        return (code >= 2) ? SKY_PARTLY_CLOUDY_DAY : SKY_CLEAR_DAY;
    }

    public static int resolveIconKind(int code, boolean isDay) {
        if (code >= 95) return ICON_STORM;
        if (code >= 51 && code <= 86) return ICON_RAIN;
        if (code >= 3 && code <= 48) return ICON_CLOUD;
        if (code == 1 || code == 2) return isDay ? ICON_PARTLY_CLOUDY : ICON_MOON_CLOUD;
        return isDay ? ICON_SUN : ICON_MOON;
    }

    public static String compassHeading(int deg) {
        String[] dirs = { "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW" };
        int normalized = ((deg % 360) + 360) % 360;
        int idx = Math.round(normalized / 22.5f) % 16;
        return dirs[idx];
    }

    public static String uvCategory(float uv) {
        if (uv < 3f) return "Low";
        if (uv < 6f) return "Moderate";
        if (uv < 8f) return "High";
        if (uv < 11f) return "Very High";
        return "Extreme";
    }

    public static String uvAdvisory(float uv) {
        if (uv < 3f) return "Low levels all day. No extra protection needed.";
        if (uv < 6f) return "Seek shade around midday hours.";
        if (uv < 8f) return "Use SPF 30+ sun protection until 4 PM.";
        return "Avoid prolonged midday sun exposure.";
    }

    public static int computeDewPointC(double tempC, double rhPct) {
        double rh = Math.max(5.0, Math.min(100.0, rhPct));
        double a = 17.625;
        double b = 243.04;
        double alpha = Math.log(rh / 100.0) + (a * tempC) / (b + tempC);
        return (int) Math.round((b * alpha) / (a - alpha));
    }
}
