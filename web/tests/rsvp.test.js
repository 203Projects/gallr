const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { valid } = require("../rsvp/rsvp.js");

assert.equal(valid({
  name: "Maya Chen", email: "maya@example.com", party_size: 2,
  privacy_acknowledged: true,
}), true);
assert.equal(valid({
  name: "Maya Chen", email: "bad", party_size: 2,
  privacy_acknowledged: true,
}), false);
assert.equal(valid({
  name: "Maya Chen", email: "maya@example.com", party_size: 2,
  privacy_acknowledged: false,
}), false);

const page = fs.readFileSync(path.join(__dirname, "..", "rsvp", "index.html"), "utf8");
assert.match(page, /data-rsvp-form/);
assert.match(page, /privacy_acknowledged/);
assert.doesNotMatch(page, /Featured|promot|revenue/i);
console.log("rsvp tests passed");
