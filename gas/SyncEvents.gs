/**
 * SyncEvents.gs
 * Google Apps Script — Sync Google Sheet → Supabase events table
 *
 * SETUP REQUIRED (one-time):
 * 1. Create a new Apps Script project bound to the events spreadsheet
 *    (separate from the exhibitions spreadsheet)
 * 2. Open Project Settings → Script Properties and add:
 *      SUPABASE_URL              = https://<project-ref>.supabase.co
 *      SUPABASE_SERVICE_ROLE_KEY = <your-service-role-key>  ← never share
 * 3. Install triggers (Triggers menu in Apps Script editor):
 *    a) onEdit: Function = syncEventsToSupabase, From spreadsheet, On edit
 *    b) Time-driven: Function = syncEventsToSupabase, Minutes timer, every 5 min
 *
 * GOOGLE SHEET LAYOUT:
 *   Row 1 = headers (lowercase snake_case matching Supabase column names)
 *   Data rows from row 2.
 *   Required headers: id, name_ko, name_en, location_label_ko,
 *                     location_label_en, start_date, end_date, brand_color
 *   Optional headers: description_ko, description_en,
 *                     ticket_url, is_active, cover_image_url
 *
 * COVER IMAGE CONVENTION:
 *   The cover_image_url column accepts either:
 *     a) A full HTTPS URL (e.g., https://example.com/hero.jpg) — used as-is
 *     b) A filename only (e.g., loop-lab-busan-hero.jpg) — resolved to:
 *        {SUPABASE_URL}/storage/v1/object/public/event-images/{filename}
 *   Upload images to the "event-images" bucket via Supabase dashboard.
 *
 * SYNC SEMANTICS (Phase 2a):
 *   Each run upserts every sheet row (Prefer: resolution=merge-duplicates)
 *   then diff-deletes Postgres rows whose ids are no longer in the sheet.
 *   Unchanged ids never trigger ON DELETE SET NULL on linked exhibitions.
 *   Renaming an event id (effectively delete + re-insert as a new id) WILL
 *   null exhibitions.event_id for the old id — operator must re-link those
 *   exhibition rows in the exhibitions sheet.
 */

var REQUIRED_HEADERS = [
  'id', 'name_ko', 'name_en',
  'location_label_ko', 'location_label_en',
  'start_date', 'end_date',
  'brand_color',
];

var REQUIRED_ROW_FIELDS = REQUIRED_HEADERS;

var KNOWN_COLUMNS = [
  'id',
  'name_ko', 'name_en',
  'description_ko', 'description_en',
  'location_label_ko', 'location_label_en',
  'start_date', 'end_date',
  'brand_color',
  'ticket_url',
  'is_active',
  'cover_image_url',
];

function syncEventsToSupabase() {
  var props = PropertiesService.getScriptProperties();
  var supabaseUrl = props.getProperty('SUPABASE_URL');
  var serviceKey = props.getProperty('SUPABASE_SERVICE_ROLE_KEY');
  var timestamp = new Date().toISOString();

  if (!supabaseUrl || !serviceKey) {
    logFailure(timestamp, 'SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY not set in Script Properties');
    return;
  }

  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheets()[0];
  var data = sheet.getDataRange().getValues();

  if (data.length === 0) {
    logFailure(timestamp, 'Sheet is empty — no header row found');
    return;
  }

  var headerRow = data[0];
  var headerMap = buildHeaderMap(headerRow);
  Logger.log('Headers found: ' + Object.keys(headerMap).join(', '));

  var missingHeaders = [];
  REQUIRED_HEADERS.forEach(function(h) { if (!(h in headerMap)) missingHeaders.push(h); });
  if (missingHeaders.length > 0) {
    logFailure(timestamp, 'Missing required headers: ' + missingHeaders.join(', '));
    return;
  }

  var dataRows = data.slice(1);
  var rowsRead = dataRows.length;
  var validRows = [];
  var skippedReasons = [];

  dataRows.forEach(function(row, index) {
    var rowNum = index + 2;
    var result = validateRow(row, rowNum, headerMap);
    if (result.valid) {
      validRows.push(buildRecord(row, headerMap));
    } else {
      skippedReasons.push(result.reason);
    }
  });

  // ── Deduplicate by id ─────────────────────────────────────────────────
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
    // Upsert via Prefer: resolution=merge-duplicates — Postgres updates
    // existing rows by id and inserts new ones. ON DELETE SET NULL never
    // fires for unchanged ids, so linked exhibitions stay linked.
    upsertEvents(uniqueRows, supabaseUrl, serviceKey);
    // Diff-delete only rows whose ids are no longer in the sheet. FK
    // fires once per genuinely-removed event (correct).
    var keepIds = uniqueRows.map(function(r) { return r.id; });
    diffDeleteEvents(keepIds, supabaseUrl, serviceKey);
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

function buildHeaderMap(headerRow) {
  var map = {};
  headerRow.forEach(function(cell, index) {
    var header = String(cell || '').toLowerCase().trim();
    if (header) map[header] = index;
  });
  return map;
}

function getCell(row, headerMap, headerName) {
  if (!(headerName in headerMap)) return '';
  return row[headerMap[headerName]];
}

function validateRow(row, rowNum, headerMap) {
  for (var i = 0; i < REQUIRED_ROW_FIELDS.length; i++) {
    var field = REQUIRED_ROW_FIELDS[i];
    var value = String(getCell(row, headerMap, field) || '').trim();
    if (!value) {
      return { valid: false, reason: 'Row ' + rowNum + ': ' + field + ' is empty' };
    }
  }
  if (!parseDate(getCell(row, headerMap, 'start_date'))) {
    return { valid: false, reason: 'Row ' + rowNum + ': start_date is not a valid date' };
  }
  if (!parseDate(getCell(row, headerMap, 'end_date'))) {
    return { valid: false, reason: 'Row ' + rowNum + ': end_date is not a valid date' };
  }
  if (!isHexColor(getCell(row, headerMap, 'brand_color'))) {
    return { valid: false, reason: 'Row ' + rowNum + ': brand_color is not a valid hex (#RRGGBB)' };
  }
  return { valid: true };
}

function isHexColor(v) {
  var s = String(v || '').trim();
  return /^#?[0-9A-Fa-f]{6}$/.test(s);
}

// Schema defaults for events table. Under upsert
// (Prefer: resolution=merge-duplicates), omitting a field leaves the
// existing DB value untouched — wrong when the operator cleared the cell.
// Sending explicit null violates NOT NULL columns. So map blank cells to
// the schema's actual default per column.
//   - description_ko/en, is_active: NOT NULL DEFAULT — send the default
//   - ticket_url, cover_image_url: nullable — send null
//   - required columns (id, name_*, location_label_*, start_date, end_date,
//     brand_color): can't be blank, validateRow catches that upstream
var BLANK_DEFAULTS = {
  description_ko: '',
  description_en: '',
  is_active: true,
  ticket_url: null,
  cover_image_url: null,
};

function buildRecord(row, headerMap) {
  var record = {};
  KNOWN_COLUMNS.forEach(function(col) {
    if (!(col in headerMap)) return;
    var raw = getCell(row, headerMap, col);
    if (raw === '' || raw === null || raw === undefined) {
      if (col in BLANK_DEFAULTS) {
        record[col] = BLANK_DEFAULTS[col];
      }
      // Required columns: omit silently — validateRow upstream guarantees
      // we never reach here with a required column blank.
      return;
    }

    if (col === 'start_date' || col === 'end_date') {
      record[col] = parseDate(raw);
    } else if (col === 'is_active') {
      record[col] = (String(raw).toLowerCase() === 'true' || raw === true);
    } else if (col === 'brand_color') {
      var s = String(raw).trim();
      record[col] = (s.charAt(0) === '#') ? s : ('#' + s);
    } else if (col === 'cover_image_url') {
      var url = String(raw || '').trim();
      if (/^https?:\/\//i.test(url)) {
        record[col] = url;
      } else {
        var props = PropertiesService.getScriptProperties();
        var baseUrl = props.getProperty('SUPABASE_URL');
        record[col] = baseUrl + '/storage/v1/object/public/event-images/' + encodeURIComponent(url);
      }
    } else {
      record[col] = String(raw).trim();
    }
  });
  return record;
}

function parseDate(v) {
  if (!v) return null;
  if (v instanceof Date) {
    var y = v.getFullYear();
    var m = String(v.getMonth() + 1).padStart(2, '0');
    var d = String(v.getDate()).padStart(2, '0');
    return y + '-' + m + '-' + d;
  }
  var str = String(v).trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(str)) return str;
  var parsed = new Date(str);
  if (isNaN(parsed.getTime())) return null;
  var y2 = parsed.getFullYear();
  var m2 = String(parsed.getMonth() + 1).padStart(2, '0');
  var d2 = String(parsed.getDate()).padStart(2, '0');
  return y2 + '-' + m2 + '-' + d2;
}

function upsertEvents(rows, supabaseUrl, serviceKey) {
  var url = supabaseUrl + '/rest/v1/events';
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
  if (code !== 201 && code !== 200) {
    throw new Error('Upsert events failed with code ' + code + ': ' + response.getContentText());
  }
}

function diffDeleteEvents(keepIds, supabaseUrl, serviceKey) {
  if (keepIds.length === 0) return;
  // PostgREST not.in.() takes comma-separated values inside parens.
  // Wrap each id in URL-encoded double quotes so commas/parens inside
  // an id can't break the parser. Safe even for plain kebab-case ids.
  var idList = keepIds.map(function(id) {
    return '%22' + encodeURIComponent(id) + '%22';
  }).join(',');
  var url = supabaseUrl + '/rest/v1/events?id=not.in.(' + idList + ')';
  var response = UrlFetchApp.fetch(url, {
    method: 'delete',
    headers: {
      'apikey': serviceKey,
      'Authorization': 'Bearer ' + serviceKey,
      'Prefer': 'return=minimal',
    },
    muteHttpExceptions: true,
  });
  var code = response.getResponseCode();
  if (code !== 204 && code !== 200) {
    throw new Error('Diff delete failed with code ' + code + ': ' + response.getContentText());
  }
}

function logFailure(timestamp, message) {
  Logger.log(JSON.stringify({
    timestamp: timestamp,
    status: 'FAILURE',
    error: message,
    rows_read: 0,
    rows_inserted: 0,
    rows_skipped: 0,
  }));
}
