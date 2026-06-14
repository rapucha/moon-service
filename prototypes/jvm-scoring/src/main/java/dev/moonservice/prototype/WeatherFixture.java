package dev.moonservice.prototype;

record WeatherFixture(
        int cloudCoverPercent,
        int lowCloudCoverPercent,
        int midCloudCoverPercent,
        int highCloudCoverPercent,
        int precipitationProbabilityPercent,
        double precipitationMm,
        int visibilityMeters,
        int weatherCode,
        double forecastAgeHours
) {
    static final WeatherFixture PRAGUE_PARTLY_CLOUDY = new WeatherFixture(
            35,
            10,
            25,
            40,
            5,
            0.0,
            20000,
            2,
            1.0
    );
}
