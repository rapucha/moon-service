# Document and Attachment Inspection

Read this reference whenever an in-scope Git blob, PR link, or attachment is
not ordinary plain text. Detect the real format with `file` and magic bytes;
extensions and MIME labels are hints only.

## Safety and Bounds

Work in a private temporary directory and never open content in an application
that can execute scripts, macros, links, media, or embedded objects. Do not
mount disk images, launch browsers for active content, follow symlinks, restore
permissions, or extract device files.

Unless a stricter repository policy applies, bound one review to:

- 50 MiB per input object;
- 200 MiB cumulative decoded or extracted content;
- 1,000 archive members;
- three embedded/archive levels; and
- 200 rendered/OCR pages per document.

Record the applied bounds. Exceeding one makes the affected required surface
`review_required`; sampling does not count as complete coverage. Validate every
member path before extraction, reject absolute paths and `..` traversal, and
stream or extract only to the private directory. Inventory active content but
never execute it.

All extraction, metadata, string, OCR, and raster content must stay inside a
local redacting inspection process. Never print raw extracted content or send
an unredacted image to a tool log, model, or image viewer. Only a derivative in
which candidate regions are already redacted may leave that process.

## PDF

Inspect all of the following:

1. Signature, encryption state, page count, document information, XMP metadata,
   form/annotation inventory, and tool parse errors.
2. Extracted text from every page, preserving page attribution.
3. Font and image inventory to identify pages where text extraction is empty or
   clearly incomplete.
4. Render image-only, suspiciously sparse, or visually encoded pages into the
   private directory and inspect them with local static/OCR analysis. Create a
   redacted derivative before any image-view step.
5. Embedded-file names, metadata, and content through this same reference.
6. Static indicators of JavaScript, launch actions, submitted forms, rich media,
   or other active content; never trigger them.

Use installed Poppler tools such as `pdfinfo`, `pdftotext`, `pdffonts`,
`pdfimages`, `pdfdetach`, and `pdftoppm` when available. Use installed OCR or a
local static image-analysis capability for rendered pages. If coverage would
require exposing an unredacted raster, or if encryption, parsing, missing
OCR/rendering, unsafe attachment extraction, or a page limit prevents required
coverage, return `review_required` and name the gap.

## Office and OpenDocument Containers

Treat OOXML and ODF files as ZIP containers after validating the signature.
List members before any selective extraction. Inspect:

- visible document, sheet, and slide text;
- headers, footers, comments, notes, tracked changes, speaker notes, shared
  strings, and formulas that contain literals;
- core, custom, application, and user-defined properties;
- relationships, external links, hyperlinks, and embedded object names/content;
- hidden sheets, rows, columns, slides, text boxes, and other hidden content
  exposed by static structure; and
- macro, script, ActiveX, OLE, template, and other active-content inventories.

Inspect XML and relationship files as text through the redacting path. For
binary macro or embedded objects, inventory them and apply bounded `strings`
inspection or recurse by detected format. Never load the document in Office,
LibreOffice, or another macro-capable renderer. Unsupported compound objects,
password protection, malformed packages, or incomplete hidden/embedded-content
coverage require `review_required`.

## Images

Inspect format, dimensions, frame/page count, EXIF, XMP, IPTC, comments,
profiles, thumbnails, GPS/location metadata, creator/device fields, and other
available metadata. OCR visible text when appropriate; inspect all frames or
pages within bounds inside the local redacting pipeline. Only redacted
derivatives may be viewed externally. Metadata tools such as `exiftool` are
optional, but a missing capability needed for the actual format means the
review cannot be `clear`.

## Archives and Generic Binaries

For ZIP, TAR, 7z, gzip, and similar containers, use a listing mode first.
Inspect member names and metadata, then recursively inspect safe regular-file
members within the limits. Do not extract symlinks, hard links, sockets, device
nodes, absolute paths, traversal paths, encrypted members, or members whose
declared and actual sizes cannot be bounded. Any required skipped member yields
`review_required`.

For generic binaries, inspect magic/type, size, filenames, available metadata,
and bounded printable strings without executing or dynamically loading the
file. Inventory signatures or names that indicate scripts, macros, credentials,
debug symbols, source maps, embedded archives, or active content. Recurse into
recognized embedded content when safe. If the format is unsupported or static
inspection cannot establish the required coverage, return `review_required`.

## Coverage Record

For each document, record only safe descriptors:

- sanitized location and detected format;
- size, page/member counts, and recursion depth;
- tools and inspection methods used;
- metadata/text/render/OCR/embedded-content coverage; and
- any encrypted, malformed, unsupported, oversized, or inaccessible portion.

Do not claim a clean document merely because text extraction returned no
matches. A `clear` verdict requires every applicable layer above to have been
inspected.
