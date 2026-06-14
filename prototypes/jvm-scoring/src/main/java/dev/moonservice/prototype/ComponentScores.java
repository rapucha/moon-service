package dev.moonservice.prototype;

record ComponentScores(
        int moonAltitudeFit,
        int sunLightFit,
        int moonIlluminationFit,
        int weatherFit,
        int forecastConfidence
) {
    int total() {
        return moonAltitudeFit + sunLightFit + moonIlluminationFit + weatherFit + forecastConfidence;
    }
}
