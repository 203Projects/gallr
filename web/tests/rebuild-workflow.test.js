const assert = require("assert").strict;
const fs = require("fs");
const path = require("path");

const workflowPath = path.resolve(__dirname, "../../.github/workflows/rebuild-web.yml");
assert.equal(fs.existsSync(workflowPath), true, "daily rebuild workflow is missing");

const workflow = fs.readFileSync(workflowPath, "utf8");

assert.match(workflow, /workflow_dispatch:/, "workflow must support manual dispatch");
assert.match(workflow, /schedule:/, "workflow must define a schedule");
assert.match(workflow, /cron:\s*["']0 0 \* \* \*["']/, "workflow must run daily at 09:00 KST");
assert.match(workflow, /VERCEL_DEPLOY_HOOK_URL:\s*\$\{\{\s*secrets\.VERCEL_DEPLOY_HOOK_URL\s*\}\}/, "workflow must read the deploy hook from GitHub secrets");
assert.match(workflow, /curl[\s\S]*--request POST[\s\S]*"\$VERCEL_DEPLOY_HOOK_URL"/, "workflow must POST to the Vercel deploy hook");

console.log("[rebuild-workflow.test] all tests passed");
