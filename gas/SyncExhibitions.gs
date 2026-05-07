/**
 * SyncExhibitions.gs
 * Google Apps Script — Sync Google Sheet → Supabase exhibitions table
 *
 * HEADER-DRIVEN: Reads column headers from row 1 and maps data by header name,
 * not by column position. New columns are automatically picked up without
 * script changes — only a matching Supabase column needs to exist.
 *
 * SETUP REQUIRED (one-time):
 * 1. Open Apps Script → Project Settings → Script Properties
 *    Add the following properties:
 *      SUPABASE_URL        = https://<project-ref>.supabase.co
 *      SUPABASE_SERVICE_ROLE_KEY = <your-service-role-key>  ← NEVER share this
 *
 * COVER IMAGE CONVENTION:
 *   The `cover_image_url` column accepts either:
 *     a) A full HTTPS URL (e.g., https://example.com/image.jpg) — used as-is
 *     b) A filename only (e.g., my-exhibition.jpg) — resolved to:
 *        {SUPABASE_URL}/storage/v1/object/public/exhibition-images/{filename}
 *   Upload images to the "exhibition-images" bucket in Supabase Storage dashboard.
 *
 * 2. Install triggers (Triggers menu in Apps Script editor):
 *    a) onEdit trigger:  Function = syncToSupabase, Event source = From spreadsheet,
 *                        Event type = On edit
 *    b) Time-driven trigger: Function = syncToSupabase, Time-based = Minutes timer,
 *                            Every 5 minutes
 *
 * GOOGLE SHEET COLUMN ORDER (Row 1 = header, data from Row 2):
 *   Headers must be lowercase snake_case matching Supabase column names.
 *   Bilingual text fields use _ko/_en suffix pairs (e.g., name_ko, name_en).
 *   Column order does not matter — headers drive mapping.
 */

// ---------------------------------------------------------------------------
// Required headers — sync aborts if any are missing from row 1
// ---------------------------------------------------------------------------

var REQUIRED_HEADERS = [
  'name_ko', 'venue_name_ko', 'city_ko', 'region_ko',
  'opening_date', 'closing_date',
];

// ---------------------------------------------------------------------------
// Required per-row values — row is skipped if any are empty
// ---------------------------------------------------------------------------

var REQUIRED_ROW_FIELDS = [
  'name_ko', 'venue_name_ko', 'city_ko', 'region_ko',
  'opening_date', 'closing_date',
];

// ---------------------------------------------------------------------------
// Event id validation — fetches all event ids once per sync run.
// Returns a Set-like object: knownIds[id] === true when the id exists.
// ---------------------------------------------------------------------------

function fetchKnownEventIds(supabaseUrl, serviceKey) {
  var url = supabaseUrl + '/rest/v1/events?select=id';
  var response = UrlFetchApp.fetch(url, {
    method: 'get',
    headers: {
      'apikey': serviceKey,
      'Authorization': 'Bearer ' + serviceKey,
    },
    muteHttpExceptions: true,
  });
  var code = response.getResponseCode();
  if (code !== 200) {
    Logger.log('WARN: events fetch returned ' + code + ' — event_id validation disabled this run');
    return null; // null signals "validation disabled" so we don't accidentally skip every row
  }
  var rows = JSON.parse(response.getContentText());
  var set = {};
  rows.forEach(function(r) { if (r && r.id) set[r.id] = true; });
  return set;
}

// ---------------------------------------------------------------------------
// Main entry point — called by both triggers
// ---------------------------------------------------------------------------

function syncToSupabase() {
  var props = PropertiesService.getScriptProperties();
  var supabaseUrl = props.getProperty('SUPABASE_URL');
  var serviceKey = props.getProperty('SUPABASE_SERVICE_ROLE_KEY');
  var timestamp = new Date().toISOString();

  if (!supabaseUrl || !serviceKey) {
    Logger.log(JSON.stringify({
      timestamp: timestamp,
      status: 'FAILURE',
      error: 'SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY not set in Script Properties',
      rows_read: 0,
      rows_inserted: 0,
      rows_skipped: 0,
    }));
    return;
  }

  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheets()[0];
  var data = sheet.getDataRange().getValues();

  if (data.length === 0) {
    Logger.log(JSON.stringify({
      timestamp: timestamp,
      status: 'FAILURE',
      error: 'Sheet is empty — no header row found',
      rows_read: 0,
      rows_inserted: 0,
      rows_skipped: 0,
    }));
    return;
  }

  // ── Build header map ────────────────────────────────────────────────────
  var headerRow = data[0];
  var headerMap = buildHeaderMap(headerRow);
  var unknownHeaders = [];

  // Log headers that were found
  Logger.log('Headers found: ' + Object.keys(headerMap).join(', '));

  // ── Validate required headers ───────────────────────────────────────────
  var missingHeaders = [];
  REQUIRED_HEADERS.forEach(function(h) {
    if (!(h in headerMap)) missingHeaders.push(h);
  });

  if (missingHeaders.length > 0) {
    Logger.log(JSON.stringify({
      timestamp: timestamp,
      status: 'FAILURE',
      error: 'Missing required headers: ' + missingHeaders.join(', '),
      rows_read: 0,
      rows_inserted: 0,
      rows_skipped: 0,
    }));
    return;
  }

  // ── Fetch known event ids for FK validation ──────────────────────────
  var knownEventIds = fetchKnownEventIds(supabaseUrl, serviceKey);

  // ── Process data rows ───────────────────────────────────────────────────
  var dataRows = data.slice(1);
  var rowsRead = dataRows.length;
  var validRows = [];
  var skippedReasons = [];

  dataRows.forEach(function(row, index) {
    var rowNum = index + 2;
    var result = validateRow(row, rowNum, headerMap);
    if (!result.valid) {
      skippedReasons.push(result.reason);
      return;
    }
    // Validate event_id FK if a value is present and validation is enabled
    var eventIdCell = String(getCell(row, headerMap, 'event_id') || '').trim();
    if (eventIdCell && knownEventIds !== null && !knownEventIds[eventIdCell]) {
      skippedReasons.push('Row ' + rowNum + ': event_id "' + eventIdCell + '" not found in events table — sync events first');
      return;
    }
    validRows.push(buildRecord(row, headerMap));
  });

  // ── Deduplicate by id ───────────────────────────────────────────────────
  var seenIds = {};
  var uniqueRows = [];
  validRows.forEach(function(row) {
    if (!seenIds[row.id]) {
      seenIds[row.id] = true;
      uniqueRows.push(row);
    } else {
      skippedReasons.push('Duplicate id ' + row.id + ': ' + row.name_ko);
    }
  });

  // ── Safety check: never delete if there are zero valid rows ────────────
  if (uniqueRows.length === 0) {
    Logger.log(JSON.stringify({
      timestamp: timestamp,
      status: 'SKIPPED',
      error: 'No valid rows to insert — DELETE skipped to protect existing data',
      rows_read: rowsRead,
      rows_inserted: 0,
      rows_skipped: skippedReasons.length,
      skipped_details: skippedReasons,
    }));
    return;
  }

  try {
    deleteAllExhibitions(supabaseUrl, serviceKey);
    insertExhibitions(uniqueRows, supabaseUrl, serviceKey);

    Logger.log(JSON.stringify({
      timestamp: timestamp,
      status: 'SUCCESS',
      rows_read: rowsRead,
      rows_inserted: uniqueRows.length,
      rows_skipped: skippedReasons.length,
      skipped_details: skippedReasons,
    }));
  } catch (e) {
    Logger.log(JSON.stringify({
      timestamp: timestamp,
      status: 'FAILURE',
      error: e.message,
      rows_read: rowsRead,
      rows_inserted: 0,
      rows_skipped: skippedReasons.length,
    }));
  }
}

// ---------------------------------------------------------------------------
// Header map construction
// ---------------------------------------------------------------------------

/**
 * Builds a map of normalized header name → column index.
 * Headers are lowercased and trimmed.
 */
function buildHeaderMap(headerRow) {
  var map = {};
  headerRow.forEach(function(cell, index) {
    var header = String(cell || '').toLowerCase().trim();
    if (header) {
      map[header] = index;
    }
  });
  return map;
}

/**
 * Gets a cell value by header name from a row.
 * Returns empty string if header not found.
 */
function getCell(row, headerMap, headerName) {
  if (!(headerName in headerMap)) return '';
  return row[headerMap[headerName]];
}

// ---------------------------------------------------------------------------
// Row validation
// ---------------------------------------------------------------------------

function validateRow(row, rowNum, headerMap) {
  // Check required fields are non-empty
  for (var i = 0; i < REQUIRED_ROW_FIELDS.length; i++) {
    var field = REQUIRED_ROW_FIELDS[i];
    var value = String(getCell(row, headerMap, field) || '').trim();
    if (!value) {
      return { valid: false, reason: 'Row ' + rowNum + ': ' + field + ' is empty' };
    }
  }

  // Validate dates
  var openingDate = parseDate(getCell(row, headerMap, 'opening_date'));
  if (!openingDate) {
    return { valid: false, reason: 'Row ' + rowNum + ': opening_date is not a valid date: ' + getCell(row, headerMap, 'opening_date') };
  }

  var closingDate = parseDate(getCell(row, headerMap, 'closing_date'));
  if (!closingDate) {
    return { valid: false, reason: 'Row ' + rowNum + ': closing_date is not a valid date: ' + getCell(row, headerMap, 'closing_date') };
  }

  // Validate coordinates if present
  var lat = getCell(row, headerMap, 'latitude');
  var lon = getCell(row, headerMap, 'longitude');
  if (lat !== '' && lat !== null && lat !== undefined && isNaN(Number(lat))) {
    return { valid: false, reason: 'Row ' + rowNum + ': latitude is not numeric: ' + lat };
  }
  if (lon !== '' && lon !== null && lon !== undefined && isNaN(Number(lon))) {
    return { valid: false, reason: 'Row ' + rowNum + ': longitude is not numeric: ' + lon };
  }

  return { valid: true };
}

// ---------------------------------------------------------------------------
// Known Supabase columns — only these headers are included in the upsert.
// Add new columns here when you add them to the Supabase schema.
// ---------------------------------------------------------------------------

var KNOWN_COLUMNS = [
  // Bilingual text fields
  'name_ko', 'name_en',
  'venue_name_ko', 'venue_name_en',
  'city_ko', 'city_en',
  'region_ko', 'region_en',
  'description_ko', 'description_en',
  'address_ko', 'address_en',
  // Non-bilingual fields
  'opening_date', 'closing_date',
  'is_featured', 'is_editors_pick',
  'latitude', 'longitude',
  'cover_image_url',
  'hours',
  'contact',
  'reception_date',
  'opening_time',
  'event_id',
];

// ---------------------------------------------------------------------------
// Record construction — header-driven, filtered to known columns
// ---------------------------------------------------------------------------

function buildRecord(row, headerMap) {
  var nameKo = String(getCell(row, headerMap, 'name_ko') || '').trim();
  var venueNameKo = String(getCell(row, headerMap, 'venue_name_ko') || '').trim();
  var cityKo = String(getCell(row, headerMap, 'city_ko') || '').trim();
  var openingDate = parseDate(getCell(row, headerMap, 'opening_date'));

  var record = {
    id: generateId(nameKo, venueNameKo, cityKo, openingDate),
    updated_at: new Date().toISOString(),
  };

  // Only map headers that exist in KNOWN_COLUMNS
  KNOWN_COLUMNS.forEach(function(header) {
    if (!(header in headerMap)) return;
    var raw = getCell(row, headerMap, header);

    // Date fields
    if (header === 'opening_date' || header === 'closing_date') {
      record[header] = parseDate(raw);
      return;
    }

    // DateTime fields (nullable) — stored as ISO 8601 timestamptz
    if (header === 'reception_date') {
      if (!raw || String(raw).trim() === '') {
        record[header] = null;
      } else if (raw instanceof Date) {
        record[header] = raw.toISOString();
      } else {
        // Try parsing text like "2026-04-05 18:00" as a Date
        var parsed = new Date(String(raw).trim());
        record[header] = isNaN(parsed.getTime()) ? null : parsed.toISOString();
      }
      return;
    }

    // Boolean fields
    if (header === 'is_featured' || header === 'is_editors_pick') {
      record[header] = parseBool(raw);
      return;
    }

    // Numeric fields
    if (header === 'latitude' || header === 'longitude') {
      record[header] = (raw !== '' && raw !== null && raw !== undefined) ? Number(raw) : null;
      return;
    }

    // URL fields (nullable) — accepts full URL or filename-only
    if (header === 'cover_image_url') {
      var url = String(raw || '').trim();
      if (!url) {
        record[header] = null;
      } else if (/^https?:\/\//i.test(url)) {
        record[header] = url;
      } else {
        // Filename only → resolve to Supabase Storage public URL
        var props = PropertiesService.getScriptProperties();
        var baseUrl = props.getProperty('SUPABASE_URL');
        record[header] = baseUrl + '/storage/v1/object/public/exhibition-images/' + encodeURIComponent(url);
      }
      return;
    }

    // FK column — blank cell must become null, never empty string,
    // or Postgres rejects with FK violation 23503 (no events row has id="").
    if (header === 'event_id') {
      var eid = String(raw || '').trim();
      record[header] = eid || null;
      return;
    }

    // Nullable text fields — empty strings become null
    if (header === 'hours' || header === 'contact' || header === 'opening_time') {
      // opening_time may come in as a Date if the sheet cell is formatted
      // as time-of-day. Extract h:mm a in the sheet's timezone; otherwise
      // use the raw string as the user typed it.
      if (header === 'opening_time' && raw instanceof Date) {
        var tz = Session.getScriptTimeZone();
        record[header] = Utilities.formatDate(raw, tz, 'h:mm a');
        return;
      }
      var txt = String(raw || '').trim();
      record[header] = txt || null;
      return;
    }

    // Text fields (default)
    record[header] = String(raw || '').trim();
  });

  // Log unknown headers (informational)
  Object.keys(headerMap).forEach(function(header) {
    if (KNOWN_COLUMNS.indexOf(header) === -1 && header !== 'updated_at' && header !== 'id') {
      // Skipped — not in KNOWN_COLUMNS
    }
  });

  return record;
}

// ---------------------------------------------------------------------------
// ID generation and helpers
// ---------------------------------------------------------------------------

function generateId(nameKo, venueNameKo, cityKo, openingDate) {
  var raw = (nameKo + '|' + venueNameKo + '|' + cityKo + '|' + openingDate).toLowerCase().trim();
  var digest = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, raw);
  return digest.slice(0, 8).map(function(b) {
    return (b & 0xff).toString(16).padStart(2, '0');
  }).join('');
}

function parseDate(value) {
  if (!value) return null;

  if (value instanceof Date) {
    var y = value.getFullYear();
    var m = String(value.getMonth() + 1).padStart(2, '0');
    var d = String(value.getDate()).padStart(2, '0');
    return y + '-' + m + '-' + d;
  }

  var normalized = String(value).replace(/\./g, '-').trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(normalized)) return normalized;

  return null;
}

function parseBool(value) {
  if (value === true || value === 1) return true;
  if (typeof value === 'string') {
    var v = value.trim().toLowerCase();
    return v === 'true' || v === '1' || v === 'yes';
  }
  return false;
}

// ---------------------------------------------------------------------------
// Supabase API calls
// ---------------------------------------------------------------------------

function deleteAllExhibitions(supabaseUrl, serviceKey) {
  var url = supabaseUrl + '/rest/v1/exhibitions?id=neq.IMPOSSIBLE_VALUE';
  var response = UrlFetchApp.fetch(url, {
    method: 'delete',
    headers: {
      'apikey': serviceKey,
      'Authorization': 'Bearer ' + serviceKey,
    },
    muteHttpExceptions: true,
  });

  var code = response.getResponseCode();
  if (code < 200 || code >= 300) {
    throw new Error('DELETE failed with HTTP ' + code + ': ' + response.getContentText());
  }
}

function insertExhibitions(rows, supabaseUrl, serviceKey) {
  if (rows.length === 0) return;

  var url = supabaseUrl + '/rest/v1/exhibitions';
  var response = UrlFetchApp.fetch(url, {
    method: 'post',
    contentType: 'application/json',
    headers: {
      'apikey': serviceKey,
      'Authorization': 'Bearer ' + serviceKey,
      'Prefer': 'resolution=merge-duplicates,return=minimal',
    },
    payload: JSON.stringify(rows),
    muteHttpExceptions: true,
  });

  var code = response.getResponseCode();
  if (code < 200 || code >= 300) {
    throw new Error('INSERT failed with HTTP ' + code + ': ' + response.getContentText());
  }
}
