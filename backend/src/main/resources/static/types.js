/**
 * @typedef {Object} MoonPathPoint
 * @property {string} at
 * @property {number} altitudeDegrees
 * @property {number} azimuthDegrees
 * @property {number} sunAltitudeDegrees
 * @property {number} sunAzimuthDegrees
 * @property {string} lightBucket
 * @property {string} role
 */

/**
 * @typedef {Object} MoonPassPath
 * @property {MoonPathPoint} start
 * @property {MoonPathPoint} end
 * @property {MoonPathPoint[]} samples
 */

/**
 * @typedef {Object} MoonPass
 * @property {string} id
 * @property {string} startsAt
 * @property {string} endsAt
 * @property {MoonPassPath} path
 */

/**
 * @typedef {Object} MoonFacts
 * @property {number} altitudeDegrees
 * @property {number} azimuthDegrees
 * @property {number} illuminationPercent
 * @property {number} phaseAngleDegrees
 * @property {number|null} [brightLimbTiltDegrees]
 * @property {number|null} [northPoleTiltDegrees]
 * @property {string} phaseName
 */

/**
 * @typedef {Object} SunFacts
 * @property {number} altitudeDegrees
 * @property {number} azimuthDegrees
 * @property {string} lightBucket
 */

/**
 * @typedef {Object} WeatherFacts
 * @property {string} sourceResolution
 * @property {string} segmentKind
 * @property {number} cloudCoverMeanPercent
 * @property {number} cloudCoverMaxPercent
 * @property {number} lowCloudCoverMaxPercent
 * @property {number} midCloudCoverMaxPercent
 * @property {number} highCloudCoverMaxPercent
 * @property {number} precipitationProbabilityMaxPercent
 * @property {number} precipitationMm
 * @property {number} visibilityMinMeters
 * @property {number} weatherCode
 * @property {string} summary
 */

/**
 * @typedef {Object} ComponentScores
 * @property {number} moonAltitudeFit
 * @property {number} sunLightFit
 * @property {number} moonIlluminationFit
 * @property {number} weatherFit
 * @property {number} forecastConfidence
 */

/**
 * @typedef {Object} ExposureBalance
 * @property {string} label
 * @property {string} text
 */

/**
 * @typedef {Object} OpportunityLinks
 * @property {string} ics
 * @property {boolean=} icsReady
 */

/**
 * @typedef {Object} Opportunity
 * @property {string} id
 * @property {string} windowKind
 * @property {MoonPass} moonPass
 * @property {string} startsAt
 * @property {string} suggestedAt
 * @property {string} endsAt
 * @property {string} localTimeZone
 * @property {number} score
 * @property {string} confidence
 * @property {ComponentScores} components
 * @property {MoonFacts} moon
 * @property {{start: MoonPathPoint, suggested: MoonPathPoint, end: MoonPathPoint, samples: MoonPathPoint[]}} moonPath
 * @property {SunFacts} sun
 * @property {WeatherFacts} weather
 * @property {ExposureBalance} exposureBalance
 * @property {string} reason
 * @property {OpportunityLinks} links
 */

/**
 * @typedef {Object} RejectedWindow
 * @property {string} startsAt
 * @property {string} endsAt
 * @property {string=} reasonCode
 * @property {string=} reason
 * @property {number=} moonSunSeparationDegrees
 * @property {number=} moonIlluminationPercent
 * @property {number=} moonAltitudeDegrees
 * @property {number=} sunAltitudeDegrees
 */

export {};
