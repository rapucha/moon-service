export function lightBandSegments(points) {
  return points.slice(0, -1).reduce(function (bands, point, index) {
    var next = points[index + 1];
    var bucket = lightBandBucket(point, next);
    var segment = {
      x: point.x,
      endX: next.x,
      width: Math.max(0, next.x - point.x),
      startsAt: point.at,
      endsAt: next.at,
      lightBucket: bucket
    };
    if (segment.width <= 0) {
      return bands;
    }

    var previous = bands[bands.length - 1];
    if (previous && previous.lightBucket === bucket) {
      previous.endX = segment.endX;
      previous.width = Math.max(0, previous.endX - previous.x);
      previous.endsAt = segment.endsAt;
    } else {
      bands.push(segment);
    }
    return bands;
  }, []);
}

function lightBandBucket(point, next) {
  if (Number.isFinite(point.sunAltitudeDegrees) && Number.isFinite(next.sunAltitudeDegrees)) {
    return lightBucketForSunAltitude((point.sunAltitudeDegrees + next.sunAltitudeDegrees) / 2);
  }
  return point.lightBucket || next.lightBucket || "unknown";
}

function lightBucketForSunAltitude(sunAltitudeDegrees) {
  // Keep these thresholds aligned with ScoringModel.lightBucket.
  if (sunAltitudeDegrees >= 6.0) {
    return "daylight";
  }
  if (sunAltitudeDegrees >= -0.833) {
    return "golden_hour";
  }
  if (sunAltitudeDegrees >= -6.0) {
    return "civil_twilight";
  }
  if (sunAltitudeDegrees >= -12.0) {
    return "nautical_twilight";
  }
  return "night";
}
