/**
 * Public exhibition submission endpoint.
 *
 * Required script properties:
 *   SUPABASE_URL
 *   SUPABASE_SERVICE_ROLE_KEY
 *   SUBMISSION_TOKEN_SECRET   (shared secret; the static site embeds an
 *                              HMAC-derived daily token, verified here)
 * Optional:
 *   SUBMISSION_SPREADSHEET_ID (falls back to active spreadsheet)
 *
 * Abuse controls (this endpoint is unauthenticated and public):
 *   - HMAC daily-rotating submission token gate (blocks trivial scripting)
 *   - per-contact + global rate limiting via CacheService
 *   - server-side image size + magic-byte validation (client caps are bypassable)
 *   - spreadsheet formula-injection escaping on every submitter value
 *   - fail-closed review gate (refuses to append without a status column)
 *   - generic error responses (no backend state leaked to the public)
 */

var SUBMISSION_REQUIRED_FIELDS = [
  'name_ko',
  'venue_name_ko',
  'opening_date',
  'closing_date',
  'address_ko',
  'hours',
  'contact',
];

var SUBMISSION_OPTIONAL_FIELDS = [
  'name_en',
  'venue_name_en',
  'address_en',
  'description_ko',
  'description_en',
  'reception_date',
  // Reception end time (Fix 3). Captured for admin review in the sheet; there
  // is no Supabase column for it yet, so SyncExhibitions.gs intentionally does
  // not list it in KNOWN_COLUMNS.
  'reception_end',
];

// Server-side cap on each decoded image. The client caps at 10MB but a
// non-browser POST bypasses it; Apps Script must enforce its own ceiling.
var SUBMISSION_MAX_IMAGE_BYTES = 8 * 1024 * 1024;
var SUBMISSION_MAX_IMAGES = 5;

// Rate limits (CacheService, best-effort — Apps Script has no client IP).
var RATE_LIMIT_PER_CONTACT = 3;        // submissions per contact per window
var RATE_LIMIT_GLOBAL = 40;            // submissions across all contacts per window
var RATE_LIMIT_WINDOW_SECONDS = 3600;  // 1 hour

function doPost(e) {
  try {
    var payload = JSON.parse(e && e.postData && e.postData.contents ? e.postData.contents : '{}');

    if (!isValidSubmissionToken(payload && payload.token)) {
      // Generic message: do not reveal the token scheme to probers.
      return responseJson({ success: false, error: 'submission rejected' });
    }

    var validation = validateFormPayload(payload);
    if (!validation.valid) {
      return responseJson({ success: false, error: validation.error });
    }

    var contact = String((payload.fields || {}).contact || '').trim().toLowerCase();
    if (!withinRateLimit(contact)) {
      return responseJson({ success: false, error: 'rate limited, please try again later' });
    }

    var props = PropertiesService.getScriptProperties();
    var supabaseUrl = props.getProperty('SUPABASE_URL');
    var serviceKey = props.getProperty('SUPABASE_SERVICE_ROLE_KEY');
    if (!supabaseUrl || !serviceKey) {
      logError('server not configured: missing SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY');
      return responseJson({ success: false, error: 'submission failed' });
    }

    var sheet = getSubmissionSheet(props);
    var headers = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0]
      .map(function(h) { return String(h || '').trim(); });

    // Fail closed: never append a public submission to a sheet that lacks a
    // status column, or it would sync straight to the live app unreviewed.
    if (headers.indexOf('status') === -1) {
      logError('submission sheet missing required "status" column; refusing to append');
      return responseJson({ success: false, error: 'submission failed' });
    }

    var imageUrls = uploadSubmissionImages(payload.images || [], supabaseUrl, serviceKey);
    var row = buildSubmissionRow(headers, payload.fields || {}, imageUrls);
    sheet.appendRow(row);

    sendConfirmationEmail(payload.fields || {});
    return responseJson({ success: true });
  } catch (error) {
    // Never echo internal exception text across the public trust boundary.
    logError('doPost error: ' + (error && (error.stack || error.message) || String(error)));
    return responseJson({ success: false, error: 'submission failed' });
  }
}

function logError(message) {
  if (typeof Logger !== 'undefined' && Logger.log) {
    Logger.log(message);
  } else if (typeof console !== 'undefined' && console.error) {
    console.error(message);
  }
}

/**
 * Token = base64( HMAC_SHA256(secret, "gallr-submit:" + UTC-yyyy-mm-dd) ).
 * The static site computes the same value at build/deploy time. Accept the
 * current and previous UTC day so a submission spanning midnight still works.
 */
function isValidSubmissionToken(token) {
  if (!token) return false;
  var secret = PropertiesService.getScriptProperties().getProperty('SUBMISSION_TOKEN_SECRET');
  if (!secret) {
    // Misconfiguration must fail closed, not open.
    logError('SUBMISSION_TOKEN_SECRET not set; rejecting all submissions');
    return false;
  }
  var candidates = [submissionTokenForOffset(secret, 0), submissionTokenForOffset(secret, -1)];
  for (var i = 0; i < candidates.length; i++) {
    if (constantTimeEquals(String(token), candidates[i])) return true;
  }
  return false;
}

function submissionTokenForOffset(secret, dayOffset) {
  var d = new Date();
  d.setUTCDate(d.getUTCDate() + dayOffset);
  var dayKey = d.toISOString().slice(0, 10);
  var raw = Utilities.computeHmacSha256Signature('gallr-submit:' + dayKey, secret);
  return Utilities.base64Encode(raw);
}

function constantTimeEquals(a, b) {
  if (a.length !== b.length) return false;
  var diff = 0;
  for (var i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

/**
 * Best-effort rate limiting. Apps Script web apps do not expose the client
 * IP, so we throttle per submitter-contact plus a global ceiling to bound
 * email/storage/sheet abuse even when contacts are rotated.
 */
function withinRateLimit(contact) {
  if (typeof CacheService === 'undefined') return true; // test/no-cache context
  var cache = CacheService.getScriptCache();
  if (!cache) return true;

  var globalCount = Number(cache.get('rl_global') || 0);
  if (globalCount >= RATE_LIMIT_GLOBAL) return false;

  var allowed = true;
  if (contact) {
    var key = 'rl_' + Utilities.base64EncodeWebSafe(
      Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, contact)
    );
    var contactCount = Number(cache.get(key) || 0);
    if (contactCount >= RATE_LIMIT_PER_CONTACT) {
      allowed = false;
    } else {
      cache.put(key, String(contactCount + 1), RATE_LIMIT_WINDOW_SECONDS);
    }
  }
  if (allowed) {
    cache.put('rl_global', String(globalCount + 1), RATE_LIMIT_WINDOW_SECONDS);
  }
  return allowed;
}

function validateFormPayload(payload) {
  if (!payload || typeof payload !== 'object') {
    return { valid: false, error: 'invalid payload' };
  }
  var fields = payload.fields || {};
  for (var i = 0; i < SUBMISSION_REQUIRED_FIELDS.length; i++) {
    var field = SUBMISSION_REQUIRED_FIELDS[i];
    if (!String(fields[field] || '').trim()) {
      return { valid: false, error: field + ' required' };
    }
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(fields.contact || '').trim())) {
    return { valid: false, error: 'contact invalid' };
  }
  if (String(fields.closing_date) < String(fields.opening_date)) {
    return { valid: false, error: 'closing_date before opening_date' };
  }
  var images = payload.images || [];
  if (!Array.isArray(images) || images.length === 0) {
    return { valid: false, error: 'images required' };
  }
  if (images.length > SUBMISSION_MAX_IMAGES) {
    return { valid: false, error: 'too many images' };
  }
  for (var j = 0; j < images.length; j++) {
    var image = images[j] || {};
    if (['image/jpeg', 'image/png'].indexOf(image.contentType) === -1 || !image.base64) {
      return { valid: false, error: 'invalid image' };
    }
    var sizeError = imageBytesError(image);
    if (sizeError) {
      return { valid: false, error: sizeError };
    }
  }
  return { valid: true, error: null };
}

/**
 * Server-side image validation: enforce the byte cap on the *decoded* size
 * and verify the real magic bytes match the declared content type. The
 * client-declared contentType string is attacker-controlled and spoofable.
 * Returns an error string, or null if the image is acceptable.
 */
function imageBytesError(image) {
  // base64 length * 3/4 ≈ decoded bytes; reject before the (costly) decode.
  var approxBytes = Math.floor(String(image.base64).length * 0.75);
  if (approxBytes > SUBMISSION_MAX_IMAGE_BYTES) {
    return 'image too large';
  }
  var bytes;
  try {
    bytes = Utilities.base64Decode(image.base64);
  } catch (err) {
    return 'invalid image';
  }
  if (!bytes || bytes.length === 0 || bytes.length > SUBMISSION_MAX_IMAGE_BYTES) {
    return 'image too large';
  }
  if (!magicBytesMatch(bytes, image.contentType)) {
    return 'invalid image';
  }
  return null;
}

function magicBytesMatch(bytes, contentType) {
  // Apps Script base64Decode yields signed byte values; mask to 0-255.
  var b0 = bytes[0] & 0xff, b1 = bytes[1] & 0xff, b2 = bytes[2] & 0xff, b3 = bytes[3] & 0xff;
  if (contentType === 'image/jpeg') {
    return b0 === 0xff && b1 === 0xd8 && b2 === 0xff;
  }
  if (contentType === 'image/png') {
    return b0 === 0x89 && b1 === 0x50 && b2 === 0x4e && b3 === 0x47;
  }
  return false;
}

/**
 * Neutralize spreadsheet/CSV formula injection. A submitter value beginning
 * with = + - @ (or a leading tab/CR that Sheets also treats as a formula
 * lead-in) executes when an admin opens the review sheet. Prefix with a
 * single quote so the cell is stored as literal text.
 */
function escapeSheetValue(value) {
  var s = String(value == null ? '' : value);
  if (s.length > 0 && '=+-@\t\r'.indexOf(s.charAt(0)) !== -1) {
    return "'" + s;
  }
  return s;
}

function buildSubmissionRow(headers, fields, imageUrls) {
  return headers.map(function(header) {
    if (header === 'status') return 'pending';
    if (header === 'contact') return ''; // submitter email is private; do not publish it.
    var match = /^image_url_([1-5])$/.exec(header);
    if (match) return imageUrls[Number(match[1]) - 1] || ''; // server-generated, safe
    if (SUBMISSION_REQUIRED_FIELDS.indexOf(header) !== -1 && header !== 'contact') {
      return escapeSheetValue(String(fields[header] || '').trim());
    }
    if (SUBMISSION_OPTIONAL_FIELDS.indexOf(header) !== -1) {
      return escapeSheetValue(String(fields[header] || '').trim());
    }
    return '';
  });
}

function uploadSubmissionImages(images, supabaseUrl, serviceKey) {
  return images.slice(0, SUBMISSION_MAX_IMAGES).map(function(image, index) {
    var extension = image.contentType === 'image/png' ? '.png' : '.jpg';
    var objectPath = new Date().toISOString().slice(0, 10) + '/' +
      Utilities.getUuid() + '-' + (index + 1) + extension;
    var bytes = Utilities.base64Decode(image.base64);
    var url = supabaseUrl + '/storage/v1/object/submissions/' + objectPath;
    var response = UrlFetchApp.fetch(url, {
      method: 'post',
      contentType: image.contentType,
      payload: bytes,
      headers: {
        apikey: serviceKey,
        Authorization: 'Bearer ' + serviceKey,
        'x-upsert': 'true',
        // Force download semantics so a spoofed payload can never render
        // inline from the bucket origin.
        'content-disposition': 'attachment',
      },
      muteHttpExceptions: true,
    });
    var code = response.getResponseCode();
    if (code < 200 || code >= 300) {
      throw new Error('image upload failed with HTTP ' + code);
    }
    return supabaseUrl + '/storage/v1/object/public/submissions/' + objectPath;
  });
}

function getSubmissionSheet(props) {
  var sheetId = props.getProperty('SUBMISSION_SPREADSHEET_ID');
  var spreadsheet = sheetId
    ? SpreadsheetApp.openById(sheetId)
    : SpreadsheetApp.getActiveSpreadsheet();
  return spreadsheet.getSheets()[0];
}

function sendConfirmationEmail(fields) {
  var contact = String(fields.contact || '').trim();
  if (!contact) return;
  var name = String(fields.name_ko || '').trim();
  var body = [
    '전시 제출이 접수되었습니다.',
    '',
    '전시 제목: ' + name,
    '갤러리 / 기관명: ' + String(fields.venue_name_ko || '').trim(),
    '기간: ' + String(fields.opening_date || '').trim() + ' - ' + String(fields.closing_date || '').trim(),
    '',
    'gallr 팀이 검토한 뒤 필요한 경우 연락드리겠습니다.',
  ].join('\n');
  // Subject/body are plain-text only (MailApp.sendEmail with no htmlBody),
  // and the recipient is rate-limited + token-gated above.
  MailApp.sendEmail(contact, '[gallr] 전시 제출이 접수되었습니다 — ' + name, body);
}

function responseJson(payload) {
  var content = JSON.stringify(payload);
  if (typeof ContentService === 'undefined') {
    return { content: content, mimeType: 'application/json' };
  }
  return ContentService
    .createTextOutput(content)
    .setMimeType(ContentService.MimeType.JSON);
}

if (typeof module !== 'undefined') {
  module.exports = {
    validateFormPayload: validateFormPayload,
    buildSubmissionRow: buildSubmissionRow,
    escapeSheetValue: escapeSheetValue,
    imageBytesError: imageBytesError,
    magicBytesMatch: magicBytesMatch,
    responseJson: responseJson,
  };
}
