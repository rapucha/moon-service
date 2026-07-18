var RECENT_KEY = "moonService.recentSearches.v1";
var MAX_RECENT = 5;

export function saveRecentLocation(location, request) {
  var entry = {
    displayName: location.displayName,
    slug: acceptedLocationId(location, request),
    timezone: location.timezone
  };
  var current = readRecent().filter(function (item) {
    return item.slug !== entry.slug && item.displayName !== entry.displayName;
  });
  current.unshift(entry);
  writeRecent(current.slice(0, MAX_RECENT));
}

export function readRecent() {
  try {
    var raw = window.localStorage.getItem(RECENT_KEY);
    var parsed = raw ? JSON.parse(raw) : [];
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed
      .filter(function (entry) {
        return entry && typeof entry.displayName === "string" && entry.displayName.trim();
      })
      .slice(0, MAX_RECENT);
  } catch (error) {
    return [];
  }
}

export function writeRecent(entries) {
  try {
    window.localStorage.setItem(RECENT_KEY, JSON.stringify(entries));
  } catch (error) {
    // Browser storage is optional for this page.
  }
}

function acceptedLocationId(location, request) {
  if (request.locationId) {
    return request.locationId;
  }
  if (location.id && location.id.indexOf("moon-service-") === 0) {
    return location.id;
  }
  if (location.id && location.id.indexOf("openmeteo:") === 0) {
    return "moon-service-" + location.id.substring("openmeteo:".length);
  }
  return location.id || "";
}
