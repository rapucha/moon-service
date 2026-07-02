export function apiPathFor(request) {
  if (request.locationId) {
    return "/api/opportunities?locationId=" + encodeURIComponent(request.locationId);
  }
  return "/api/opportunities?q=" + encodeURIComponent(request.q);
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
  if (request.locationId) {
    return "/search?locationId=" + encodeURIComponent(request.locationId);
  }
  return "/search?q=" + encodeURIComponent(request.q);
}
