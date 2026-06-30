(function () {
  "use strict";

  var RECENT_KEY = "moonService.recentSearches.v1";
  var MAX_RECENT = 5;
  var SVG_NS = "http://www.w3.org/2000/svg";
  var CONTROL_CHARACTER_PATTERN = /[\u0000-\u001F\u007F-\u009F\u061C\u200E\u200F\u202A-\u202E\u2066-\u2069]/u;
  var TERM_DESCRIPTIONS = {
    "Location": "The kind of place this lookup accepts.",
    "Storage": "Where recent searches are kept.",
    "Output": "What this page produces after a lookup.",
    "Forecast": "How many days of forecast data were considered.",
    "Evaluated": "How many candidate Moon windows were checked before ranking.",
    "Timezone": "The local timezone used for displayed times.",
    "Lookup": "Whether this result came from a typed query or a selected location.",
    "Suggested": "The best instant inside the displayed Moon window.",
    "Duration": "How long the candidate window lasts.",
    "Moon altitude": "Moon height above the horizon. Lower values are usually more useful for landscape composition.",
    "Moon azimuth": "Compass direction of the Moon, in degrees.",
    "Bucket": "The ambient-light category around the window, such as golden hour or twilight.",
    "Sun altitude": "Sun height relative to the horizon. Negative values mean the Sun is below the horizon.",
    "Moon phase": "How much of the visible Moon disk is lit at the suggested time, including waxing or waning shape.",
    "Summary": "A compact weather description for the candidate window.",
    "Cloud": "Mean and maximum cloud cover across the candidate window.",
    "Precip": "Precipitation risk and expected amount across the candidate window.",
    "Visibility": "Forecast surface visibility near the location.",
    "Phase score": "How well the Moon illumination fits the scoring model.",
    "Sun light": "How well the ambient light fits the scoring model.",
    "Weather": "How well forecast conditions fit the scoring model.",
    "Confidence": "How much confidence the scoring model has in the forecast inputs."
  };

  var form = document.getElementById("search-form");
  var input = document.getElementById("location-input");
  var formFeedback = document.getElementById("form-feedback");
  var results = document.getElementById("results");
  var recentList = document.getElementById("recent-list");
  var clearRecent = document.getElementById("clear-recent");
  var submitButton = form.querySelector("button[type='submit']");
  var activeRequest = null;

  form.addEventListener("submit", function (event) {
    event.preventDefault();
    search(input.value, { updateUrl: true });
  });

  clearRecent.addEventListener("click", function () {
    writeRecent([]);
    renderRecent();
  });

  window.addEventListener("popstate", function () {
    runLookup(lookupFromUrl(), { updateUrl: false });
  });

  renderRecent();
  runLookup(lookupFromUrl(), { updateUrl: false });

  function lookupFromUrl() {
    var params = new URLSearchParams(window.location.search);
    var locationId = normalizeQuery(params.get("locationId") || "");
    var query = normalizeQuery(params.get("q") || "");
    if (locationId) {
      return { locationId: locationId, label: locationId };
    }
    if (query) {
      return { q: query, label: query };
    }
    return null;
  }

  function runLookup(request, options) {
    if (!request) {
      input.value = "";
      renderIntro();
      return;
    }
    if (request.locationId) {
      searchLocationId(request.locationId, request.label, options);
    } else {
      search(request.q, options);
    }
  }

  function normalizeQuery(value) {
    return String(value || "").trim().replace(/\s+/g, " ");
  }

  function search(rawQuery, options) {
    var query = normalizeQuery(rawQuery);
    var validationMessage = validateQuery(query);

    input.value = query;
    formFeedback.textContent = validationMessage || "";

    if (validationMessage) {
      renderInvalid(validationMessage);
      return;
    }

    if (options.updateUrl) {
      window.history.pushState({ q: query }, "", "/search?q=" + encodeURIComponent(query));
    }

    fetchOpportunities({ q: query, label: query });
  }

  function searchLocationId(rawLocationId, displayName, options) {
    var locationId = normalizeQuery(rawLocationId);
    var label = normalizeQuery(displayName) || locationId;
    var validationMessage = validateLocationId(locationId);

    input.value = label;
    formFeedback.textContent = validationMessage || "";

    if (validationMessage) {
      renderInvalid(validationMessage);
      return;
    }

    if (options.updateUrl) {
      window.history.pushState(
        { locationId: locationId },
        "",
        "/search?locationId=" + encodeURIComponent(locationId));
    }

    fetchOpportunities({ locationId: locationId, label: label });
  }

  function validateQuery(query) {
    if (!query) {
      return "Enter a city or town.";
    }
    if (Array.from(query).length > 100) {
      return "Use 100 characters or fewer.";
    }
    if (CONTROL_CHARACTER_PATTERN.test(query)) {
      return "Remove unsupported control or formatting characters.";
    }
    return "";
  }

  function validateLocationId(locationId) {
    if (!locationId) {
      return "Choose a location.";
    }
    if (Array.from(locationId).length > 100) {
      return "Use 100 characters or fewer.";
    }
    if (CONTROL_CHARACTER_PATTERN.test(locationId)) {
      return "Remove unsupported control or formatting characters.";
    }
    return "";
  }

  function fetchOpportunities(request) {
    if (activeRequest) {
      activeRequest.abort();
    }

    activeRequest = new AbortController();
    var requestController = activeRequest;
    setSearchBusy(true);
    results.setAttribute("aria-busy", "true");
    renderLoading(request.label);

    fetch(apiPathFor(request), {
      headers: {
        "Accept": "application/json"
      },
      signal: requestController.signal
    })
      .then(function (response) {
        return response.json()
          .catch(function () {
            return fallbackPayload(response.status);
          })
          .then(function (payload) {
            renderResponse(payload || fallbackPayload(response.status), request, response.status);
          });
      })
      .catch(function (error) {
        if (error.name !== "AbortError") {
          renderResponse({
            status: "temporarily_unavailable",
            message: "The lookup could not be reached. Try again shortly."
          }, request, 503);
        }
      })
      .finally(function () {
        if (activeRequest === requestController) {
          results.setAttribute("aria-busy", "false");
          setSearchBusy(false);
          activeRequest = null;
        }
      });
  }

  function apiPathFor(request) {
    if (request.locationId) {
      return "/api/opportunities?locationId=" + encodeURIComponent(request.locationId);
    }
    return "/api/opportunities?q=" + encodeURIComponent(request.q);
  }

  function fallbackPayload(statusCode) {
    if (statusCode === 429) {
      return {
        status: "rate_limited",
        message: "Too many requests. Please try again shortly."
      };
    }
    if (statusCode === 400) {
      return {
        status: "invalid_request",
        message: "The location query could not be used."
      };
    }
    return {
      status: "temporarily_unavailable",
      message: "The lookup is temporarily unavailable."
    };
  }

  function renderResponse(payload, request, statusCode) {
    switch (payload.status) {
      case "ok":
        renderOk(payload, request);
        break;
      case "ambiguous_location":
        renderAmbiguous(payload, request.label);
        break;
      case "location_not_found":
        renderStatus("No place found", payload.message || "Try a city or town, optionally with a country.", "warning", payload.suggestions);
        break;
      case "invalid_request":
        renderStatus("Check the location", payload.message || "Enter a city or town.", "error", errorsToSuggestions(payload));
        break;
      case "temporarily_unavailable":
        renderStatus("Lookup temporarily unavailable", payload.message || "Try again shortly.", "warning");
        break;
      case "rate_limited":
        renderStatus("Too many requests", payload.message || "Please wait before trying again.", "warning", retrySuggestion(payload));
        break;
      default:
        renderStatus("Unexpected response", "The backend returned a response this page does not understand.", statusCode >= 500 ? "warning" : "error");
    }
  }

  function renderIntro() {
    replaceResults(
      element("section", { className: "state-panel intro-state" },
        element("div", { className: "state-header" },
          element("p", { className: "eyebrow" }, "Ready"),
          element("h3", {}, "Search a city or town"),
          element("p", {}, "Ranked windows will appear here with Moon position, ambient light, weather, and caveats.")),
        element("dl", { className: "intro-grid" },
          fact("Location", "City or town"),
          fact("Storage", "Browser recent list only"),
          fact("Output", "Shareable result page")))
    );
  }

  function renderLoading(query) {
    replaceResults(
      element("section", { className: "state-panel loading-state" },
        element("div", { className: "state-header" },
          element("p", { className: "eyebrow" }, "Working"),
          element("h3", {}, "Looking up " + query),
          element("p", {}, "Resolving the location, checking Moon windows, and reading the forecast.")),
        element("div", { className: "loading-bar", ariaLabel: "Loading" },
          element("span", {})))
    );
  }

  function renderInvalid(message) {
    renderStatus("Check the location", message, "error");
  }

  function renderOk(payload, request) {
    var location = payload.location || {};
    var timezone = location.timezone || "UTC";
    var countryCode = location.countryCode || "";
    var opportunities = Array.isArray(payload.opportunities) ? payload.opportunities : [];
    var children = [
      resultSummary(payload, request, opportunities.length)
    ];

    if (location.kind === "real_location" && location.displayName) {
      saveRecent(location, request);
      input.value = location.displayName;
    }

    if (opportunities.length === 0) {
      children.push(emptyOpportunities(payload));
    } else {
      children.push(element("div", { className: "opportunity-list" },
        opportunities.map(function (opportunity, index) {
          return opportunityCard(opportunity, index, timezone, countryCode);
        })
      ));
    }

    if (Array.isArray(payload.messages) && payload.messages.length > 0) {
      children.push(messagesList(payload.messages));
    }

    if (Array.isArray(payload.rejected) && payload.rejected.length > 0) {
      children.push(rejectedDetails(payload.rejected, timezone, countryCode));
    }

    replaceResults(children);
  }

  function resultSummary(payload, request, opportunityCount) {
    var location = payload.location || {};
    var sharePath = sharePathFor(request);
    var shareUrl = window.location.origin + sharePath;
    var forecastText = payload.forecastHorizonDays ? payload.forecastHorizonDays + "-day forecast" : "Forecast window";
    var evaluatedText = Number.isFinite(payload.candidateWindowsEvaluated)
      ? payload.candidateWindowsEvaluated + " windows evaluated"
      : "Ranked opportunities";

    return element("section", { className: "result-panel result-summary", ariaLabelledby: "result-title" },
      element("div", { className: "summary-topline" },
        element("div", {},
          element("p", { className: "eyebrow" }, "Resolved location"),
          element("h3", { id: "result-title" }, location.displayName || "Resolved location"),
          element("p", { className: "summary-count" }, opportunityCount === 1 ? "1 ranked Moon opportunity" : opportunityCount + " ranked Moon opportunities")),
        element("div", { className: "share-tools" },
          element("button", { type: "button", className: "copy-button", "data-share-url": shareUrl }, "Copy link"),
          element("a", { href: sharePath }, "Open share link"))
      ),
      element("dl", { className: "summary-grid" },
        fact("Forecast", forecastText),
        fact("Evaluated", evaluatedText),
        fact("Timezone", location.timezone || "Unavailable"),
        fact("Lookup", request.locationId ? "Selected location" : "Search query"))
    );
  }

  function sharePathFor(request) {
    if (request.locationId) {
      return "/search?locationId=" + encodeURIComponent(request.locationId);
    }
    return "/search?q=" + encodeURIComponent(request.q);
  }

  function emptyOpportunities(payload) {
    var reason = payload.emptyReason && payload.emptyReason.text
      ? payload.emptyReason.text
      : "No useful Moon window passed the current scoring threshold in this forecast period.";
    return element("section", { className: "status-panel warning" },
      element("p", {
        className: "eyebrow tooltip",
        title: "No candidate window met the current scoring threshold.",
        "data-tooltip": "No candidate window met the current scoring threshold."
      }, "No match"),
      element("h3", {}, "No ranked windows"),
      element("p", {}, reason));
  }

  function opportunityCard(opportunity, index, timezone, countryCode) {
    var moon = opportunity.moon || {};
    var sun = opportunity.sun || {};
    var weather = opportunity.weather || {};
    var exposureBalance = opportunity.exposureBalance || {};
    var components = opportunity.components || {};

    return element("article", { className: "opportunity-card" + (index === 0 ? " is-primary" : "") },
      element("header", { className: "opportunity-header" },
        element("div", { className: "opportunity-title" },
          element("p", { className: "rank-label" }, index === 0 ? "Best match" : "Option " + (index + 1)),
          element("h3", {}, formatWindow(opportunity, timezone, countryCode)),
          element("p", { className: "reason" }, opportunity.reason || "Ranked Moon opportunity.")),
        scoreBlock(opportunity.score)
      ),
      element("dl", { className: "fact-grid key-facts" },
        fact("Suggested", formatDateTime(opportunity.suggestedAt, timezone, countryCode)),
        fact("Duration", durationText(opportunity.startsAt, opportunity.endsAt)),
        fact("Moon altitude", degrees(moon.altitudeDegrees)),
        fact("Moon azimuth", degrees(moon.azimuthDegrees))
      ),
      moonPathPanel(opportunity, timezone, countryCode),
      element("div", { className: "metric-columns" },
        metricGroup("Light", [
          fact("Bucket", readableToken(sun.lightBucket) || "Unavailable"),
          fact("Sun altitude", degrees(sun.altitudeDegrees)),
          fact("Moon phase", moonPhaseSummary(moon))
        ]),
        metricGroup("Weather", [
          fact("Summary", weather.summary || readableToken(weather.segmentKind) || "Forecast unavailable"),
          fact("Cloud", cloudText(weather)),
          fact("Precip", precipitationText(weather)),
          fact("Visibility", visibilityText(weather))
        ])
      ),
      exposureBalance.text
        ? element("p", { className: "exposure" }, exposureBalance.label ? readableToken(exposureBalance.label) + ": " + exposureBalance.text : exposureBalance.text)
        : null,
      opportunityActions(opportunity),
      scoreDetails(components)
    );
  }

  function scoreBlock(score) {
    var value = Number.isFinite(score) ? Math.max(0, Math.min(100, score)) : 0;
    return element("div", { className: "score-block", ariaLabel: "Opportunity score" },
      element("span", { className: "score-value" }, Number.isFinite(score) ? String(score) : "--"),
      element("span", {
        className: "score-label tooltip",
        title: "Overall fit score from 0 to 100, combining Moon position, light, phase, weather, and confidence.",
        "data-tooltip": "Overall fit score from 0 to 100, combining Moon position, light, phase, weather, and confidence."
      }, "score"),
      element("span", { className: "score-meter" },
        element("span", { style: "width: " + value + "%" })));
  }

  function metricGroup(title, facts) {
    return element("section", { className: "metric-group" },
      element("h4", {}, title),
      element("dl", { className: "fact-grid compact" }, facts));
  }

  function moonPhaseSummary(moon) {
    if (!Number.isFinite(moon.illuminationPercent) && !moon.phaseName) {
      return "Unavailable";
    }

    var phaseName = readableToken(moon.phaseName) || "Moon phase";
    var canvas = element("canvas", {
      className: "moon-phase-canvas",
      width: 48,
      height: 48,
      ariaLabel: phaseName,
      title: phaseName + ", " + percent(moon.illuminationPercent) + " lit"
    });
    drawMoonPhase(canvas, moon.phaseAngleDegrees);

    return element("span", { className: "phase-summary" },
      canvas,
      element("span", {},
        element("strong", {}, phaseName),
        element("span", {}, percent(moon.illuminationPercent) + " lit")));
  }

  function drawMoonPhase(canvas, phaseAngleDegrees) {
    var context = canvas.getContext && canvas.getContext("2d");
    if (!context) {
      return;
    }

    var size = canvas.width;
    var center = size / 2;
    var radius = size * 0.43;
    var image = context.createImageData(size, size);
    var phase = Number.isFinite(phaseAngleDegrees) ? normalizeDegrees(phaseAngleDegrees) : 180;
    var radians = phase * Math.PI / 180;
    var sunX = Math.sin(radians);
    var sunZ = -Math.cos(radians);

    for (var y = 0; y < size; y += 1) {
      for (var x = 0; x < size; x += 1) {
        var dx = (x + 0.5 - center) / radius;
        var dy = (y + 0.5 - center) / radius;
        var distanceSquared = dx * dx + dy * dy;
        var index = (y * size + x) * 4;
        if (distanceSquared > 1) {
          image.data[index + 3] = 0;
          continue;
        }

        var z = Math.sqrt(1 - distanceSquared);
        var lit = dx * sunX + z * sunZ > 0;
        var shade = 0.72 + 0.28 * z;
        var color = lit ? [244, 238, 206] : [42, 47, 52];
        image.data[index] = Math.round(color[0] * shade);
        image.data[index + 1] = Math.round(color[1] * shade);
        image.data[index + 2] = Math.round(color[2] * shade);
        image.data[index + 3] = 255;
      }
    }

    context.putImageData(image, 0, 0);
    context.beginPath();
    context.arc(center, center, radius, 0, Math.PI * 2);
    context.strokeStyle = "#d8e0df";
    context.lineWidth = 1.5;
    context.stroke();
  }

  function moonPathPanel(opportunity, timezone, countryCode) {
    var path = opportunity.moonPath || {};
    var samples = moonPathSamples(path);
    if (samples.length < 2) {
      return null;
    }

    return element("section", { className: "moon-path-panel" },
      element("div", { className: "moon-path-header" },
        element("h4", {}, "Moon path"),
        element("p", {}, "Start, suggested, and end positions across the window")),
      element("div", { className: "moon-path-summary" },
        moonPathPoint("Start", path.start, timezone, countryCode),
        moonPathPoint("Suggested", path.suggested, timezone, countryCode),
        moonPathPoint("End", path.end, timezone, countryCode)),
      element("div", { className: "moon-path-charts" },
        chartBlock("Altitude", altitudeChart(samples, timezone, countryCode)),
        chartBlock("Azimuth", azimuthChart(samples))));
  }

  function moonPathPoint(label, point, timezone, countryCode) {
    if (!hasPosition(point)) {
      return null;
    }
    return element("div", { className: "moon-path-point" },
      element("span", { className: "moon-path-label" }, label),
      element("span", { className: "moon-path-time" }, formatTime(point.at, timezone, countryCode)),
      element("span", { className: "moon-path-position" },
        element("span", {}, "Alt " + degrees(point.altitudeDegrees)),
        element("span", {}, "Az " + degrees(point.azimuthDegrees))));
  }

  function chartBlock(label, chart) {
    if (!chart) {
      return null;
    }
    return element("div", { className: "moon-chart" },
      element("span", { className: "moon-chart-label" }, label),
      chart);
  }

  function altitudeChart(samples, timezone, countryCode) {
    var points = chartSamples(samples);
    if (points.length < 2) {
      return null;
    }

    var width = 400;
    var height = 130;
    var left = 34;
    var right = 14;
    var top = 18;
    var bottom = 94;
    var firstTime = points[0].time;
    var lastTime = points[points.length - 1].time;
    var timeSpan = Math.max(1, lastTime - firstTime);
    var maxAltitude = points.reduce(function (max, point) {
      return Math.max(max, point.altitudeDegrees);
    }, 0);
    var ceiling = Math.min(90, Math.max(12, Math.ceil((maxAltitude + 1) / 5) * 5));
    var chartWidth = width - left - right;
    var chartHeight = bottom - top;

    points.forEach(function (point) {
      point.x = left + ((point.time - firstTime) / timeSpan) * chartWidth;
      point.y = bottom - (clamp(point.altitudeDegrees, 0, ceiling) / ceiling) * chartHeight;
    });

    var bands = lightBandSegments(points);
    var timeTicks = altitudeHourTicks(points, timezone);
    var line = altitudeArcPath(points);

    return svgElement("svg", {
      className: "altitude-chart",
      viewBox: "0 0 " + width + " " + height,
      role: "img",
      ariaLabel: "Moon altitude across the opportunity window"
    },
      bands.map(function (band) {
        return svgElement("rect", {
          className: "light-band is-" + roleClass(band.lightBucket),
          x: round1(band.x),
          y: top,
          width: round1(band.width),
          height: chartHeight
        },
          svgElement("title", {}, lightBandTitle(band, timezone, countryCode)));
      }),
      svgElement("line", { className: "chart-gridline", x1: left, y1: bottom, x2: width - right, y2: bottom }),
      svgElement("line", { className: "chart-gridline", x1: left, y1: top, x2: width - right, y2: top }),
      svgElement("text", { className: "chart-axis-label", x: 4, y: bottom + 4 }, "0 deg"),
      svgElement("text", { className: "chart-axis-label", x: 4, y: top + 4 }, ceiling + " deg"),
      timeTicks.map(function (tick) {
        return svgElement("line", {
          className: "chart-tick",
          x1: round1(tick.x),
          y1: bottom,
          x2: round1(tick.x),
          y2: bottom + 5
        });
      }),
      timeTicks.map(function (tick) {
        return svgElement("text", {
          className: "chart-time-label is-" + tick.role,
          x: round1(tick.x),
          y: tick.y,
          textAnchor: tick.anchor
        }, formatHourTick(tick.at, timezone, countryCode));
      }),
      svgElement("path", { className: "chart-path", d: line }),
      keyPathMarkers(points).map(function (point) {
        return svgElement("circle", {
          className: "chart-dot is-" + roleClass(point.role),
          cx: round1(point.x),
          cy: round1(point.y),
          r: point.role === "suggested" ? 4.5 : 3.4
        });
      })
    );
  }

  function azimuthChart(samples) {
    var points = chartSamples(samples);
    if (points.length < 2) {
      return null;
    }

    var width = 190;
    var height = 150;
    var centerX = 95;
    var centerY = 72;
    var radius = 48;
    var ringPoints = points.map(function (point) {
      var plotted = polarPoint(point.azimuthDegrees, centerX, centerY, radius);
      plotted.role = point.role;
      return plotted;
    });
    var sector = "M " + centerX + " " + centerY + " L " + ringPoints.map(function (point) {
      return round1(point.x) + " " + round1(point.y);
    }).join(" L ") + " Z";
    var sweep = ringPoints.map(function (point, index) {
      return (index === 0 ? "M " : "L ") + round1(point.x) + " " + round1(point.y);
    }).join(" ");

    return svgElement("svg", {
      className: "azimuth-chart",
      viewBox: "0 0 " + width + " " + height,
      role: "img",
      ariaLabel: "Moon azimuth sweep across cardinal directions"
    },
      svgElement("title", {}, "Moon direction sweep across the opportunity window"),
      svgElement("circle", { className: "compass-ring", cx: centerX, cy: centerY, r: radius }),
      svgElement("line", { className: "compass-axis", x1: centerX, y1: centerY - radius, x2: centerX, y2: centerY + radius }),
      svgElement("line", { className: "compass-axis", x1: centerX - radius, y1: centerY, x2: centerX + radius, y2: centerY }),
      svgElement("text", { className: "compass-label", x: centerX, y: 16, textAnchor: "middle" }, "N"),
      svgElement("text", { className: "compass-label", x: centerX + radius + 18, y: centerY + 4, textAnchor: "middle" }, "E"),
      svgElement("text", { className: "compass-label", x: centerX, y: centerY + radius + 24, textAnchor: "middle" }, "S"),
      svgElement("text", { className: "compass-label", x: centerX - radius - 18, y: centerY + 4, textAnchor: "middle" }, "W"),
      svgElement("path", { className: "compass-sector", d: sector }),
      svgElement("path", { className: "chart-path", d: sweep }),
      keyPathMarkers(ringPoints).map(function (point) {
        return svgElement("circle", {
          className: "chart-dot is-" + roleClass(point.role),
          cx: round1(point.x),
          cy: round1(point.y),
          r: point.role === "suggested" ? 4.5 : 3.4
        });
      })
    );
  }

  function moonPathSamples(path) {
    var samples = Array.isArray(path.samples) ? path.samples : [path.start, path.suggested, path.end];
    return samples.filter(hasPosition).slice().sort(function (a, b) {
      return new Date(a.at).getTime() - new Date(b.at).getTime();
      });
  }

  function keyPathMarkers(points) {
    return points.filter(function (point) {
      return point.role === "start" || point.role === "suggested" || point.role === "end";
    });
  }

  function lightBandSegments(points) {
    return points.slice(0, -1).reduce(function (bands, point, index) {
      var next = points[index + 1];
      var bucket = point.lightBucket || next.lightBucket || "unknown";
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

  function altitudeHourTicks(points, timezone) {
    var first = points[0];
    var last = points[points.length - 1];
    var span = Math.max(1, last.time - first.time);
    var chartWidth = Math.max(1, last.x - first.x);
    var minimumGap = 46;
    var cursor = firstLocalHourAtOrAfter(first.time, timezone);
    var ticks = [];

    while (cursor <= last.time) {
      var x = first.x + ((cursor - first.time) / span) * chartWidth;
      if (ticks.length === 0 || x - ticks[ticks.length - 1].x >= minimumGap) {
        ticks.push({
          at: new Date(cursor).toISOString(),
          x: x,
          anchor: "middle",
          role: "hour",
          y: 123
        });
      }
      cursor += 60 * 60 * 1000;
    }

    return ticks;
  }

  function firstLocalHourAtOrAfter(time, timezone) {
    var minuteMillis = 60 * 1000;
    var cursor = Math.ceil(time / minuteMillis) * minuteMillis;
    var searchLimit = cursor + (60 * minuteMillis);

    while (cursor <= searchLimit) {
      if (localMinuteOfHour(cursor, timezone) === 0) {
        return cursor;
      }
      cursor += minuteMillis;
    }

    return Math.ceil(time / (60 * minuteMillis)) * 60 * minuteMillis;
  }

  function localMinuteOfHour(time, timezone) {
    try {
      var parts = new Intl.DateTimeFormat("en-US", {
        minute: "numeric",
        timeZone: timezone || "UTC"
      }).formatToParts(new Date(time));
      var minute = parts.find(function (part) {
        return part.type === "minute";
      });
      return minute ? Number(minute.value) : new Date(time).getUTCMinutes();
    } catch (error) {
      return new Date(time).getUTCMinutes();
    }
  }

  function altitudeArcPath(points) {
    if (points.length === 0) {
      return "";
    }
    if (points.length === 1) {
      return "M " + round1(points[0].x) + " " + round1(points[0].y);
    }
    if (points.length === 2) {
      return linearPath(points);
    }

    var start = points[0];
    var anchor = arcAnchor(points);
    var end = points[points.length - 1];
    var circle = circleThrough(start, anchor, end);
    if (!circle) {
      return quadraticArcPath(start, anchor, end);
    }

    var startAngle = Math.atan2(start.y - circle.y, start.x - circle.x);
    var anchorAngle = Math.atan2(anchor.y - circle.y, anchor.x - circle.x);
    var endAngle = Math.atan2(end.y - circle.y, end.x - circle.x);
    var forwardTotal = positiveAngleDelta(startAngle, endAngle);
    var forwardAnchor = positiveAngleDelta(startAngle, anchorAngle);
    var forward = forwardAnchor <= forwardTotal;

    return "M " + round1(start.x) + " " + round1(start.y)
      + arcCommand(circle.radius, startAngle, anchorAngle, forward, anchor)
      + arcCommand(circle.radius, anchorAngle, endAngle, forward, end);
  }

  function linearPath(points) {
    return points.map(function (point, index) {
      return (index === 0 ? "M " : "L ") + round1(point.x) + " " + round1(point.y);
    }).join(" ");
  }

  function arcAnchor(points) {
    var start = points[0];
    var end = points[points.length - 1];
    var dx = end.x - start.x;
    var dy = end.y - start.y;
    var length = Math.sqrt(dx * dx + dy * dy);
    var fallback = points[Math.floor(points.length / 2)];
    if (length === 0) {
      return fallback;
    }

    return points.slice(1, -1).reduce(function (best, point) {
      var distance = Math.abs(dy * point.x - dx * point.y + end.x * start.y - end.y * start.x) / length;
      return distance > best.distance ? { point: point, distance: distance } : best;
    }, { point: fallback, distance: -1 }).point;
  }

  function circleThrough(first, second, third) {
    var determinant = 2 * (first.x * (second.y - third.y)
      + second.x * (third.y - first.y)
      + third.x * (first.y - second.y));
    if (Math.abs(determinant) < 0.001) {
      return null;
    }

    var firstSquared = first.x * first.x + first.y * first.y;
    var secondSquared = second.x * second.x + second.y * second.y;
    var thirdSquared = third.x * third.x + third.y * third.y;
    var x = (firstSquared * (second.y - third.y)
      + secondSquared * (third.y - first.y)
      + thirdSquared * (first.y - second.y)) / determinant;
    var y = (firstSquared * (third.x - second.x)
      + secondSquared * (first.x - third.x)
      + thirdSquared * (second.x - first.x)) / determinant;
    var radius = Math.sqrt((first.x - x) * (first.x - x) + (first.y - y) * (first.y - y));

    if (!Number.isFinite(radius) || radius > 10000) {
      return null;
    }
    return { x: x, y: y, radius: radius };
  }

  function arcCommand(radius, fromAngle, toAngle, forward, point) {
    var delta = forward ? positiveAngleDelta(fromAngle, toAngle) : positiveAngleDelta(toAngle, fromAngle);
    var largeArc = delta > Math.PI ? 1 : 0;
    var sweep = forward ? 1 : 0;
    return " A " + round1(radius) + " " + round1(radius) + " 0 "
      + largeArc + " " + sweep + " "
      + round1(point.x) + " " + round1(point.y);
  }

  function positiveAngleDelta(from, to) {
    var delta = (to - from) % (Math.PI * 2);
    return delta < 0 ? delta + Math.PI * 2 : delta;
  }

  function quadraticArcPath(start, anchor, end) {
    var t = clamp((anchor.x - start.x) / Math.max(1, end.x - start.x), 0.1, 0.9);
    var inverse = 1 - t;
    var denominator = 2 * inverse * t;
    var controlX = (anchor.x - inverse * inverse * start.x - t * t * end.x) / denominator;
    var controlY = (anchor.y - inverse * inverse * start.y - t * t * end.y) / denominator;

    return "M " + round1(start.x) + " " + round1(start.y)
      + " Q " + round1(controlX) + " " + round1(controlY)
      + " " + round1(end.x) + " " + round1(end.y);
  }

  function lightBandTitle(band, timezone, countryCode) {
    return (readableToken(band.lightBucket) || "Light bucket")
      + ", "
      + formatTime(band.startsAt, timezone, countryCode)
      + " to "
      + formatTime(band.endsAt, timezone, countryCode);
  }

  function chartSamples(samples) {
    return samples.map(function (sample) {
      return {
        at: sample.at,
        time: new Date(sample.at).getTime(),
        altitudeDegrees: sample.altitudeDegrees,
        azimuthDegrees: sample.azimuthDegrees,
        sunAltitudeDegrees: sample.sunAltitudeDegrees,
        lightBucket: sample.lightBucket,
        role: sample.role || "path"
      };
    }).filter(function (sample) {
      return Number.isFinite(sample.time)
        && Number.isFinite(sample.altitudeDegrees)
        && Number.isFinite(sample.azimuthDegrees);
    });
  }

  function hasPosition(point) {
    return point
      && point.at
      && Number.isFinite(point.altitudeDegrees)
      && Number.isFinite(point.azimuthDegrees);
  }

  function roleClass(role) {
    return String(role || "path").replace(/[^a-z0-9_-]/gi, "").toLowerCase() || "path";
  }

  function polarPoint(azimuthDegrees, centerX, centerY, radius) {
    var radians = normalizeDegrees(azimuthDegrees) * Math.PI / 180;
    return {
      x: centerX + Math.sin(radians) * radius,
      y: centerY - Math.cos(radians) * radius
    };
  }

  function opportunityActions(opportunity) {
    var links = opportunity.links || {};
    var actions = [];

    if (links.ics && links.icsReady === true) {
      actions.push(element("a", { className: "secondary-action", href: links.ics }, "Download calendar event"));
    }

    if (actions.length === 0) {
      return null;
    }
    return element("div", { className: "opportunity-actions" }, actions);
  }

  function fact(label, value) {
    return element("div", {},
      element("dt", {}, term(label)),
      element("dd", {}, value || "Unavailable"));
  }

  function term(label) {
    var description = TERM_DESCRIPTIONS[label];
    if (!description) {
      return label;
    }
    return element("span", {
      className: "tooltip",
      title: description,
      "data-tooltip": description
    }, label);
  }

  function scoreDetails(components) {
    var entries = [
      ["Moon altitude", components.moonAltitudeFit],
      ["Sun light", components.sunLightFit],
      ["Phase score", components.moonIlluminationFit],
      ["Weather", components.weatherFit],
      ["Confidence", components.forecastConfidence]
    ].filter(function (entry) {
      return Number.isFinite(entry[1]);
    });

    if (entries.length === 0) {
      return null;
    }

    return element("details", { className: "score-details" },
      element("summary", {}, "Score details"),
      element("dl", { className: "detail-grid" },
        entries.map(function (entry) {
          return fact(entry[0], String(entry[1]));
        })
      )
    );
  }

  function renderAmbiguous(payload, query) {
    var candidates = Array.isArray(payload.candidates) ? payload.candidates : [];
    replaceResults(
      element("section", { className: "status-panel action-state" },
        element("p", { className: "eyebrow" }, "Ambiguous match"),
        element("h3", {}, "Choose a location"),
        element("p", {}, "Several places matched " + query + ". Pick one to search that place."),
        element("div", { className: "candidate-list" },
          candidates.map(function (candidate) {
            var button = element("button", { type: "button" },
              element("span", { className: "candidate-name" }, candidate.displayName || candidate.id || "Unnamed location"),
              element("span", { className: "candidate-meta" }, candidateMeta(candidate))
            );
            button.addEventListener("click", function () {
              searchLocationId(candidate.id, candidate.displayName || candidate.id || query, { updateUrl: true });
            });
            return button;
          })
        )
      )
    );
  }

  function renderStatus(title, message, tone, suggestions) {
    var items = Array.isArray(suggestions) ? suggestions : [];
    replaceResults(
      element("section", { className: "status-panel " + (tone || "") },
        element("p", { className: "eyebrow" }, tone === "error" ? "Needs attention" : "Status"),
        element("h3", {}, title),
        element("p", {}, message),
        items.length > 0
          ? element("ul", { className: "suggestions" }, items.map(function (item) {
            return element("li", {}, item);
          }))
          : null)
    );
  }

  function errorsToSuggestions(payload) {
    if (!Array.isArray(payload.errors)) {
      return [];
    }
    return payload.errors.map(function (error) {
      return error.text || error.message || error.code;
    }).filter(Boolean);
  }

  function retrySuggestion(payload) {
    if (payload.retryAfterSeconds) {
      return ["Try again in about " + payload.retryAfterSeconds + " seconds."];
    }
    return [];
  }

  function messagesList(messages) {
    return element("section", { className: "message-panel" },
      element("h3", {}, "Lookup notes"),
      element("ul", { className: "messages" },
      messages.map(function (message) {
        return element("li", {}, message.text || message.code || "Additional lookup note.");
      }))
    );
  }

  function rejectedDetails(rejected, timezone, countryCode) {
    return element("details", { className: "rejected-details" },
      element("summary", {}, "Rejected windows"),
      element("ul", { className: "messages" },
        rejected.slice(0, 5).map(function (window) {
          return element("li", {},
            formatDateTime(window.startsAt, timezone, countryCode)
              + " - "
              + formatDateTime(window.endsAt, timezone, countryCode)
              + ": "
              + (window.reason || "Rejected by scoring filters."))
        })
      )
    );
  }

  function saveRecent(location, request) {
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
    renderRecent();
  }

  function renderRecent() {
    var recent = readRecent();
    clearRecent.disabled = recent.length === 0;

    if (recent.length === 0) {
      recentList.replaceChildren(element("p", { className: "recent-empty" }, "No recent searches in this browser."));
      return;
    }

    recentList.replaceChildren.apply(recentList, recent.map(function (entry) {
      var button = element("button", { type: "button" },
        element("span", { className: "candidate-name" }, entry.displayName),
        element("span", { className: "candidate-meta" }, entry.timezone || entry.slug || "")
      );
      button.addEventListener("click", function () {
        if (entry.slug) {
          searchLocationId(entry.slug, entry.displayName, { updateUrl: true });
        } else {
          search(entry.displayName, { updateUrl: true });
        }
      });
      return button;
    }));
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

  function readRecent() {
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

  function writeRecent(entries) {
    try {
      window.localStorage.setItem(RECENT_KEY, JSON.stringify(entries));
    } catch (error) {
      // Browser storage is optional for this page.
    }
  }

  document.addEventListener("click", function (event) {
    var button = event.target.closest("[data-share-url]");
    if (!button) {
      return;
    }

    var shareUrl = button.getAttribute("data-share-url");
    copyText(shareUrl).then(function () {
      var original = button.textContent;
      button.textContent = "Copied";
      window.setTimeout(function () {
        button.textContent = original;
      }, 1500);
    });
  });

  function copyText(value) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(value);
    }
    window.prompt("Copy share link", value);
    return Promise.resolve();
  }

  function replaceResults(children) {
    if (!Array.isArray(children)) {
      children = [children];
    }
    results.replaceChildren.apply(results, children.filter(Boolean));
  }

  function element(tagName, attributes) {
    var node = document.createElement(tagName);
    var children = Array.prototype.slice.call(arguments, 2);
    attributes = attributes || {};

    Object.keys(attributes).forEach(function (name) {
      var value = attributes[name];
      if (value === null || value === undefined) {
        return;
      }
      if (name === "className") {
        node.className = value;
      } else if (name === "textContent") {
        node.textContent = value;
      } else if (name === "htmlFor") {
        node.htmlFor = value;
      } else if (name === "ariaLabelledby") {
        node.setAttribute("aria-labelledby", value);
      } else if (name === "ariaLabel") {
        node.setAttribute("aria-label", value);
      } else {
        node.setAttribute(name, value);
      }
    });

    appendChildren(node, children);
    return node;
  }

  function svgElement(tagName, attributes) {
    var node = document.createElementNS(SVG_NS, tagName);
    var children = Array.prototype.slice.call(arguments, 2);
    attributes = attributes || {};

    Object.keys(attributes).forEach(function (name) {
      var value = attributes[name];
      if (value === null || value === undefined) {
        return;
      }
      if (name === "className") {
        node.setAttribute("class", value);
      } else if (name === "textContent") {
        node.textContent = value;
      } else if (name === "ariaLabel") {
        node.setAttribute("aria-label", value);
      } else if (name === "textAnchor") {
        node.setAttribute("text-anchor", value);
      } else {
        node.setAttribute(name, value);
      }
    });

    appendChildren(node, children);
    return node;
  }

  function appendChildren(node, children) {
    children.flat().forEach(function (child) {
      if (child === null || child === undefined) {
        return;
      }
      if (typeof child === "string" || typeof child === "number") {
        node.appendChild(document.createTextNode(String(child)));
      } else {
        node.appendChild(child);
      }
    });
  }

  function formatWindow(opportunity, timezone, countryCode) {
    return formatDateTime(opportunity.startsAt, timezone, countryCode)
      + " to "
      + formatTime(opportunity.endsAt, timezone, countryCode);
  }

  function formatDateTime(value, timezone, countryCode) {
    if (!value) {
      return "Unavailable";
    }
    try {
      return new Intl.DateTimeFormat(localeForCountry(countryCode), {
        dateStyle: "medium",
        timeStyle: "short",
        timeZone: timezone || "UTC"
      }).format(new Date(value));
    } catch (error) {
      return value;
    }
  }

  function formatTime(value, timezone, countryCode) {
    if (!value) {
      return "Unavailable";
    }
    try {
      return new Intl.DateTimeFormat(localeForCountry(countryCode), {
        timeStyle: "short",
        timeZone: timezone || "UTC"
      }).format(new Date(value));
    } catch (error) {
      return value;
    }
  }

  function formatHourTick(value, timezone, countryCode) {
    if (!value) {
      return "";
    }
    try {
      var options = usesTwentyFourHourClock(countryCode)
        ? {
          hour: "2-digit",
          minute: "2-digit",
          hourCycle: "h23",
          timeZone: timezone || "UTC"
        }
        : {
          hour: "numeric",
          timeZone: timezone || "UTC"
        };
      return new Intl.DateTimeFormat(localeForCountry(countryCode), options).format(new Date(value));
    } catch (error) {
      return formatTime(value, timezone, countryCode);
    }
  }

  function usesTwentyFourHourClock(countryCode) {
    try {
      var hourCycle = new Intl.DateTimeFormat(localeForCountry(countryCode), {
        hour: "numeric"
      }).resolvedOptions().hourCycle;
      return hourCycle === "h23" || hourCycle === "h24";
    } catch (error) {
      return false;
    }
  }

  function localeForCountry(countryCode) {
    var code = String(countryCode || "").trim().toUpperCase();
    if (!/^[A-Z]{2}$/.test(code)) {
      return undefined;
    }

    var language = browserLanguage();
    var regionalLocale = language + "-" + code;
    if (Intl.DateTimeFormat.supportedLocalesOf([regionalLocale]).length > 0) {
      return regionalLocale;
    }

    var regionOnlyLocale = "und-" + code;
    if (Intl.DateTimeFormat.supportedLocalesOf([regionOnlyLocale]).length > 0) {
      return regionOnlyLocale;
    }

    return undefined;
  }

  function browserLanguage() {
    var locale = navigator.languages && navigator.languages.length > 0
      ? navigator.languages[0]
      : navigator.language;
    var match = String(locale || "en").match(/^[a-z]{2,3}/i);
    return match ? match[0].toLowerCase() : "en";
  }

  function durationText(startsAt, endsAt) {
    if (!startsAt || !endsAt) {
      return "Unavailable";
    }
    var started = new Date(startsAt).getTime();
    var ended = new Date(endsAt).getTime();
    if (!Number.isFinite(started) || !Number.isFinite(ended) || ended <= started) {
      return "Unavailable";
    }
    var minutes = Math.round((ended - started) / 60000);
    if (minutes < 60) {
      return minutes + " min";
    }
    var hours = Math.floor(minutes / 60);
    var remainder = minutes % 60;
    return remainder ? hours + " hr " + remainder + " min" : hours + " hr";
  }

  function degrees(value) {
    return Number.isFinite(value) ? value.toFixed(1) + " deg" : "unavailable";
  }

  function round1(value) {
    return Math.round(value * 10) / 10;
  }

  function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  function normalizeDegrees(value) {
    var normalized = value % 360;
    return normalized < 0 ? normalized + 360 : normalized;
  }

  function percent(value) {
    return Number.isFinite(value) ? Math.round(value) + "%" : "unavailable";
  }

  function readableToken(value) {
    var text = String(value || "").replace(/_/g, " ").trim();
    return text ? text.charAt(0).toUpperCase() + text.slice(1) : "";
  }

  function cloudText(weather) {
    if (Number.isFinite(weather.cloudCoverMeanPercent) && Number.isFinite(weather.cloudCoverMaxPercent)) {
      return Math.round(weather.cloudCoverMeanPercent) + "% mean, " + Math.round(weather.cloudCoverMaxPercent) + "% max";
    }
    if (Number.isFinite(weather.cloudCoverMaxPercent)) {
      return Math.round(weather.cloudCoverMaxPercent) + "% max";
    }
    return "unavailable";
  }

  function precipitationText(weather) {
    var parts = [];
    if (Number.isFinite(weather.precipitationProbabilityMaxPercent)) {
      parts.push(Math.round(weather.precipitationProbabilityMaxPercent) + "% risk");
    }
    if (Number.isFinite(weather.precipitationMm)) {
      parts.push(weather.precipitationMm.toFixed(1) + " mm");
    }
    return parts.length > 0 ? parts.join(", ") : "unavailable";
  }

  function visibilityText(weather) {
    if (!Number.isFinite(weather.visibilityMinMeters)) {
      return "unavailable";
    }
    return (weather.visibilityMinMeters / 1000).toFixed(1) + " km";
  }

  function candidateMeta(candidate) {
    var parts = [];
    if (candidate.kind === "fictional_location") {
      parts.push("fictional");
    } else if (candidate.kind) {
      parts.push(candidate.kind.replace(/_/g, " "));
    }
    if (candidate.countryCode) {
      parts.push(candidate.countryCode);
    }
    if (candidate.timezone) {
      parts.push(candidate.timezone);
    }
    return parts.join(" | ");
  }

  function setSearchBusy(isBusy) {
    if (submitButton) {
      submitButton.disabled = isBusy;
      submitButton.textContent = isBusy ? "Finding" : "Find";
    }
  }
}());
