/**
 * Public exhibition submission endpoint.
 *
 * Required script properties:
 *   SUPABASE_URL
 *   SUPABASE_SERVICE_ROLE_KEY
 * Optional:
 *   SUBMISSION_SPREADSHEET_ID (falls back to active spreadsheet)
 */

var SUBMISSION_REQUIRED_FIELDS = [
  'name_ko',
  'venue_name_ko',
  'opening_date',
  'closing_date',
  'address_ko',
  'opening_time',
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
];

function doPost(e) {
  try {
    var payload = JSON.parse(e && e.postData && e.postData.contents ? e.postData.contents : '{}');
    var validation = validateFormPayload(payload);
    if (!validation.valid) {
      return responseJson({ success: false, error: validation.error });
    }

    var props = PropertiesService.getScriptProperties();
    var supabaseUrl = props.getProperty('SUPABASE_URL');
    var serviceKey = props.getProperty('SUPABASE_SERVICE_ROLE_KEY');
    if (!supabaseUrl || !serviceKey) {
      return responseJson({ success: false, error: 'server not configured' });
    }

    var imageUrls = uploadSubmissionImages(payload.images || [], supabaseUrl, serviceKey);
    var sheet = getSubmissionSheet(props);
    var headers = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0]
      .map(function(h) { return String(h || '').trim(); });
    var row = buildSubmissionRow(headers, payload.fields || {}, imageUrls);
    sheet.appendRow(row);

    sendConfirmationEmail(payload.fields || {});
    return responseJson({ success: true });
  } catch (error) {
    return responseJson({ success: false, error: error.message || String(error) });
  }
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
  if (images.length > 5) {
    return { valid: false, error: 'too many images' };
  }
  for (var j = 0; j < images.length; j++) {
    var image = images[j] || {};
    if (['image/jpeg', 'image/png'].indexOf(image.contentType) === -1 || !image.base64) {
      return { valid: false, error: 'invalid image' };
    }
  }
  return { valid: true, error: null };
}

function buildSubmissionRow(headers, fields, imageUrls) {
  return headers.map(function(header) {
    if (header === 'status') return 'pending';
    if (header === 'contact') return ''; // submitter email is private; do not publish it.
    var match = /^image_url_([1-5])$/.exec(header);
    if (match) return imageUrls[Number(match[1]) - 1] || '';
    if (SUBMISSION_REQUIRED_FIELDS.indexOf(header) !== -1 && header !== 'contact') {
      return String(fields[header] || '').trim();
    }
    if (SUBMISSION_OPTIONAL_FIELDS.indexOf(header) !== -1) {
      return String(fields[header] || '').trim();
    }
    return '';
  });
}

function uploadSubmissionImages(images, supabaseUrl, serviceKey) {
  return images.slice(0, 5).map(function(image, index) {
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
    responseJson: responseJson,
  };
}
