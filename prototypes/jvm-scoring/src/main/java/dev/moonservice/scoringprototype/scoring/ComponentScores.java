package dev.moonservice.scoringprototype.scoring;

public record ComponentScores(
        int moonAltitudeFit,
        int sunLightFit,
        int moonIlluminationFit,
        int weatherFit,
        int forecastConfidence
) {
    public int total() {
        return moonAltitudeFit + sunLightFit + moonIlluminationFit + weatherFit + forecastConfidence;
    }
}
