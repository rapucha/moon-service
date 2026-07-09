// 64x32 luminance map derived from NASA SVS's 2025 LRO CGI Moon Kit color map.
// Source and transformation details live in assets/moon-textures/README.md.
var MOON_TEXTURE_WIDTH = 64;
var MOON_TEXTURE_HEIGHT = 32;
var MOON_TEXTURE_BASE64 =
  "zdDQz9LR0NPT1NPQz9DT1tfX19fW1dbX1NTU1NTS09bRzc3OztLV1dXV1dXV1dbV0tHR09PS0c/Oz9DQ0tDOzsLDyNDT19rW0svI" +
  "w8LCwMDH19HT0NDQzM/Rz8/OysXEyMvP09bU0MzMycfHysfIzMzPz9XW0c/Qz83Rz9DIxMTMycjPzszLysO+wsTGxsO+v8bHxMjO" +
  "0NbX1tjV09ba2Nra083Kx8PDxMbExcXFx8fKz8/Nzs/T1tvVys3NzcvNzM3Q0cvLysrEwcPFxcrO0cvGxsrX4NXOycfJy9LW3ejj" +
  "0cS9vbm2vsXJzdbb4d7Z3N3e3NPSzM3QycbKzM7LyszQyszFxMO+vL68u77Gyc/Rzs7P1dbTysG6t7q1srfBwbu9vLi/vcPM19vd" +
  "4+vu6+vn5eLX0c3MzM3Nz8nCyM7Bw8HCxdLNv72+vri8v77Iwb69wsfFwLqwqaqjnZ6knKaurK2tsrjK2tPNwsvAvtLf5OLd087J" +
  "vr/EwsDAwMHFwsfEwL7Cw8LEwbu+t7a2u762q5epr5+cpa21rqiup7SspKSjnpmlvsbDwMW+trzH097e1tHJvr7CuLq9wb+7uNDT" +
  "xcPJvry/xb2+xb+9uLm9rpqPkpSQmrS1oJ2Ql5eouaussqiotLu/tLPAvL3JyMzZ2NjY0dPNxL21u8K7x8nR1snExcjSzMLHvr7N" +
  "ybi1rqSUhYaLiZWqnYOAg4yOlau1uK+iq6qyurSzs8HFxMTI0NTSzc3Rw8THx8C/vcPG2NnPx8nQ283Av7q1urW6uLOjiIOGh4WK" +
  "lIuFgYKJipqgrZmcl5yduLa7xLS2vsbFxdvk1dHLycPFxMvHyMzCx87Iys/N08/JzMTFu7uzs7ewlIOKgoGGi4mKiYSDiIqYoZSD" +
  "iImitcDDv8C0vsDFzMW8xc7W1sTBy8qmvsXMzdPWztDb0djOzsrAwL+3t7nDt5OTj4B+iKCQh4eIhomSnaKJgYl/l8K4trWstLnE" +
  "zsm4tLjIys/Fwcu1kLzDv8vV1dPa1MzIyMrEw87PycC9w8OwkoOCeIONjoaNkIyNiqGjhoWIfpOcs7qXiYanvre7vrW2wcnGxcfN" +
  "yMK/xMzV08/U09HBwcPGxM7OztPFt7+9uaCGfXl4fYqPkqipopWOhImWhHh9epvGln6QsrGYk6W2tLzT0MzIwr/Bw9PX3eHT1tPJ" +
  "yMfKzcrDxcnJw8K+uMPCqYh+fXuPppKorpqFk5qRonp4d3WIpbKpqaW6r56lsb670tjOysPBwcHM19jX0MbI1c7FztfUyMHB0NLQ" +
  "wr69vsOdeH19g5OPm5uJhZOrwLSKeoaLj4mJop2jsa6PornFxM3l0MrGysXFydDO0MrCzM3SzczT1cy/usbLysnEtrOwon96fXt/" +
  "jI6Ymaipr726vZSespGBf4qgqa6cjaq2u7y+49vJysfDyM+8xMvKzNvQzM3IxMHBv7nK1NbRxLy8qbWZfoF/gJKJkZehs7K8vsut" +
  "pranh4ystq2zsKu8t7K+s87QwL27vr/LzMTNwtDWz8TKyMS/t7q/z9rSy722ube0sZmTn5CCkY6Pk66xuLvD0KOYt6iboqytsrvD" +
  "w7W0ubG6wr+6ury7xcfIw7e3vcK5ur+5ub7AxcnRrp3Dwru9xLmnq5aElJyRhJm3tLW5uMixnq2oorOmqrm7vb/Fua29yLa7vrrA" +
  "urS5w7Ssqq+yrau6wb7Bv73G1MG20cm8wNfGva6HgJeWjYKYvb/AvrjHw7q3wcDDvbaxvbe2uairtLe6wsG9wLy0uLGvop2iqqSn" +
  "ucbKvLS4vse8x8vEure6t8G+oKGcn7Stx9TPxr3EwLu9vsnd08i+tL6zqK2nsLa7uLe7va66s6KoqZ+dnKWcoay8wry5t7S2vMC/" +
  "vrm6t7uysaizrLTH0t3d0MnFwL27vr3D0Mm8ubqonJyhpbnFubWysa+murCdqKCsqJ+gk6CksLi0t7i0s7S6v72+usCzqbKyvr2+" +
  "y9bX19TMw8TBvsTKybeytbSnpKCWoqKsvrW3trPIwrinoqunsaKenZecoaSytbW6urm5ur66wMC3t7KprLm+vsvX2NjVzMC8vcTG" +
  "zr+2u7mtnqShpaeurLG3uLO3urCvq6mjo6amoZyYnaGhqK26u7zBvba+vL/Atbu9qbC7u8bM1dTS0MrDxcXCx8G9ur2zqpelpbGv" +
  "r7e2wbWyubmxsrGqo6erp6monaGkrauqtbe8w8C/wcHIysTEy8bHwcXLz9rVy8nMysnEwcXBxMHDwca7tKqsq6m5wdXKt7m+u7Gi" +
  "oqywsKisrKWmqaelq7O4ur7CxcPDyM3Oz8rLy8THzNTe2szLx8zFxMTDyM3Qz8PFw7KrucC8vb7E0cO8vK+vs6qpq6eipqCjq66r" +
  "r7Kusbi2uMDAw8bHy9XPxcLAu7/Kz83KxcXOy83KzMvLycS/vbi2sLKrsbe4uL7AvL61t8C8qqm1uLehp6+xsrewrq60urvBwcHE" +
  "wb/AwsLBv73DzdHKytDW1NbZ1s/Lx8G/w8XGysO/tby6uL3Au7Oztrm5xb61vLi6srWztbe3ubu5usHBvMDBwcC/v7+8vb3BxMjB" +
  "xMbJzdDQysfDvr6/wcfIydLQysi+vb7Bv8PDu7i+wMfCvtPQzs7Pz83Jy8zLy8zLzczNy8zOzczNzs7Pz9DN0NDO0c3KysnLzc/R" +
  "0tLU19ve3Nvb19XV0c7Pzs3OztHU1tU=";

var MOON_TEXTURE = decodeTexture(MOON_TEXTURE_BASE64);

export function moonSurfaceAlbedo(dx, dy, z) {
  if (![dx, dy, z].every(Number.isFinite)) {
    return 0.5;
  }

  var longitude = Math.atan2(dx, z);
  var latitude = Math.asin(clamp(-dy, -1, 1));
  var textureX = ((longitude / (Math.PI * 2)) + 0.5) * MOON_TEXTURE_WIDTH;
  var textureY = (0.5 - latitude / Math.PI) * (MOON_TEXTURE_HEIGHT - 1);
  var left = Math.floor(textureX) % MOON_TEXTURE_WIDTH;
  var right = (left + 1) % MOON_TEXTURE_WIDTH;
  var top = Math.floor(textureY);
  var bottom = Math.min(MOON_TEXTURE_HEIGHT - 1, top + 1);
  var horizontalRatio = textureX - Math.floor(textureX);
  var verticalRatio = textureY - top;
  var topValue = mix(textureValue(left, top), textureValue(right, top), horizontalRatio);
  var bottomValue = mix(textureValue(left, bottom), textureValue(right, bottom), horizontalRatio);
  return mix(topValue, bottomValue, verticalRatio) / 255;
}

function decodeTexture(encoded) {
  var binary = atob(encoded);
  var values = new Uint8Array(binary.length);
  for (var index = 0; index < binary.length; index += 1) {
    values[index] = binary.charCodeAt(index);
  }
  return values;
}

function textureValue(x, y) {
  return MOON_TEXTURE[y * MOON_TEXTURE_WIDTH + x];
}

function mix(start, end, ratio) {
  return start + (end - start) * ratio;
}

function clamp(value, minimum, maximum) {
  return Math.min(maximum, Math.max(minimum, value));
}
