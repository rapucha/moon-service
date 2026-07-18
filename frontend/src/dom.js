var SVG_NS = "http://www.w3.org/2000/svg";

export function element(tagName, attributes) {
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

export function svgElement(tagName, attributes) {
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
