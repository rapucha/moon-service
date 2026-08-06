export function apiPathFor(request) {
  return orderedPath(lookupPath("/api/opportunities", request), request.order);
}

export function preferenceApiPathFor(request) {
  return orderedPath("/api/opportunities", request.order);
}

export function fallbackPayload(statusCode) {
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

export function sharePathFor(request) {
  var order = request.order === "soonest" ? request.order : undefined;
  return orderedPath(lookupPath("/search", request), order);
}

export function searchPathFor(request) {
  return orderedPath(lookupPath("/search", request), request.order);
}

function lookupPath(path, request) {
  if (request.locationId) {
    return path + "?locationId=" + encodeURIComponent(request.locationId);
  }
  return path + "?q=" + encodeURIComponent(request.q);
}

function orderedPath(path, order) {
  var separator = path.includes("?") ? "&" : "?";
  return order === undefined ? path : path + separator + "order=" + encodeURIComponent(order);
}
